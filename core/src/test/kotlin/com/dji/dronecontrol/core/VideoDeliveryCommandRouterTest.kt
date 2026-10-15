package com.dji.dronecontrol.core

import com.dji.dronecontrol.core.mock.MockAircraft
import com.dji.dronecontrol.core.mock.MockMqttTransport
import com.dji.dronecontrol.core.model.MqttBrokerConfig
import com.dji.dronecontrol.core.video.HttpVideoUploadClient
import com.dji.dronecontrol.core.video.VideoUploadClient
import com.dji.dronecontrol.core.video.VideoUploadResult
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
class VideoDeliveryCommandRouterTest {

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
            photoDeliveryPort = mockAircraft,
            mediaLibraryPort = mockAircraft,
            videoUploadClient = object : VideoUploadClient {
                override suspend fun uploadMp4File(
                    uploadUrl: String,
                    bearerSecret: String,
                    mp4FilePath: String,
                    aircraftId: String,
                    msgId: String,
                    fileIndex: Int?,
                ): VideoUploadResult = VideoUploadResult.Success(
                    id = "v1",
                    downloadUrl = "https://tronico.kujuhk.com/live/video/v1",
                    expiresAt = "2026-10-10T00:00:00Z",
                    bytes = 4096,
                )
            },
            scope = testScope,
        )
        router.updateConfig(MqttBrokerConfig(aircraftId = "m3e-01", host = "localhost"))
        router.updateStreamTokenConfig(HttpVideoUploadClient.DEFAULT_VIDEO_UPLOAD_URL) { "secret" }
        router.updateVideoUploadConfig(HttpVideoUploadClient.DEFAULT_VIDEO_UPLOAD_URL)
    }

    @Test
    fun recordStop_deliverLocal_skipsUpload() = testScope.runTest {
        router.handleIncomingMessage(
            """
            {"schema_version":"1.0","msg_id":"r1","ts":"2026-10-09T12:00:00Z","aircraft_id":"m3e-01","cmd":"camera.record_start","params":{}}
            """.trimIndent(),
        )
        router.handleIncomingMessage(
            """
            {
              "schema_version":"1.0",
              "msg_id":"r2",
              "ts":"2026-10-09T12:00:01Z",
              "aircraft_id":"m3e-01",
              "cmd":"camera.record_stop",
              "params":{"deliver":"local"}
            }
            """.trimIndent(),
        )
        advanceUntilIdle()
        val withLink = mockTransport.publishedMessages.map { it.second }
            .filter { it.contains("camera.record_status") && it.contains(""""download_url":"http""") }
        assertTrue(withLink.isEmpty())
    }
}
