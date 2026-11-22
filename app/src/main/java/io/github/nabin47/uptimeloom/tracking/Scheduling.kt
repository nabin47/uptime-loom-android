package io.github.nabin47.uptimeloom.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.nabin47.uptimeloom.Graph
import java.util.concurrent.TimeUnit

/** All background work, kept to the minimum WorkManager allows. */
object Scheduling {
    private const val EXIT_CONFIRM = "exit-confirm"
    private const val BOOT_CHECK = "boot-check"
    private const val MAINTENANCE = "daily-maintenance"
    private const val FALLBACK = "fallback-location-check"

    fun scheduleExitConfirm(context: Context, exitTime: Long, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<ExitConfirmWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("exitTime" to exitTime))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(EXIT_CONFIRM, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelExitConfirm(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(EXIT_CONFIRM)
    }

    fun scheduleBootCheck(context: Context, bootTime: Long) {
        val req = OneTimeWorkRequestBuilder<BootCheckWorker>()
            .setInitialDelay(10, TimeUnit.MINUTES)
            .setInputData(workDataOf("bootTime" to bootTime))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(BOOT_CHECK, ExistingWorkPolicy.REPLACE, req)
    }

    /** Once a day: re-arm geofences (they can be dropped by the system) and prune the event log. */
    fun ensureDailyMaintenance(context: Context) {
        val req = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(MAINTENANCE, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Only on phones without Google Play services: check location every 15 minutes. */
    fun setFallbackChecks(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            val req = PeriodicWorkRequestBuilder<FallbackCheckWorker>(15, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(FALLBACK, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(FALLBACK)
        }
    }

    fun runFallbackCheckNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<FallbackCheckWorker>().build())
    }
}

class ExitConfirmWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        val exitTime = inputData.getLong("exitTime", System.currentTimeMillis())
        Graph.tracker.confirmExit(exitTime)
        return Result.success()
    }
}

class BootCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        Graph.tracker.bootCheck(inputData.getLong("bootTime", System.currentTimeMillis()))
        return Result.success()
    }
}

class MaintenanceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        AreaMonitor.sync(applicationContext, reason = "daily check")
        Graph.db.events().prune(System.currentTimeMillis() - 30L * 24 * 3_600_000)
        return Result.success()
    }
}

/** Battery-saver fallback for phones without Google Play services. */
class FallbackCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        AreaMonitor.fallbackCheck(applicationContext)
        return Result.success()
    }
}
