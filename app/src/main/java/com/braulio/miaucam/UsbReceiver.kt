package com.braulio.miaucam

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice

class UsbReceiver(private val deviceListener: (UsbDevice?) -> Unit) : android.content.BroadcastReceiver() {

    var filter: android.content.IntentFilter = android.content.IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action: String? = intent.action
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            deviceListener(device)
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            deviceListener(null) // Device detached, send null to listener
        }
    }
}