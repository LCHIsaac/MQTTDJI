package com.dji.dronecontrol.core

import com.dji.dronecontrol.core.mock.MockAircraft
import com.dji.dronecontrol.core.mock.MockMqttTransport
import com.dji.dronecontrol.core.model.MqttBrokerConfig
import com.dji.dronecontrol.core.router.CommandRouter
import com.dji.dronecontrol.core.stream.DualRoleStreamTokenFetcher
import com.dji.dronecontrol.core.stream.StreamErrorCodes
import com.dji.dronecontrol.core.stream.StreamPublisherToken
import com.dji.dronecontrol.core.stream.StreamTokenFetchResult
import com.dji.dronecontrol.core.stream.StreamTokenFetcher
import com.dji.dronecontrol.core.stream.HttpStreamTokenFetcher
import com.dji.dronecontrol.core.stream.StreamViewerUrl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamAgoraCommandRouterTest {

    private lateinit var testScope: TestScope
    private lateinit var mockAircraft: MockAircraft
    private lateinit var mockTransport: MockMqttTransport
    private lateinit var router: CommandRouter

    @Before
    fun setUp() {
        val dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        mockAircraft = MockAircraft(testScope)
        mockTransport = MockMqttTransport()
        router = CommandRouter(
            flightPort = mockAircraft,
            cameraPort = mockAircraft,
            gimbalPort = mockAircraft,
            streamPort = mockAircraft,
            aircraftPort = mockAircraft,
            mqttTransport = mockTransport,
            streamTokenFetcher = DualRoleStreamTokenFetcher(),
            scope = testScope,
        )
        router.updateConfig(
            MqttBrokerConfig(
                aircraftId = "mini4pro-01",
                host = "localhost",
                viewerBaseUrl = StreamViewerUrl.DEFAULT_BASE_URL,
            )
        )
        router.updateStreamSecrets("test-agora-app-id")
        router.updateStreamTokenConfig(
            defaultTokenUrl = "https://example.com/live/token",
            secretProvider = { "test-secret" },
        )
    }

    @Test
    fun streamStartAgora_ackViewerUrlIncludesAudienceTokenInFragment() = testScope.runTest {
        val payload = """
            {
              "schema_version": "1.0",
              "msg_id": "agora-01",
              "ts": "2026-09-24T12:00:00Z",
              "aircraft_id": "mini4pro-01",
              "cmd": "stream.start",
              "params": {
                "protocol": "agora",
                "channel": "room-42",
                "token": "publisher-token-secret",
                "uid": 1001
              }
            }
        """.trimIndent()

        router.handleIncomingMessage(payload)

        val expectedViewer = StreamViewerUrl.buildWatchable(
            StreamViewerUrl.DEFAULT_BASE_URL,
            "room-42",
            "audience-token-for-viewer",
        )
        val ack = mockTransport.publishedMessages.first { it.second.contains("\"type\":\"ack\"") }.second
        assertTrue(ack.contains("\"ok\":true"))
        assertTrue(ack.contains("\"viewer_url\":\"$expectedViewer\""))
        assertFalse(ack.contains("publisher-token-secret"))

        val status = mockTransport.publishedMessages.last { it.second.contains("stream.status") }.second
        assertTrue(status.contains("\"state\":\"live\""))
        assertFalse(status.contains("publisher-token-secret"))
    }

    @Test
    fun streamStartAgora_withoutToken_fetchesFromEndpoint() = testScope.runTest {
        val fakeFetcher = object : StreamTokenFetcher {
            override suspend fun fetchPublisherToken(
                tokenUrl: String,
                bearerSecret: String,
                channel: String,
                uid: Int,
            ): StreamTokenFetchResult {
                assertEquals("room-99", channel)
                assertEquals(1001, uid)
                assertEquals("test-secret", bearerSecret)
                return StreamTokenFetchResult.Success(
                    StreamPublisherToken(
                        token = "fetched-publisher-token",
                        channel = channel,
                        uid = uid,
                    )
                )
            }

            override suspend fun fetchAudienceToken(
                tokenUrl: String,
                bearerSecret: String,
                channel: String,
            ): StreamTokenFetchResult {
                assertEquals("room-99", channel)
                return StreamTokenFetchResult.Success(
                    StreamPublisherToken(
                        token = "fetched-audience-token",
                        channel = channel,
                        uid = 0,
                    )
                )
            }
        }
        router = CommandRouter(
            flightPort = mockAircraft,
            cameraPort = mockAircraft,
            gimbalPort = mockAircraft,
            streamPort = mockAircraft,
            aircraftPort = mockAircraft,
            mqttTransport = mockTransport,
            streamTokenFetcher = fakeFetcher,
            scope = testScope,
        )
        router.updateConfig(
            MqttBrokerConfig(
                aircraftId = "mini4pro-01",
                host = "localhost",
                viewerBaseUrl = StreamViewerUrl.DEFAULT_BASE_URL,
            )
        )
        router.updateStreamSecrets("test-agora-app-id")
        router.updateStreamTokenConfig(
            defaultTokenUrl = "https://example.com/live/token",
            secretProvider = { "test-secret" },
        )

        val payload = """
            {
              "schema_version": "1.0",
              "msg_id": "agora-fetch-01",
              "ts": "2026-09-24T12:00:00Z",
              "aircraft_id": "mini4pro-01",
              "cmd": "stream.start",
              "params": {
                "protocol": "agora",
                "channel": "room-99",
                "uid": 1001
              }
            }
        """.trimIndent()

        router.handleIncomingMessage(payload)

        val expectedViewer = StreamViewerUrl.buildWatchable(
            StreamViewerUrl.DEFAULT_BASE_URL,
            "room-99",
            "fetched-audience-token",
        )
        val ack = mockTransport.publishedMessages.first { it.second.contains("\"type\":\"ack\"") }.second
        assertTrue(ack.contains("\"ok\":true"))
        assertTrue(ack.contains(expectedViewer))
        assertFalse(ack.contains("fetched-publisher-token"))
        assertTrue(ack.contains("#token=fetched-audience-token"))
    }

    @Test
    fun streamStartAgora_audienceFetchFails_doesNotAckSuccess() = testScope.runTest {
        router = CommandRouter(
            flightPort = mockAircraft,
            cameraPort = mockAircraft,
            gimbalPort = mockAircraft,
            streamPort = mockAircraft,
            aircraftPort = mockAircraft,
            mqttTransport = mockTransport,
            streamTokenFetcher = DualRoleStreamTokenFetcher(
                audienceResult = StreamTokenFetchResult.Failure(
                    StreamErrorCodes.TOKEN_ENDPOINT_FAILED,
                    "Audience token fetch failed",
                ),
            ),
            scope = testScope,
        )
        router.updateConfig(
            MqttBrokerConfig(
                aircraftId = "mini4pro-01",
                host = "localhost",
                viewerBaseUrl = StreamViewerUrl.DEFAULT_BASE_URL,
            )
        )
        router.updateStreamSecrets("test-agora-app-id")
        router.updateStreamTokenConfig(
            defaultTokenUrl = "https://example.com/live/token",
            secretProvider = { "test-secret" },
        )

        val payload = """
            {
              "schema_version": "1.0",
              "msg_id": "agora-audience-fail",
              "ts": "2026-09-24T12:00:00Z",
              "aircraft_id": "mini4pro-01",
              "cmd": "stream.start",
              "params": {
                "protocol": "agora",
                "channel": "room-42",
                "token": "publisher-token-secret",
                "uid": 1001
              }
            }
        """.trimIndent()

        router.handleIncomingMessage(payload)

        val acks = mockTransport.publishedMessages.filter { it.second.contains("\"type\":\"ack\"") }
        assertTrue(acks.isNotEmpty())
        val lastAck = acks.last().second
        assertTrue(lastAck.contains("\"ok\":false"))
        assertTrue(lastAck.contains(StreamErrorCodes.TOKEN_ENDPOINT_FAILED))
        assertFalse(mockTransport.publishedMessages.any { it.second.contains("\"ok\":true") && it.second.contains("viewer_url") })
    }

    @Test
    fun streamStartAgora_withoutToken_notConfigured_returnsErrorCode() = testScope.runTest {
        router = CommandRouter(
            flightPort = mockAircraft,
            cameraPort = mockAircraft,
            gimbalPort = mockAircraft,
            streamPort = mockAircraft,
            aircraftPort = mockAircraft,
            mqttTransport = mockTransport,
            streamTokenFetcher = HttpStreamTokenFetcher(),
            scope = testScope,
        )
        router.updateConfig(
            MqttBrokerConfig(
                aircraftId = "mini4pro-01",
                host = "localhost",
                viewerBaseUrl = StreamViewerUrl.DEFAULT_BASE_URL,
            )
        )
        router.updateStreamSecrets("test-agora-app-id")
        router.updateStreamTokenConfig(
            defaultTokenUrl = "https://example.com/live/token",
            secretProvider = { "" },
        )

        val payload = """
            {
              "schema_version": "1.0",
              "msg_id": "agora-no-secret",
              "ts": "2026-09-24T12:00:00Z",
              "aircraft_id": "mini4pro-01",
              "cmd": "stream.start",
              "params": {
                "protocol": "agora",
                "channel": "room-x"
              }
            }
        """.trimIndent()

        router.handleIncomingMessage(payload)

        val ack = mockTransport.publishedMessages.first { it.second.contains("\"type\":\"ack\"") }.second
        assertTrue(ack.contains("\"ok\":false"))
        assertTrue(ack.contains(StreamErrorCodes.TOKEN_ENDPOINT_NOT_CONFIGURED))
    }
}
