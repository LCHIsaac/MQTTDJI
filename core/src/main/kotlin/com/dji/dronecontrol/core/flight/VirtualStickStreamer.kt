package com.dji.dronecontrol.core.flight

import com.dji.dronecontrol.core.model.VirtualStickCommand
import com.dji.dronecontrol.core.model.VirtualStickMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Velocity components for virtual stick control.
 */
data class VirtualStickVelocity(
    val vx: Double,
    val vy: Double,
    val vz: Double,
    val yawRate: Double
)

object VirtualStickConstants {
    /** Default move duration if not specified in command payload (500 ms). */
    const val DEFAULT_DURATION_MS: Long = 500L

    /** Minimum allowable duration for a single move command (50 ms, at least 1 tick). */
    const val MIN_DURATION_MS: Long = 50L

    /** Safety watchdog cap: maximum allowable duration for a single move command (10,000 ms / 10 s). */
    const val MAX_DURATION_MS: Long = 10_000L

    /** Periodic transmission interval for DJI flight controller virtual stick packets (50 ms / 20 Hz). */
    const val FRAME_INTERVAL_MS: Long = 50L

    /** Maximum horizontal speed scaling for normalized stick mode (m/s). */
    const val MAX_HORIZONTAL_SPEED: Double = 15.0

    /** Maximum vertical speed scaling for normalized stick mode (m/s). */
    const val MAX_VERTICAL_SPEED: Double = 4.0

    /** Maximum yaw rate scaling for normalized stick mode (deg/s). */
    const val MAX_YAW_RATE: Double = 100.0
}

/**
 * Functional interface for sending raw virtual stick velocity frames to the flight controller.
 */
fun interface VirtualStickFrameSender {
    suspend fun sendFrame(vx: Double, vy: Double, vz: Double, yawRate: Double)
}

/**
 * Manages periodic transmission of Virtual Stick frames for the exact duration requested.
 *
 * Hardware context:
 * DJI Mobile SDK V5 Flight Controller expects periodic stick control frames at 10–50 Hz (typically 20 Hz / 50 ms).
 * If a stick command is only sent once, the flight controller's internal safety watchdog stops movement
 * after ~200–500 ms.
 *
 * [VirtualStickStreamer] solves this by:
 * 1. Continuously streaming control frames at [frameIntervalMs] (default 20 Hz / 50 ms) for [durationMs].
 * 2. Enforcing safety bounds: [minDurationMs] (50 ms) to [maxDurationMs] (10,000 ms).
 * 3. Atomically cancelling any previous active move job when a new move arrives (preemption).
 * 4. Automatically transmitting a zero-velocity hover frame at the end of the duration window.
 * 5. Providing immediate cancellation and zeroing on [stopAndHover], [emergencyStop], and [onPilotOverride].
 */
class VirtualStickStreamer(
    private val frameSender: VirtualStickFrameSender,
    private val scope: CoroutineScope,
    private val frameIntervalMs: Long = VirtualStickConstants.FRAME_INTERVAL_MS,
    private val defaultDurationMs: Long = VirtualStickConstants.DEFAULT_DURATION_MS,
    private val minDurationMs: Long = VirtualStickConstants.MIN_DURATION_MS,
    private val maxDurationMs: Long = VirtualStickConstants.MAX_DURATION_MS
) {
    private var activeStreamJob: Job? = null

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentVelocity = MutableStateFlow(VirtualStickVelocity(0.0, 0.0, 0.0, 0.0))
    val currentVelocity: StateFlow<VirtualStickVelocity> = _currentVelocity.asStateFlow()

    /**
     * Resolves the target velocity components from a [VirtualStickCommand].
     */
    fun resolveVelocity(command: VirtualStickCommand): VirtualStickVelocity {
        return when (command.mode) {
            VirtualStickMode.VELOCITY -> VirtualStickVelocity(
                vx = command.vx,
                vy = command.vy,
                vz = command.vz,
                yawRate = command.yawRate
            )
            VirtualStickMode.STICK -> VirtualStickVelocity(
                vx = command.pitch * VirtualStickConstants.MAX_HORIZONTAL_SPEED,
                vy = command.roll * VirtualStickConstants.MAX_HORIZONTAL_SPEED,
                vz = command.throttle * VirtualStickConstants.MAX_VERTICAL_SPEED,
                yawRate = command.yaw * VirtualStickConstants.MAX_YAW_RATE
            )
        }
    }

    /**
     * Starts continuous periodic transmission of pure rotation (yaw) for [params.durationMs].
     *
     * @return The effective duration in milliseconds after applying safety clamping.
     */
    suspend fun startRotate(params: com.dji.dronecontrol.core.model.RotateParams): Long {
        val vsCommand = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = 0.0,
            vy = 0.0,
            vz = 0.0,
            yawRate = params.yawRate,
            durationMs = params.durationMs
        )
        return startMove(vsCommand)
    }

    /**
     * Starts continuous periodic transmission of the requested [command] for its duration.
     * Cancels any existing active movement stream immediately.
     *
     * @return The effective duration in milliseconds after applying safety clamping.
     */
    suspend fun startMove(command: VirtualStickCommand): Long {
        // 1. Cancel previous stream job if running
        val prevJob = activeStreamJob
        activeStreamJob = null
        prevJob?.cancel()

        // 2. Resolve velocity and bounded duration
        val velocity = resolveVelocity(command)
        val durationMs = (command.durationMs ?: defaultDurationMs).coerceIn(minDurationMs, maxDurationMs)

        _currentVelocity.value = velocity
        _isStreaming.value = true

        // 3. Launch periodic stream loop
        activeStreamJob = scope.launch {
            try {
                var elapsed = 0L
                while (isActive && elapsed < durationMs) {
                    frameSender.sendFrame(velocity.vx, velocity.vy, velocity.vz, velocity.yawRate)
                    val nextDelay = minOf(frameIntervalMs, durationMs - elapsed)
                    if (nextDelay > 0) {
                        delay(nextDelay)
                        elapsed += nextDelay
                    } else {
                        break
                    }
                }

                // 4. Auto-hover when duration completes normally without cancellation
                if (isActive) {
                    _currentVelocity.value = VirtualStickVelocity(0.0, 0.0, 0.0, 0.0)
                    _isStreaming.value = false
                    frameSender.sendFrame(0.0, 0.0, 0.0, 0.0)
                }
            } catch (e: CancellationException) {
                // Job was cancelled by another command or pilot override; do not send zero-hover here
                // to avoid overriding the incoming replacement command.
                throw e
            }
        }

        return durationMs
    }

    /**
     * Stops active virtual stick streaming and sends an immediate zero-velocity hover frame.
     */
    suspend fun stopAndHover() {
        val prevJob = activeStreamJob
        activeStreamJob = null
        prevJob?.cancel()

        _currentVelocity.value = VirtualStickVelocity(0.0, 0.0, 0.0, 0.0)
        _isStreaming.value = false
        frameSender.sendFrame(0.0, 0.0, 0.0, 0.0)
    }

    /**
     * Emergency stop: halts active streaming and sends immediate zero-velocity.
     */
    suspend fun emergencyStop() {
        stopAndHover()
    }

    /**
     * Callback when pilot physical stick/switch override is detected.
     * Immediately stops streaming and releases automated control.
     */
    fun onPilotOverride() {
        val prevJob = activeStreamJob
        activeStreamJob = null
        prevJob?.cancel()

        _currentVelocity.value = VirtualStickVelocity(0.0, 0.0, 0.0, 0.0)
        _isStreaming.value = false
    }
}
