package com.dji.dronecontrol.adapter.dji

import android.util.Log
import com.dji.dronecontrol.core.gimbal.GimbalConstants
import com.dji.dronecontrol.core.gimbal.GimbalRotateStreamer
import com.dji.dronecontrol.core.gimbal.GimbalSetAngleAxisPolicy
import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.model.GimbalRotateParams
import com.dji.dronecontrol.core.model.GimbalSetAngleParams
import com.dji.dronecontrol.core.port.GimbalPort
import com.dji.dronecontrol.core.port.GimbalResult
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.CtrlInfo
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.sdk.keyvalue.value.gimbal.GimbalSpeedRotation
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DjiGimbalAdapter(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : GimbalPort {

    private val tag = "DjiGimbal"
    private val gimbalIndex = ComponentIndexType.LEFT_OR_MAIN

    private val rotateStreamer = GimbalRotateStreamer(
        frameSender = { pitch, yaw -> sendSpeedRotation(pitch, yaw) },
        scope = scope,
    )

    init {
        DjiMsdkSession.onProductDisconnect { cancelActiveRotation() }
    }

    private fun logI(msg: String) {
        Log.i(tag, msg)
        AppLogger.i(tag, msg)
    }

    private fun logW(msg: String) {
        Log.w(tag, msg)
        AppLogger.w(tag, msg)
    }

    override fun cancelActiveRotation() {
        rotateStreamer.cancelActive()
    }

    override suspend fun rotate(params: GimbalRotateParams): GimbalResult = withContext(Dispatchers.IO) {
        if (!DjiMsdkSession.isProductConnected) {
            return@withContext GimbalResult.Failure("Aircraft not connected", "NOT_CONNECTED")
        }
        if (params.pitchRateDps == 0.0 && params.yawRateDps == 0.0) {
            return@withContext GimbalResult.Failure("At least one of pitch_rate_dps or yaw_rate_dps must be non-zero")
        }
        if (params.yawRateDps != 0.0 && !isYawAdjustSupported()) {
            return@withContext GimbalResult.Failure("Gimbal yaw rate not supported on this aircraft", "GIMBAL_YAW_UNSUPPORTED")
        }
        try {
            val duration = rotateStreamer.startRotate(params)
            logI("Gimbal rotate started pitch=${params.pitchRateDps} yaw=${params.yawRateDps} duration=${duration}ms")
            GimbalResult.Success
        } catch (t: Throwable) {
            GimbalResult.Failure("Gimbal rotate failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    override suspend fun setAngle(params: GimbalSetAngleParams): GimbalResult = withContext(Dispatchers.IO) {
        if (!DjiMsdkSession.isProductConnected) {
            return@withContext GimbalResult.Failure("Aircraft not connected", "NOT_CONNECTED")
        }
        if (params.pitchDeg == null && params.yawDeg == null && params.rollDeg == null) {
            return@withContext GimbalResult.Failure("At least one of pitch_deg, yaw_deg, or roll_deg is required")
        }
        if (params.rollDeg != null && params.rollDeg != 0.0) {
            return@withContext GimbalResult.Failure("Gimbal roll angle not supported on this gimbal", "GIMBAL_ROLL_UNSUPPORTED")
        }
        val yawAdjustSupported = isYawAdjustSupported()
        if (GimbalSetAngleAxisPolicy.yawRequested(params) && !yawAdjustSupported) {
            return@withContext GimbalResult.Failure("Gimbal yaw angle not supported on this aircraft", "GIMBAL_YAW_UNSUPPORTED")
        }
        val rotation = buildAngleRotation(params, yawAdjustSupported)
        performAngleRotation(rotation)
    }

    private fun buildAngleRotation(
        params: GimbalSetAngleParams,
        yawAdjustSupported: Boolean,
    ): GimbalAngleRotation {
        val ignorePitch = GimbalSetAngleAxisPolicy.pitchIgnored(params)
        val ignoreYaw = GimbalSetAngleAxisPolicy.yawIgnored(params, yawAdjustSupported)
        return GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            if (!ignorePitch) {
                pitch = clampPitch(params.pitchDeg!!)
            }
            if (!ignoreYaw) {
                yaw = params.yawDeg
            }
            pitchIgnored = ignorePitch
            yawIgnored = ignoreYaw
            rollIgnored = true
            duration = GimbalConstants.SET_ANGLE_DURATION_SEC
            jointReferenceUsed = true
            timeout = GimbalConstants.SET_ANGLE_ACTION_TIMEOUT_SEC
        }
    }

    override suspend fun reset(): GimbalResult = withContext(Dispatchers.IO) {
        if (!DjiMsdkSession.isProductConnected) {
            return@withContext GimbalResult.Failure("Aircraft not connected", "NOT_CONNECTED")
        }
        cancelActiveRotation()
        val key = KeyTools.createKey(GimbalKey.KeyGimbalReset, gimbalIndex)
        suspendCoroutine<GimbalResult> { cont ->
            KeyManager.getInstance().performAction(
                key,
                GimbalResetType.RECENTER,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        logI("Gimbal reset (RECENTER) OK")
                        cont.resume(GimbalResult.Success)
                    }

                    override fun onFailure(error: IDJIError) {
                        cont.resume(
                            GimbalResult.Failure(
                                "Gimbal reset failed: ${error.formatForLog()}",
                                "GIMBAL_ERROR",
                            ),
                        )
                    }
                },
            )
        }
    }

    private suspend fun performAngleRotation(rotation: GimbalAngleRotation): GimbalResult {
        val key = KeyTools.createKey(GimbalKey.KeyRotateByAngle, gimbalIndex)
        if (!KeyManager.getInstance().isKeySupported(key)) {
            return GimbalResult.Failure(
                "Gimbal absolute angle (KeyRotateByAngle) not supported on this aircraft",
                "GIMBAL_ANGLE_UNSUPPORTED",
            )
        }
        return suspendCoroutine<GimbalResult> { cont ->
            KeyManager.getInstance().performAction(
                key,
                rotation,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        logI(
                            "Gimbal set_angle OK pitchIgnored=${rotation.pitchIgnored} " +
                                "yawIgnored=${rotation.yawIgnored} duration=${rotation.duration}s",
                        )
                        cont.resume(GimbalResult.Success)
                    }

                    override fun onFailure(error: IDJIError) {
                        logW("Gimbal set_angle failed: ${error.formatForLog()}")
                        cont.resume(
                            GimbalResult.Failure(
                                "Gimbal set_angle failed: ${error.formatForLog()}",
                                "GIMBAL_ERROR",
                            ),
                        )
                    }
                },
            )
        }
    }

    private suspend fun sendSpeedRotation(pitchDps: Double, yawDps: Double) {
        val key = KeyTools.createKey(GimbalKey.KeyRotateBySpeed, gimbalIndex)
        val payload = GimbalSpeedRotation().apply {
            pitch = pitchDps
            yaw = yawDps
            roll = 0.0
            ctrlInfo = CtrlInfo().apply {
                disableReturnCenter = true
                enableGimbalLock = false
            }
        }
        suspendCoroutine { cont ->
            KeyManager.getInstance().performAction(
                key,
                payload,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        cont.resume(Unit)
                    }

                    override fun onFailure(error: IDJIError) {
                        logW("Gimbal speed frame failed: ${error.formatForLog()}")
                        cont.resume(Unit)
                    }
                },
            )
        }
    }

    private fun isYawAdjustSupported(): Boolean {
        return try {
            val key = KeyTools.createKey(GimbalKey.KeyYawAdjustSupported, gimbalIndex)
            KeyManager.getInstance().getValue(key) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun clampPitch(deg: Double): Double =
        deg.coerceIn(GimbalConstants.PITCH_MIN_DEG, GimbalConstants.PITCH_MAX_DEG)
}
