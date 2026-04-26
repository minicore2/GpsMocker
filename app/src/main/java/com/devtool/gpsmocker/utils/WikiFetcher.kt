package com.devtool.gpsmocker.utils

import android.content.Context
import android.util.Log
import com.devtool.gpsmocker.db.LandmarkDatabase
import com.devtool.gpsmocker.db.LandmarkEntity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches Wikipedia geolocated articles for 4 categories × 7 continents.
 * Target: ≥ 500 points per continent, stored in Room DB.
 *
 * Wikipedia API strategy:
 *  - geosearch API: returns articles near a coordinate grid, with coordinates embedded.
 *    We scatter seed points across each continent's bounding box and geosearch radius 10000m.
 *  - This is far more reliable than categorymembers because every result HAS coordinates.
 *
 * Categories (mapped to Wikipedia geosearch "namespace=0" with keyword filter):
 *   landmark / heritage / culture / scenery
 */
object WikiFetcher {

    private const val TAG = "WikiFetcher"
    private const val TARGET_PER_CONTINENT = 500

    // Progress callback: (continent, fetched, total_target, message)
    var onProgress: ((String, Int, Int, String) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Continent seed grids: bounding boxes divided into sample points
    // Each entry: (continent, lat_min, lat_max, lon_min, lon_max)
    private val CONTINENTS = listOf(
        ContDef("亞洲",    "Asia",      5.0,  55.0,  26.0, 150.0),
        ContDef("歐洲",    "Europe",   36.0,  71.0, -11.0,  40.0),
        ContDef("非洲",    "Africa",  -35.0,  37.0, -18.0,  51.0),
        ContDef("北美洲",  "Americas", 15.0,  72.0,-169.0, -52.0),
        ContDef("南美洲",  "Americas",-56.0,  13.0, -82.0, -34.0),
        ContDef("大洋洲",  "Oceania", -47.0,  -0.5, 110.0, 179.0),
        ContDef("中東",    "Asia",     12.0,  42.0,  34.0,  63.0),
    )

    // Category keywords used to filter geosearch results by type
    private val CATEGORY_KEYWORDS = mapOf(
        "landmark"  to listOf("tower","monument","statue","bridge","gate","square","landmark","temple","church","mosque","cathedral","castle","palace","fort","ruins","arch"),
        "heritage"  to listOf("heritage","UNESCO","historic","ancient","archaeological","conservation","preserved","colonial","old town"),
        "culture"   to listOf("museum","gallery","theatre","theater","art","culture","library","university","opera"),
        "scenery"   to listOf("park","nature","lake","river","waterfall","mountain","beach","island","canyon","glacier","forest","reserve","bay","volcano")
    )

    private data class ContDef(
        val displayName: String,
        val continent:   String,
        val latMin: Double, val latMax: Double,
        val lonMin: Double, val lonMax: Double
    )

    suspend fun fetchAll(context: Context) {
        val dao = LandmarkDatabase.get(context).landmarkDao()
        dao.deleteAll()

        CONTINENTS.forEach { cont ->
            fetchForContinent(dao, cont)
        }
        val total = dao.count()
        onProgress?.invoke("完成", total, total, "✅ 地標庫更新完成，共 $total 個地點")
    }

    private suspend fun fetchForContinent(
        dao: com.devtool.gpsmocker.db.LandmarkDao,
        cont: ContDef
    ) {
        val inserted = mutableSetOf<String>() // "lat,lon" dedup key
        var fetched = 0

        // Generate a grid of seed points across this continent's bounding box
        val seeds = generateSeeds(cont.latMin, cont.latMax, cont.lonMin, cont.lonMax, 80)
        seeds.shuffle()

        for (seed in seeds) {
            if (fetched >= TARGET_PER_CONTINENT) break

            try {
                val batch = geosearch(seed.first, seed.second, radiusKm = 80)
                val entities = batch.mapNotNull { item ->
                    val key = "${"%.3f".format(item.lat)},${"%.3f".format(item.lon)}"
                    if (inserted.contains(key)) return@mapNotNull null
                    inserted.add(key)
                    val cat = guessCategory(item.name)
                    LandmarkEntity(
                        name      = item.name,
                        summary   = item.snippet,
                        lat       = item.lat,
                        lon       = item.lon,
                        category  = cat,
                        continent = cont.continent,
                        wikiId    = item.pageId
                    )
                }

                if (entities.isNotEmpty()) {
                    dao.insertAll(entities)
                    fetched += entities.size
                    onProgress?.invoke(
                        cont.displayName,
                        fetched,
                        TARGET_PER_CONTINENT,
                        "${cont.displayName}：已抓取 $fetched / $TARGET_PER_CONTINENT"
                    )
                }

                // Be polite to Wikipedia API
                delay(300)

            } catch (e: Exception) {
                Log.w(TAG, "${cont.displayName} seed error: ${e.message}")
                delay(500)
            }
        }

        Log.d(TAG, "${cont.displayName} done: $fetched inserted")
    }

    private data class GeoItem(
        val pageId: Int,
        val name:   String,
        val snippet: String,
        val lat:    Double,
        val lon:    Double
    )

    /**
     * Wikipedia geosearch: returns up to 50 geolocated articles near a point.
     * Every result is guaranteed to have coordinates — no extra coordinate lookup needed.
     */
    private suspend fun geosearch(lat: Double, lon: Double, radiusKm: Int = 80): List<GeoItem> =
        withContext(Dispatchers.IO) {
            val url = "https://en.wikipedia.org/w/api.php" +
                "?action=query&list=geosearch" +
                "&gscoord=${lat}|${lon}" +
                "&gsradius=${radiusKm * 1000}" +   // metres
                "&gslimit=50" +
                "&format=json"

            val body = fetch(url) ?: return@withContext emptyList()
            val arr  = JSONObject(body)
                .optJSONObject("query")
                ?.optJSONArray("geosearch") ?: return@withContext emptyList()

            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val lt  = obj.optDouble("lat", Double.NaN)
                val ln  = obj.optDouble("lon", Double.NaN)
                if (lt.isNaN() || ln.isNaN()) return@mapNotNull null
                GeoItem(
                    pageId  = obj.optInt("pageid", 0),
                    name    = obj.optString("title", ""),
                    snippet = "",   // geosearch doesn't return extracts; keep empty for speed
                    lat     = lt,
                    lon     = ln
                )
            }
        }

