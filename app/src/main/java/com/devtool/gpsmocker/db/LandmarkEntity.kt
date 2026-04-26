package com.devtool.gpsmocker.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "landmarks",
    indices = [
        Index("category"),
        Index("continent"),
        Index(value = ["lat", "lon"], unique = true)
    ]
)
data class LandmarkEntity(
    @PrimaryKey(autoGenerate = true) val id:        Int    = 0,
    val name:      String,
    val summary:   String  = "",
    val lat:       Double,
    val lon:       Double,
    val category:  String  = "",   // landmark / heritage / culture / scenery
    val continent: String  = "",   // Asia / Europe / Africa / Americas / Oceania / etc.
    val wikiId:    Int     = 0
)
