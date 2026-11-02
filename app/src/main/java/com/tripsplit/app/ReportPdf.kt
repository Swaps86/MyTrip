package com.tripsplit.app

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

const val CLOSING = "Thank you all for the wonderful memories. Safe travels and see you on the next trip."
const val CLOSING_TITLE = "Trip completed successfully!"

fun summaryText(t: Trip): String {
    val left = t.budget - t.spent
    val byCat = t.expenses.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
        .toList().sortedByDescending { it.second }
    return buildString {
        append("${t.name} · ${fmtDate(t.start)} – ${fmtDate(t.end)}\n")
        append("Total spent: ${inr(t.spent)} of ${inr(t.budget)}\n")
        append(if (left >= 0) "Left: ${inr(left)}\n" else "Over budget by: ${inr(-left)}\n")
        val byPerson = t.expenses.groupBy { it.paidBy }.mapValues { e -> e.value.sumOf { it.amount } }
            .toList().sortedByDescending { it.second }
        if (byPerson.isNotEmpty()) {
            append("\nPaid by each person\n")
            byPerson.forEach { (n, a) -> append("• $n ${inr(a)}\n") }
        }
        if (byCat.isNotEmpty()) {
            append("\nBy category\n")
            byCat.forEach { (c, a) -> append("• $c ${inr(a)}\n") }
        }
        append("\n$CLOSING_TITLE $CLOSING\n\nSent from TripSplit")
    }
}

fun fileName(t: Trip) = t.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "Trip" } + "_Report.pdf"

fun downloadPdf(ctx: Context, t: Trip) {
    try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName(t))
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { Toast.makeText(ctx, "Could not save PDF", Toast.LENGTH_LONG).show(); return }
        ctx.contentResolver.openOutputStream(uri)!!.use { buildPdf(t, it) }
        Toast.makeText(ctx, "Saved to Downloads: ${fileName(t)}", Toast.LENGTH_SHORT).show()
        // open the PDF automatically
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "PDF saved. Install a PDF viewer app to open it.", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(ctx, "Could not save PDF", Toast.LENGTH_LONG).show()
    }
}

fun sharePdf(ctx: Context, t: Trip) {
    try {
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val f = File(dir, fileName(t))
        FileOutputStream(f).use { buildPdf(t, it) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, "${t.name} – expense report")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launchShare(ctx, i)
    } catch (e: Exception) {
        Toast.makeText(ctx, "Could not share PDF", Toast.LENGTH_LONG).show()
    }
}

fun shareText(ctx: Context, text: String) =
    launchShare(ctx, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))

/** Opens WhatsApp directly if installed, otherwise the normal share sheet. */
private fun launchShare(ctx: Context, i: Intent) {
    try { ctx.startActivity(Intent(i).setPackage("com.whatsapp")) }
    catch (e: ActivityNotFoundException) { ctx.startActivity(Intent.createChooser(i, "Share")) }
}

// ---------------- PDF drawing ----------------
private const val PW = 595
private const val PH = 842
private const val MG = 40f

fun buildPdf(t: Trip, out: OutputStream) {
    val doc = PdfDocument()
    val pages = render(t, null, 0)   // first pass: count pages
    render(t, doc, pages)            // second pass: draw
    doc.writeTo(out)
    doc.close()
}

private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
    val out = mutableListOf<String>(); var line = ""
    for (w in s.split(" ")) {
        val test = if (line.isEmpty()) w else "$line $w"
        if (p.measureText(test) <= maxW) line = test else { if (line.isNotEmpty()) out += line; line = w }
    }
    if (line.isNotEmpty()) out += line
    return out
}

private fun fit(s: String, p: Paint, maxW: Float): String {
    if (p.measureText(s) <= maxW) return s
    val n = p.breakText(s, true, maxW - p.measureText("…"), null)
    return s.take(n) + "…"
}

