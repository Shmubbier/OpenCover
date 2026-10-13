package dev.opencover.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

    /** Blocking; call off the main thread. False without permission, location or network. */
    fun fetch(ctx: Context): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        val loc = runCatching {
            ctx.getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull() ?: return false // no fix yet: show nothing rather than 0,0
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
