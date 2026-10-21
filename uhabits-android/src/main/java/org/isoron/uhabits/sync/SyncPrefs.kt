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
import android.content.SharedPreferences

/**
 * Sync state, kept apart from the regular app preferences so that background syncs don't
 * trigger the listeners of the settings screen.
 */
class SyncPrefs(context: Context) {
    val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The user must authorize again before sync can continue. */
    var needsAuth: Boolean
        get() = prefs.getBoolean(KEY_NEEDS_AUTH, false)
        set(value) = prefs.edit().putBoolean(KEY_NEEDS_AUTH, value).apply()

    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_EMAIL, value).apply()

    /** MD5 of the file in Google Drive, the last time this device was in sync with it. */
    var remoteMd5: String?
        get() = prefs.getString(KEY_REMOTE_MD5, null)
        set(value) = prefs.edit().putString(KEY_REMOTE_MD5, value).apply()

    /** Fingerprint of the local data, the last time this device was in sync. */
    var localFingerprint: Long
        get() = prefs.getLong(KEY_LOCAL_FINGERPRINT, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCAL_FINGERPRINT, value).apply()

    var lastSuccess: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SUCCESS, value).apply()

    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    /** Records a successful sync, in a single write. */
    fun markSynced(remoteMd5: String?, localFingerprint: Long, time: Long) {
        prefs.edit()
            .putString(KEY_REMOTE_MD5, remoteMd5)
            .putLong(KEY_LOCAL_FINGERPRINT, localFingerprint)
            .putLong(KEY_LAST_SUCCESS, time)
            .remove(KEY_LAST_ERROR)
            .putBoolean(KEY_NEEDS_AUTH, false)
            .apply()
    }

    /** Forgets everything about the last sync, so the next one does a full merge. */
    fun forgetSyncState() {
        prefs.edit()
            .remove(KEY_REMOTE_MD5)
            .remove(KEY_LOCAL_FINGERPRINT)
            .remove(KEY_LAST_SUCCESS)
            .remove(KEY_LAST_ERROR)
            .remove(KEY_NEEDS_AUTH)
            .remove(KEY_ACCOUNT_EMAIL)
            .apply()
    }

    companion object {
        const val FILE_NAME = "sync_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_NEEDS_AUTH = "needs_auth"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val KEY_REMOTE_MD5 = "remote_md5"
        const val KEY_LOCAL_FINGERPRINT = "local_fingerprint"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
    }
}
