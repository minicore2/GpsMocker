package com.devtool.gpsmocker.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit

data class SearchResult(
    val displayName: String,   // full display string
    val shortName:   String,   // landmark / road name
    val city:        String,   // city / district
    val country:     String,   // country
    val point:       GeoPoint
)

object GeocoderHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Search via Nominatim (OpenStreetMap).
     * Returns up to 8 results ordered by relevance.
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val url = "https://nominatim.openstreetmap.org/search" +
                  "?q=${query.trim().replace(" ", "+")}" +
                  "&format=json&addressdetails=1&limit=8&accept-language=zh-TW,en"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GpsMocker/2.0 Android")
            .build()

        try {
            val body = client.newCall(request).execute().use { it.body?.string() ?: "" }
            val arr  = JSONArray(body)
            val results = mutableListOf<SearchResult>()

            for (i in 0 until arr.length()) {
                val obj  = arr.getJSONObject(i)
                val addr = obj.optJSONObject("address")

                val lat = obj.getString("lat").toDouble()
                val lon = obj.getString("lon").toDouble()

                // Build short name: use name, road, or display_name prefix
                val shortName = addr?.let {
                    it.optString("tourism").takeIf { s -> s.isNotEmpty() }
                        ?: it.optString("amenity").takeIf { s -> s.isNotEmpty() }
                        ?: it.optString("building").takeIf { s -> s.isNotEmpty() }
                        ?: it.optString("road").takeIf { s -> s.isNotEmpty() }
                        ?: it.optString("pedestrian").takeIf { s -> s.isNotEmpty() }
                        ?: obj.optString("display_name").split(",").firstOrNull()?.trim()
                        ?: query
                } ?: obj.optString("display_name").split(",").firstOrNull()?.trim() ?: query

                val city = addr?.let {
                    it.optString("city").takeIf { s -> s.isNotEmpty() }
                        ?: it.optString("town").takeIf { s -> s.isNotEmpty() }
                        ?: it.optString("county").takeIf { s -> s.isNotEmpty() }
                        ?: it.optString("state").takeIf { s -> s.isNotEmpty() }
                        ?: ""
                } ?: ""

                val country = addr?.optString("country") ?: ""

                results.add(
                    SearchResult(
                        displayName = obj.optString("display_name"),
                        shortName   = shortName,
                        city        = city,
                        country     = country,
                        point       = GeoPoint(lat, lon)
                    )
                )
            }
            results
        } catch (e: Exception) {
            android.util.Log.e("GeocoderHelper", "search error: ${e.message}")
            emptyList()
        }
    }
}
