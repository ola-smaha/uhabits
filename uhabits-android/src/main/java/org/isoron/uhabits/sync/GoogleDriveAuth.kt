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
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import org.isoron.uhabits.R
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Access to the user's Google Drive, through Google Play services. Only the hidden app folder
 * is requested (drive.appdata), which Google classifies as a non-sensitive scope.
 */
object GoogleDriveAuth {
    const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

    /**
     * Starts authorization. If the result has a resolution, the caller must launch its pending
     * intent so the user can pick an account and grant access.
     */
    fun authorize(context: Context): Task<AuthorizationResult> =
        Identity.getAuthorizationClient(context).authorize(request())

    /**
     * Returns an access token without any user interaction, or null if the user has to
     * (re)authorize first. Blocks; never call it on the main thread.
     */
    fun getTokenSilently(context: Context): String? {
        val result = await(Identity.getAuthorizationClient(context).authorize(request()))
        if (result.hasResolution() || !hasDriveAccess(result)) return null
        return result.accessToken
    }

    /** True if the user granted access to the app folder in Google Drive. */
    fun hasDriveAccess(result: AuthorizationResult): Boolean {
        return result.accessToken != null &&
            result.grantedScopes.any { it.toString() == DRIVE_APPDATA_SCOPE }
    }

    /** Forgets a cached token that Google rejected, so the next request gets a fresh one. */
    fun clearToken(context: Context, token: String) {
        try {
            val request = ClearTokenRequest.builder().setToken(token).build()
            await(Identity.getAuthorizationClient(context).clearToken(request))
        } catch (e: Exception) {
            // Not fatal; the next authorization attempt will tell.
        }
    }

    /** A message for the user explaining why authorization failed. */
    fun describe(context: Context, error: Throwable): String {
        val cause = (error as? ExecutionException)?.cause ?: error
        if (cause is ApiException) {
            return when (cause.statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR -> context.getString(R.string.sync_developer_error)
                CommonStatusCodes.NETWORK_ERROR -> context.getString(R.string.sync_network_error)
                CommonStatusCodes.CANCELED -> context.getString(R.string.sync_cancelled)
                else -> "${CommonStatusCodes.getStatusCodeString(cause.statusCode)} (${cause.statusCode})"
            }
        }
        return cause.message ?: cause.javaClass.simpleName
    }

    /** True if the failure is temporary and worth retrying later. */
    fun isTransient(error: Throwable): Boolean {
        val cause = (error as? ExecutionException)?.cause ?: error
        if (cause is ApiException) {
            return cause.statusCode == CommonStatusCodes.NETWORK_ERROR ||
                cause.statusCode == CommonStatusCodes.TIMEOUT ||
                cause.statusCode == CommonStatusCodes.INTERNAL_ERROR
        }
        return cause is java.util.concurrent.TimeoutException
    }

    private fun <T> await(task: Task<T>): T = Tasks.await(task, 60, TimeUnit.SECONDS)
}