    /** Heuristic category from article title keywords */
    private fun guessCategory(title: String): String {
        val lower = title.lowercase()
        for ((cat, kws) in CATEGORY_KEYWORDS) {
            if (kws.any { lower.contains(it) }) return cat
        }
        return "landmark"
    }

    /** Generate a grid of (lat, lon) seeds covering a bounding box */
    private fun generateSeeds(
        latMin: Double, latMax: Double,
        lonMin: Double, lonMax: Double,
        count: Int
    ): MutableList<Pair<Double, Double>> {
        val sqrt  = Math.sqrt(count.toDouble()).toInt().coerceAtLeast(1)
        val latStep = (latMax - latMin) / sqrt
        val lonStep = (lonMax - lonMin) / sqrt
        val result = mutableListOf<Pair<Double, Double>>()
        var lat = latMin + latStep / 2
        while (lat < latMax) {
            var lon = lonMin + lonStep / 2
            while (lon < lonMax) {
                // Add small random jitter so repeated runs surface different articles
                val jLat = lat + (Math.random() - 0.5) * latStep * 0.5
                val jLon = lon + (Math.random() - 0.5) * lonStep * 0.5
                result.add(Pair(jLat.coerceIn(latMin, latMax), jLon.coerceIn(lonMin, lonMax)))
                lon += lonStep
            }
            lat += latStep
        }
        return result
    }

    private fun fetch(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PikminGPSMocker/2.1 Android (com.devtool.gpsmocker)")
            .build()
        client.newCall(req).execute().use { it.body?.string() }
    } catch (e: Exception) {
        Log.e(TAG, "fetch error: ${e.message}")
        null
    }
}
