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

// ── Wikipedia Random Landmark ─────────────────────────────────────────────────

data class LandmarkResult(
    val name:    String,
    val summary: String,   // short Wikipedia extract
    val point:   GeoPoint
)

/**
 * Strategy: Wikipedia "random" API + Geosearch
 *
 * Step 1 – Pick N random Wikipedia page IDs in the "landmark / tourist attraction"
 *           space by using the categorymembers API for a broad category list,
 *           then picking one at random from the returned batch.
 *
 * Step 2 – For each candidate, fetch its coordinates via the Wikipedia
 *           geo|coordinates prop API. Pages without coordinates are skipped.
 *
 * Step 3 – Return the first hit with valid coordinates + a short extract.
 *
 * This means every call can surface a completely different obscure landmark
 * from any country on earth.
 */
object WikiLandmarkHelper {

    private const val TAG = "WikiLandmarkHelper"

    // Broad Wikipedia categories that contain geolocated landmark articles.
    // We rotate through them randomly so results are diverse.
    private val CATEGORIES = listOf(
        "World_Heritage_Sites",
        "Tourist_attractions",
        "National_parks",
        "Cathedrals",
        "Castles",
        "Ancient_cities",
        "Waterfalls",
        "Museums",
        "Bridges",
        "Volcanoes",
        "Islands",
        "Mountains",
        "Archaeological_sites",
        "Temples",
        "Palaces",
        "Lighthouses",
        "Natural_monuments",
        "Historic_districts",
        "Botanical_gardens",
        "Zoos",
        "Amusement_parks",
        "Beaches",
        "Lakes",
        "Caves",
        "Glaciers",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Return a random geolocated Wikipedia landmark.
     * Retries up to [maxAttempts] times across different categories if a
     * page has no coordinates.
     */
    suspend fun random(maxAttempts: Int = 6): LandmarkResult? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            repeat(maxAttempts) { attempt ->
                try {
                    val category = CATEGORIES.random()
                    // Fetch up to 50 members from this category, offset randomly
                    val offset = (0..500).random()
                    val membersUrl = "https://en.wikipedia.org/w/api.php" +
                        "?action=query&list=categorymembers&cmtitle=Category:$category" +
                        "&cmlimit=50&cmoffset=$offset&cmnamespace=0&format=json"

                    val membersBody = fetch(membersUrl) ?: return@repeat
                    val members = org.json.JSONObject(membersBody)
                        .optJSONObject("query")
                        ?.optJSONArray("categorymembers") ?: return@repeat

                    if (members.length() == 0) return@repeat

                    // Pick a random page from the batch
                    val page = members.getJSONObject((0 until members.length()).random())
                    val pageId = page.optInt("pageid", -1).takeIf { it > 0 } ?: return@repeat
                    val title  = page.optString("title").takeIf { it.isNotBlank() } ?: return@repeat

                    // Fetch coordinates + extract for this page
                    val detailUrl = "https://en.wikipedia.org/w/api.php" +
                        "?action=query&pageids=$pageId" +
                        "&prop=coordinates|extracts&exintro=true&exsentences=2&explaintext=true" +
                        "&format=json"

                    val detailBody = fetch(detailUrl) ?: return@repeat
                    val pageObj = org.json.JSONObject(detailBody)
                        .optJSONObject("query")
                        ?.optJSONObject("pages")
                        ?.optJSONObject(pageId.toString()) ?: return@repeat

                    val coordsArr = pageObj.optJSONArray("coordinates")
                    if (coordsArr == null || coordsArr.length() == 0) return@repeat

                    val coord = coordsArr.getJSONObject(0)
                    val lat = coord.optDouble("lat", Double.NaN)
                    val lon = coord.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) return@repeat

                    val extract = pageObj.optString("extract", "")
                        .replace(Regex("\\s+"), " ")
                        .take(120)
                        .trimEnd()

                    android.util.Log.d(TAG, "Found: $title [$lat, $lon] via $category (attempt $attempt)")
                    return@withContext LandmarkResult(
                        name    = title,
                        summary = extract,
                        point   = GeoPoint(lat, lon)
                    )
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Attempt $attempt failed: ${e.message}")
                }
            }
            null  // all attempts exhausted
        }

    private fun fetch(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PikminGPSMocker/2.0 Android (com.devtool.gpsmocker)")
            .build()
        client.newCall(req).execute().use { it.body?.string() }
    } catch (e: Exception) {
        android.util.Log.e(TAG, "fetch error: ${e.message}")
        null
    }
}
