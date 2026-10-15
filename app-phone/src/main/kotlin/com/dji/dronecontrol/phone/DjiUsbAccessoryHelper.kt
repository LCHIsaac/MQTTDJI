package com.dji.dronecontrol.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import com.dji.dronecontrol.core.log.AppLogger

object DjiUsbAccessoryHelper {

    fun describeAccessory(accessory: UsbAccessory): String {
        return "manufacturer=${accessory.manufacturer} model=${accessory.model} version=${accessory.version}"
    }

    fun scanAccessories(context: Context): Pair<Boolean, String> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            return false to "UsbManager unavailable"
        }
        val list = usbManager.accessoryList
        if (list == null || list.isEmpty()) {
            return false to "No USB accessory (plug phone into RC-N2 USB port)"
        }
        val primary = list.first()
        val summary = list.joinToString(" | ") { describeAccessory(it) }
        AppLogger.i("UsbAccessory", "accessoryList size=${list.size}: $summary")
        return true to describeAccessory(primary)
    }

    fun registerDetachReceiver(context: Context, onDetach: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                    val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    }
                    AppLogger.i(
                        "UsbAccessory",
                        "DETACHED ${accessory?.let { describeAccessory(it) } ?: "unknown"}"
                    )
                    onDetach()
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return receiver
    }
}
