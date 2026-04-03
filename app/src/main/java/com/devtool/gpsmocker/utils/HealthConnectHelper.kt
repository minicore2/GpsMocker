package com.devtool.gpsmocker.utils

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

object HealthConnectHelper {

    private const val TAG = "HealthConnectHelper"

    // Both READ and WRITE must be explicitly listed
    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
    )

    /**
     * Check Health Connect availability.
     *
     * Android 13: needs standalone HC app installed → SDK_AVAILABLE
     * Android 14: HC is built-in → SDK_AVAILABLE
     *             Sometimes returns SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
     *             on Android 14 even though it works — we treat that as available too.
     */
    fun isAvailable(context: Context): Boolean {
        return try {
            val status = HealthConnectClient.getSdkStatus(context)
            Log.d(TAG, "HC SDK status: $status")
            status == HealthConnectClient.SDK_AVAILABLE ||
                    status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        } catch (e: Exception) {
            Log.e(TAG, "isAvailable error: ${e.message}")
            false
        }
    }

    /**
     * Get client safely — returns null if unavailable.
     */
    private fun clientOrNull(context: Context): HealthConnectClient? {
        return try {
            if (isAvailable(context)) {
                HealthConnectClient.getOrCreate(context).also {
                    Log.d(TAG, "HC client created OK")
                }
            } else {
                Log.w(TAG, "HC not available on this device")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getClient error: ${e.message}")
            null
        }
    }

    /**
     * Check if BOTH read and write permissions are granted.
     */
    suspend fun hasPermissions(context: Context): Boolean {
        return try {
            val client = clientOrNull(context) ?: return false
            val granted = client.permissionController.getGrantedPermissions()
            Log.d(TAG, "Granted HC permissions: $granted")
            granted.containsAll(PERMISSIONS)
        } catch (e: Exception) {
            Log.e(TAG, "hasPermissions error: ${e.message}")
            false
        }
    }

    /**
     * Read today's steps. Returns -1 on any error.
     */
    suspend fun readTodaySteps(context: Context): Long {
        return try {
            val client = clientOrNull(context) ?: return -1L
            val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val response = client.aggregate(
                AggregateRequest(
                    metrics         = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, Instant.now())
                )
            )
            val steps = response[StepsRecord.COUNT_TOTAL] ?: 0L
            Log.d(TAG, "HC today steps: $steps")
            steps
        } catch (e: Exception) {
            Log.e(TAG, "readTodaySteps error: ${e.message}")
            -1L
        }
    }

    /**
     * Write steps. Returns false on any error.
     */
    suspend fun writeSteps(context: Context, steps: Int): Boolean {
        return try {
            if (steps <= 0) return true
            val client = clientOrNull(context) ?: return false
            val now    = Instant.now()
            val offset = ZoneId.systemDefault().rules.getOffset(now)
            val record = StepsRecord(
                startTime       = now.minusSeconds(1),
                endTime         = now,
                count           = steps.toLong(),
                startZoneOffset = offset,
                endZoneOffset   = offset,
                metadata        = Metadata.manualEntry()
            )
            client.insertRecords(listOf(record))
            Log.d(TAG, "HC wrote $steps steps")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeSteps error: ${e.message}")
            false
        }
    }
}
