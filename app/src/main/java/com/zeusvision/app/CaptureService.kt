
package com.zeusvision.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "zeus_capture"
        private const val NOTIFICATION_ID = 303
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var windowManager: WindowManager? = null
    private var overlay: android.view.View? = null

    private var frameCount = 0L
    private var frameStatus: TextView? = null
    private var resultStatus: TextView? = null
    private var leaderStatus: TextView? = null

    private var leader = "player"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0

        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }

        if (data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                },
                mainHandler
            )

            startScreenCapture()
            showOverlay()

        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startScreenCapture() {

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener({ reader ->

            val image = reader.acquireLatestImage()

            if (image != null) {
                frameCount++
                image.close()

                mainHandler.post {
                    frameStatus?.text =
                        "CAPTURA ACTIVA · $frameCount fotogramas"
                }
            }

        }, mainHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "ZEUS-VISION-CAPTURE",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    private fun showOverlay() {

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xF0182938.toInt())
        }

        val title = TextView(this).apply {
            text = "ZEUS VISION · BOT DE PRUEBA"
            textSize = 14f
            setTextColor(0xFF35E59A.toInt())
            setPadding(0, 0, 0, dp(5))
        }

        frameStatus = TextView(this).apply {
            text = "Esperando fotogramas..."
            textSize = 11f
            setTextColor(Color.WHITE)
        }

        leaderStatus = TextView(this).apply {
            text = "Salida: tú"
            textSize = 12f
            setTextColor(0xFFFFD166.toInt())
            setPadding(0, dp(5), 0, dp(3))
        }

        val leaderButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val you = Button(this).apply {
            text = "SALGO YO"
            textSize = 10f

            setOnClickListener {
                leader = "player"
                updateLeader()
            }
        }

        val rival = Button(this).apply {
            text = "SALE RIVAL"
            textSize = 10f

            setOnClickListener {
                leader = "rival"
                updateLeader()
            }
        }

        leaderButtons.addView(
            you,
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )

        leaderButtons.addView(
            rival,
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )

        val handInput = field(
            "TU MANO",
            "Ej.: 6-5, 2-2, 0-3"
        )

        val leftInput = field(
            "EXTREMO IZQUIERDO",
            "0 a 6 o - si está vacía"
        )

        val rightInput = field(
            "EXTREMO DERECHO",
            "0 a 6 o - si está vacía"
        )

        val suggest = Button(this).apply {
            text = "CALCULAR JUGADA"

            setOnClickListener {

                val result = DominoBot.suggest(
                    handInput.text.toString(),
                    leftInput.text.toString(),
                    rightInput.text.toString()
                )

                resultStatus?.text =
                    if (result.suggestion == null) {
                        result.message
                    } else {
                        val move = result.suggestion

                        "${result.message}\n" +
                        "JUGAR: ${move.oriented} · ${move.side}\n" +
                        "${move.reason}\n" +
                        "Salida seleccionada: " +
                        if (leader == "player") "TÚ" else "RIVAL"
                    }
            }
        }

        val pass = Button(this).apply {
            text = "PASAR"

            setOnClickListener {
                resultStatus?.text =
                    "PASAR: no se encontró ninguna ficha legal " +
                    "con los extremos introducidos."
            }
        }

        resultStatus = TextView(this).apply {
            text = "Introduce tu mano y los extremos de la mesa."
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
        }

        val close = Button(this).apply {
            text = "CERRAR ZEUS"

            setOnClickListener {
                stopSelf()
            }
        }

        panel.addView(title)
        panel.addView(frameStatus)
        panel.addView(leaderStatus)
        panel.addView(leaderButtons)
        panel.addView(handInput)
        panel.addView(leftInput)
        panel.addView(rightInput)
        panel.addView(suggest)
        panel.addView(pass)
        panel.addView(resultStatus)
        panel.addView(close)

        val scroll = ScrollView(this).apply {
            addView(panel)
            isFillViewport = true
        }

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(90)
        }

        try {
            windowManager?.addView(scroll, params)
            overlay = scroll
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun field(
        label: String,
        hint: String
    ): EditText {

        return EditText(this).apply {
            this.hint = hint
            textSize = 12f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFB7C7D3.toInt())
            setSingleLine(true)
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(0xFF304454.toInt())
            contentDescription = label
        }
    }

    private fun updateLeader() {
        leaderStatus?.text =
            "Salida: ${if (leader == "player") "tú" else "rival"}"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Captura de ZEUS",
                NotificationManager.IMPORTANCE_LOW
            )

            (getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ZEUS VISION activo")
                .setContentText("Captura de pantalla en ejecución")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(open)
                .setOngoing(true)
                .build()

        } else {

            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ZEUS VISION activo")
                .setContentText("Captura de pantalla en ejecución")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {

        try {
            overlay?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }

        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()

        super.onDestroy()
    }
}
