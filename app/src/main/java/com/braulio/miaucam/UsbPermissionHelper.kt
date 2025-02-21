package com.braulio.miaucam

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat

object UsbPermissionHelper {
    fun requestUsbPermission(context: Context, usbManager: UsbManager, device: UsbDevice, callback: UsbDeviceConnectionCallback) {
        val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intentFilter = android.content.IntentFilter(ACTION_USB_PERMISSION)
        val usbReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                context.unregisterReceiver(this)
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val deviceFromIntent: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            deviceFromIntent?.apply {
                                // Permission granted, establish connection
                                val connection = usbManager.openDevice(deviceFromIntent)
                                if (connection != null) {
                                    if (connection.claimInterface(device.getInterface(0), true)) { // Suponiendo la interfaz 0
                                        callback.onUsbDeviceConnectionEstablished(connection)
                                    } else {
                                        // Añadidas llaves aquí para el bloque else
                                        callback.onUsbDevicePermissionDenied() // Fallo al reclamar la interfaz
                                        connection.close() // Asegúrate de que esta línea esté DENTRO de las llaves del else
                                    }
                                } else {
                                    // Añadidas llaves aquí para el bloque else
                                    callback.onUsbDevicePermissionDenied() // Fallo al abrir la conexión
                                    // No es necesario connection.close() aquí porque connection nunca se abrió
                                }
                            }
                        } else {
                            callback.onUsbDevicePermissionDenied() // Permission denied by user
                        }
                    }
                }
            }
        }

        // Modified line to include RECEIVER_EXPORTED flag for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU is API 33 (Android 13)
            context.registerReceiver(usbReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        }
        else {
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        usbManager.requestPermission(device, permissionIntent)
    }

    private const val ACTION_USB_PERMISSION = "com.braulio.miaucam.USB_PERMISSION"
}