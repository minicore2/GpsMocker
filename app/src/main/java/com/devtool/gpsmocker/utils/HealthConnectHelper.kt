package com.devtool.gpsmocker.utils

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata as HealthMetadata
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
        // SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED can still work on Android 14
        status == HealthConnectClient.SDK_AVAILABLE ||
        status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
    } catch (e: Exception) {
        Log.e(TAG, "isAvailable error: ${e.message}")
        false
    }

    private fun clientOrNull(context: Context): HealthConnectClient? = try {
        if (isAvailable(context)) HealthConnectClient.getOrCreate(context)
        else { Log.w(TAG, "HC not available"); null }
    } catch (e: Exception) {
        Log.e(TAG, "getClient error: ${e.message}"); null
    }

    // ── Permissions ───────────────────────────────

    suspend fun hasPermissions(context: Context): Boolean {
        return try {
            val client = clientOrNull(context) ?: return false
            val granted = client.permissionController.getGrantedPermissions()
            Log.d(TAG, "HC granted permissions: $granted")
            granted.containsAll(PERMISSIONS)
        } catch (e: Exception) {
            Log.e(TAG, "hasPermissions error: ${e.message}"); false
        }
    }

    // ── Read ──────────────────────────────────────

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
            Log.e(TAG, "readTodaySteps error: ${e.message}"); -1L
        }
    }

    // ── Write ─────────────────────────────────────

    /**
     * Write [steps] into Health Connect with full Device metadata.
     *
     * Google Fit will only sync steps when:
     *  1. Device.type is NOT TYPE_UNKNOWN
     *  2. Device has manufacturer + model filled in
     *  3. The time interval is at least 1 second wide
     *
     * We use Device.TYPE_PHONE and the actual device's Build info
     * so Google Fit treats the source as trustworthy.
     */
    suspend fun writeSteps(
        context: Context,
        steps: Int,
        startTime: Instant = Instant.now().minusSeconds(steps.toLong().coerceAtLeast(1)),
        endTime: Instant   = Instant.now()
    ): Boolean {
        return try {
            if (steps <= 0) return true
            val client = clientOrNull(context) ?: return false

            // Build a Device object with real hardware info so Google Fit accepts it
            val device = Device(
                manufacturer = Build.MANUFACTURER,          // e.g. "samsung"
                model        = Build.MODEL,                 // e.g. "SM-S928B"
                type         = Device.TYPE_PHONE            // NOT TYPE_UNKNOWN
            )

            val zoneOffset = ZoneId.systemDefault().rules.getOffset(endTime)

            // Ensure interval is at least 1 second (HC rejects zero-length intervals)
            val safeStart = if (endTime.epochSecond - startTime.epochSecond < 1)
                endTime.minusSeconds(1) else startTime

            val record = StepsRecord(
                startTime       = safeStart,
                endTime         = endTime,
                count           = steps.toLong(),
                startZoneOffset = zoneOffset,
                endZoneOffset   = zoneOffset,
                metadata        = HealthMetadata.activelyRecorded(device = device)
            )

            client.insertRecords(listOf(record))
            Log.d(TAG, "HC wrote $steps steps [${safeStart} → ${endTime}] device=${Build.MODEL}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeSteps error: ${e.message}"); false
        }
    }
}
