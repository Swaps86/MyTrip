package com.tripsplit.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One payment found in an SMS: either a FASTag toll or a UPI "Sent Rs..." payment. */
data class TollSms(
    val plaza: String,            // toll plaza or UPI merchant name
    val amount: Double,
    val dateUtc: Long,
    val time: String,
    val vrn: String,
    val balance: Double?,
    val ref: String,
    val kind: String = "toll",    // "toll" or "upi"
    val category: String = "Toll",
    val confident: Boolean = true, // false = category was only a guess, so not pre-ticked
    val detail: String = ""
)

data class ScanResult(val inTrip: List<TollSms>, val ignored: Int)

private val TOLL = Regex("""(?i)(?:Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)\s+toll paid at\s+(.+?)\s+on\s+(\d{1,2})-(\d{1,2})-(\d{4})\s+at\s+(\d{1,2}):(\d{2})""")
private val VRN = Regex("""(?i)VRN\s+([A-Za-z0-9*]+)""")
private val BAL = Regex("""(?i)Bal\.?\s*(?:Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""")
private val UPI = Regex("""(?i)Sent\s+(?:Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)\s+from\s+(.+?)\s+to\s+(.+?)\s+on\s+(\d{1,2})-(\d{1,2})-(\d{2,4})\.?\s+UPI Ref\s*(?:No\.?)?\s*(\d+)""")
private const val DAY = 86_400_000L

fun refTag(ref: String) = when {
    ref.startsWith("fastag") -> " · FASTag"
    ref.startsWith("upi") -> " · UPI"
    else -> ""
}

private fun utcDate(y: Int, m: Int, d: Int): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d) }.timeInMillis

/** Reads every "... toll paid at <plaza> on dd-mm-yyyy at hh:mm ..." message found in the text. */
fun parseTolls(text: String): List<TollSms> {
    val ms = TOLL.findAll(text).toList()
    return ms.mapIndexed { i, m ->
        val end = if (i + 1 < ms.size) ms[i + 1].range.first else text.length
        val seg = text.substring(m.range.first, end)
        val g = m.groupValues
        val amount = g[1].replace(",", "").toDouble()
        val plaza = g[2].trim()
        val dateUtc = utcDate(g[5].toInt(), g[4].toInt(), g[3].toInt())
        val time = String.format("%02d:%02d", g[6].toInt(), g[7].toInt())
        val vrn = VRN.find(seg)?.groupValues?.get(1) ?: ""
        val bal = BAL.find(seg)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        TollSms(plaza, amount, dateUtc, time, vrn, bal, "fastag|$dateUtc|$time|${plaza.lowercase()}|$amount")
    }
}

/** Guesses a category from the shop name. Returns the category and whether a keyword matched. */
fun guessCategory(name: String): Pair<String, Boolean> {
    val n = name.lowercase()
    fun any(vararg k: String) = k.any { n.contains(it) }
    return when {
        any("petrol", "fuel", "filling", "bpcl", "hpcl", "indian oil", "ioc", "nayara", "shell") -> "Fuel" to true
        any("lodge", "resort", "homestay", "guest house", "stay", "lodging") -> "Hotel" to true
        any("ticket", "museum", "entry", "fort", "amusement") -> "Tickets" to true
        any("mart", "store", "mall", "shop", "fashion", "cloth", "gift", "bazaar", "market") -> "Shopping" to true
        any("veg", "hotel", "restaurant", "dhaba", "cafe", "caf\u00e9", "mess", "bhojan", "food", "kitchen",
            "biryani", "pizza", "bakery", "sweet", "misal", "thali", "juice", "tea", "chai", "snack",
            "canteen", "bhavan", "udupi", "resto", "grill", "dining", "hut", "darbar", "rasoi") -> "Food" to true
        else -> "Food" to false
    }
}

/** Reads UPI "Sent Rs.x from <bank a/c> to <merchant> on dd-mm-yy. UPI Ref nnn" messages. */
fun parseUpi(text: String, smsMillis: Long? = null): List<TollSms> =
    UPI.findAll(text).map { m ->
        val g = m.groupValues
        val amount = g[1].replace(",", "").toDouble()
        val acct = Regex("""(?i)A/c\s*(\S+)""").find(g[2])?.groupValues?.get(1)
        val merchant = g[3].trim().replace(Regex("""(?i)^M/S\.?\s*"""), "").trim()
        val y = g[6].toInt().let { if (it < 100) 2000 + it else it }
        val dateUtc = utcDate(y, g[5].toInt(), g[4].toInt())
        val time = smsMillis?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it)) } ?: ""
        val (cat, sure) = guessCategory(merchant)
        TollSms(merchant, amount, dateUtc, time, "", null, "upi|${g[7]}", "upi", cat, sure,
            listOfNotNull("Ref ${g[7]}", acct?.let { "A/c $it" }).joinToString(" · "))
    }.toList()

fun hasSmsPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

/** Finds FASTag toll and UPI payment messages dated between the trip start and end dates. */
fun scanTollSms(ctx: Context, trip: Trip): ScanResult {
    if (!hasSmsPermission(ctx)) return ScanResult(emptyList(), 0)
    val found = mutableListOf<TollSms>()
    try {
        ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("body", "date"),
            "date>=? AND date<=?", arrayOf((trip.start - 2 * DAY).toString(), (trip.end + 3 * DAY).toString()),
            "date ASC")?.use { c ->
            val bi = c.getColumnIndexOrThrow("body"); val di = c.getColumnIndexOrThrow("date")
            while (c.moveToNext()) {
                val body = c.getString(bi) ?: continue
                if (body.contains("FASTag", true)) found += parseTolls(body)
                if (body.contains("UPI Ref", true)) found += parseUpi(body, c.getLong(di))
            }
        }
    } catch (e: Exception) { }
    val uniq = found.distinctBy { it.ref }
    val inside = uniq.filter { it.dateUtc >= trip.start && it.dateUtc <= trip.end }
    return ScanResult(inside, uniq.size - inside.size)
}

fun tollNote(t: TollSms): String =
    if (t.kind == "upi") listOfNotNull("UPI", t.time.ifBlank { null }, t.detail.ifBlank { null }).joinToString(" · ")
    else listOfNotNull("FASTag", t.time, if (t.vrn.isNotBlank()) "VRN ${t.vrn}" else null,
        t.balance?.let { "Balance ${inr(it)}" }).joinToString(" · ")
