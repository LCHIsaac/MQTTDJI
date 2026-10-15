package com.dji.dronecontrol.core.stream

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpStreamTokenFetcherRequestBodyTest {

    private val requestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun publisherRequestJson_includesRoleAndNumericUid() {
        val body = requestJson.encodeToString(
            StreamTokenRequestBody(channel = "test-01", role = "publisher", uid = 1001),
        )
        assertTrue(body.contains("\"role\":\"publisher\""))
        assertTrue(body.contains("\"channel\":\"test-01\""))
        val root = requestJson.parseToJsonElement(body).jsonObject
        assertEquals("publisher", root.getValue("role").jsonPrimitive.content)
        assertEquals(1001, root.getValue("uid").jsonPrimitive.int)
        assertFalse(root.getValue("uid").jsonPrimitive.isString)
    }

    @Test
    fun fetcherCompanionBody_matchesPublisherContract() {
        val body = HttpStreamTokenFetcher.encodePublisherRequestBody("test-01", 1001)
        assertTrue(body.contains("\"role\":\"publisher\""))
        val root = requestJson.parseToJsonElement(body).jsonObject
        assertEquals("test-01", root.getValue("channel").jsonPrimitive.content)
        assertEquals(1001, root.getValue("uid").jsonPrimitive.int)
    }
}
