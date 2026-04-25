package com.devtool.gpsmocker.utils

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object HealthConnectHelper {

    private const val TAG = "HealthConnectHelper"

    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
    )

    // ── Availability ──────────────────────────────

    fun isAvailable(context: Context): Boolean = try {
        val status = HealthConnectClient.getSdkStatus(context)
        Log.d(TAG, "HC SDK status: $status")
        status == HealthConnectClient.SDK_AVAILABLE ||
        status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
    } catch (e: Exception) {
        Log.e(TAG, "isAvailable error: ${e.message}"); false
    }

    private fun clientOrNull(context: Context): HealthConnectClient? = try {
        if (isAvailable(context)) {
            HealthConnectClient.getOrCreate(context).also { Log.d(TAG, "HC client OK") }
        } else { Log.w(TAG, "HC not available"); null }
    } catch (e: Exception) {
        Log.e(TAG, "getClient error: ${e.message}"); null
    }

    // ── Permissions ───────────────────────────────

    suspend fun hasPermissions(context: Context): Boolean {
        return try {
            val client = clientOrNull(context) ?: return false
            val granted = client.permissionController.getGrantedPermissions()
            Log.d(TAG, "HC granted: $granted")
            granted.containsAll(PERMISSIONS)
        } catch (e: Exception) {
            Log.e(TAG, "hasPermissions error: ${e.message}"); false
        }
    }

    // ── Read ──────────────────────────────────────

    suspend fun readTodaySteps(context: Context): Long {
        return try {
            val client    = clientOrNull(context) ?: return -1L
            val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val response  = client.aggregate(
                AggregateRequest(
                    metrics         = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, Instant.now())
                )
            )
            val steps = response[StepsRecord.COUNT_TOTAL] ?: 0L
            Log.d(TAG, "HC today steps: $steps"); steps
        } catch (e: Exception) {
            Log.e(TAG, "readTodaySteps error: ${e.message}"); -1L
        }
    }

    // ── Write ─────────────────────────────────────

    /**
     * Write [steps] into Health Connect.
     * Providing full Device metadata ensures Google Fit syncs correctly
     * (TYPE_UNKNOWN causes data to be silently deprioritised).
     */
    suspend fun writeSteps(
        context:   Context,
        steps:     Int,
        startTime: Instant = Instant.now().minusSeconds(steps.toLong().coerceAtLeast(1)),
        endTime:   Instant = Instant.now()
    ): Boolean {
        return try {
            if (steps <= 0) return true
            val client = clientOrNull(context) ?: return false

            val device = Device(
                manufacturer = Build.MANUFACTURER,
                model        = Build.MODEL,
                type         = Device.TYPE_PHONE      // must NOT be TYPE_UNKNOWN
            )
            val offset = ZoneId.systemDefault().rules.getOffset(endTime)
            // HC rejects zero-length intervals
            val safeStart = if (endTime.epochSecond - startTime.epochSecond < 1)
                endTime.minusSeconds(1) else startTime

            val record = StepsRecord(
                startTime       = safeStart,
                endTime         = endTime,
                count           = steps.toLong(),
                startZoneOffset = offset,
                endZoneOffset   = offset,
                metadata        = Metadata.activelyRecorded(device)
            )
            client.insertRecords(listOf(record))
            Log.d(TAG, "HC wrote $steps steps [${safeStart}→${endTime}] dev=${Build.MODEL}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeSteps error: ${e.message}"); false
        }
    }
}
