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
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

// ---------------- Chat data ----------------
data class ChatMsg(
    val id: String, val from: String, val dev: String, val ts: Long, val kind: String, val text: String,
    val fname: String = "", val size: Long = 0, val mime: String = "", val fid: String = ""
)

private fun ChatMsg.toJson(): JSONObject = JSONObject().put("id", id).put("f", from).put("d", dev).put("t", ts)
    .put("k", kind).put("x", text).put("n", fname).put("s", size).put("m", mime).put("i", fid)

private fun chatFrom(o: JSONObject) = ChatMsg(o.getString("id"), o.getString("f"), o.optString("d"), o.getLong("t"),
    o.getString("k"), o.optString("x"), o.optString("n"), o.optLong("s"), o.optString("m"), o.optString("i"))

fun devId(ctx: Context): String {
    val p = ctx.getSharedPreferences("trips", 0)
    return p.getString("dev", null) ?: UUID.randomUUID().toString().take(12).also { p.edit().putString("dev", it).apply() }
}

fun limitBytes(s: String, max: Int): String {
    var r = s
    while (r.toByteArray().size > max) r = r.dropLast(1)
    return r
}

fun fmtSize(b: Long): String = when {
    b >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

/** Saves chat messages, downloaded files and who has joined, per trip, on this phone. */
class ChatStore(ctx: Context, tripId: String) {
    private val file = File(ctx.filesDir, "chat_${tripId.take(40)}.json")
    val msgs = mutableListOf<ChatMsg>()
    val local = HashMap<String, String>()
    val joined = HashMap<String, Long>()
    val devs = HashMap<String, MutableSet<String>>()

    init {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                val a = o.optJSONArray("msgs") ?: JSONArray()
                for (i in 0 until a.length()) msgs.add(chatFrom(a.getJSONObject(i)))
                o.optJSONObject("local")?.let { l -> l.keys().forEach { k -> local[k] = l.getString(k) } }
                o.optJSONObject("joined")?.let { l -> l.keys().forEach { k -> joined[k] = l.getLong(k) } }
                o.optJSONObject("devs")?.let { l -> l.keys().forEach { k -> devs[k] = l.getJSONArray(k).strings().toMutableSet() } }
                msgs.sortBy { it.ts }
            }
        } catch (e: Exception) { }
    }

    @Synchronized fun save() {
        try {
            val o = JSONObject().put("msgs", JSONArray(msgs.map { it.toJson() }))
                .put("local", JSONObject(local as Map<*, *>)).put("joined", JSONObject(joined as Map<*, *>))
                .put("devs", JSONObject(devs.mapValues { JSONArray(it.value.toList()) } as Map<*, *>))
            file.writeText(o.toString())
        } catch (e: Exception) { }
    }
}

private class Peer(val ip: InetAddress, val port: Int)

// ---------------- Wi-Fi network: chat, files and voice ----------------
/**
 * Everything is sent over the local Wi-Fi / hotspot and encrypted with the trip key:
 * UDP broadcast for presence, chat messages and voice, and a direct TCP link phone-to-phone for files.
 */
class TripNet(private val ctx: Context, private val tripId: String, key: String, val me: String, private val deviceId: String) {
    companion object {
        const val PORT = 47900; const val RATE = 16000
        const val AUTO_MAX = 25L * 1024 * 1024; const val MAX_FILE = 100L * 1024 * 1024
    }

