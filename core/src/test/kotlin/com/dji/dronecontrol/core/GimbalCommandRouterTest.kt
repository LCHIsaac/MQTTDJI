package com.dji.dronecontrol.core

import com.dji.dronecontrol.core.mock.MockAircraft
import com.dji.dronecontrol.core.mock.MockMqttTransport
import com.dji.dronecontrol.core.model.MqttBrokerConfig
import com.dji.dronecontrol.core.router.CommandRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GimbalCommandRouterTest {

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
            scope = testScope,
        )
        router.updateConfig(MqttBrokerConfig(aircraftId = "m3e-01", host = "localhost"))
    }

    @Test
    fun gimbalRotate_ackOkAndRecordsParams() = testScope.runTest {
        val payload = """
            {
              "schema_version": "1.0",
              "msg_id": "gimbal-rot-1",
              "ts": "2026-09-24T12:00:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "gimbal.rotate",
              "params": { "pitch_rate_dps": -10.0, "yaw_rate_dps": 0.0, "duration_ms": 500 }
            }
        """.trimIndent()
        router.handleIncomingMessage(payload)
        val ack = mockTransport.publishedMessages.first { it.second.contains("\"type\":\"ack\"") }.second
        assertTrue(ack.contains("\"ok\":true"))
        assertEquals(1, mockAircraft.executedGimbalRotates.size)
        assertEquals(-10.0, mockAircraft.executedGimbalRotates.first().pitchRateDps, 0.001)
    }

    @Test
    fun gimbalSetAngle_ackOk() = testScope.runTest {
        val payload = """
            {
              "cmd": "gimbal.set_angle",
              "msg_id": "gimbal-ang-1",
              "schema_version": "1.0",
              "ts": "2026-09-24T12:00:00Z",
              "aircraft_id": "m3e-01",
              "params": { "pitch_deg": -45.0, "yaw_deg": 0.0, "roll_deg": 0.0 }
            }
        """.trimIndent()
        router.handleIncomingMessage(payload)
        val ack = mockTransport.publishedMessages.first { it.second.contains("gimbal-ang-1") }.second
        assertTrue(ack.contains("\"ok\":true"))
        assertEquals(1, mockAircraft.executedGimbalSetAngles.size)
    }

    @Test
    fun gimbalReset_ackOk() = testScope.runTest {
        val payload = """
            {
              "cmd": "gimbal.reset",
              "msg_id": "gimbal-reset-1",
              "schema_version": "1.0",
              "ts": "2026-09-24T12:00:00Z",
              "aircraft_id": "m3e-01",
              "params": {}
            }
        """.trimIndent()
        router.handleIncomingMessage(payload)
        val ack = mockTransport.publishedMessages.first { it.second.contains("gimbal-reset-1") }.second
        assertTrue(ack.contains("\"ok\":true"))
        assertEquals(1, mockAircraft.gimbalResetCount)
    }

    @Test
    fun emergencyStop_cancelsGimbalRotateJob() = testScope.runTest {
        router.handleIncomingMessage(
            """
            {"schema_version":"1.0","msg_id":"gr","ts":"2026-09-24T12:00:00Z","aircraft_id":"m3e-01",
             "cmd":"gimbal.rotate","params":{"pitch_rate_dps":10.0,"duration_ms":5000}}
            """.trimIndent()
        )
        router.handleIncomingMessage(
            """
            {"schema_version":"1.0","msg_id":"estop","ts":"2026-09-24T12:00:00Z","aircraft_id":"m3e-01",
             "cmd":"flight.emergency_stop","params":{}}
            """.trimIndent()
        )
        assertTrue(mockAircraft.executedFlightCommands.contains("emergencyStop"))
    }
}
