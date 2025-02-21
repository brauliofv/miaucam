package com.braulio.miaucam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var imageReader: ImageReader
    private var isEmitterMode by mutableStateOf(false)

    // USB variables
    private lateinit var usbManager: UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbInterface: UsbInterface? = null
    private var usbEndpointIn: UsbEndpoint? = null
    private var usbEndpointOut: UsbEndpoint? = null
    private var usbDeviceConnection: UsbDeviceConnection? = null
    private val usbReceiver: UsbReceiver by lazy { // Usar lazy initialization
        UsbReceiver { device ->
            if (device != null) {
                (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                    Toast.makeText(
                        this@MainActivity, // Contexto más explícito
                        "Dispositivo USB detectado: ${device?.productName}",
                        Toast.LENGTH_LONG
                    ).show()
                    usbDevice = device
                    setupUsbCommunication()
                }
            } else {
                (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                    Toast.makeText(this@MainActivity, "Dispositivo USB desconectado", Toast.LENGTH_LONG).show()
                    usbDevice = null
                    closeUsbConnection()
                }
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val stopUsbReceiver = AtomicBoolean(false)
    private var receivedBitmap by mutableStateOf<Bitmap?>(null)
    private var isCapturingScreen by mutableStateOf(false) // Variable para rastrear si la captura está activa


    // Activity Result API para captura de pantalla
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val mediaProjectionData: Intent? = result.data
                if (mediaProjectionData != null) {
                    ScreenCaptureService.startService(this@MainActivity, mediaProjectionData) // Contexto más explícito
                    isCapturingScreen = true // Marcar la captura como activa
                    ScreenCaptureService().setFrameListener { bitmap -> // Listener para recibir fotogramas del servicio
                        bitmap?.let {
                            sendFrameOverUsb(it) // Enviar Bitmap USB
                        }
                    }
                } else {
                    isCapturingScreen = false
                    Toast.makeText(this@MainActivity, "Error al obtener datos de MediaProjection", Toast.LENGTH_SHORT)
                        .show()
                }
            } else {
                isCapturingScreen = false
                Toast.makeText(this@MainActivity, "Captura de pantalla cancelada", Toast.LENGTH_SHORT).show()
            }
        }


    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        checkAndRequestPermissions()
        // Registrar el receiver USB - Añadido flag RECEIVER_EXPORTED para Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU is API 33 (Android 13)
            registerReceiver(usbReceiver, usbReceiver.filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, usbReceiver.filter)
        }


        setContent {
            MiauCamApp(
                isEmitterMode = isEmitterMode,
                onToggleMode = { isEmitterMode = !isEmitterMode },
                onStartScreenCapture = ::startScreenCapture,
                onStopScreenCapture = ::stopScreenCapture, // Añadido onStopScreenCapture
                receivedBitmap = receivedBitmap,
                isCapturingScreen = isCapturingScreen // Añadido isCapturingScreen
            )
        }

        if (!isEmitterMode) {
            startUsbReceiver() // Iniciar la recepción USB en modo receptor
        }
    }


    override fun onResume() {
        super.onResume()
        checkInitialUsbDevice() // Verificar si un dispositivo USB ya está conectado al iniciar la app
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver) // Desregistrar el receiver USB
        stopUsbReceiver.set(true) // Detener el hilo receptor USB
        closeUsbConnection() // Cerrar conexión USB al destruir la actividad
    }


    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        }
    }


    private fun startScreenCapture() {
        if (isEmitterMode) {
            if (!isCapturingScreen) { // Verificar si la captura NO está ya activa
                val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                screenCaptureLauncher.launch(captureIntent)
            } else {
                Toast.makeText(this, "La captura de pantalla ya está activa", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "La captura de pantalla solo se inicia en modo Emisor", Toast.LENGTH_SHORT)
                .show()
        }
    }


    private fun stopScreenCapture() {
        if (isEmitterMode && isCapturingScreen) {
            ScreenCaptureService.stopService(this@MainActivity) // Contexto más explícito
            isCapturingScreen = false
            Toast.makeText(this, "Captura de pantalla detenida", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "No se puede detener la captura: No está en modo Emisor o la captura no está activa",
                Toast.LENGTH_SHORT
            ).show()
        }
    }



    private fun checkInitialUsbDevice() {
        val deviceList: HashMap<String, UsbDevice> = usbManager.deviceList
        deviceList.values.firstOrNull()?.let { device ->
            Toast.makeText(
                this,
                "Dispositivo USB detectado al inicio: ${device.productName}",
                Toast.LENGTH_LONG
            ).show()
            usbDevice = device
            setupUsbCommunication()
        }
    }


    private fun setupUsbCommunication() {
        usbDevice?.let { device ->
            usbInterface = findUsbInterface(device)
            usbInterface?.let { interfaceVal ->
                usbEndpointIn = findUsbEndpoint(interfaceVal, UsbConstants.USB_DIR_IN)
                usbEndpointOut = findUsbEndpoint(interfaceVal, UsbConstants.USB_DIR_OUT)
                if (usbEndpointIn != null && usbEndpointOut != null) {
                    requestUsbPermission(device)
                } else {
                    (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                        Toast.makeText(this@MainActivity, "Endpoints USB no encontrados", Toast.LENGTH_LONG).show()
                    }
                }
            } ?: run {
                (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                    Toast.makeText(this@MainActivity, "Interfaz USB no encontrada", Toast.LENGTH_LONG).show()
                }
            }
        } ?: run {
            (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                Toast.makeText(this@MainActivity, "Dispositivo USB no disponible", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun findUsbInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            return usbInterface // Return first interface for simplicity
        }
        return null
    }

    private fun findUsbEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint? {
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == direction) {
                return endpoint
            }
        }
        return null
    }


    private fun requestUsbPermission(device: UsbDevice) {
        UsbPermissionHelper.requestUsbPermission(
            this@MainActivity, // Contexto más explícito
            usbManager,
            device,
            object : UsbDeviceConnectionCallback {
                override fun onUsbDeviceConnectionEstablished(connection: UsbDeviceConnection) {
                    usbDeviceConnection = connection
                    (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                        Toast.makeText(
                            this@MainActivity,
                            "Conexión USB establecida",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (!isEmitterMode) {
                        startUsbReceiver() // Iniciar la recepción solo en modo receptor después de obtener permiso y conexión
                    }
                }

                override fun onUsbDevicePermissionDenied() {
                    (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                        Toast.makeText(this@MainActivity, "Permiso USB denegado", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            })
    }


    private fun closeUsbConnection() {
        usbDeviceConnection?.let {
            try {
                it.close()
                (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                    Toast.makeText(this@MainActivity, "Conexión USB cerrada", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                    Toast.makeText(this@MainActivity, "Error al cerrar conexión USB", Toast.LENGTH_SHORT).show()
                }
            } finally {
                usbDeviceConnection = null // Ensure usbDeviceConnection is null even if close fails
            }
        }
    }


    private fun sendFrameOverUsb(bitmap: Bitmap) { // Modificado para aceptar Bitmap no-nulo
        if (isEmitterMode && usbDeviceConnection != null && usbEndpointOut != null) {
            executor.execute {
                try {
                    val byteArrayOutputStream = java.io.ByteArrayOutputStream()
                    bitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        byteArrayOutputStream
                    ) // Comprimir a PNG
                    val byteArray = byteArrayOutputStream.toByteArray()
                    val bytesSent = usbDeviceConnection!!.bulkTransfer(
                        usbEndpointOut,
                        byteArray,
                        byteArray.size,
                        0
                    )
                    if (bytesSent == -1) {
                        (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                            Toast.makeText(this@MainActivity, "Error al enviar datos USB", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                        Toast.makeText(
                            this@MainActivity,
                            "Error al procesar y enviar fotograma",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }


    private fun startUsbReceiver() {
        if (!isEmitterMode && usbDeviceConnection != null && usbEndpointIn != null) {
            stopUsbReceiver.set(false)
            executor.execute {
                val buffer = ByteArray(16384) // Tamaño del buffer de recepción (ajustar según necesidad)
                while (!stopUsbReceiver.get()) {
                    try {
                        val bytesReceived =
                            usbDeviceConnection?.bulkTransfer(usbEndpointIn, buffer, buffer.size, 0)
                        if (bytesReceived != null && bytesReceived > 0) {
                            val bitmap =
                                BitmapFactory.decodeByteArray(buffer, 0, bytesReceived)
                            bitmap?.let {
                                (this@MainActivity).runOnUiThread { receivedBitmap = it } // Llamada a runOnUiThread más explícita
                            } ?: run {
                                (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Error al decodificar Bitmap desde USB",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } else if (bytesReceived == -1) {
                            (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                                Toast.makeText(this@MainActivity, "Error en recepción USB", Toast.LENGTH_SHORT)
                                    .show()
                            }
                            break // Salir del bucle en caso de error de recepción
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                            Toast.makeText(
                                this@MainActivity,
                                "Error en el hilo receptor USB: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        break // Salir del bucle en caso de excepción
                    }
                }
                (this@MainActivity).runOnUiThread { // Llamada a runOnUiThread más explícita
                    Toast.makeText(this@MainActivity, "Receptor USB detenido", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}


@Composable
fun MiauCamApp(
    isEmitterMode: Boolean,
    onToggleMode: () -> Unit,
    onStartScreenCapture: () -> Unit,
    onStopScreenCapture: () -> Unit, // Añadido onStopScreenCapture
    receivedBitmap: Bitmap?,
    isCapturingScreen: Boolean // Añadido isCapturingScreen
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Bienvenido a MIAUCAM (USB)", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onToggleMode) {
            Text(if (isEmitterMode) "Cambiar a Receptor" else "Cambiar a Emisor")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = if (isEmitterMode) "Modo: Emisor (USB)" else "Modo: Receptor (USB)")
        Spacer(modifier = Modifier.height(20.dp))
        if (isEmitterMode) {
            Button(onClick = { if (!isCapturingScreen) onStartScreenCapture() else onStopScreenCapture() }, enabled = !isCapturingScreen) { // Iniciar captura
                Text(text = if (!isCapturingScreen) "Iniciar Captura de Pantalla" else "Capturando...")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = onStopScreenCapture, enabled = isCapturingScreen) { // Detener captura
                Text("Detener Captura de Pantalla")
            }
        } else {
            if (receivedBitmap != null) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                    },
                    update = { imageView ->
                        imageView.setImageBitmap(receivedBitmap)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                )
            } else {
                Text("Esperando fotograma USB...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    MiauCamApp(
        isEmitterMode = false,
        onToggleMode = {},
        onStartScreenCapture = {},
        onStopScreenCapture = {},
        receivedBitmap = null,
        isCapturingScreen = false
    )
}

// Extension function para convertir Bitmap a android.media.Image (Necesitarás implementarla o encontrar una alternativa adecuada)
fun Bitmap.toImage(): Image { //  Corregido para retornar Image no-nulo
    val image: Image? = null // Inicializar a null temporalmente
    // Implementa la conversión de Bitmap a android.media.Image (ImageFormat.YUV_420_888 u otro formato adecuado)
    // ... (Esta conversión puede ser compleja y dependerá del formato que quieras usar para la transmisión USB) ...
    // ... (Para simplificar inicialmente, podrías intentar enviar el Bitmap directamente por USB como bytes sin convertirlo a Image, si es factible) ...

    TODO("Implementar la conversión de Bitmap a android.media.Image si es necesario. De lo contrario, adaptar sendFrameOverUsb para trabajar con Bitmap directamente.")
    return image!! //  Retornar image no-nulo (temporalmente, hasta implementar la conversion)
}