package com.dji.dronecontrol.core

import com.dji.dronecontrol.core.mock.MockAircraft
import com.dji.dronecontrol.core.mock.MockMqttTransport
import com.dji.dronecontrol.core.model.MqttBrokerConfig
import com.dji.dronecontrol.core.photo.PhotoErrorCodes
import com.dji.dronecontrol.core.photo.PhotoUploadClient
import com.dji.dronecontrol.core.photo.PhotoUploadResult
import com.dji.dronecontrol.core.router.CommandRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoDeliveryCommandRouterTest {

    private lateinit var testScope: TestScope
    private lateinit var mockAircraft: MockAircraft
    private lateinit var mockTransport: MockMqttTransport
    private lateinit var router: CommandRouter

    private val config = MqttBrokerConfig(aircraftId = "m3e-01", host = "localhost")

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
            photoDeliveryPort = mockAircraft,
            mediaLibraryPort = mockAircraft,
            photoUploadClient = object : PhotoUploadClient {
                override suspend fun uploadJpeg(
                    uploadUrl: String,
                    bearerSecret: String,
                    jpegBytes: ByteArray,
                    aircraftId: String,
                    msgId: String,
                    fileIndex: Int?,
                ): PhotoUploadResult = PhotoUploadResult.Success(
                    id = "id1",
                    downloadUrl = "https://tronico.kujuhk.com/live/photo/id1",
                    expiresAt = "2026-10-10T00:00:00Z",
                    bytes = jpegBytes.size.toLong(),
                )
            },
            scope = testScope,
        )
        router.updateConfig(config)
        router.updateStreamTokenConfig("https://example.com/token") { "secret" }
    }

    @Test
    fun deliverBoth_includesDownloadUrlAndLocalPath() = testScope.runTest {
        val payload = """
            {
              "schema_version": "1.0",
              "msg_id": "photo-both",
              "ts": "2026-10-09T12:00:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "camera.photo",
              "params": { "deliver": "both" }
            }
        """.trimIndent()
        router.handleIncomingMessage(payload)
        advanceUntilIdle()

        val status = mockTransport.publishedMessages
            .map { it.second }
            .filter { it.contains("camera.photo_done") }
            .last()
        assertTrue(status.contains(""""ok":true"""))
        assertTrue(status.contains(""""download_url":"https://tronico.kujuhk.com/live/photo/id1""""))
        assertTrue(status.contains(""""local_path":"mock://photos/"""))
    }

    @Test
    fun deliverBoth_uploadFailureStillReportsLocal() = testScope.runTest {
        router = CommandRouter(
            flightPort = mockAircraft,
            cameraPort = mockAircraft,
            gimbalPort = mockAircraft,
            streamPort = mockAircraft,
            aircraftPort = mockAircraft,
            mqttTransport = mockTransport,
            photoDeliveryPort = mockAircraft,
            mediaLibraryPort = mockAircraft,
            photoUploadClient = object : PhotoUploadClient {
                override suspend fun uploadJpeg(
                    uploadUrl: String,
                    bearerSecret: String,
                    jpegBytes: ByteArray,
                    aircraftId: String,
                    msgId: String,
                    fileIndex: Int?,
                ): PhotoUploadResult = PhotoUploadResult.Failure(
                    PhotoErrorCodes.UPLOAD_FAILED,
                    "endpoint down",
                )
            },
            scope = testScope,
        )
        router.updateConfig(config)
        router.updateStreamTokenConfig("https://example.com/token") { "secret" }

        router.handleIncomingMessage(
            """
            {
              "schema_version": "1.0",
              "msg_id": "photo-fail-up",
              "ts": "2026-10-09T12:00:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "camera.photo",
              "params": { "deliver": "both" }
            }
            """.trimIndent(),
        )
        advanceUntilIdle()

        val status = mockTransport.publishedMessages
            .map { it.second }
            .filter { it.contains("camera.photo_done") }
            .last()
        assertTrue(status.contains(""""ok":false"""))
        assertTrue(status.contains(""""local_path":"mock://photos/"""))
        assertTrue(status.contains("UPLOAD_FAILED"))
    }
}
