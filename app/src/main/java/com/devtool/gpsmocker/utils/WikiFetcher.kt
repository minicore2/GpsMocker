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
import kotlin.coroutines.coroutineContext

object WikiFetcher {

    private const val TAG = "WikiFetcher"
    private const val TARGET_PER_CONTINENT = 500

    // Progress callback — always invoked on the coroutine thread;
    // SettingsFragment posts to main thread itself.
    var onProgress: ((String, Int, Int, String) -> Unit)? = null

    // Single OkHttpClient reused across all requests
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private data class ContDef(
        val displayName: String,
        val continent:   String,
        val latMin: Double, val latMax: Double,
        val lonMin: Double, val lonMax: Double
    )

    private val CONTINENTS = listOf(
        ContDef("亞洲",   "Asia",      5.0,  55.0,  26.0, 150.0),
        ContDef("歐洲",   "Europe",   36.0,  71.0, -11.0,  40.0),
        ContDef("非洲",   "Africa",  -35.0,  37.0, -18.0,  51.0),
        ContDef("北美洲", "Americas", 15.0,  72.0,-169.0, -52.0),
        ContDef("南美洲", "Americas",-56.0,  13.0, -82.0, -34.0),
        ContDef("大洋洲", "Oceania", -47.0,  -0.5, 110.0, 179.0),
        ContDef("中東",   "Asia",     12.0,  42.0,  34.0,  63.0),
    )

    private val CATEGORY_KEYWORDS = mapOf(
        "landmark" to listOf("tower","monument","statue","bridge","gate","square",
                             "temple","church","mosque","cathedral","castle","palace",
                             "fort","ruins","arch","shrine","pagoda","lighthouse"),
        "heritage" to listOf("heritage","UNESCO","historic","ancient","archaeological",
                             "conservation","preserved","colonial","old town","world heritage"),
        "culture"  to listOf("museum","gallery","theatre","theater","art","culture",
                             "library","university","opera","concert"),
        "scenery"  to listOf("park","nature","lake","river","waterfall","mountain","beach",
                             "island","canyon","glacier","forest","reserve","bay","volcano",
                             "gorge","valley","cave","hot spring","garden","falls","reef")
    )

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Must be called from a non-main coroutine (e.g. Dispatchers.IO).
     * Clears the DB, then fetches all continents sequentially.
     */
    suspend fun fetchAll(context: Context) = withContext(Dispatchers.IO) {
        val dao = LandmarkDatabase.get(context).landmarkDao()
        dao.deleteAll()

        for (cont in CONTINENTS) {
            coroutineContext.ensureActive()   // stop immediately if job was cancelled
            fetchForContinent(dao, cont)
        }

        val total = dao.count()
        onProgress?.invoke("完成", total, total, "✅ 地標庫更新完成，共 $total 個地點")
    }

    // ── Per-continent fetch ────────────────────────────────────────────────────

    private suspend fun fetchForContinent(
        dao: com.devtool.gpsmocker.db.LandmarkDao,
        cont: ContDef
    ) {
        val inserted = mutableSetOf<String>()
        var fetched  = 0

        // Wikipedia geosearch radius is capped at 10 000 m.
        // Use many small seeds (radius 8 km each) spread across the bounding box.
        val seeds = generateSeeds(cont.latMin, cont.latMax, cont.lonMin, cont.lonMax, count = 120)
        seeds.shuffle()

        onProgress?.invoke(cont.displayName, 0, TARGET_PER_CONTINENT,
            "${cont.displayName}：開始抓取…")

        for (seed in seeds) {
            coroutineContext.ensureActive()
            if (fetched >= TARGET_PER_CONTINENT) break

            try {
                // radius 8000 m stays safely within the 10 000 m limit
                val batch = geosearch(seed.first, seed.second, radiusM = 8_000)

                val entities = batch.mapNotNull { item ->
                    val key = "${"%.4f".format(item.lat)},${"%.4f".format(item.lon)}"
                    if (key in inserted) return@mapNotNull null
                    inserted.add(key)
                    LandmarkEntity(
                        name      = item.name,
                        summary   = "",
                        lat       = item.lat,
                        lon       = item.lon,
                        category  = guessCategory(item.name),
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

                delay(200)   // polite pause — avoids rate-limit 429

            } catch (e: CancellationException) {
                throw e      // always re-throw cancellation
            } catch (e: Exception) {
                Log.w(TAG, "${cont.displayName} seed error: ${e.message}")
                delay(800)   // back-off on error
            }
        }

        Log.d(TAG, "${cont.displayName} done: $fetched inserted")
    }

    // ── Wikipedia geosearch ───────────────────────────────────────────────────

    private data class GeoItem(val pageId: Int, val name: String, val lat: Double, val lon: Double)

    /**
     * Synchronous OkHttp call — safe to call inside Dispatchers.IO coroutine.
     * radiusM must be ≤ 10 000 (Wikipedia API hard limit).
     */
    private fun geosearch(lat: Double, lon: Double, radiusM: Int = 8_000): List<GeoItem> {
        val safeRadius = radiusM.coerceIn(1, 10_000)
        val url = "https://en.wikipedia.org/w/api.php" +
            "?action=query&list=geosearch" +
            "&gscoord=${lat}|${lon}" +
            "&gsradius=${safeRadius}" +
            "&gslimit=50" +
            "&format=json"

        val body = fetchSync(url) ?: return emptyList()

        return try {
            val arr = JSONObject(body)
                .optJSONObject("query")
                ?.optJSONArray("geosearch")
                ?: return emptyList()

            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val lt  = obj.optDouble("lat", Double.NaN)
                val ln  = obj.optDouble("lon", Double.NaN)
                if (lt.isNaN() || ln.isNaN()) return@mapNotNull null
                GeoItem(
                    pageId = obj.optInt("pageid", 0),
                    name   = obj.optString("title", ""),
                    lat    = lt,
                    lon    = ln
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            emptyList()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun guessCategory(title: String): String {
        val lower = title.lowercase()
        for ((cat, kws) in CATEGORY_KEYWORDS) {
            if (kws.any { lower.contains(it) }) return cat
        }
        return "landmark"
    }

    private fun generateSeeds(
        latMin: Double, latMax: Double,
        lonMin: Double, lonMax: Double,
        count:  Int
    ): MutableList<Pair<Double, Double>> {
        val side    = Math.sqrt(count.toDouble()).toInt().coerceAtLeast(2)
        val latStep = (latMax - latMin) / side
        val lonStep = (lonMax - lonMin) / side
        val result  = mutableListOf<Pair<Double, Double>>()
        var la = latMin + latStep / 2.0
        while (la < latMax) {
            var lo = lonMin + lonStep / 2.0
            while (lo < lonMax) {
                val jLat = la + (Math.random() - 0.5) * latStep * 0.4
                val jLon = lo + (Math.random() - 0.5) * lonStep * 0.4
                result.add(jLat.coerceIn(latMin, latMax) to jLon.coerceIn(lonMin, lonMax))
                lo += lonStep
            }
            la += latStep
        }
        return result
    }

    /**
     * Synchronous HTTP GET — must only be called from a background thread / IO dispatcher.
     * Returns null on any error (logged).
     */
    private fun fetchSync(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PikminGPSMocker/2.1 Android (com.devtool.gpsmocker)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $url")
                return null
            }
            resp.body?.string()
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchSync error: ${e.message}")
        null
    }
}
