package com.devtool.gpsmocker.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.devtool.gpsmocker.db.LandmarkDatabase
import com.devtool.gpsmocker.db.LandmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

object CsvHelper {
    private const val TAG       = "CsvHelper"
    private const val HEADER    = "name,lat,lon,category,continent,summary,wikiId"
    private const val AUTHORITY = "com.devtool.gpsmocker.fileprovider"

    suspend fun exportToCsv(context: Context): Uri? = withContext(Dispatchers.IO) {
        try {
            val rows = LandmarkDatabase.get(context).landmarkDao().getAll()
            if (rows.isEmpty()) return@withContext null
            val file = File(context.cacheDir, "landmarks_export.csv")
            BufferedWriter(FileWriter(file)).use { w ->
                w.write(HEADER); w.newLine()
                rows.forEach { w.write(toRow(it)); w.newLine() }
            }
            FileProvider.getUriForFile(context, AUTHORITY, file)
        } catch (e: Exception) { Log.e(TAG, "export: ${e.message}", e); null }
    }

    data class ImportResult(val inserted: Int, val skipped: Int, val errors: Int)

    suspend fun importFromCsv(context: Context, uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            var inserted = 0; var skipped = 0; var errors = 0
            try {
                val dao = LandmarkDatabase.get(context).landmarkDao()
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        val batch = mutableListOf<LandmarkEntity>()
                        var rawLine: String? = reader.readLine()
                        while (rawLine != null) {
                            val line = rawLine.trim()
                            if (line.isNotEmpty() && !line.startsWith("name,", true) &&
                                !line.startsWith("Name,")) {
                                try {
                                    val cols = parseCsv(line)
                                    if (cols.size >= 3) {
                                        val name = cols[0].trim()
                                        val lat = cols[1].trim().toDoubleOrNull()
                                        val lon = cols[2].trim().toDoubleOrNull()
                                        if (name.isNotBlank() && lat != null && lon != null &&
                                            lat in -90.0..90.0 && lon in -180.0..180.0
                                        ) {
                                            batch.add(
                                                LandmarkEntity(
                                                    name = name, lat = lat, lon = lon,
                                                    category = cols.getOrNull(3)?.trim() ?: "landmark",
                                                    continent = cols.getOrNull(4)?.trim() ?: "",
                                                    summary = cols.getOrNull(5)?.trim() ?: "",
                                                    wikiId = cols.getOrNull(6)?.trim()?.toIntOrNull() ?: 0
                                                )
                                            )
                                            if (batch.size >= 200) {
                                                val before = dao.count()
                                                dao.insertAll(batch)
                                                inserted += dao.count() - before
                                                skipped += batch.size - (dao.count() - before)
                                                batch.clear()
                                            }
                                        } else {
                                            skipped++
                                        }
                                    } else {
                                        skipped++
                                    }
                                } catch (e: Exception) {
                                    errors++
                                }
                            }
                            rawLine = reader.readLine()
                        }
                        if (batch.isNotEmpty()) {
                            val before = dao.count()
                            dao.insertAll(batch)
                            inserted += dao.count() - before
                            skipped += batch.size - (dao.count() - before)
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "import: ${e.message}", e); errors++ }
            ImportResult(inserted, skipped, errors)
        }

    private fun toRow(lm: LandmarkEntity) = listOf(
        esc(lm.name), lm.lat, lm.lon, esc(lm.category),
        esc(lm.continent), esc(lm.summary), lm.wikiId
    ).joinToString(",")

    private fun esc(v: String) =
        if (v.contains(',') || v.contains('"') || v.contains('\n'))
            "\"${v.replace("\"","\"\"")}\""
        else v

    private fun parseCsv(line: String): List<String> {
        val cols = mutableListOf<String>(); val cur = StringBuilder()
        var inQ = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQ && c=='"' && i+1<line.length && line[i+1]=='"' -> { cur.append('"'); i+=2 }
                inQ && c=='"' -> { inQ=false; i++ }
                !inQ && c=='"' -> { inQ=true; i++ }
                !inQ && c==',' -> { cols.add(cur.toString()); cur.clear(); i++ }
                else -> { cur.append(c); i++ }
            }
        }
        cols.add(cur.toString()); return cols
    }
}
