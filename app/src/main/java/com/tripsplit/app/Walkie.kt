package com.tripsplit.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

// ---------------- Invite text ----------------
fun makeInviteText(t: Trip): String {
    val o = JSONObject().put("id", t.id).put("name", t.name).put("start", t.start).put("end", t.end)
        .put("dest", JSONArray(t.destinations)).put("trav", JSONArray(t.travelers))
        .put("budget", t.budget).put("key", t.key)
    val code = Base64.encodeToString(o.toString().toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
    return "Join my TripSplit trip \"${t.name}\" (${fmtDate(t.start)} - ${fmtDate(t.end)}).\n" +
        "1. Copy this whole message.\n2. Open TripSplit, tap the 3 dots menu, then Join a trip.\n3. Tap Paste.\n" +
        "Trip key: ${t.key}\n\nTRIPSPLIT:$code"
}

fun parseInvite(text: String): Trip? {
    val i = text.indexOf("TRIPSPLIT:")
    if (i < 0) return null
    val token = text.substring(i + 10).trim().takeWhile { !it.isWhitespace() }
    return try {
        val o = JSONObject(String(Base64.decode(token, Base64.NO_WRAP or Base64.URL_SAFE)))
        Trip(id = o.getString("id"), name = o.getString("name"), start = o.getLong("start"), end = o.getLong("end"),
            destinations = o.getJSONArray("dest").strings(), travelers = o.getJSONArray("trav").strings(),
            budget = o.getDouble("budget"), key = o.getString("key"))
    } catch (e: Exception) { null }
}

fun shareToWhatsApp(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try { ctx.startActivity(Intent(i).setPackage("com.whatsapp")) }
    catch (e: Exception) { ctx.startActivity(Intent.createChooser(i, "Send invite")) }
}

// ---------------- Wi-Fi voice engine ----------------
/**
 * Half-duplex push-to-talk over the local Wi-Fi / hotspot using UDP broadcast.
 * Every packet is encrypted with the trip key, so only phones that joined the trip can hear it.
 */
class WalkieEngine(private val ctx: Context, tripId: String, key: String, private val me: String) {
    companion object { const val PORT = 47900; const val RATE = 16000 }

    var seen by mutableStateOf<Map<String, Long>>(emptyMap())
    var talkingName by mutableStateOf<String?>(null)
    var iAmTalking by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private val aesKey = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("tripsplit|$key".toByteArray()), "AES")
    private val tag = MessageDigest.getInstance("SHA-256").digest("trip|$tripId".toByteArray()).copyOf(4)
    private val rnd = SecureRandom()
    @Volatile private var running = false
    @Volatile private var talking = false
    @Volatile private var lastVoice = 0L
    @Volatile private var targets: List<InetAddress> = emptyList()
    private var socket: DatagramSocket? = null
    private var track: AudioTrack? = null
    private var lock: WifiManager.MulticastLock? = null

    private fun broadcasts(): List<InetAddress> {
        val out = linkedSetOf<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        } catch (e: Exception) { }
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) { }
        return out.toList()
    }

    fun start() {
        if (running) return
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("tripsplit").apply { setReferenceCounted(false); acquire() }
            socket = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(PORT)) }
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 4, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
        } catch (e: Exception) { error = "Could not start: ${e.message}"; return }
        running = true
        targets = broadcasts()
        thread(name = "walkie-rx") { receiveLoop() }
        thread(name = "walkie-hello") {
            while (running) {
                targets = broadcasts()
                send(0, ByteArray(0))
                try { Thread.sleep(2000) } catch (e: InterruptedException) { }
            }
        }
    }

    fun stop() {
        running = false; talking = false; iAmTalking = false
        try { socket?.close() } catch (e: Exception) { }
        try { track?.stop(); track?.release() } catch (e: Exception) { }
        try { lock?.release() } catch (e: Exception) { }
        socket = null; track = null
    }

    private fun receiveLoop() {
        val s = socket ?: return
        try { s.soTimeout = 500 } catch (e: Exception) { return }
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try { pkt.length = buf.size; s.receive(pkt); handle(buf, pkt.length) }
            catch (e: SocketTimeoutException) { }
            catch (e: Exception) { if (!running) break }
            if (talkingName != null && System.currentTimeMillis() - lastVoice > 700) talkingName = null
        }
    }

    private fun handle(d: ByteArray, len: Int) {
        if (len < 40 || d[0] != 'T'.code.toByte() || d[1] != 'S'.code.toByte()) return
        for (i in 0..3) if (d[2 + i] != tag[i]) return
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, d.copyOfRange(6, 18)))
            c.doFinal(d, 18, len - 18)
        } catch (e: Exception) { return }
        if (plain.size < 2) return
        val type = plain[0].toInt()
        val nl = plain[1].toInt() and 0xFF
        if (2 + nl > plain.size) return
        val name = String(plain, 2, nl, Charsets.UTF_8)
        if (name == me) return
        val now = System.currentTimeMillis()
        seen = seen + (name to now)
        if (type == 1) {
            talkingName = name; lastVoice = now
            if (!muted && !talking) track?.write(plain, 2 + nl, plain.size - 2 - nl)
        }
    }

    private fun send(type: Int, payload: ByteArray) {
        val s = socket ?: return
        try {
            val nb = me.toByteArray(Charsets.UTF_8).take(60).toByteArray()
            val plain = ByteArray(2 + nb.size + payload.size)
            plain[0] = type.toByte(); plain[1] = nb.size.toByte()
            System.arraycopy(nb, 0, plain, 2, nb.size)
            System.arraycopy(payload, 0, plain, 2 + nb.size, payload.size)
            val nonce = ByteArray(12).also { rnd.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, nonce))
            val ct = c.doFinal(plain)
            val out = ByteArray(18 + ct.size)
            out[0] = 'T'.code.toByte(); out[1] = 'S'.code.toByte()
            System.arraycopy(tag, 0, out, 2, 4); System.arraycopy(nonce, 0, out, 6, 12); System.arraycopy(ct, 0, out, 18, ct.size)
            for (a in targets) { try { s.send(DatagramPacket(out, out.size, a, PORT)) } catch (e: Exception) { } }
        } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    fun talk(on: Boolean) {
        if (!running) return
        if (!on) { talking = false; iAmTalking = false; return }
        if (talking) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "Microphone permission needed"; return
        }
        talking = true; iAmTalking = true
        thread(name = "walkie-mic") {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 4096))
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                talking = false; iAmTalking = false; error = "Could not use the microphone"; rec?.release()
                return@thread
            }
            rec.startRecording()
            val chunk = ByteArray(640)
            while (talking && running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) send(1, if (n == chunk.size) chunk else chunk.copyOf(n))
            }
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
        }
    }
}
