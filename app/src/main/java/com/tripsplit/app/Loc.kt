package com.tripsplit.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.Locale

fun hasLocPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Finds the current place name (e.g. "Calangute, Goa"). Calls onResult(null) if it can't. */
@SuppressLint("MissingPermission")
fun detectPlace(ctx: Context, onResult: (String?) -> Unit) {
    if (!hasLocPermission(ctx)) { onResult(null); return }
    try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { lm.isProviderEnabled(it) }
        if (provider == null) { onResult(null); return }
        val signal: android.os.CancellationSignal? = null
        val consumer = androidx.core.util.Consumer<Location?> { loc ->
            if (loc == null) onResult(null) else Thread { onResult(nameFor(ctx, loc)) }.start()
        }
        LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(ctx), consumer)
    } catch (e: Exception) { onResult(null) }
}

@Suppress("DEPRECATION")
private fun nameFor(ctx: Context, l: Location): String {
    val coords = String.format(Locale.US, "%.4f, %.4f", l.latitude, l.longitude)
    return try {
        if (!Geocoder.isPresent()) coords else {
            val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
            if (a == null) coords else listOfNotNull(a.subLocality, a.locality, a.adminArea).distinct()
                .joinToString(", ").ifBlank { a.getAddressLine(0) ?: coords }
        }
    } catch (e: Exception) { coords }
}
