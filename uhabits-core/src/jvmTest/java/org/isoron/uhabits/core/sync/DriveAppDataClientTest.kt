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

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks the requests sent to the Drive REST API against a fake local server.
 */
class DriveAppDataClientTest {
    private class Recorded(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private lateinit var server: HttpServer
    private val requests = mutableListOf<Recorded>()
    private var responseCode = 200
    private var responseBody = "{}".toByteArray()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            requests.add(
                Recorded(
                    ex.requestMethod,
                    ex.requestURI.rawPath,
                    URLDecoder.decode(ex.requestURI.rawQuery ?: "", "UTF-8"),
                    headers,
                    ex.requestBody.readBytes()
                )
            )
            if (responseCode == 204) {
                ex.sendResponseHeaders(204, -1)
            } else {
                ex.sendResponseHeaders(responseCode, responseBody.size.toLong())
                ex.responseBody.write(responseBody)
            }
            ex.close()
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun client() = DriveAppDataClient("token123", "http://127.0.0.1:${server.address.port}")

    private fun respond(code: Int, body: String) {
        responseCode = code
        responseBody = body.toByteArray()
    }

    @Test
    fun testListFiles() {
        respond(
            200,
            """{"files":[{"id":"f1","name":"loop.db.gz","md5Checksum":"abc","size":"123",
               "modifiedTime":"2026-10-21T10:00:00.000Z"}]}"""
        )
        val files = client().listFiles("loop.db.gz")
        assertEquals(1, files.size)
        assertEquals("f1", files[0].id)
        assertEquals("abc", files[0].md5)
        assertEquals(123L, files[0].size)

        val r = requests.single()
        assertEquals("GET", r.method)
        assertEquals("/drive/v3/files", r.path)
        assertTrue(r.query.contains("spaces=appDataFolder"))
        assertTrue(r.query.contains("q=name = 'loop.db.gz' and trashed = false"))
        assertTrue(r.query.contains("orderBy=createdTime"))
        assertEquals("Bearer token123", r.headers["authorization"])
    }

    @Test
    fun testCreateSendsMultipartRequest() {
        respond(200, """{"id":"new","name":"loop.db.gz","md5Checksum":"m"}""")
        val content = byteArrayOf(1, 2, 3, 0, -1)
        val file = client().create("loop.db.gz", content)
        assertEquals("new", file.id)

        val r = requests.single()
        assertEquals("POST", r.method)
        assertEquals("/upload/drive/v3/files", r.path)
        assertTrue(r.query.contains("uploadType=multipart"))
        val contentType = r.headers.getValue("content-type")
        assertTrue(contentType.startsWith("multipart/related; boundary="))
        val boundary = contentType.substringAfter("boundary=")
        val body = String(r.body, Charsets.ISO_8859_1)
        assertTrue(body.startsWith("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"))
        assertTrue(body.contains("\"parents\":[\"appDataFolder\"]"))
        assertTrue(body.endsWith("\r\n--$boundary--\r\n"))
        val mediaStart = body.indexOf("Content-Type: application/gzip\r\n\r\n") +
            "Content-Type: application/gzip\r\n\r\n".length
        val media = r.body.copyOfRange(mediaStart, mediaStart + content.size)
        assertContentEquals(content, media)
    }

    @Test
    fun testUpdateUsesPatch() {
        respond(200, """{"id":"f1","name":"loop.db.gz","md5Checksum":"m2"}""")
        val file = client().update("f1", byteArrayOf(9, 8, 7))
        assertEquals("m2", file.md5)

        val r = requests.single()
        val method = r.headers["x-http-method-override"] ?: r.method
        assertEquals("PATCH", method)
        assertEquals("/upload/drive/v3/files/f1", r.path)
        assertTrue(r.query.contains("uploadType=media"))
        assertContentEquals(byteArrayOf(9, 8, 7), r.body)
    }

    @Test
    fun testDownloadAndDelete() {
        respond(200, "raw-bytes")
        assertEquals("raw-bytes", String(client().download("f1")))
        assertEquals("alt=media", requests.last().query)

        respond(204, "")
        client().delete("f1")
        assertEquals("DELETE", requests.last().method)
    }

    @Test
    fun testMissingFileReturnsNull() {
        respond(404, """{"error":{"code":404,"message":"File not found: f1."}}""")
        assertNull(client().getFile("f1"))
    }

    @Test
    fun testErrors() {
        respond(401, """{"error":{"code":401,"message":"Invalid Credentials"}}""")
        try {
            client().listFiles("x")
            fail("expected an exception")
        } catch (e: DriveAppDataClient.UnauthorizedException) {
            assertEquals("Invalid Credentials", e.message)
        }

        respond(
            403,
            """{"error":{"code":403,"message":"Google Drive API has not been used in project 1 before",
               "errors":[{"reason":"accessNotConfigured"}]}}"""
        )
        try {
            client().listFiles("x")
            fail("expected an exception")
        } catch (e: DriveAppDataClient.DriveException) {
            assertEquals(403, e.code)
            assertEquals("accessNotConfigured", e.reason)
            assertTrue(e.message!!.contains("Google Drive API"))
        }
    }

    @Test
    fun testMiniJson() {
        val json = MiniJson.parse("""{"a":[1,2.5,-3e2],"b":"x\"y\\zé\n","c":true,"d":null,"e":{}}""")
        json as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0), json["a"])
        assertEquals("x\"y\\zé\n", json["b"])
        assertEquals(true, json["c"])
        assertNull(json["d"])
        assertEquals(emptyMap<String, Any?>(), json["e"])
        val tricky = "quote \" backslash \\ newline \n tab \t"
        assertEquals(tricky, MiniJson.parse(MiniJson.quote(tricky)))
    }
}
