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

    @Query("DELETE FROM landmarks")
    suspend fun deleteAll()
}

data class ContinentStat(val continent: String, val cnt: Int)
