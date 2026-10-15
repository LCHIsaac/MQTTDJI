package com.dji.dronecontrol.core.port

import com.dji.dronecontrol.core.model.GimbalRotateParams
import com.dji.dronecontrol.core.model.GimbalSetAngleParams

sealed class GimbalResult {
    object Success : GimbalResult()
    data class Failure(val message: String, val code: String = "GIMBAL_ERROR") : GimbalResult()
}

interface GimbalPort {
    suspend fun rotate(params: GimbalRotateParams): GimbalResult
    suspend fun setAngle(params: GimbalSetAngleParams): GimbalResult
    suspend fun reset(): GimbalResult
    fun cancelActiveRotation()
}
