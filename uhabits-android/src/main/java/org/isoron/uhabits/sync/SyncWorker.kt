/*
 * Copyright (C) 2026 Loop Habit Tracker contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.isoron.uhabits.HabitsApplication
import java.util.concurrent.TimeUnit

/** Runs a sync in the background, whenever WorkManager decides it is a good time. */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? HabitsApplication ?: return Result.failure()
        val outcome = app.component.syncManager.syncNow()
        return if (outcome is SyncOutcome.Failed && outcome.retryable && runAttemptCount < MAX_RETRIES) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val MAX_RETRIES = 5
    }
}

object SyncScheduler {
    private const val PERIODIC = "loop-sync-periodic"
    private const val AFTER_CHANGE = "loop-sync-after-change"
    private const val NOW = "loop-sync-now"

    /** Local changes are uploaded after this delay, so that a burst of check-ins syncs once. */
    const val CHANGE_DELAY_SECONDS = 5L

    private fun constraints() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Syncs every 15 minutes (the minimum allowed by Android), whenever there is a network. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun requestSync(context: Context, delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val name = if (delaySeconds > 0) AFTER_CHANGE else NOW
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC)
        workManager.cancelUniqueWork(AFTER_CHANGE)
        workManager.cancelUniqueWork(NOW)
    }
}
