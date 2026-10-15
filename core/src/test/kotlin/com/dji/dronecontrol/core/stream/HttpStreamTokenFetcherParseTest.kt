package com.dji.dronecontrol.core.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpStreamTokenFetcherParseTest {

    private val fetcher = HttpStreamTokenFetcher()

    @Test
    fun parseSuccess_acceptsNumericExpiresAtAndBuildsPublisherToken() {
        val response = """
            {
              "token": "agora-publisher-token-value",
              "channel": "test-01",
              "uid": 1001,
              "expires_at": 1760000000
            }
        """.trimIndent()

        val result = fetcher.parseSuccessResponse(response, "test-01", 1001)

        assertTrue(result is StreamTokenFetchResult.Success)
        val token = (result as StreamTokenFetchResult.Success).publisherToken
        assertEquals("test-01", token.channel)
        assertEquals(1001, token.uid)
        assertEquals("1760000000", token.expiresAt)
        assertEquals("agora-publisher-token-value", token.token)
    }

    @Test
    fun parseSuccess_acceptsStringExpiresAt() {
        val response = """
            {"token":"t","channel":"test-01","uid":1001,"expires_at":"2026-10-08T12:00:00Z"}
        """.trimIndent()

        val result = fetcher.parseSuccessResponse(response, "test-01", 1001)
        assertTrue(result is StreamTokenFetchResult.Success)
        assertEquals(
            "2026-10-08T12:00:00Z",
            (result as StreamTokenFetchResult.Success).publisherToken.expiresAt,
        )
    }

    @Test
    fun parseSuccess_rejectsChannelMismatch() {
        val response = """{"token":"t","channel":"other","uid":1001,"expires_at":1}"""
        val result = fetcher.parseSuccessResponse(response, "test-01", 1001)
        assertTrue(result is StreamTokenFetchResult.Failure)
        assertEquals(
            StreamErrorCodes.TOKEN_ENDPOINT_FAILED,
            (result as StreamTokenFetchResult.Failure).code,
        )
    }
}
