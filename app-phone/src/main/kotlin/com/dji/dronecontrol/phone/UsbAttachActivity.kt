package com.dji.dronecontrol.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Entry when RC-N2/N1 attaches in USB accessory mode (MSDK V5 sample pattern).
 */
class UsbAttachActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (detected, desc) = DjiUsbAccessoryHelper.scanAccessories(this)
        if (detected) {
            com.dji.dronecontrol.core.log.AppLogger.i("UsbAttachActivity", "ATTACHED $desc")
        }
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(MainActivity.EXTRA_USB_ACCESSORY_ATTACHED, true)
            if (intent != null) {
                putExtras(intent)
            }
        }
        startActivity(launch)
        finish()
    }
}
