package com.tripsplit.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Backup(val trips: List<Trip>, val cats: List<String>, val saved: Long)

fun parseBackup(text: String): Backup? = try {
    val o = JSONObject(text)
    if (o.optString("app") != "TripSplit") null else {
        val a = o.getJSONArray("trips")
        Backup(List(a.length()) { tripFrom(a.getJSONObject(it)) },
            o.optJSONArray("cats")?.strings() ?: emptyList(), o.optLong("saved", 0L))
    }
} catch (e: Exception) { null }

fun backupFileName(prefix: String = "TripSplit_Backup") =
    "${prefix}_${SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())}.json"

fun saveTextToDownloads(ctx: Context, name: String, text: String): Boolean = try {
    val resolver = ctx.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "application/json")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    if (uri == null) false else {
        resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        // mark the file as finished so it shows up in Files / Downloads straight away
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        true
    }
} catch (e: Exception) { false }

/** Opens the phone's Downloads list. */
fun openDownloads(ctx: Context) {
    try {
        ctx.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Open the Files app and look in Downloads", android.widget.Toast.LENGTH_LONG).show()
    }
}

fun shareBackupFile(ctx: Context, name: String, text: String) {
    val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
    val f = File(dir, name); f.writeText(text)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val i = Intent(Intent.ACTION_SEND).setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share backup"))
}

fun readTextFromUri(ctx: Context, uri: Uri): String? = try {
    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
} catch (e: Exception) { null }
