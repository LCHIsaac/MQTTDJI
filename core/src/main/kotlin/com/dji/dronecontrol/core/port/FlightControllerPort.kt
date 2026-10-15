package com.dji.dronecontrol.core.port

import com.dji.dronecontrol.core.model.RotateParams
import com.dji.dronecontrol.core.model.TakeoffParams
import com.dji.dronecontrol.core.model.VirtualStickCommand
import kotlinx.coroutines.flow.StateFlow

sealed class FlightResult {
    object Success : FlightResult()
    data class Failure(val message: String, val cause: Throwable? = null) : FlightResult()
}

/**
 * Domain port for aircraft flight controller operations.
 * Pure Kotlin interface, completely decoupled from DJI SDK.
 */
interface FlightControllerPort {
    suspend fun takeoff(params: TakeoffParams = TakeoffParams()): FlightResult
    suspend fun land(): FlightResult
    suspend fun goHome(): FlightResult
    suspend fun hover(): FlightResult
    suspend fun move(command: VirtualStickCommand): FlightResult
    suspend fun rotate(params: RotateParams): FlightResult
    suspend fun emergencyStop(): FlightResult

    val isFlying: StateFlow<Boolean>
    val flightMode: StateFlow<String>
    val motorsOn: StateFlow<Boolean>
}
