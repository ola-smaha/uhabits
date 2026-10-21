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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.isoron.platform.io.Database
import org.isoron.platform.io.begin
import org.isoron.platform.io.commit
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.settings.SettingsActivity
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.SyncAppliedCommand
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.sync.DriveAppDataClient
import org.isoron.uhabits.core.sync.SyncApplier
import org.isoron.uhabits.core.sync.SyncMerger
import org.isoron.uhabits.core.sync.SyncSchema
import org.isoron.uhabits.core.sync.SyncSnapshot
import org.isoron.uhabits.core.sync.SyncSnapshotReader
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.database.AndroidDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

sealed class SyncOutcome {
    object Disabled : SyncOutcome()
    object NeedsAuth : SyncOutcome()
    data class Success(val downloaded: Boolean, val uploaded: Boolean) : SyncOutcome()
    data class Failed(val message: String, val retryable: Boolean) : SyncOutcome()
}

/** A sync problem that retrying will not fix. */
class SyncException(message: String) : Exception(message)

/**
 * Keeps the local database in sync with a single file stored in the hidden app folder of the
 * user's Google Drive.
 *
 * Each sync downloads the file only if another device changed it, merges it into the local
 * database (see [SyncMerger]) and uploads the result if this device has anything new.
 */
class SyncManager(
    private val context: Context,
    private val db: Database,
    private val dbFile: File,
    private val habitList: HabitList,
    modelFactory: ModelFactory,
    private val commandRunner: CommandRunner,
    private val notificationTray: NotificationTray
) : CommandRunner.Listener {
    val prefs = SyncPrefs(context)
    private val applier = SyncApplier(db, habitList, modelFactory)
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingLocalChanges = false

    fun start() {
        commandRunner.addListener(this)
        if (prefs.enabled) SyncScheduler.schedulePeriodic(context)
    }

    override fun onCommandFinished(command: Command) {
        if (command is SyncAppliedCommand || !prefs.enabled) return
        pendingLocalChanges = true
        SyncScheduler.requestSync(context, SyncScheduler.CHANGE_DELAY_SECONDS)
    }

    fun onAppOpened() {
        if (prefs.enabled) SyncScheduler.requestSync(context, 0)
    }

    fun requestSyncNow() {
        if (prefs.enabled) SyncScheduler.requestSync(context, 0)
    }

    /** Turns sync on, after the user granted access to Google Drive. */
    fun enable() {
        if (!prefs.enabled) prefs.forgetSyncState()
        prefs.enabled = true
        prefs.needsAuth = false
        prefs.lastError = null
        cancelReconnectNotification()
        SyncScheduler.schedulePeriodic(context)
        SyncScheduler.requestSync(context, 0)
    }

    /** Turns sync off on this device. The data in Google Drive is kept. */
    fun disable() {
        prefs.enabled = false
        prefs.forgetSyncState()
        cancelReconnectNotification()
        SyncScheduler.cancelAll(context)
    }

    /** Runs a full sync. Safe to call from any thread; concurrent calls run one at a time. */
    suspend fun syncNow(): SyncOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            var outcome: SyncOutcome
            var rounds = 0
            do {
                pendingLocalChanges = false
                outcome = syncOnce()
                rounds++
            } while (outcome is SyncOutcome.Success && pendingLocalChanges && rounds < 3)
            outcome
        }
    }

    private fun syncOnce(): SyncOutcome {
        if (!prefs.enabled) return SyncOutcome.Disabled
        return try {
            val token = GoogleDriveAuth.getTokenSilently(context) ?: return needsAuth()
            try {
                syncWithToken(token)
            } catch (e: DriveAppDataClient.UnauthorizedException) {
                GoogleDriveAuth.clearToken(context, token)
                val freshToken = GoogleDriveAuth.getTokenSilently(context) ?: return needsAuth()
                syncWithToken(freshToken)
            }
        } catch (e: DriveAppDataClient.UnauthorizedException) {
            needsAuth()
        } catch (e: DriveAppDataClient.DriveException) {
            val retryable = e.code == 429 || e.code >= 500 || RATE_LIMIT_REASONS.contains(e.reason ?: "")
            failed(e.message ?: "HTTP ${e.code}", retryable)
        } catch (e: SyncException) {
            failed(e.message ?: "", retryable = false)
        } catch (e: IOException) {
            failed(e.message ?: e.javaClass.simpleName, retryable = true)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            failed(GoogleDriveAuth.describe(context, e), GoogleDriveAuth.isTransient(e))
        }
    }

    private fun syncWithToken(token: String): SyncOutcome {
        val drive = DriveAppDataClient(token)
        if (prefs.accountEmail == null) drive.userEmail()?.let { prefs.accountEmail = it }

        repeat(MAX_ATTEMPTS) {
            val files = drive.listFiles(FILE_NAME)
            val primary = files.firstOrNull()

            // Nothing in Google Drive yet: this device goes first.
            if (primary == null) {
                val upload = prepareUpload()
                val created = drive.create(FILE_NAME, upload.bytes)
                prefs.markSynced(created.md5 ?: upload.md5, upload.fingerprint, now())
                Log.i(TAG, "Uploaded initial data (${upload.bytes.size} bytes)")
                return SyncOutcome.Success(downloaded = false, uploaded = true)
            }

            val onlyFile = files.size == 1
            val remoteUnchanged = onlyFile && primary.md5 != null && primary.md5 == prefs.remoteMd5
            if (remoteUnchanged) {
                val fingerprint = SyncSnapshotReader.read(db).fingerprint()
                if (fingerprint == prefs.localFingerprint) {
                    prefs.markSynced(primary.md5, fingerprint, now())
                    Log.i(TAG, "Already in sync")
                    return SyncOutcome.Success(downloaded = false, uploaded = false)
                }
                // Only this device has changed since the last sync.
                val upload = prepareUpload()
                if (drive.getFile(primary.id)?.md5 != primary.md5) return@repeat
                val updated = drive.update(primary.id, upload.bytes)
                prefs.markSynced(updated.md5 ?: upload.md5, upload.fingerprint, now())
                Log.i(TAG, "Uploaded local changes (${upload.bytes.size} bytes)")
                return SyncOutcome.Success(downloaded = false, uploaded = true)
            }

            // Another device has changed the data. Merge it into the local database.
            var remote = SyncSnapshot.EMPTY
            for (file in files) {
                remote = SyncMerger.merge(remote, readRemoteSnapshot(drive.download(file.id)))
            }
            val result = applier.apply(remote)
            if (result.changedAnything) announce(result)
            Log.i(
                TAG,
                "Merged remote data: created=${result.createdHabits.size} " +
                    "updated=${result.updatedHabits.size} deleted=${result.deletedHabits.size} " +
                    "entries=${result.habitsWithChangedEntries.size}"
            )

            if (onlyFile && result.localState == remote) {
                prefs.markSynced(primary.md5, result.localState.fingerprint(), now())
                return SyncOutcome.Success(downloaded = true, uploaded = false)
            }

            // This device has something the others don't. Upload the merged data.
            val upload = prepareUpload()
            if (drive.getFile(primary.id)?.md5 != primary.md5) return@repeat
            val updated = drive.update(primary.id, upload.bytes)
            for (extra in files.drop(1)) drive.delete(extra.id)
            prefs.markSynced(updated.md5 ?: upload.md5, upload.fingerprint, now())
            Log.i(TAG, "Uploaded merged data (${upload.bytes.size} bytes)")
            return SyncOutcome.Success(downloaded = true, uploaded = true)
        }
        throw IOException("The data in Google Drive kept changing during sync")
    }

    private class Upload(val bytes: ByteArray, val md5: String, val fingerprint: Long)

    /** Takes a consistent copy of the local database, compressed. */
    private fun prepareUpload(): Upload {
        val tmp = File(context.cacheDir, "sync-upload.db")
        val snapshot: SyncSnapshot
        db.begin()
        try {
            snapshot = SyncSnapshotReader.read(db)
            dbFile.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            db.commit()
        }
        val bytes = try {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { gz -> tmp.inputStream().use { it.copyTo(gz) } }
            out.toByteArray()
        } finally {
            tmp.delete()
        }
        if (bytes.size > MAX_UPLOAD_BYTES) {
            throw SyncException("The database is too large to sync (${bytes.size / 1024} KB compressed)")
        }
        return Upload(bytes, md5Hex(bytes), snapshot.fingerprint())
    }

    /** Opens a database downloaded from Google Drive and reads its data. */
    private fun readRemoteSnapshot(compressed: ByteArray): SyncSnapshot {
        val tmp = File(context.cacheDir, "sync-download.db")
        SQLiteDatabase.deleteDatabase(tmp)
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val sqlite = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val remoteDb = AndroidDatabase(sqlite)
            val version = remoteDb.getVersion()
            if (version > DATABASE_VERSION) {
                throw SyncException(context.getString(R.string.sync_newer_version))
            }
            if (version < DATABASE_VERSION) {
                runBlocking {
                    remoteDb.migrateTo(DATABASE_VERSION) { v ->
                        context.assets.open("migrations/%02d.sql".format(v)).bufferedReader().readText()
                    }
                }
            }
            SyncSchema.ensure(remoteDb)
            return SyncSnapshotReader.read(remoteDb)
        } finally {
            sqlite.close()
            SQLiteDatabase.deleteDatabase(tmp)
        }
    }

    /** Tells lists, widgets, reminders and notifications that data changed. */
    private fun announce(result: SyncApplier.Result) {
        val changed = (result.createdHabits + result.updatedHabits + result.habitsWithChangedEntries).distinct()
        mainHandler.post {
            try {
                for (habit in result.deletedHabits) notificationTray.cancel(habit)
                for (habit in result.habitsWithChangedEntries) {
                    if (habit.isCompletedToday()) notificationTray.cancel(habit)
                }
                commandRunner.notifyListeners(SyncAppliedCommand(changed))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh after sync", e)
            }
        }
    }

    private fun needsAuth(): SyncOutcome {
        val alreadyNeeded = prefs.needsAuth
        prefs.needsAuth = true
        if (!alreadyNeeded) showReconnectNotification()
        return SyncOutcome.NeedsAuth
    }

    private fun failed(message: String, retryable: Boolean): SyncOutcome {
        Log.w(TAG, "Sync failed (retryable=$retryable): $message")
        prefs.lastError = message
        return SyncOutcome.Failed(message, retryable)
    }

    private fun showReconnectNotification() {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.sync),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            val intent = Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.sync_reconnect_title))
                .setContentText(context.getString(R.string.sync_reconnect_text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Could not show the reconnect notification", e)
        }
    }

    private fun cancelReconnectNotification() {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "SyncManager"
        const val FILE_NAME = "loop-habits-sync.db.gz"
        private const val MAX_ATTEMPTS = 3
        private const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 0x5c4c
        private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")

        private fun md5Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
