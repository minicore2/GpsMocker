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

    private const val TAG        = "CsvHelper"
    private const val CSV_HEADER = "name,lat,lon,category,continent,summary,wikiId"
    private const val AUTHORITY  = "com.devtool.gpsmocker.fileprovider"

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Export all landmarks to a CSV file in the app's cache dir.
     * Returns a content:// URI (via FileProvider) ready to share/save,
     * or null on failure.
     */
    suspend fun exportToCsv(context: Context): Uri? = withContext(Dispatchers.IO) {
        try {
            val dao       = LandmarkDatabase.get(context).landmarkDao()
            val landmarks = dao.getAll()
            if (landmarks.isEmpty()) return@withContext null

            val file = File(context.cacheDir, "landmarks_export.csv")
            BufferedWriter(FileWriter(file)).use { w ->
                w.write(CSV_HEADER)
                w.newLine()
                for (lm in landmarks) {
                    w.write(encodeCsvRow(lm))
                    w.newLine()
                }
            }

            Log.d(TAG, "Exported ${landmarks.size} rows to ${file.absolutePath}")

            FileProvider.getUriForFile(context, AUTHORITY, file)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            null
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    data class ImportResult(val inserted: Int, val skipped: Int, val errors: Int)

    /**
     * Import landmarks from a CSV Uri (picked via system file picker).
     * Accepts files with or without the header row.
     * Duplicate lat/lon entries are silently skipped (Room IGNORE strategy).
     */
    suspend fun importFromCsv(context: Context, uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            var inserted = 0
            var skipped  = 0
            var errors   = 0

            try {
                val dao = LandmarkDatabase.get(context).landmarkDao()

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        val batch = mutableListOf<LandmarkEntity>()

                        var rawLine: String?
                        while (reader.readLine().also { rawLine = it } != null) {
                            val line = rawLine?.trim() ?: ""
                            if (line.isEmpty()) continue

                            // Skip header row (matches our own header or generic "name,lat,lon")
                            if (line.startsWith("name,", ignoreCase = true) ||
                                line.startsWith("Name,", ignoreCase = true)) {
                                continue
                            }

                            try {
                                val cols = parseCsvRow(line)
                                if (cols.size < 3) { skipped++; continue }

                                val name = cols[0].trim()
                                val lat  = cols[1].trim().toDoubleOrNull()
                                val lon  = cols[2].trim().toDoubleOrNull()

                                if (name.isBlank() || lat == null || lon == null ||
                                    lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                                    skipped++; continue
                                }

                                batch.add(LandmarkEntity(
                                    name      = name,
                                    lat       = lat,
                                    lon       = lon,
                                    category  = cols.getOrNull(3)?.trim() ?: "landmark",
                                    continent = cols.getOrNull(4)?.trim() ?: "",
                                    summary   = cols.getOrNull(5)?.trim() ?: "",
                                    wikiId    = cols.getOrNull(6)?.trim()?.toIntOrNull() ?: 0
                                ))

                                // Flush in batches of 200 for memory efficiency
                                if (batch.size >= 200) {
                                    val before = dao.count()
                                    dao.insertAll(batch)
                                    val after  = dao.count()
                                    inserted  += (after - before)
                                    skipped   += batch.size - (after - before)
                                    batch.clear()
                                }

                            } catch (e: Exception) {
                                Log.w(TAG, "Row parse error: $line → ${e.message}")
                                errors++
                            }
                        }

                        // Flush remaining
                        if (batch.isNotEmpty()) {
                            val before = dao.count()
                            dao.insertAll(batch)
                            val after  = dao.count()
                            inserted  += (after - before)
                            skipped   += batch.size - (after - before)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import failed: ${e.message}", e)
                errors++
            }

            ImportResult(inserted, skipped, errors)
        }

    // ── CSV encode / decode ───────────────────────────────────────────────────

    private fun encodeCsvRow(lm: LandmarkEntity): String {
        return listOf(
            escapeCsv(lm.name),
            lm.lat.toString(),
            lm.lon.toString(),
            escapeCsv(lm.category),
            escapeCsv(lm.continent),
            escapeCsv(lm.summary),
            lm.wikiId.toString()
        ).joinToString(",")
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }

    /**
     * RFC 4180-compliant CSV parser that handles quoted fields with embedded commas/newlines.
     */
    private fun parseCsvRow(line: String): List<String> {
        val cols  = mutableListOf<String>()
        val cur   = StringBuilder()
        var inQ   = false
        var i     = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQ && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"'); i += 2        // escaped quote inside quoted field
                }
                inQ && c == '"' -> { inQ = false; i++ }
                !inQ && c == '"' -> { inQ = true; i++ }
                !inQ && c == ',' -> { cols.add(cur.toString()); cur.clear(); i++ }
                else -> { cur.append(c); i++ }
            }
        }
        cols.add(cur.toString())
        return cols
    }
}
