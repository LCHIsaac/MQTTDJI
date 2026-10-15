package com.dji.dronecontrol.core.gimbal

import com.dji.dronecontrol.core.model.GimbalSetAngleParams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GimbalSetAngleAxisPolicyTest {

    @Test
    fun yawZero_isIgnoredEvenWhenYawSupported() {
        val params = GimbalSetAngleParams(pitchDeg = 0.0, yawDeg = 0.0, rollDeg = 0.0)
        assertTrue(GimbalSetAngleAxisPolicy.yawIgnored(params, yawAdjustSupported = true))
        assertFalse(GimbalSetAngleAxisPolicy.yawRequested(params))
    }

    @Test
    fun yawNonZero_requiresSupport() {
        val params = GimbalSetAngleParams(yawDeg = 15.0)
        assertTrue(GimbalSetAngleAxisPolicy.yawRequested(params))
        assertFalse(GimbalSetAngleAxisPolicy.yawIgnored(params, yawAdjustSupported = true))
        assertTrue(GimbalSetAngleAxisPolicy.yawIgnored(params, yawAdjustSupported = false))
    }
}
