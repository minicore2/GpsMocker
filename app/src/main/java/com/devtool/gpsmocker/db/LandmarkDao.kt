package com.devtool.gpsmocker.db

import androidx.room.*

@Dao
interface LandmarkDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(landmarks: List<LandmarkEntity>)

    @Query("SELECT COUNT(*) FROM landmarks")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM landmarks WHERE continent = :continent")
    suspend fun countByContinent(continent: String): Int

    /** Pick one random landmark (SQLite RANDOM() is fast on indexed tables) */
    @Query("SELECT * FROM landmarks ORDER BY RANDOM() LIMIT 1")
    suspend fun random(): LandmarkEntity?

    /** Pick random within a category */
    @Query("SELECT * FROM landmarks WHERE category = :cat ORDER BY RANDOM() LIMIT 1")
    suspend fun randomByCategory(cat: String): LandmarkEntity?

    /** Pick random within a continent */
    @Query("SELECT * FROM landmarks WHERE continent = :cont ORDER BY RANDOM() LIMIT 1")
    suspend fun randomByContinent(cont: String): LandmarkEntity?

    /** Stats per continent */
    @Query("SELECT continent, COUNT(*) as cnt FROM landmarks GROUP BY continent ORDER BY cnt DESC")
    suspend fun statsByContinent(): List<ContinentStat>

    /** Return all landmarks (for CSV export) */
    @Query("SELECT * FROM landmarks ORDER BY continent, name")
    suspend fun getAll(): List<LandmarkEntity>

    /** Return all wikiIds that already exist (for fast dedup during fetch) */
    @Query("SELECT wikiId FROM landmarks WHERE wikiId > 0")
    suspend fun getAllWikiIds(): List<Int>

    /** Return all lat/lon keys for entries with wikiId=0 (name-only dedup) */
    @Query("SELECT lat, lon FROM landmarks WHERE wikiId = 0")
    suspend fun getAllLatLon(): List<LatLonOnly>

    @Query("DELETE FROM landmarks")
    suspend fun deleteAll()
}

data class LatLonOnly(val lat: Double, val lon: Double)

data class ContinentStat(val continent: String, val cnt: Int)
