package dev.opencover.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

/** WMO weather codes as used by Open-Meteo. */
fun skyFor(code: Int) = when (code) {
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    45, 48 -> Sky.FOG
    in 51..57 -> Sky.DRIZZLE
    in 61..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.THUNDER
    else -> Sky.CLOUDY
}

data class Reading(val tempC: Double, val code: Int, val at: Long)

/** Open-Meteo "current" block; null for anything else. */
fun parseReading(json: String, now: Long): Reading? = runCatching {
    val c = JSONObject(json).getJSONObject("current")
    Reading(c.getDouble("temperature_2m"), c.getInt("weather_code"), now)
}.getOrNull()

/** A location fix older than 3 hours may be another city by now. */
fun fixUsable(ageMillis: Long) = ageMillis <= 3 * 3600_000L

fun stale(r: Reading, now: Long) = now - r.at > 2 * 3600_000L
fun due(r: Reading?, now: Long) = r == null || now - r.at >= 30 * 60_000L
fun degrees(tempC: Double, fahrenheit: Boolean) = "${(if (fahrenheit) tempC * 9 / 5 + 32 else tempC).roundToInt()}°"

/** Opt-in weather: the only part of OpenCover that uses the internet. */
object Weather {
    var reading by mutableStateOf<Reading?>(null)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains("w_at")) reading = Reading(p.getFloat("w_temp", 0f).toDouble(), p.getInt("w_code", 3), p.getLong("w_at", 0))
    }

    /** Newest recent last-known fix, else a fresh one (up to 10 s). Blocking; null if none. */
    private fun location(ctx: Context): Location? = runCatching {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val age = { l: Location -> (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000 }
        val known = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .minByOrNull(age)
        if (known != null && fixUsable(age(known))) return@runCatching known
        val provider = if (lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
        val latch = CountDownLatch(1)
        var fresh: Location? = null
        lm.getCurrentLocation(provider, null, Executors.newSingleThreadExecutor()) { fresh = it; latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        fresh
    }.getOrNull()

    /** Blocking; call off the main thread. False without permission, location or network. */
    fun fetch(ctx: Context): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        val loc = location(ctx) ?: return false // no fix yet: show nothing rather than 0,0
        // Rough coordinates only (about 1 km).
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code"
            .format(java.util.Locale.US, loc.latitude, loc.longitude)
        val body = runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 8000; readTimeout = 8000
                try { inputStream.bufferedReader().readText() } finally { disconnect() }
            }
        }.getOrNull() ?: return false
        val r = parseReading(body, System.currentTimeMillis()) ?: return false
        reading = r
        prefs(ctx).edit().putFloat("w_temp", r.tempC.toFloat()).putInt("w_code", r.code).putLong("w_at", r.at).apply()
        return true
    }
}
