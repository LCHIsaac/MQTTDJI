package com.dji.dronecontrol.core.flight

import com.dji.dronecontrol.core.model.VirtualStickCommand
import com.dji.dronecontrol.core.model.VirtualStickMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualStickStreamerTest {

    private lateinit var testScope: TestScope
    private val sentFrames = mutableListOf<VirtualStickVelocity>()
    private lateinit var streamer: VirtualStickStreamer

    @Before
    fun setUp() {
        val dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        sentFrames.clear()
        streamer = VirtualStickStreamer(
            frameSender = { vx, vy, vz, yawRate ->
                sentFrames.add(VirtualStickVelocity(vx, vy, vz, yawRate))
            },
            scope = testScope,
            frameIntervalMs = 50L,
            defaultDurationMs = 500L,
            minDurationMs = 50L,
            maxDurationMs = 10_000L
        )
    }

    @Test
    fun testDurationControlsStreamingFrameCountAndAutoHover() = testScope.runTest {
        val command = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = 2.0,
            vy = -1.0,
            vz = 0.5,
            yawRate = 10.0,
            durationMs = 1000L
        )

        val effectiveDuration = streamer.startMove(command)
        assertEquals(1000L, effectiveDuration)

        // Initially at t = 0
        testScope.testScheduler.runCurrent()
        assertEquals(1, sentFrames.size)
        assertEquals(2.0, sentFrames.first().vx, 0.001)
        assertEquals(-1.0, sentFrames.first().vy, 0.001)
        assertEquals(0.5, sentFrames.first().vz, 0.001)
        assertEquals(10.0, sentFrames.first().yawRate, 0.001)
        assertTrue(streamer.isStreaming.value)

        // Advance by 500ms (10 intervals of 50ms -> 11 frames total at t=0, 50, ..., 500)
        testScope.testScheduler.advanceTimeBy(500)
        testScope.testScheduler.runCurrent()
        assertEquals(11, sentFrames.size)
        assertTrue(sentFrames.all { it.vx == 2.0 && it.vy == -1.0 })
        assertTrue(streamer.isStreaming.value)

        // Advance to full 1000ms duration
        testScope.testScheduler.advanceTimeBy(500)
        testScope.testScheduler.advanceUntilIdle()

        // 20 active frames + 1 auto-hover zero frame = 21 frames
        assertEquals(21, sentFrames.size)
        val lastFrame = sentFrames.last()
        assertEquals(0.0, lastFrame.vx, 0.001)
        assertEquals(0.0, lastFrame.vy, 0.001)
        assertEquals(0.0, lastFrame.vz, 0.001)
        assertEquals(0.0, lastFrame.yawRate, 0.001)
        assertFalse(streamer.isStreaming.value)
    }

    @Test
    fun testDurationSafetyClamping() = testScope.runTest {
        // 1. Below minimum duration (10ms -> clamped to 50ms)
        val shortCmd = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = 1.0,
            durationMs = 10L
        )
        val shortDuration = streamer.startMove(shortCmd)
        assertEquals(50L, shortDuration)

        testScope.testScheduler.advanceUntilIdle()
        // 1 active frame (t=0) + 1 hover frame (t=50)
        assertEquals(2, sentFrames.size)
        assertEquals(1.0, sentFrames[0].vx, 0.001)
        assertEquals(0.0, sentFrames[1].vx, 0.001)

        // 2. Above maximum duration (60,000ms -> clamped to 10,000ms)
        sentFrames.clear()
        val longCmd = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = 1.5,
            durationMs = 60_000L
        )
        val longDuration = streamer.startMove(longCmd)
        assertEquals(10_000L, longDuration)

        testScope.testScheduler.advanceUntilIdle()
        // 200 active frames (10,000 / 50) + 1 hover frame = 201 frames
        assertEquals(201, sentFrames.size)
        assertEquals(0.0, sentFrames.last().vx, 0.001)

        // 3. Null duration (defaults to 500ms)
        sentFrames.clear()
        val defaultCmd = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = 2.0,
            durationMs = null
        )
        val defaultDuration = streamer.startMove(defaultCmd)
        assertEquals(500L, defaultDuration)

        testScope.testScheduler.advanceUntilIdle()
        // 10 active frames (500 / 50) + 1 hover frame = 11 frames
        assertEquals(11, sentFrames.size)
        assertEquals(0.0, sentFrames.last().vx, 0.001)
    }

    @Test
    fun testPreemptionBetweenConsecutiveMoveCommands() = testScope.runTest {
        val move1 = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = 2.0,
            durationMs = 1000L
        )
        streamer.startMove(move1)

        // Advance 200ms into Move 1 (t=0, 50, 100, 150, 200)
        testScope.testScheduler.advanceTimeBy(200)
        testScope.testScheduler.runCurrent()
        val move1FrameCount = sentFrames.size
        assertEquals(5, move1FrameCount)
        assertTrue(sentFrames.all { it.vx == 2.0 })

        // Preempt with Move 2 (vx = -3.0, duration = 300ms)
        val move2 = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = -3.0,
            durationMs = 300L
        )
        streamer.startMove(move2)

        // Advance through Move 2 to completion (300ms)
        testScope.testScheduler.advanceTimeBy(300)
        testScope.testScheduler.advanceUntilIdle()

        // Move 2 sends frame at t=0, 50, 100, 150, 200, 250 (6 frames) + 1 hover frame = 7 frames
        val move2Frames = sentFrames.drop(move1FrameCount)
        assertEquals(7, move2Frames.size)
        assertEquals(-3.0, move2Frames[0].vx, 0.001)
        assertEquals(-3.0, move2Frames[5].vx, 0.001)
        assertEquals(0.0, move2Frames.last().vx, 0.001)

        // Verify Move 1 did NOT inject an extra zero frame before Move 2 started
        assertEquals(-3.0, sentFrames[move1FrameCount].vx, 0.001)
    }

    @Test
    fun testStopAndHoverCancelsActiveMoveImmediately() = testScope.runTest {
        val move = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = 3.0,
            durationMs = 2000L
        )
        streamer.startMove(move)

        // Advance 200ms
        testScope.testScheduler.advanceTimeBy(200)
        testScope.testScheduler.runCurrent()
        assertEquals(5, sentFrames.size)
        assertTrue(streamer.isStreaming.value)

        // Call hover
        streamer.stopAndHover()
        assertFalse(streamer.isStreaming.value)
        assertEquals(6, sentFrames.size)
        assertEquals(0.0, sentFrames.last().vx, 0.001)

        // Advance past original 2000ms
        testScope.testScheduler.advanceTimeBy(3000)
        testScope.testScheduler.advanceUntilIdle()

        // No new frames were sent
        assertEquals(6, sentFrames.size)
    }

    @Test
    fun testEmergencyStopCancelsActiveMoveImmediately() = testScope.runTest {
        val move = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = 4.0,
            durationMs = 2000L
        )
        streamer.startMove(move)
        testScope.testScheduler.advanceTimeBy(100)
        testScope.testScheduler.runCurrent()
        assertEquals(3, sentFrames.size) // t=0, 50, 100

        streamer.emergencyStop()
        assertFalse(streamer.isStreaming.value)
        assertEquals(0.0, sentFrames.last().vx, 0.001)

        testScope.testScheduler.advanceTimeBy(3000)
        testScope.testScheduler.advanceUntilIdle()
        assertEquals(4, sentFrames.size) // 3 active + 1 emergency zero
    }

    @Test
    fun testPilotOverrideCancelsStreamingImmediately() = testScope.runTest {
        val move = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = 2.0,
            durationMs = 3000L
        )
        streamer.startMove(move)
        testScope.testScheduler.advanceTimeBy(100)
        testScope.testScheduler.runCurrent()
        assertEquals(3, sentFrames.size) // t=0, 50, 100

        streamer.onPilotOverride()
        assertFalse(streamer.isStreaming.value)
        val countAfterOverride = sentFrames.size
        assertEquals(3, countAfterOverride)

        // Advance time - verify no further automated frames are sent
        testScope.testScheduler.advanceTimeBy(4000)
        testScope.testScheduler.advanceUntilIdle()
        assertEquals(countAfterOverride, sentFrames.size)
    }

    @Test
    fun testStartRotateStreamsYawRateWithZeroLinearVelocitiesAndAutoHovers() = testScope.runTest {
        val rotateParams = com.dji.dronecontrol.core.model.RotateParams(
            yawRate = 25.0,
            durationMs = 1000L
        )

        val effectiveDuration = streamer.startRotate(rotateParams)
        assertEquals(1000L, effectiveDuration)

        testScope.testScheduler.runCurrent()
        assertEquals(1, sentFrames.size)
        val initialFrame = sentFrames.first()
        assertEquals(0.0, initialFrame.vx, 0.001)
        assertEquals(0.0, initialFrame.vy, 0.001)
        assertEquals(0.0, initialFrame.vz, 0.001)
        assertEquals(25.0, initialFrame.yawRate, 0.001)
        assertTrue(streamer.isStreaming.value)

        // Advance 500ms -> 11 frames (t=0, 50, ..., 500)
        testScope.testScheduler.advanceTimeBy(500)
        testScope.testScheduler.runCurrent()
        assertEquals(11, sentFrames.size)
        assertTrue(sentFrames.all { it.vx == 0.0 && it.vy == 0.0 && it.vz == 0.0 && it.yawRate == 25.0 })

        // Complete 1000ms duration
        testScope.testScheduler.advanceTimeBy(500)
        testScope.testScheduler.advanceUntilIdle()

        // 20 active rotation frames + 1 auto-hover zero frame = 21 frames
        assertEquals(21, sentFrames.size)
        val lastFrame = sentFrames.last()
        assertEquals(0.0, lastFrame.vx, 0.001)
        assertEquals(0.0, lastFrame.vy, 0.001)
        assertEquals(0.0, lastFrame.vz, 0.001)
        assertEquals(0.0, lastFrame.yawRate, 0.001)
        assertFalse(streamer.isStreaming.value)
    }

    @Test
    fun testStickNormalizedModeScaling() {
        val stickCmd = VirtualStickCommand(
            mode = VirtualStickMode.STICK,
            pitch = 1.0,
            roll = -0.5,
            throttle = 0.25,
            yaw = -1.0,
            durationMs = 500L
        )
        val velocity = streamer.resolveVelocity(stickCmd)
        assertEquals(15.0, velocity.vx, 0.001)    // 1.0 * 15.0
        assertEquals(-7.5, velocity.vy, 0.001)   // -0.5 * 15.0
        assertEquals(1.0, velocity.vz, 0.001)    // 0.25 * 4.0
        assertEquals(-100.0, velocity.yawRate, 0.001) // -1.0 * 100.0
    }
}