private fun render(t: Trip, doc: PdfDocument?, total: Int): Int {
    var pageNo = 0
    var page: PdfDocument.Page? = null
    var c: Canvas? = null
    var y = 0f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val green = Color.parseColor("#0F6B5C"); val grey = Color.parseColor("#55605A")
    val tint = Color.parseColor("#EAF4F1"); val line = Color.parseColor("#DDE3DE")

    fun style(size: Float, bold: Boolean = false, color: Int = Color.BLACK, align: Paint.Align = Paint.Align.LEFT) {
        p.textSize = size; p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.color = color; p.textAlign = align
    }
    fun txt(s: String, x: Float, yy: Float) { c?.drawText(s, x, yy, p) }
    fun box(l: Float, top: Float, r: Float, b: Float, color: Int) {
        fill.color = color; fill.style = Paint.Style.FILL; c?.drawRoundRect(RectF(l, top, r, b), 8f, 8f, fill)
    }
    fun hline(yy: Float) { fill.color = line; fill.strokeWidth = 0.8f; c?.drawLine(MG, yy, PW - MG, yy, fill) }
    fun finish() {
        if (page != null) {
            style(9f, false, grey)
            txt("Shri Swami Samarth · © 2026 Swapnil Palande · TripSplit v$APP_VERSION", MG, PH - 36f)
            txt("Generated by TripSplit · ${fmtDate(todayUtc())} · Page $pageNo of $total", MG, PH - 24f)
            doc!!.finishPage(page!!)
            page = null
        }
    }
    fun newPage() {
        finish()
        pageNo++
        if (doc != null) { page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create()); c = page!!.canvas }
        y = MG + 8
    }
    fun need(h: Float) { if (y + h > PH - 50f) newPage() }
    fun para(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) {
        style(size, bold, color)
        for (l in wrap(s, p, PW - 2 * MG)) {
            need(size + 6); style(size, bold, color); txt(l, MG, y + size); y += size + 5
        }
    }
    fun section(s: String) { need(44f); y += 8; style(13f, true); txt(s, MG, y + 13); y += 24 }

    newPage()
    para("TRIPSPLIT · EXPENSE REPORT", 10f, true, green)
    para(t.name, 26f, true)
    para(listOf("${fmtDate(t.start)} – ${fmtDate(t.end)}", t.destinations.joinToString(", ")).filter { it.isNotBlank() }
        .joinToString(" · "), 11f, false, grey)
    if (t.travelers.isNotEmpty()) para("Travelers (${t.travelers.size}): ${t.travelers.joinToString(", ")}", 11f)
    y += 8

    // summary boxes
    need(64f)
    val left = t.budget - t.spent
    val w = (PW - 2 * MG - 20f) / 3
    val items = listOf("Total spent" to inr(t.spent), "Budget" to inr(t.budget),
        (if (left >= 0) "Remaining" else "Over budget") to inr(Math.abs(left)))
    items.forEachIndexed { i, (lab, v) ->
        val x = MG + i * (w + 10f)
        box(x, y, x + w, y + 48f, tint)
        style(9f, false, grey); txt(lab, x + 10f, y + 17f)
        style(15f, true); txt(v, x + 10f, y + 37f)
    }
    y += 62f

    // expenses table
    val dateX = MG; val catX = MG + 72f; val paidX = MG + 142f; val detX = MG + 207f; val amtR = PW - MG
    val groups = t.expenses.groupBy { it.paidBy }.toList().sortedByDescending { g -> g.second.sumOf { it.amount } }
    if (groups.isNotEmpty()) {
        section("Paid by each person")
        style(9f, true, grey); txt("NAME", MG, y); txt("PAYMENTS", MG + 200f, y)
        style(9f, true, grey, Paint.Align.RIGHT); txt("TOTAL PAID", amtR, y)
        y += 6; hline(y); y += 14
        for ((name, l) in groups) {
            need(20f)
            style(11f); txt(fit(name, p, 180f), MG, y); txt("${l.size}", MG + 200f, y)
            style(11f, true, Color.BLACK, Paint.Align.RIGHT); txt(inr(l.sumOf { it.amount }), amtR, y)
            y += 6; hline(y); y += 14
        }
    }
    section("Expenses (${t.expenses.size})")
    style(9f, true, grey); txt("DATE", dateX, y); txt("CATEGORY", catX, y); txt("PAID BY", paidX, y); txt("DETAILS", detX, y)
    style(9f, true, grey, Paint.Align.RIGHT); txt("AMOUNT", amtR, y)
    y += 6; hline(y); y += 14
    for (e in t.expenses.sortedBy { it.date }) {
        need(20f)
        style(10f); txt(fmtDate(e.date).take(11), dateX, y)
        txt(fit(e.category, p, 64f), catX, y)
        txt(fit(e.paidBy, p, 60f), paidX, y)
        txt(fit(listOf(e.location, e.note).filter { it.isNotBlank() }.joinToString(" · "), p, amtR - 70f - detX), detX, y)
        style(10f, true, Color.BLACK, Paint.Align.RIGHT); txt(inr(e.amount), amtR, y)
        y += 6; hline(y); y += 14
    }
    if (t.expenses.isEmpty()) { style(10f, false, grey); txt("No expenses recorded.", MG, y); y += 16 }

    // category totals
    val byCat = t.expenses.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
        .toList().sortedByDescending { it.second }
    if (byCat.isNotEmpty()) {
        section("By category")
        for ((cat, a) in byCat) {
            need(20f)
            style(11f); txt(cat, MG, y)
            style(11f, true, Color.BLACK, Paint.Align.RIGHT)
            txt("${inr(a)}  ·  ${(a / t.spent * 100).toInt()}%", amtR, y)
            y += 6; hline(y); y += 14
        }
    }

    // closing message
    style(11f)
    val lines = wrap(CLOSING, p, PW - 2 * MG - 28f)
    val h = 28f + 18f + lines.size * 15f
    need(h + 16f); y += 16f
    box(MG, y, PW - MG, y + h, tint)
    style(13f, true, green); txt(CLOSING_TITLE, MG + 14f, y + 24f)
    style(11f, false, green)
    lines.forEachIndexed { i, l -> txt(l, MG + 14f, y + 44f + i * 15f) }
    y += h

    val n = pageNo
    finish()
    return n
}
