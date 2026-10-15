package com.dji.dronecontrol.adapter.dji

import android.util.Log
import com.dji.dronecontrol.core.flight.VirtualStickConstants
import com.dji.dronecontrol.core.flight.VirtualStickStreamer
import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.model.RotateParams
import com.dji.dronecontrol.core.model.TakeoffParams
import com.dji.dronecontrol.core.model.VirtualStickCommand
import com.dji.dronecontrol.core.port.FlightControllerPort
import com.dji.dronecontrol.core.port.FlightResult
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * DJI Mobile SDK V5 Flight Controller Adapter.
 *
 * Implements [FlightControllerPort] using official DJI Mobile SDK V5 Aircraft APIs:
 * - [KeyManager] & [FlightControllerKey] for auto-takeoff, auto-landing, RTH, and telemetry.
 * - [VirtualStickManager] with [VirtualStickStreamer] for sustained periodic velocity control
 *   (20 Hz / 50 ms transmission rate) over the entire [VirtualStickCommand.durationMs] window.
 * - Automatic safety timeout: velocity commands automatically reset to zero / hover
 *   after the commanded duration (bounded between 50 ms and 10,000 ms) to prevent runaway flight.
 * - Pilot-in-the-loop safety & stick override detection via [VirtualStickStateListener].
 */
