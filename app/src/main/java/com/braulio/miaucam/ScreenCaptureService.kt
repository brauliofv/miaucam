package com.braulio.miaucam

import android.R
import android.app.Activity.RESULT_OK // Añadida importación para RESULT_OK
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo



class ScreenCaptureService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var imageReader: ImageReader
    private var frameListener: ((Bitmap?) -> Unit)? = null // Listener para enviar fotogramas a MainActivity
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START_CAPTURE = "com.braulio.miaucam.ACTION_START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.braulio.miaucam.ACTION_STOP_CAPTURE"
        const val MEDIA_PROJECTION_DATA = "MEDIA_PROJECTION_DATA"

        fun startService(context: Context, mediaProjectionData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(MEDIA_PROJECTION_DATA, mediaProjectionData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.stopService(intent)
        }
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null // Servicio no enlazado (no usamos binding)
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }


    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val mediaProjectionData = intent.getParcelableExtra<Intent>(MEDIA_PROJECTION_DATA)
                if (mediaProjectionData != null) {
                    startMediaProjection(mediaProjectionData)
                    startForegroundNotification() // Iniciar la notificación de Foreground Service
                    Log.d(TAG, "ScreenCaptureService started")
                } else {
                    Log.e(TAG, "MediaProjection data is null")
                    stopSelf()
                }
            }
            ACTION_STOP_CAPTURE -> {
                stopMediaProjection()
                stopForegroundService()
                Log.d(TAG, "ScreenCaptureService stopped")
                stopSelf()
            }
        }
        return START_STICKY // Reiniciar servicio si el sistema lo detiene inesperadamente
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun startMediaProjection(mediaProjectionData: Intent) {
        mediaProjection = mediaProjectionManager.getMediaProjection(RESULT_OK, mediaProjectionData)
        mediaProjection?.registerCallback(MediaProjectionCallback(), mainHandler) // Registrar callback para MediaProjection

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val screenDensity = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image: Image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener // Handle null image

            val bitmap = image.toBitmap()
            frameListener?.invoke(bitmap) // Enviar bitmap a MainActivity a través del listener
            image.close()

        }, mainHandler)

        Log.d(TAG, "VirtualDisplay and ImageReader created")
    }


    private fun stopMediaProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader.close()
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "MediaProjection stopped")
    }


    private fun startForegroundNotification() {
        val channelId = "miaucam_screen_capture_channel" // ID del canal de notificación (debe coincidir con el manifiesto si usas canales)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "MIAUCAM Screen Capture"
            val channelDescription = "Notification for screen capture foreground service"
            val channelImportance = NotificationManager.IMPORTANCE_LOW // Importancia baja para no ser intrusiva
            val channel = NotificationChannel(channelId, channelName, channelImportance).apply {
                description = channelDescription
                setShowBadge(false) // Opcional: Ocultar badge en el icono de la app
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC // Opcional: Mostrar en la pantalla de bloqueo
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }


        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_media_play) // Usando icono de sistema android.R.drawable.ic_media_play (temporalmente)
            .setContentTitle("MIAUCAM Screen Sharing")
            .setContentText("Screen sharing is active. Tap to stop.")
            .setPriority(NotificationCompat.PRIORITY_LOW) // Prioridad baja
            .setOngoing(true) // Notificación no cancelable por el usuario (hasta que se detenga el servicio)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_BEHAVIOR_DEFAULT) // TEMPORAL: Usar DEFAULT para prueba

        val notification = notificationBuilder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) // Para Android 10+
        } else {
            startForeground(NOTIFICATION_ID, notification) // Para versiones anteriores a Android 10
        }


        Log.d(TAG, "Foreground notification started")
    }


    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE) // Detener el Foreground Service y la notificación
        Log.d(TAG, "Foreground notification stopped")
    }


    fun setFrameListener(listener: (Bitmap?) -> Unit) {
        this.frameListener = listener
    }


    private fun Image.toBitmap(): Bitmap? { // Corregido: ImageReader.ImageListener -> Image.toBitmap() y movido fuera de ImageReader.OnImageAvailableListener
        val planes = planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }



    private inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            stopForegroundService()
            stopSelf()
            mediaProjection = null // Liberar MediaProjection
            Log.d(TAG, "MediaProjectionCallback: MediaProjection stopped")
        }
    }


    override fun onDestroy() {
        stopMediaProjection()
        stopForegroundService()
        frameListener = null // Limpiar listener
        Log.d(TAG, "ScreenCaptureService destroyed")
        super.onDestroy()
    }
}