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
class SnapshotCommandRouterTest {

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
            snapshotPort = mockAircraft,
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
                    id = "snap1",
                    downloadUrl = "https://tronico.kujuhk.com/live/photo/snap1",
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
    fun snapshotDeliverBoth_reportsVideoFrameSource() = testScope.runTest {
        router.handleIncomingMessage(
            """
            {
              "schema_version": "1.0",
              "msg_id": "snap-1",
              "ts": "2026-10-09T12:00:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "camera.snapshot",
              "params": { "deliver": "both", "quality": 85 }
            }
            """.trimIndent(),
        )
        advanceUntilIdle()

        val status = mockTransport.publishedMessages
            .map { it.second }
            .filter { it.contains("camera.snapshot_done") }
            .last()
        assertTrue(status.contains(""""ok":true"""))
        assertTrue(status.contains(""""source":"video_frame""""))
        assertTrue(status.contains(""""download_url":"https://tronico.kujuhk.com/live/photo/snap1""""))
        assertTrue(status.contains(""""local_path":"mock://photos/"""))
        assertTrue(status.contains(""""width":640"""))
    }

    @Test
    fun snapshotCaptureFailure_emitsErrorCode() = testScope.runTest {
        mockAircraft.mockSnapshotShouldFail = true
        router.handleIncomingMessage(
            """
            {
              "schema_version": "1.0",
              "msg_id": "snap-fail",
              "ts": "2026-10-09T12:00:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "camera.snapshot",
              "params": {}
            }
            """.trimIndent(),
        )
        advanceUntilIdle()

        val status = mockTransport.publishedMessages
            .map { it.second }
            .filter { it.contains("camera.snapshot_done") }
            .last()
        assertTrue(status.contains(""""ok":false"""))
        assertTrue(status.contains(PhotoErrorCodes.NO_VIDEO_FRAME))
    }
}