    private val store = ChatStore(ctx, tripId)
    var seen by mutableStateOf<Map<String, Long>>(emptyMap())
    var joined by mutableStateOf<Map<String, Long>>(store.joined.toMap())
    var dupNames by mutableStateOf<Set<String>>(store.devs.filter { it.value.size > 1 }.keys)
    var messages by mutableStateOf<List<ChatMsg>>(store.msgs.toList())
    var localFiles by mutableStateOf<Map<String, String>>(store.local.toMap())
    var progress by mutableStateOf<Map<String, Float>>(emptyMap())
    var note by mutableStateOf<String?>(null)
    var talkingName by mutableStateOf<String?>(null)
    var iAmTalking by mutableStateOf(false)
    var talkStart by mutableStateOf(0L)
    var muted by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private val aesKey = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("tripsplit|$key".toByteArray()), "AES")
    private val tag = MessageDigest.getInstance("SHA-256").digest("trip|$tripId".toByteArray()).copyOf(4)
    private val rnd = SecureRandom()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val lastHello = ConcurrentHashMap<String, Long>()
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running = false
    @Volatile private var talking = false
    @Volatile private var lastVoice = 0L
    @Volatile private var lastSyncReply = 0L
    @Volatile private var targets: List<InetAddress> = emptyList()
    private var socket: DatagramSocket? = null
    private var server: ServerSocket? = null
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
            server = ServerSocket(0)
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
        noteJoin(me, deviceId, System.currentTimeMillis())
        thread(name = "net-rx") { receiveLoop() }
        thread(name = "net-tcp") { acceptLoop() }
        thread(name = "net-hello") {
            var first = true
            while (running) {
                targets = broadcasts()
                val port = server?.localPort ?: 0
                send(0, JSONObject().put("d", deviceId).put("p", port).toString().toByteArray())
                if (first) { first = false; requestSync() }
                store.joined[me] = System.currentTimeMillis()
                try { Thread.sleep(2000) } catch (e: InterruptedException) { }
            }
        }
    }

    fun stop() {
        running = false; talking = false; iAmTalking = false
        try { socket?.close() } catch (e: Exception) { }
        try { server?.close() } catch (e: Exception) { }
        try { track?.stop(); track?.release() } catch (e: Exception) { }
        try { lock?.release() } catch (e: Exception) { }
        store.save()
        socket = null; server = null; track = null
    }

    // ---------- receiving ----------
    private fun receiveLoop() {
        val s = socket ?: return
        try { s.soTimeout = 500 } catch (e: Exception) { return }
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try { pkt.length = buf.size; s.receive(pkt); handle(buf, pkt.length, pkt.address) }
            catch (e: SocketTimeoutException) { }
            catch (e: Exception) { if (!running) break }
            if (talkingName != null && System.currentTimeMillis() - lastVoice > 700) talkingName = null
        }
    }

    private fun handle(d: ByteArray, len: Int, addr: InetAddress) {
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
        val payload = plain.copyOfRange(2 + nl, plain.size)
        val now = System.currentTimeMillis()
        seen = seen + (name to now)
        try {
            when (type) {
                0 -> {
                    val o = JSONObject(String(payload))
                    val dev = o.getString("d")
                    peers[dev] = Peer(addr, o.getInt("p"))
                    noteJoin(name, dev, now)
                    val prev = lastHello.put(dev, now)
                    if (prev == null || now - prev > 10_000) requestSync()
                    messages.filter { it.dev == dev }.forEach { autoDownload(it) }
                }
                1 -> {
                    talkingName = name; lastVoice = now
                    if (!muted && !talking) track?.write(payload, 0, payload.size)
                }
                2 -> onChat(chatFrom(JSONObject(String(payload))))
                3 -> onSync(JSONObject(String(payload)).optLong("since", 0L))
            }
        } catch (e: Exception) { }
    }

    private fun noteJoin(name: String, dev: String, now: Long) {
        synchronized(store) {
            store.joined[name] = now
            store.devs.getOrPut(name) { mutableSetOf() }.add(dev)
        }
        joined = store.joined.toMap()
        dupNames = store.devs.filter { it.value.size > 1 }.keys
    }

    private fun onChat(m: ChatMsg) {
        synchronized(store) {
            if (store.msgs.any { it.id == m.id }) return
            store.msgs.add(m); store.msgs.sortBy { it.ts }; store.save()
            messages = store.msgs.toList()
        }
        noteJoin(m.from, m.dev, System.currentTimeMillis())
        autoDownload(m)
    }

    private fun requestSync() {
        val since = synchronized(store) { store.msgs.maxOfOrNull { it.ts } ?: 0L }
        send(3, JSONObject().put("since", since).toString().toByteArray())
    }

    private fun onSync(since: Long) {
        val now = System.currentTimeMillis()
        if (now - lastSyncReply < 3000) return
        lastSyncReply = now
        thread(name = "net-sync") {
            try { Thread.sleep((rnd.nextInt(1200)).toLong()) } catch (e: InterruptedException) { }
            val list = synchronized(store) { store.msgs.filter { it.ts > since }.takeLast(60) }
            for (m in list) { sendChat(m); try { Thread.sleep(40) } catch (e: InterruptedException) { } }
        }
    }

    // ---------- sending ----------
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

    private fun sendChat(m: ChatMsg) = send(2, m.toJson().toString().toByteArray())

    private fun addOwn(m: ChatMsg) {
        synchronized(store) { store.msgs.add(m); store.msgs.sortBy { it.ts }; store.save(); messages = store.msgs.toList() }
        thread(name = "net-send") {
            for (i in 0..2) { sendChat(m); try { Thread.sleep(if (i == 0) 400L else 800L) } catch (e: InterruptedException) { } }
        }
    }

    fun sendText(t: String) {
        val clean = limitBytes(t.trim(), 700)
        if (clean.isEmpty()) return
        addOwn(ChatMsg(UUID.randomUUID().toString(), me, deviceId, System.currentTimeMillis(), "text", clean))
    }

    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)

    /** Copies a photo, video or file the user picked into the app and shares it with the group. */
    fun sendUri(uri: Uri) {
        thread(name = "net-copy") {
            try {
                val cr = ctx.contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                var name = "file"; var total = -1L
                cr.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni) ?: name
                        if (si >= 0) total = c.getLong(si)
                    }
                }
                if (total > MAX_FILE) { note = "That file is bigger than 100 MB"; return@thread }
                val fid = UUID.randomUUID().toString().take(8)
                val dir = File(ctx.filesDir, "chat_out").apply { mkdirs() }
                val out = File(dir, "${fid}_${safe(name)}")
                progress = progress + (fid to 0f)
                var copied = 0L; var lastUi = 0L
                cr.openInputStream(uri)!!.use { ins ->
                    FileOutputStream(out).use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf); if (n <= 0) break
                            o.write(buf, 0, n); copied += n
                            if (copied > MAX_FILE) throw IOException("too big")
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastUi > 200) { progress = progress + (fid to copied.toFloat() / total); lastUi = now }
                        }
                    }
                }
                progress = progress - fid
                val kind = when { mime.startsWith("image/") -> "image"; mime.startsWith("video/") -> "video"; else -> "file" }
                synchronized(store) { store.local[fid] = out.absolutePath }
                localFiles = store.local.toMap()
                addOwn(ChatMsg(UUID.randomUUID().toString(), me, deviceId, System.currentTimeMillis(), kind, "",
                    limitBytes(name, 80), out.length(), mime, fid))
            } catch (e: Exception) { note = "Could not send the file" }
        }
    }

    // ---------- files (direct phone-to-phone over TCP) ----------
    private fun writeFrame(o: DataOutputStream, data: ByteArray) {
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, nonce))
        val ct = c.doFinal(data)
        o.writeInt(12 + ct.size); o.write(nonce); o.write(ct)
    }

    private fun readFrame(i: DataInputStream): ByteArray {
        val len = i.readInt()
        if (len < 28 || len > 2_000_000) throw IOException("bad frame")
        val buf = ByteArray(len); i.readFully(buf)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, buf.copyOfRange(0, 12)))
        return c.doFinal(buf, 12, len - 12)
    }

    private fun acceptLoop() {
        val ss = server ?: return
        while (running) {
            try { val s = ss.accept(); thread(name = "net-serve") { serve(s) } }
            catch (e: Exception) { if (!running) break }
        }
    }

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 15000
            val ins = DataInputStream(BufferedInputStream(s.getInputStream()))
            val outs = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val req = String(readFrame(ins))
            if (req.startsWith("GET ")) {
                val f = synchronized(store) { store.local[req.substring(4).trim()] }?.let { File(it) }
                if (f == null || !f.exists()) { writeFrame(outs, "ERR".toByteArray()); outs.flush() }
                else {
                    writeFrame(outs, "OK ${f.length()}".toByteArray())
                    FileInputStream(f).use { i ->
                        val buf = ByteArray(60 * 1024)
                        while (true) { val n = i.read(buf); if (n <= 0) break; writeFrame(outs, buf.copyOf(n)) }
                    }
                    outs.flush()
                }
            }
        } catch (e: Exception) { } finally { try { s.close() } catch (e: Exception) { } }
    }

    fun autoDownload(m: ChatMsg) {
        if (m.kind == "text" || m.from == me || m.fid.isEmpty() || m.size > AUTO_MAX) return
        if (store.local.containsKey(m.fid) || !peers.containsKey(m.dev)) return
        download(m)
    }

    fun download(m: ChatMsg) {
        if (m.fid.isEmpty() || store.local.containsKey(m.fid) || !downloading.add(m.fid)) return
        thread(name = "net-dl") {
            try {
                val peer = peers[m.dev] ?: throw IOException("${m.from} is not on this Wi-Fi right now")
                Socket().use { s ->
                    s.connect(InetSocketAddress(peer.ip, peer.port), 4000); s.soTimeout = 15000
                    val ins = DataInputStream(BufferedInputStream(s.getInputStream()))
                    val outs = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    writeFrame(outs, "GET ${m.fid}".toByteArray()); outs.flush()
                    val head = String(readFrame(ins))
                    if (!head.startsWith("OK ")) throw IOException("The file is no longer on ${m.from}'s phone")
                    val total = head.substring(3).toLong()
                    val dir = File(ctx.filesDir, "chat_in").apply { mkdirs() }
                    val f = File(dir, "${m.fid}_${safe(m.fname)}")
                    var got = 0L; var lastUi = 0L
                    FileOutputStream(f).use { o ->
                        while (got < total) {
                            val c = readFrame(ins); o.write(c); got += c.size
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 200) { progress = progress + (m.fid to got.toFloat() / total); lastUi = now }
                        }
                    }
                    synchronized(store) { store.local[m.fid] = f.absolutePath; store.save() }
                    localFiles = store.local.toMap()
                }
            } catch (e: Exception) { note = e.message ?: "Download failed" }
            finally { downloading.remove(m.fid); progress = progress - m.fid }
        }
    }

    // ---------- voice (tap to start, tap to stop) ----------
    fun toggleTalk() = talk(!iAmTalking)

    @SuppressLint("MissingPermission")
    fun talk(on: Boolean) {
        if (!running) return
        if (!on) { talking = false; iAmTalking = false; return }
        if (talking) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "Microphone permission needed"; return
        }
        talking = true; iAmTalking = true; talkStart = System.currentTimeMillis()
        thread(name = "net-mic") {
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
