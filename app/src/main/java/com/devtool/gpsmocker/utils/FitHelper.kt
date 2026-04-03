package com.devtool.gpsmocker.utils

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.*
import com.google.android.gms.fitness.request.DataReadRequest
import java.util.concurrent.TimeUnit

object FitHelper {

    private const val TAG = "FitHelper"

    val fitnessOptions: FitnessOptions = FitnessOptions.builder()
        .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_READ)
        .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_WRITE)
        .addDataType(DataType.AGGREGATE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_READ)
        .build()

    /**
     * Returns the signed-in Google account.
     * We intentionally do NOT call GoogleSignIn.hasPermissions() here because
     * on some devices/Play Services versions it returns false even after the user
     * has granted Fit access (the scope list in the cached token doesn't match).
     *
     * Instead: if there's a signed-in account, we just try to read and handle
     * the failure gracefully inside readTodaySteps().
     */
    fun getAuthorizedAccount(context: Context) =
        GoogleSignIn.getLastSignedInAccount(context)

    /**
     * True if we have a Google account to attempt Fit operations with.
     * A false here means the user has never signed in to any Google service
     * in this app — we need to run the OAuth flow.
     */
    fun hasPermission(context: Context): Boolean =
        getAuthorizedAccount(context) != null

    /**
     * Read today's total steps from Google Fit.
     * Calls [onResult] with step count, or -1 on auth failure.
     */
    fun readTodaySteps(context: Context, onResult: (Long) -> Unit) {
        val account = getAuthorizedAccount(context)
        if (account == null) {
            Log.w(TAG, "readTodaySteps: no authorized account")
            onResult(-1)
            return
        }

        // Use local time midnight as start-of-day
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val now = System.currentTimeMillis()

        val request = DataReadRequest.Builder()
            .aggregate(DataType.TYPE_STEP_COUNT_DELTA)
            .bucketByTime(1, TimeUnit.DAYS)
            .setTimeRange(startOfDay, now, TimeUnit.MILLISECONDS)
            .build()

        Fitness.getHistoryClient(context, account)
            .readData(request)
            .addOnSuccessListener { response ->
                val steps = response.buckets
                    .flatMap { it.dataSets }
                    .flatMap { it.dataPoints }
                    .sumOf { it.getValue(Field.FIELD_STEPS).asInt().toLong() }
                Log.d(TAG, "Today's steps from Fit: $steps")
                onResult(steps)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to read steps: ${e.message}")
                onResult(0)
            }
    }

    /**
     * Write [stepsDelta] steps into Google Fit right now.
     */
    fun writeSteps(context: Context, stepsDelta: Int, onDone: ((Boolean) -> Unit)? = null) {
        if (stepsDelta <= 0) { onDone?.invoke(true); return }

        val account = getAuthorizedAccount(context)
        if (account == null) {
            Log.w(TAG, "writeSteps: no authorized account, skipping")
            onDone?.invoke(false)
            return
        }

        val dataSource = DataSource.Builder()
            .setAppPackageName(context.packageName)
            .setDataType(DataType.TYPE_STEP_COUNT_DELTA)
            .setStreamName("GpsMocker_steps")
            .setType(DataSource.TYPE_RAW)
            .build()

        val now = System.currentTimeMillis()
        val dataPoint = DataPoint.builder(dataSource)
            .setField(Field.FIELD_STEPS, stepsDelta)
            .setTimeInterval(now - 1000L, now, TimeUnit.MILLISECONDS)
            .build()

        val dataSet = DataSet.builder(dataSource).add(dataPoint).build()

        Fitness.getHistoryClient(context, account)
            .insertData(dataSet)
            .addOnSuccessListener {
                Log.d(TAG, "Wrote $stepsDelta steps to Fit")
                onDone?.invoke(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to write steps: ${e.message}")
                onDone?.invoke(false)
            }
    }
}
