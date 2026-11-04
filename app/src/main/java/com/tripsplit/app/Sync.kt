package com.tripsplit.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        "1. Copy this whole message.\n2. Open TripSplit, tap the 3 dots menu, then Join a trip.\n3. Paste it, pick your name and join.\n" +
        "Trip key: ${t.key}\nExpenses merge by themselves when your phones are on the same Wi-Fi.\n\nTRIPSPLIT:$code"
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

fun devId(ctx: Context): String {
    val p = ctx.getSharedPreferences("trips", 0)
    return p.getString("dev", null) ?: UUID.randomUUID().toString().take(12).also { p.edit().putString("dev", it).apply() }
}

fun normKey(k: String) = k.trim().uppercase()

// ---------------- Auto sync over the local Wi-Fi ----------------
class Incoming(val tripId: String, val who: String, val text: String, val at: Long)

private class Peer(val ip: InetAddress, val port: Int, val name: String, val tripId: String, val dev: String, val seenAt: Long)
private class Entry(val tag: ByteArray, val aes: SecretKeySpec, val trip: Trip?)

/**
 * Finds other phones that have the same trip key on the same Wi-Fi / hotspot (UDP broadcast, encrypted with the
 * key) and merges expenses with them over a direct TCP link. Runs while the app is open.
 */
