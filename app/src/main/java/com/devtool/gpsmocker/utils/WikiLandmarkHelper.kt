package com.devtool.gpsmocker.utils

import android.content.Context
import android.util.Log
import com.devtool.gpsmocker.db.LandmarkDatabase
import com.devtool.gpsmocker.db.LandmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint

data class LandmarkResult(
    val name:    String,
    val summary: String,
    val point:   GeoPoint
)

object WikiLandmarkHelper {

    private const val TAG = "WikiLandmarkHelper"

    /** Return a random landmark from Room DB; fall back to seed list if DB empty. */
    suspend fun random(context: Context): LandmarkResult? =
        withContext(Dispatchers.IO) {
            val dao = LandmarkDatabase.get(context).landmarkDao()
            val entity = dao.random()
            if (entity != null) {
                Log.d(TAG, "DB: ${entity.name} [${entity.continent}]")
                entity.toLandmarkResult()
            } else {
                Log.d(TAG, "DB empty — seed fallback")
                SEEDS.random().toLandmarkResult()
            }
        }

    suspend fun dbCount(context: Context): Int =
        withContext(Dispatchers.IO) {
            LandmarkDatabase.get(context).landmarkDao().count()
        }

    suspend fun dbStats(context: Context): List<com.devtool.gpsmocker.db.ContinentStat> =
        withContext(Dispatchers.IO) {
            LandmarkDatabase.get(context).landmarkDao().statsByContinent()
        }

    private fun LandmarkEntity.toLandmarkResult() =
        LandmarkResult(name = name, summary = summary, point = GeoPoint(lat, lon))

    private data class Seed(val name: String, val lat: Double, val lon: Double)
    private fun Seed.toLandmarkResult() =
        LandmarkResult(name = name, summary = "", point = GeoPoint(lat, lon))

    // ~100 seed landmarks across all continents — used before first DB fetch
    private val SEEDS = listOf(
        Seed("台北101",              25.0338,  121.5646),
        Seed("太魯閣國家公園",       24.1525,  121.6219),
        Seed("日月潭",               23.8638,  120.9125),
        Seed("阿里山",               23.5136,  120.8032),
        Seed("九份老街",             25.1093,  121.8442),
        Seed("富士山",               35.3606,  138.7274),
        Seed("東京鐵塔",             35.6586,  139.7454),
        Seed("京都嵐山",             35.0167,  135.6780),
        Seed("奈良東大寺",           34.6888,  135.8399),
        Seed("首爾景福宮",           37.5796,  126.9770),
        Seed("濟州漢拏山",           33.3625,  126.5339),
        Seed("泰姬瑪哈陵",           27.1751,   78.0421),
        Seed("吳哥窟",               13.4125,  103.8670),
        Seed("峇里島烏布",           -8.5069,  115.2625),
        Seed("婆羅浮屠",             -7.6079,  110.2038),
        Seed("曼谷大皇宮",           13.7500,  100.4914),
        Seed("下龍灣",               20.9101,  107.1839),
        Seed("長城八達嶺",           40.3594,  116.0200),
        Seed("北京故宮",             39.9163,  116.3972),
        Seed("西安兵馬俑",           34.3841,  109.2785),
        Seed("黃山",                 30.1338,  118.1689),
        Seed("張家界天門山",         29.1327,  110.4480),
        Seed("桂林漓江",             25.2740,  110.2990),
        Seed("九寨溝",               33.2600,  103.9170),
        Seed("布達拉宮",             29.6575,   91.1175),
        Seed("新加坡濱海灣花園",      1.2816,  103.8636),
        Seed("吉隆坡雙子塔",          3.1579,  101.7116),
        Seed("杜拜哈里發塔",         25.1972,   55.2744),
        Seed("約旦佩特拉",           30.3285,   35.4444),
        Seed("耶路撒冷舊城",         31.7767,   35.2345),
        Seed("伊斯坦堡聖索菲亞",     41.0086,   28.9802),
        Seed("卡帕多奇亞",           38.6431,   34.8289),
        Seed("艾菲爾鐵塔",           48.8584,    2.2945),
        Seed("羅浮宮",               48.8606,    2.3376),
        Seed("大笨鐘",               51.5007,   -0.1246),
        Seed("羅馬競技場",           41.8902,   12.4922),
        Seed("梵蒂岡聖彼得大教堂",   41.9022,   12.4539),
        Seed("雅典帕德嫩神廟",       37.9715,   23.7267),
        Seed("桑托里尼",             36.3932,   25.4615),
        Seed("巴塞隆納聖家堂",       41.4036,    2.1744),
        Seed("布拉格舊城廣場",       50.0875,   14.4214),
        Seed("維也納美泉宮",         48.1843,   16.3122),
        Seed("阿姆斯特丹運河",       52.3731,    4.8919),
        Seed("冰島瀑布",             64.3271,  -20.1199),
        Seed("挪威峽灣",             62.1035,    7.0868),
        Seed("愛丁堡城堡",           55.9486,   -3.1999),
        Seed("威尼斯聖馬可廣場",     45.4341,   12.3388),
        Seed("瑞士少女峰",           46.5582,    7.9086),
        Seed("科隆大教堂",           50.9413,    6.9583),
        Seed("葡萄牙辛特拉宮",       38.7878,   -9.3906),
        Seed("吉薩大金字塔",         29.9792,   31.1342),
        Seed("盧克索神廟",           25.6989,   32.6421),
        Seed("馬拉喀什麥地那",       31.6295,   -7.9811),
        Seed("塞倫蓋提",             -2.3333,   34.8333),
        Seed("維多利亞瀑布",        -17.9243,   25.8572),
        Seed("開普敦桌山",          -33.9625,   18.4107),
        Seed("吉力馬扎羅山",         -3.0674,   37.3556),
        Seed("自由女神像",           40.6892,  -74.0445),
        Seed("帝國大廈",             40.7484,  -73.9856),
        Seed("大峽谷",               36.0544, -112.1401),
        Seed("黃石老忠實噴泉",       44.4605, -110.8281),
        Seed("優勝美地國家公園",     37.8651, -119.5383),
        Seed("尼加拉瀑布",           43.0962,  -79.0377),
        Seed("墨西哥奇琴伊察",       20.6843,  -88.5678),
        Seed("羚羊峽谷",             36.8619, -111.3743),
        Seed("夏威夷火山",           19.4194, -155.2885),
        Seed("加拿大班夫",           51.4968, -115.9281),
        Seed("里約基督像",          -22.9519,  -43.2105),
        Seed("馬丘比丘",            -13.1631,  -72.5450),
        Seed("伊瓜蘇瀑布",          -25.6953,  -54.4367),
        Seed("加拉巴哥群島",         -0.9538,  -90.9656),
        Seed("烏尤尼鹽湖",          -20.1338,  -67.4891),
        Seed("復活節島",            -27.1127, -109.3497),
        Seed("雪梨歌劇院",          -33.8568,  151.2153),
        Seed("大堡礁",              -18.2861,  147.6992),
        Seed("艾爾斯岩",            -25.3444,  131.0369),
        Seed("紐西蘭米佛峽灣",      -44.6717,  167.9270),
        Seed("帛琉海洋保護區",        7.5150,  134.5825),
        Seed("澳洲藍山",            -33.7139,  150.3116),
    )
}
