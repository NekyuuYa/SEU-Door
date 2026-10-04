package com.nkyuu.dooropener

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PasswordResetTest {
    private fun validate(phone: String, code: String, password: String, confirmation: String): String? {
        return PasswordResetRules.validate(phone, code, password, confirmation)?.name
    }

    @Test fun rejectsInvalidInputBeforeAccountChanges() {
        assertEquals("Phone", validate("123", "123456", "246810", "246810"))
        assertEquals("Code", validate("13800138000", "", "246810", "246810"))
        assertEquals("Code", validate("13800138000", "abc123", "246810", "246810"))
        assertEquals("Password", validate("13800138000", "123456", "12345", "12345"))
        assertEquals("Password", validate("13800138000", "123456", "abcdef", "abcdef"))
        assertEquals("Confirmation", validate("13800138000", "123456", "246810", "246811"))
        assertNull(validate("13800138000", "123456", "024680", "024680"))
    }

    @Test fun cooldownIsPerPhoneAndIncludesTheFinalPartialSecond() {
        val cooldown = PasswordResetCooldown()
        assertEquals(0L, cooldown.remainingSeconds("13800138000", 1000L))
        cooldown.recordSent("13800138000", 1000L)
        assertEquals(60L, cooldown.remainingSeconds("13800138000", 1000L))
        assertEquals(1L, cooldown.remainingSeconds("13800138000", 60999L))
        assertEquals(0L, cooldown.remainingSeconds("13800138000", 61000L))
        assertEquals(0L, cooldown.remainingSeconds("13900139000", 2000L))
    }

    @Test fun smsRequestUsesTheDocumentedEndpointAndSignature() {
        withServer("{\"result\":true,\"data\":{}}") { api, request ->
            api.sendPasswordResetCode("13800138000")
            val captured = request.get(3, TimeUnit.SECONDS)
            assertEquals("GET", captured.method)
            assertEquals("/webapi/users/sendresetpwdSMS", captured.path)
            assertEquals("13800138000", captured.params["phone"])
            assertFalse(captured.params.containsKey("pwd"))
            assertSignature(captured.params)
        }
    }

    @Test fun resetSubmitsCodeAndNewPasswordInPostBody() {
        withServer("{\"result\":true,\"data\":{}}") { api, request ->
            api.resetPassword("13800138000", "123456", "024680")
            val captured = request.get(3, TimeUnit.SECONDS)
            assertEquals("POST", captured.method)
            assertEquals("/webapi/oauth/pwd_reset", captured.path)
            assertEquals("", captured.query)
            assertEquals("13800138000", captured.params["phone"])
            assertEquals("123456", captured.params["code"])
            assertEquals("024680", captured.params["newpwd"])
            assertSignature(captured.params)
        }
    }

    @Test fun preservesServerRejectionInsteadOfReportingSuccess() {
        withServer("{\"result\":false,\"err_msg\":\"SMS code expired\",\"data\":null}") { api, request ->
            try {
                api.resetPassword("13800138000", "123456", "024680")
                fail("Server rejection must be reported")
            } catch (exception: DoorApiException) {
                assertEquals("SMS code expired", exception.serverMessage)
            }
            assertEquals("POST", request.get(3, TimeUnit.SECONDS).method)
        }
    }

    @Test fun invalidApiInputsNeverReachTheServer() {
        withServer("{\"result\":true,\"data\":{}}") { api, request ->
            for (operation in listOf<() -> Unit>(
                { api.sendPasswordResetCode("123") },
                { api.resetPassword("13800138000", "", "024680") },
                { api.resetPassword("13800138000", "123456", "12345") }
            )) {
                try { operation(); fail("Invalid input must be rejected") }
                catch (_: IllegalArgumentException) { }
            }
            assertFalse(request.isDone)
        }
    }

    private fun assertSignature(params: Map<String, String>) {
        assertTrue(params["timestamp"]!!.toLong() > 0)
        assertEquals(32, params["noncestr"]!!.length)
        val source = params.filterKeys { it != "sign" }.toSortedMap().entries
            .joinToString("&") { "${it.key}=${it.value}" } + "&key=6d5dbb85b949447a95ff8fda9a9b759b"
        val expected = MessageDigest.getInstance("MD5").digest(source.toByteArray())
            .joinToString("") { "%02X".format(it) }
        assertEquals(expected, params["sign"])
    }

    private data class Request(val method: String, val path: String, val query: String, val params: Map<String, String>)

    private fun withServer(response: String, test: (DoorApi, CompletableFuture<Request>) -> Unit) {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val request = CompletableFuture<Request>()
            val worker = Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        val line = reader.readLine().split(' ')
                        var contentLength = 0
                        while (true) {
                            val header = reader.readLine()
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) contentLength = header.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(contentLength)
                        var offset = 0
                        while (offset < body.size) {
                            val count = reader.read(body, offset, body.size - offset)
                            require(count > 0)
                            offset += count
                        }
                        val query = line[1].substringAfter('?', "")
                        val encoded = if (line[0] == "POST") String(body) else query
                        val params = encoded.split('&').filter { it.isNotEmpty() }.associate {
                            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                        }
                        request.complete(Request(line[0], line[1].substringBefore('?'), query, params))
                        val bytes = response.toByteArray()
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                    }
                } catch (exception: Exception) {
                    if (!server.isClosed) request.completeExceptionally(exception)
                }
            }.apply { isDaemon = true; start() }
            try { test(DoorApi("http://127.0.0.1:${server.localPort}"), request) }
            finally { server.close(); worker.join(3000) }
        }
    }
}