class SyncEngine(
    ctx: Context,
    private val deviceId: String,
    private val trips: () -> List<Trip>,
    private val apply: (String, Trip) -> List<Expense>
) {
    companion object { const val PORT = 47901 }

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val rnd = SecureRandom()

    var linkUp by mutableStateOf(false)
    var internetOk by mutableStateOf(false)
    var seen by mutableStateOf<Map<String, Long>>(emptyMap())        // "tripId|name" -> last seen
    var syncedAt by mutableStateOf<Map<String, Long>>(emptyMap())    // "tripId|name" -> last merged with
    var lastSync by mutableStateOf<Map<String, Long>>(emptyMap())    // tripId -> last merge
    var incoming by mutableStateOf<List<Incoming>>(emptyList())
    var syncing by mutableStateOf(false)
    var joinFound by mutableStateOf<Trip?>(null)
    var joinKey by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    @Volatile private var running = false
    @Volatile private var cache: List<Entry> = emptyList()
    @Volatile private var targets: List<InetAddress> = emptyList()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val lastTry = ConcurrentHashMap<String, Long>()
    private val active = AtomicInteger(0)
    private val joinBusy = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var server: ServerSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    private fun entryFor(key: String, trip: Trip?): Entry {
        val k = normKey(key)
        return Entry(sha("tag|$k").copyOf(4), SecretKeySpec(sha("tripsplit|$k"), "AES"), trip)
    }

    private fun entriesNow(): List<Entry> {
        val out = trips().filter { it.key.isNotBlank() && it.me.isNotBlank() }.map { entryFor(it.key, it) }.toMutableList()
        joinKey?.let { k ->
            val e = entryFor(k, null)
            if (out.none { it.tag.contentEquals(e.tag) }) out.add(e)
        }
        return out
    }

    /** Start (or stop, with null) listening for a trip key that this phone has not joined yet. */
    fun listenForKey(k: String?) {
        joinKey = k?.let { normKey(it) }
        joinFound = null
        cache = entriesNow()
    }

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

    private fun checkInternet(): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } catch (e: Exception) { false }

    // ---------- crypto ----------
    private fun encrypt(e: Entry, data: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, e.aes, GCMParameterSpec(128, nonce))
        return nonce to c.doFinal(data)
    }

    private fun decrypt(e: Entry, nonce: ByteArray, buf: ByteArray, off: Int, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, e.aes, GCMParameterSpec(128, nonce))
        return c.doFinal(buf, off, len)
    }

    private fun tagEq(tag: ByteArray, buf: ByteArray, off: Int): Boolean {
        for (i in 0..3) if (buf[off + i] != tag[i]) return false
        return true
    }

    private fun writeFrame(o: DataOutputStream, e: Entry, data: ByteArray) {
        val (nonce, ct) = encrypt(e, data)
        o.writeInt(16 + ct.size); o.write(e.tag); o.write(nonce); o.write(ct)
    }

    private fun readFrame(i: DataInputStream): Pair<Entry, ByteArray> {
        val len = i.readInt()
        if (len < 32 || len > 16_000_000) throw IOException("bad frame")
        val buf = ByteArray(len); i.readFully(buf)
        val e = cache.firstOrNull { tagEq(it.tag, buf, 0) } ?: throw IOException("unknown key")
        return e to decrypt(e, buf.copyOfRange(4, 16), buf, 16, len - 16)
    }

    private fun snapshot(t: Trip) = t.copy(me = "").toJson().toString()

    // ---------- lifecycle ----------
    fun start() {
        if (running) return
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("tripsplit").apply { setReferenceCounted(false); acquire() }
            socket = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(PORT)) }
            server = ServerSocket(0)
        } catch (e: Exception) { error = "Could not start sync: ${e.message}"; return }
        error = null; running = true
        cache = entriesNow(); targets = broadcasts()
        thread(name = "sync-rx") { receiveLoop() }
        thread(name = "sync-tcp") { acceptLoop() }
        thread(name = "sync-loop") {
            while (running) {
                cache = entriesNow(); targets = broadcasts()
                linkUp = targets.any { it.hostAddress != "255.255.255.255" }
                internetOk = checkInternet()
                for (e in cache) {
                    val t = e.trip ?: continue
                    broadcast(e, JSONObject().put("d", deviceId).put("p", server?.localPort ?: 0)
                        .put("t", t.id).put("n", t.me).toString().toByteArray())
                }
                try { Thread.sleep(2000) } catch (x: InterruptedException) { }
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) { }
        try { server?.close() } catch (e: Exception) { }
        try { lock?.release() } catch (e: Exception) { }
        socket = null; server = null
        linkUp = false
    }

    // ---------- discovery (UDP) ----------
    private fun broadcast(e: Entry, payload: ByteArray) {
        val s = socket ?: return
        try {
            val (nonce, ct) = encrypt(e, payload)
            val out = ByteArray(18 + ct.size)
            out[0] = 'T'.code.toByte(); out[1] = 'S'.code.toByte()
            System.arraycopy(e.tag, 0, out, 2, 4); System.arraycopy(nonce, 0, out, 6, 12); System.arraycopy(ct, 0, out, 18, ct.size)
            for (a in targets) { try { s.send(DatagramPacket(out, out.size, a, PORT)) } catch (x: Exception) { } }
        } catch (x: Exception) { }
    }

    private fun receiveLoop() {
        val s = socket ?: return
        try { s.soTimeout = 1000 } catch (e: Exception) { return }
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try { pkt.length = buf.size; s.receive(pkt); handleUdp(buf, pkt.length, pkt.address) }
            catch (e: SocketTimeoutException) { }
            catch (e: Exception) { if (!running) break }
        }
    }

    private fun handleUdp(d: ByteArray, len: Int, addr: InetAddress) {
        if (len < 40 || d[0] != 'T'.code.toByte() || d[1] != 'S'.code.toByte()) return
        val e = cache.firstOrNull { tagEq(it.tag, d, 2) } ?: return
        try {
            val o = JSONObject(String(decrypt(e, d.copyOfRange(6, 18), d, 18, len - 18)))
            val dev = o.getString("d")
            if (dev == deviceId) return
            val tripId = o.getString("t"); val name = o.getString("n")
            val p = Peer(addr, o.getInt("p"), name, tripId, dev, System.currentTimeMillis())
            val mine = e.trip
            if (mine == null) {                       // this phone is waiting to join with a key
                if (joinFound == null && joinBusy.compareAndSet(false, true)) thread(name = "sync-join") { fetchTrip(p, e) }
                return
            }
            if (tripId != mine.id) return
            peers["$tripId|$dev"] = p
            seen = seen + ("$tripId|$name" to p.seenAt)
            val k = "$tripId|$dev"
            if (p.seenAt - (lastTry[k] ?: 0L) > 20_000) { lastTry[k] = p.seenAt; syncWith(p, e) }
        } catch (x: Exception) { }
    }

    // ---------- merging (TCP) ----------
    fun syncNow(tripId: String) {
        val now = System.currentTimeMillis()
        val e = cache.firstOrNull { it.trip?.id == tripId } ?: return
        for (p in peers.values) if (p.tripId == tripId && now - p.seenAt < 10_000) syncWith(p, e)
    }

    private fun syncWith(p: Peer, e: Entry) {
        val t = e.trip ?: return
        thread(name = "sync-out") {
            active.incrementAndGet(); syncing = true
            try {
                val local = trips().find { it.id == t.id } ?: return@thread
                Socket().use { s ->
                    s.connect(InetSocketAddress(p.ip, p.port), 3000); s.soTimeout = 20000
                    val ins = DataInputStream(BufferedInputStream(s.getInputStream()))
                    val outs = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    writeFrame(outs, e, ("SYNC ${local.me}\n" + snapshot(local)).toByteArray()); outs.flush()
                    val (_, rep) = readFrame(ins)
                    val txt = String(rep)
                    if (txt.startsWith("SNAP ")) finish(t.id, tripFrom(JSONObject(txt.substring(5))), p.name)
                }
            } catch (x: Exception) { } finally { if (active.decrementAndGet() == 0) syncing = false }
        }
    }

    private fun fetchTrip(p: Peer, e: Entry) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(p.ip, p.port), 3000); s.soTimeout = 20000
                val ins = DataInputStream(BufferedInputStream(s.getInputStream()))
                val outs = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                writeFrame(outs, e, "TRIP".toByteArray()); outs.flush()
                val (_, rep) = readFrame(ins)
                val txt = String(rep)
                if (txt.startsWith("SNAP ")) { val tr = tripFrom(JSONObject(txt.substring(5))); main.post { if (joinKey != null) joinFound = tr } }
            }
        } catch (x: Exception) { } finally { joinBusy.set(false) }
    }

    private fun finish(tripId: String, remote: Trip, who: String) {
        main.post {
            val fresh = apply(tripId, remote)
            val now = System.currentTimeMillis()
            lastSync = lastSync + (tripId to now)
            syncedAt = syncedAt + ("$tripId|$who" to now)
            if (fresh.isNotEmpty()) incoming = (incoming + fresh.map {
                Incoming(tripId, it.addedBy.ifBlank { who }, "${it.category} ${inr(it.amount)}", now)
            }).takeLast(20)
        }
    }

    private fun acceptLoop() {
        val ss = server ?: return
        while (running) {
            try { val s = ss.accept(); thread(name = "sync-serve") { serve(s) } }
            catch (e: Exception) { if (!running) break }
        }
    }

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 20000
            val ins = DataInputStream(BufferedInputStream(s.getInputStream()))
            val outs = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val (e, plain) = readFrame(ins)
            val t = e.trip ?: return
            val req = String(plain)
            when {
                req == "TRIP" -> {
                    val cur = trips().find { it.id == t.id } ?: t
                    writeFrame(outs, e, ("SNAP " + snapshot(cur)).toByteArray()); outs.flush()
                }
                req.startsWith("SYNC ") -> {
                    val nl = req.indexOf('\n')
                    val who = req.substring(5, nl)
                    val remote = tripFrom(JSONObject(req.substring(nl + 1)))
                    val local = trips().find { it.id == t.id } ?: return
                    if (remote.id == t.id) {
                        writeFrame(outs, e, ("SNAP " + snapshot(mergeTrips(local, remote))).toByteArray()); outs.flush()
                        finish(t.id, remote, who)
                    }
                }
            }
        } catch (x: Exception) { } finally { try { s.close() } catch (x: Exception) { } }
    }
}