class DjiFlightControllerAdapter(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : FlightControllerPort {

    private val tag = "DjiFlightAdapter"

    private fun logI(msg: String) {
        Log.i(tag, msg)
        AppLogger.i(tag, msg)
    }

    private fun logW(msg: String) {
        Log.w(tag, msg)
        AppLogger.w(tag, msg)
    }

    private fun logE(msg: String) {
        Log.e(tag, msg)
        AppLogger.e(tag, msg)
    }

    private val _isFlying = MutableStateFlow(false)
    override val isFlying: StateFlow<Boolean> = _isFlying.asStateFlow()

    private val _flightMode = MutableStateFlow("UNKNOWN")
    override val flightMode: StateFlow<String> = _flightMode.asStateFlow()

    private val _motorsOn = MutableStateFlow(false)
    override val motorsOn: StateFlow<Boolean> = _motorsOn.asStateFlow()

    private var isVirtualStickEnabled = false

    private val vsStreamer = VirtualStickStreamer(
        frameSender = { vx, vy, vz, yawRate ->
            try {
                val flightParam = VirtualStickFlightControlParam(
                    vx,
                    vy,
                    yawRate,
                    vz,
                    VerticalControlMode.VELOCITY,
                    RollPitchControlMode.VELOCITY,
                    YawControlMode.ANGULAR_VELOCITY,
                    FlightCoordinateSystem.BODY
                )
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(flightParam)
            } catch (t: Throwable) {
                logE("Error sending Virtual Stick frame (vx=%.2f vy=%.2f vz=%.2f yaw=%.2f): ${t.message}".format(vx, vy, vz, yawRate))
            }
        },
        scope = scope
    )

    private var flightListenersRegistered = false
    private val fcIndex = ComponentIndexType.LEFT_OR_MAIN

    init {
        DjiMsdkSession.whenProductConnected {
            listenFlightState()
            initVirtualStick()
        }
        DjiMsdkSession.whenFlightControllerLinkChanged { linked ->
            if (linked) {
                scope.launch { bootstrapFlightTelemetry() }
            } else {
                _isFlying.value = false
                _flightMode.value = "UNKNOWN"
                _motorsOn.value = false
            }
        }
        DjiMsdkSession.onProductDisconnect {
            flightListenersRegistered = false
            DjiMsdkSession.cancelKeyListeners(this)
            _isFlying.value = false
            _flightMode.value = "UNKNOWN"
            _motorsOn.value = false
        }
    }

    private suspend fun bootstrapFlightTelemetry() {
        val log: (String) -> Unit = { msg -> logI(msg) }
        DjiKeyValueHelper.getValueAsync(
            "FlightController.KeyIsFlying",
            KeyTools.createKey(FlightControllerKey.KeyIsFlying, fcIndex),
            log
        )?.let { _isFlying.value = it }

        DjiKeyValueHelper.getValueAsync(
            "FlightController.KeyFlightMode",
            KeyTools.createKey(FlightControllerKey.KeyFlightMode, fcIndex),
            log
        )?.let { _flightMode.value = it.name }

        DjiKeyValueHelper.getValueAsync(
            "FlightController.KeyAreMotorsOn",
            KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn, fcIndex),
            log
        )?.let { _motorsOn.value = it }
    }

    private fun listenFlightState() {
        if (flightListenersRegistered) return
        flightListenersRegistered = true
        try {
            val keyManager = KeyManager.getInstance()
            logI("Registering FlightController KeyManager listeners (LEFT_OR_MAIN, getOnce=true)...")

            val isFlyingKey = KeyTools.createKey(FlightControllerKey.KeyIsFlying, fcIndex)
            keyManager.listen(isFlyingKey, this, true, object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    val flying = newValue ?: false
                    _isFlying.value = flying
                    logI("Flight state updated: isFlying=$flying")
                }
            })

            val flightModeKey = KeyTools.createKey(FlightControllerKey.KeyFlightMode, fcIndex)
            keyManager.listen(flightModeKey, this, true, object : CommonCallbacks.KeyListener<FlightMode> {
                override fun onValueChange(oldValue: FlightMode?, newValue: FlightMode?) {
                    val mode = newValue?.name ?: "UNKNOWN"
                    _flightMode.value = mode
                    logI("Flight mode updated: $mode")
                }
            })

            val areMotorsOnKey = KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn, fcIndex)
            keyManager.listen(areMotorsOnKey, this, true, object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    val motors = newValue ?: false
                    _motorsOn.value = motors
                    logI("Motors state updated: motorsOn=$motors")
                }
            })

            scope.launch { bootstrapFlightTelemetry() }

        } catch (t: Throwable) {
            val msg = t.message ?: t.toString()
            if (DjiMsdkSession.isBenignKeyError(msg)) {
                Log.d(tag, "FlightController listeners deferred: $msg")
            } else {
                logE("Failed to initialize FlightController key listeners: $msg")
            }
        }
    }

    private fun initVirtualStick() {
        try {
            val vsManager = VirtualStickManager.getInstance()
            vsManager.init()
            vsManager.setVirtualStickAdvancedModeEnabled(true)
            vsManager.setVirtualStickStateListener(object : VirtualStickStateListener {
                override fun onVirtualStickStateUpdate(state: VirtualStickState) {
                    isVirtualStickEnabled = state.isVirtualStickEnable
                    logI("Virtual stick state update: enabled=$isVirtualStickEnabled, authority=${state.currentFlightControlAuthorityOwner}")
                }

                override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
                    logW("Flight control authority change reason: $reason")
                    if (reason == FlightControlAuthorityChangeReason.RC_SWITCH ||
                        reason == FlightControlAuthorityChangeReason.RC_PAUSE_STOP ||
                        reason == FlightControlAuthorityChangeReason.RC_ONE_KEY_GO_HOME
                    ) {
                        vsStreamer.onPilotOverride()
                        logW("Pilot manual stick/RC override active - cancelled automated VS control")
                    }
                }
            })
            logI("VirtualStickManager initialized with advanced mode")
        } catch (t: Throwable) {
            logE("VirtualStickManager initialization note: ${t.message}")
        }
    }

    override suspend fun takeoff(params: TakeoffParams): FlightResult = withContext(Dispatchers.IO) {
        vsStreamer.stopAndHover()
        logI("Initiating auto-takeoff (target height: ${params.targetHeightM}m)...")
        suspendCoroutine { cont ->
            try {
                val takeoffKey = KeyTools.createKey(FlightControllerKey.KeyStartTakeoff)
                KeyManager.getInstance().performAction(takeoffKey, EmptyMsg(), object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        logI("Takeoff command succeeded")
                        _isFlying.value = true
                        _motorsOn.value = true
                        cont.resume(FlightResult.Success)
                    }

                    override fun onFailure(error: IDJIError) {
                        val errMsg = "Takeoff failed: ${error.description()} (code: ${error.errorCode()})"
                        logE(errMsg)
                        cont.resume(FlightResult.Failure(errMsg))
                    }
                })
            } catch (t: Throwable) {
                logE("Takeoff exception: ${t.message}")
                cont.resume(FlightResult.Failure("Takeoff exception: ${t.message}", t))
            }
        }
    }

    override suspend fun land(): FlightResult = withContext(Dispatchers.IO) {
        vsStreamer.stopAndHover()
        logI("Initiating auto-landing...")
        suspendCoroutine { cont ->
            try {
                val landKey = KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding)
                KeyManager.getInstance().performAction(landKey, EmptyMsg(), object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        logI("Auto-landing initiated successfully")
                        cont.resume(FlightResult.Success)
                    }

                    override fun onFailure(error: IDJIError) {
                        val errMsg = "Auto-landing failed: ${error.description()} (code: ${error.errorCode()})"
                        logE(errMsg)
                        cont.resume(FlightResult.Failure(errMsg))
                    }
                })
            } catch (t: Throwable) {
                logE("Land exception: ${t.message}")
                cont.resume(FlightResult.Failure("Land exception: ${t.message}", t))
            }
        }
    }

    override suspend fun goHome(): FlightResult = withContext(Dispatchers.IO) {
        vsStreamer.stopAndHover()
        logI("Initiating Return-to-Home (RTH)...")
        suspendCoroutine { cont ->
            try {
                val goHomeKey = KeyTools.createKey(FlightControllerKey.KeyStartGoHome)
                KeyManager.getInstance().performAction(goHomeKey, EmptyMsg(), object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        logI("Return-to-Home initiated successfully")
                        cont.resume(FlightResult.Success)
                    }

                    override fun onFailure(error: IDJIError) {
                        val errMsg = "Return-to-Home failed: ${error.description()} (code: ${error.errorCode()})"
                        logE(errMsg)
                        cont.resume(FlightResult.Failure(errMsg))
                    }
                })
            } catch (t: Throwable) {
                logE("GoHome exception: ${t.message}")
                cont.resume(FlightResult.Failure("GoHome exception: ${t.message}", t))
            }
        }
    }

    override suspend fun hover(): FlightResult = withContext(Dispatchers.IO) {
        try {
            vsStreamer.stopAndHover()
            logI("Sent hover zero-velocity parameters and stopped streaming")
            FlightResult.Success
        } catch (t: Throwable) {
            logE("Hover exception: ${t.message}")
            FlightResult.Failure("Hover failed: ${t.message}", t)
        }
    }

    override suspend fun move(command: VirtualStickCommand): FlightResult = withContext(Dispatchers.IO) {
        try {
            val vsManager = VirtualStickManager.getInstance()

            // 1. Ensure Virtual Stick is enabled
            if (!isVirtualStickEnabled) {
                val enableSuccess = suspendCoroutine { cont ->
                    vsManager.setVirtualStickAdvancedModeEnabled(true)
                    vsManager.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            isVirtualStickEnabled = true
                            logI("Virtual stick enabled successfully")
                            cont.resume(true)
                        }

                        override fun onFailure(error: IDJIError) {
                            logW("enableVirtualStick returned error: ${error.description()} (${error.errorCode()})")
                            // Continue anyway as advanced mode may already be active
                            cont.resume(false)
                        }
                    })
                }
                if (!enableSuccess) {
                    logW("Proceeding with advanced parameter send even if enable callback was not clean")
                }
            }

            // 2. Start continuous streaming loop for duration_ms at 20 Hz
            val effectiveDuration = vsStreamer.startMove(command)
            val velocity = vsStreamer.resolveVelocity(command)
            logI("Started VS move streaming: vx=%.2f vy=%.2f vz=%.2f yaw=%.2f duration=%dms (req=%s) mode=%s".format(
                velocity.vx, velocity.vy, velocity.vz, velocity.yawRate,
                effectiveDuration, command.durationMs?.toString() ?: "default", command.mode
            ))

            FlightResult.Success
        } catch (t: Throwable) {
            logE("Move exception: ${t.message}")
            FlightResult.Failure("Virtual stick move failed: ${t.message}", t)
        }
    }

    override suspend fun rotate(params: RotateParams): FlightResult = withContext(Dispatchers.IO) {
        try {
            val vsManager = VirtualStickManager.getInstance()

            // 1. Ensure Virtual Stick is enabled
            if (!isVirtualStickEnabled) {
                val enableSuccess = suspendCoroutine { cont ->
                    vsManager.setVirtualStickAdvancedModeEnabled(true)
                    vsManager.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            isVirtualStickEnabled = true
                            logI("Virtual stick enabled successfully")
                            cont.resume(true)
                        }

                        override fun onFailure(error: IDJIError) {
                            logW("enableVirtualStick returned error: ${error.description()} (${error.errorCode()})")
                            // Continue anyway as advanced mode may already be active
                            cont.resume(false)
                        }
                    })
                }
                if (!enableSuccess) {
                    logW("Proceeding with advanced parameter send even if enable callback was not clean")
                }
            }

            // 2. Start continuous streaming loop for rotation at 20 Hz (vx=0, vy=0, vz=0, yawRate=params.yawRate)
            val effectiveDuration = vsStreamer.startRotate(params)
            logI("Started VS rotate streaming: yawRate=%.2f deg/s duration=%dms (req=%s)".format(
                params.yawRate, effectiveDuration, params.durationMs?.toString() ?: "default"
            ))

            FlightResult.Success
        } catch (t: Throwable) {
            logE("Rotate exception: ${t.message}")
            FlightResult.Failure("Virtual stick rotate failed: ${t.message}", t)
        }
    }

    override suspend fun emergencyStop(): FlightResult = withContext(Dispatchers.IO) {
        logW("EMERGENCY STOP initiated!")
        try {
            vsStreamer.emergencyStop()
            logI("Emergency zero-velocity sent to virtual sticks and streaming halted")
        } catch (t: Throwable) {
            logW("Zeroing virtual sticks on emergency stop: ${t.message}")
        }

        // Perform Emergency Stop action key on KeyManager
        suspendCoroutine { cont ->
            try {
                val emKey = KeyTools.createKey(FlightControllerKey.KeyEmergencyStop)
                KeyManager.getInstance().performAction(emKey, EmptyMsg(), object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        logW("Emergency stop action completed successfully")
                        cont.resume(FlightResult.Success)
                    }

                    override fun onFailure(error: IDJIError) {
                        logW("Emergency stop action key failed: ${error.description()} (${error.errorCode()}), fallback to zero hover")
                        // Even if firmware restricts emergency motor kill during flight, zero-hover was already issued
                        cont.resume(FlightResult.Success)
                    }
                })
            } catch (t: Throwable) {
                logW("Emergency stop action exception: ${t.message}, fallback to zero hover")
                cont.resume(FlightResult.Success)
            }
        }
    }
}
