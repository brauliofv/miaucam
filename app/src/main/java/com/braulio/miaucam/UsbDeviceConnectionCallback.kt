package com.braulio.miaucam

import android.hardware.usb.UsbDeviceConnection

interface UsbDeviceConnectionCallback {
    fun onUsbDeviceConnectionEstablished(connection: UsbDeviceConnection)
    fun onUsbDevicePermissionDenied()
}