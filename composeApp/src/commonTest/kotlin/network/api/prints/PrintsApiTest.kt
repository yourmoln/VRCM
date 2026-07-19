package io.github.vrcmteam.vrcm.network.api.prints

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PrintsApiTest {
    @Test
    fun getUserPrintsSendsClampedPaginationParameters() = runBlocking {
        var capturedN: String? = null
        var capturedOffset: String? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedN = request.url.parameters["n"]
                    capturedOffset = request.url.parameters["offset"]
                    respond(
                        content = "[]",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        PrintsApi(client).getUserPrints(userId = "usr_test", n = 500, offset = -10)

        assertEquals("100", capturedN)
        assertEquals("0", capturedOffset)
        client.close()
    }

    @Test
    fun uploadPrintRejectsNonPngBytesBeforeSendingRequest() = runBlocking {
        var requestCount = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestCount++
                    respond(
                        content = "{\"id\":\"prnt_test\"}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        assertFailsWith<IllegalArgumentException> {
            PrintsApi(client).uploadPrint(
                imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
                fileName = "photo.png",
            )
        }

        assertEquals(0, requestCount)
        client.close()
    }
}
