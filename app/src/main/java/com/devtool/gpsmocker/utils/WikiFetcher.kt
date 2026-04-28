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

object WikiFetcher {

    private const val TAG = "WikiFetcher"
    private const val TARGET_PER_CONTINENT = 500

    var onProgress: ((String, Int, Int, String) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private data class ContDef(
        val displayName: String, val continent: String,
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

    /**
     * Incremental update — existing data is NEVER deleted.
     * All wikiIds and lat/lon keys already in the DB are loaded into HashSets
     * before starting. Each geosearch result is checked in O(1); duplicates
     * are skipped immediately without touching the DB, saving bandwidth.
     */
    suspend fun fetchAll(context: Context) = withContext(Dispatchers.IO) {
        val dao = LandmarkDatabase.get(context).landmarkDao()

        // Build dedup sets from existing DB (one-time load)
        val existingWikiIds = HashSet<Int>(dao.getAllWikiIds())
        val existingLatLon  = dao.getAllLatLon().mapTo(HashSet()) {
            "${"%.4f".format(it.lat)},${"%.4f".format(it.lon)}"
        }

        val beforeTotal = dao.count()
        Log.d(TAG, "Incremental fetch start. DB=$beforeTotal, wikiIds=${existingWikiIds.size}")
        onProgress?.invoke("開始", beforeTotal, beforeTotal,
            "已有 $beforeTotal 個地點，開始增量更新…")

        for (cont in CONTINENTS) {
            ensureActive()
            fetchForContinent(dao, cont, existingWikiIds, existingLatLon)
        }

        val added = dao.count() - beforeTotal
        val total = dao.count()
        onProgress?.invoke("完成", total, total, "✅ 更新完成，新增 $added 個，共 $total 個地點")
    }

    private suspend fun fetchForContinent(
        dao:             com.devtool.gpsmocker.db.LandmarkDao,
        cont:            ContDef,
        existingWikiIds: HashSet<Int>,
        existingLatLon:  HashSet<String>
    ) {
        var newThisSession = 0
        val seeds = generateSeeds(cont.latMin, cont.latMax, cont.lonMin, cont.lonMax, 120)
        seeds.shuffle()

        onProgress?.invoke(cont.displayName, 0, TARGET_PER_CONTINENT,
            "${cont.displayName}：開始增量抓取…")

        for (seed in seeds) {
            currentCoroutineContext().ensureActive()
            if (newThisSession >= TARGET_PER_CONTINENT) break

            try {
                val batch = geosearch(seed.first, seed.second, radiusM = 8_000)

                val entities = batch.mapNotNull { item ->
                    // Primary dedup: wikiId
                    if (item.pageId > 0 && item.pageId in existingWikiIds) return@mapNotNull null
                    // Secondary dedup: lat/lon (covers CSV-imported rows with wikiId=0)
                    val llKey = "${"%.4f".format(item.lat)},${"%.4f".format(item.lon)}"
                    if (llKey in existingLatLon) return@mapNotNull null

                    // Register immediately so same-session duplicates are also skipped
                    if (item.pageId > 0) existingWikiIds.add(item.pageId)
                    existingLatLon.add(llKey)

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
                    newThisSession += entities.size
                    onProgress?.invoke(cont.displayName, newThisSession, TARGET_PER_CONTINENT,
                        "${cont.displayName}：本次新增 $newThisSession / $TARGET_PER_CONTINENT")
                }

                delay(200)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${cont.displayName} error: ${e.message}")
                delay(800)
            }
        }

        Log.d(TAG, "${cont.displayName} done: $newThisSession new this session")
    }

    private data class GeoItem(val pageId: Int, val name: String, val lat: Double, val lon: Double)

    private fun geosearch(lat: Double, lon: Double, radiusM: Int = 8_000): List<GeoItem> {
        val url = "https://en.wikipedia.org/w/api.php" +
            "?action=query&list=geosearch" +
            "&gscoord=${lat}|${lon}" +
            "&gsradius=${radiusM.coerceIn(1, 10_000)}" +
            "&gslimit=50&format=json"
        val body = fetchSync(url) ?: return emptyList()
        return try {
            val arr = JSONObject(body).optJSONObject("query")
                ?.optJSONArray("geosearch") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o  = arr.getJSONObject(i)
                val lt = o.optDouble("lat", Double.NaN)
                val ln = o.optDouble("lon", Double.NaN)
                if (lt.isNaN() || ln.isNaN()) null
                else GeoItem(o.optInt("pageid", 0), o.optString("title", ""), lt, ln)
            }
        } catch (e: Exception) { Log.e(TAG, "parse: ${e.message}"); emptyList() }
    }

    private fun guessCategory(title: String): String {
        val lower = title.lowercase()
        for ((cat, kws) in CATEGORY_KEYWORDS) { if (kws.any { lower.contains(it) }) return cat }
        return "landmark"
    }

    private fun generateSeeds(
        latMin: Double, latMax: Double, lonMin: Double, lonMax: Double, count: Int
    ): MutableList<Pair<Double, Double>> {
        val side = Math.sqrt(count.toDouble()).toInt().coerceAtLeast(2)
        val latStep = (latMax - latMin) / side
        val lonStep = (lonMax - lonMin) / side
        val result  = mutableListOf<Pair<Double, Double>>()
        var la = latMin + latStep / 2.0
        while (la < latMax) {
            var lo = lonMin + lonStep / 2.0
            while (lo < lonMax) {
                result.add(
                    (la + (Math.random()-.5)*latStep*.4).coerceIn(latMin,latMax) to
                    (lo + (Math.random()-.5)*lonStep*.4).coerceIn(lonMin,lonMax)
                )
                lo += lonStep
            }
            la += latStep
        }
        return result
    }

    private fun fetchSync(url: String): String? = try {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "PikminGPSMocker/2.1 Android (com.devtool.gpsmocker)")
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "HTTP ${r.code}"); null }
            else r.body?.string()
        }
    } catch (e: Exception) { Log.e(TAG, "fetch: ${e.message}"); null }
}
