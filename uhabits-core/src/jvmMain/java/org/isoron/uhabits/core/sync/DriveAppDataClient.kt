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

package org.isoron.uhabits.core.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder

/**
 * Tiny client for the hidden, per-app folder ("appDataFolder") of Google Drive. Requires an
 * OAuth access token with the drive.appdata scope. All calls block; run them off the main thread.
 */
class DriveAppDataClient(
    private val accessToken: String,
    private val baseUrl: String = "https://www.googleapis.com"
) {
    data class RemoteFile(
        val id: String,
        val name: String,
        val md5: String?,
        val size: Long?,
        val modifiedTime: String?
    )

    open class DriveException(val code: Int, message: String, val reason: String? = null) :
        IOException(message)

    class UnauthorizedException(message: String) : DriveException(401, message)

    /** Files with the given name, oldest first. */
    fun listFiles(name: String): List<RemoteFile> {
        val q = "name = '${name.replace("'", "\\'")}' and trashed = false"
        val url = "$baseUrl/drive/v3/files?spaces=appDataFolder" +
            "&q=${enc(q)}" +
            "&fields=${enc("files($FILE_FIELDS)")}" +
            "&orderBy=createdTime&pageSize=100"
        val json = MiniJson.parse(String(request("GET", url), Charsets.UTF_8)) as Map<*, *>
        val files = json["files"] as? List<*> ?: emptyList<Any>()
        return files.mapNotNull { (it as? Map<*, *>)?.let(::toRemoteFile) }
    }

    /** Metadata of the given file, or null if it no longer exists. */
    fun getFile(id: String): RemoteFile? {
        val url = "$baseUrl/drive/v3/files/${enc(id)}?fields=${enc(FILE_FIELDS)}"
        return try {
            toRemoteFile(MiniJson.parse(String(request("GET", url), Charsets.UTF_8)) as Map<*, *>)
        } catch (e: DriveException) {
            if (e.code == 404) null else throw e
        }
    }

    fun download(id: String): ByteArray {
        return request("GET", "$baseUrl/drive/v3/files/${enc(id)}?alt=media")
    }

    fun create(name: String, content: ByteArray, mimeType: String = MIME_TYPE): RemoteFile {
        val boundary = "loop-sync-boundary-${System.nanoTime()}"
        val metadata = "{\"name\":${MiniJson.quote(name)},\"parents\":[\"appDataFolder\"]," +
            "\"mimeType\":${MiniJson.quote(mimeType)}}"
        val body = ByteArrayOutputStream()
        body.write(
            (
                "--$boundary\r\n" +
                    "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                    "$metadata\r\n" +
                    "--$boundary\r\n" +
                    "Content-Type: $mimeType\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
        )
        body.write(content)
        body.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        val url = "$baseUrl/upload/drive/v3/files?uploadType=multipart&fields=${enc(FILE_FIELDS)}"
        val response = request("POST", url, body.toByteArray(), "multipart/related; boundary=$boundary")
        return toRemoteFile(MiniJson.parse(String(response, Charsets.UTF_8)) as Map<*, *>)
    }

    fun update(id: String, content: ByteArray, mimeType: String = MIME_TYPE): RemoteFile {
        val url = "$baseUrl/upload/drive/v3/files/${enc(id)}?uploadType=media&fields=${enc(FILE_FIELDS)}"
        val response = request("PATCH", url, content, mimeType)
        return toRemoteFile(MiniJson.parse(String(response, Charsets.UTF_8)) as Map<*, *>)
    }

    fun delete(id: String) {
        try {
            request("DELETE", "$baseUrl/drive/v3/files/${enc(id)}")
        } catch (e: DriveException) {
            if (e.code != 404) throw e
        }
    }

    /** Email address of the signed-in account, if Google shares it with this app. */
    fun userEmail(): String? {
        val url = "$baseUrl/drive/v3/about?fields=${enc("user(emailAddress)")}"
        return try {
            val json = MiniJson.parse(String(request("GET", url), Charsets.UTF_8)) as Map<*, *>
            (json["user"] as? Map<*, *>)?.get("emailAddress") as? String
        } catch (e: DriveException) {
            null
        }
    }

    private fun request(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null
    ): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            setMethod(conn, method)
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.use { readAll(it) }
            }
            val errorBody = conn.errorStream?.use { readAll(it) }?.toString(Charsets.UTF_8) ?: ""
            throw toException(code, errorBody)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MIME_TYPE = "application/gzip"
        private const val FILE_FIELDS = "id,name,md5Checksum,size,modifiedTime"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun setMethod(conn: HttpURLConnection, method: String) {
            try {
                conn.requestMethod = method
            } catch (e: ProtocolException) {
                // Some HttpURLConnection implementations don't know PATCH. Google APIs accept
                // the method override header instead.
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", method)
            }
        }

        private fun readAll(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            return out.toByteArray()
        }

        private fun toRemoteFile(json: Map<*, *>): RemoteFile {
            return RemoteFile(
                id = json["id"] as? String ?: throw IOException("Drive response without file id"),
                name = json["name"] as? String ?: "",
                md5 = json["md5Checksum"] as? String,
                size = (json["size"] as? String)?.toLongOrNull() ?: (json["size"] as? Double)?.toLong(),
                modifiedTime = json["modifiedTime"] as? String
            )
        }

        internal fun toException(code: Int, body: String): DriveException {
            var message = "HTTP $code"
            var reason: String? = null
            try {
                val error = (MiniJson.parse(body) as? Map<*, *>)?.get("error")
                if (error is Map<*, *>) {
                    (error["message"] as? String)?.let { message = it }
                    reason = ((error["errors"] as? List<*>)?.firstOrNull() as? Map<*, *>)
                        ?.get("reason") as? String
                } else if (error is String) {
                    message = error
                }
            } catch (_: Exception) {
                if (body.isNotBlank()) message = "HTTP $code: ${body.take(200)}"
            }
            return if (code == 401) UnauthorizedException(message) else DriveException(code, message, reason)
        }
    }
}
