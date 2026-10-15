package com.dji.dronecontrol.core.gimbal

import com.dji.dronecontrol.core.model.GimbalSetAngleParams

/**
 * Axis ignore rules for SDK [GimbalAngleRotation].
 * Mini 4 / consumer gimbals reject yaw=0 with yawIgnored=false (DJI MSDK #527).
 */
object GimbalSetAngleAxisPolicy {

    fun pitchIgnored(params: GimbalSetAngleParams): Boolean = params.pitchDeg == null

    fun yawIgnored(params: GimbalSetAngleParams, yawAdjustSupported: Boolean): Boolean {
        if (!yawAdjustSupported) return true
        val yaw = params.yawDeg ?: return true
        return yaw == 0.0
    }

    fun yawRequested(params: GimbalSetAngleParams): Boolean {
        val yaw = params.yawDeg ?: return false
        return yaw != 0.0
    }
}
