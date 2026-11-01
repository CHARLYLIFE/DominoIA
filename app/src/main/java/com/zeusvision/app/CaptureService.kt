
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
import android.view.MotionEvent
import android.view.View
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

    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var frameCount = 0L
    private var frameStatus: TextView? = null
    private var resultStatus: TextView? = null
    private var leaderStatus: TextView? = null
    private var expandedContent: LinearLayout? = null
    private var expandButton: Button? = null

    private var leader = "player"
    private var expanded = false

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
                handler
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

        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()

            if (image != null) {
                frameCount++
                image.close()

                handler.post {
                    frameStatus?.text = "CAPTURA ACTIVA · $frameCount"
                }
            }
        }, handler)

        virtualDisplay = projection?.createVirtualDisplay(
            "ZEUS-VISION-CAPTURE",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    }

    private fun showOverlay() {

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xF0182938.toInt())
        }

        // Barra superior: también sirve para mover la ventana.
        val header = TextView(this).apply {
            text = "☰  ZEUS VISION       ⋮"
            textSize = 15f
            setTextColor(0xFF35E59A.toInt())
            setPadding(dp(5), dp(9), dp(5), dp(9))
            setBackgroundColor(0xFF102332.toInt())
        }

        frameStatus = TextView(this).apply {
            text = "Esperando captura..."
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(3), dp(5), dp(3), dp(5))
        }

        expandButton = Button(this).apply {
            text = "ABRIR CONTROLES"
            textSize = 11f
            setOnClickListener {
                setExpanded(!expanded)
            }
        }

        expandedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val leaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        leaderStatus = TextView(this).apply {
            text = "Salida: tú"
            textSize = 12f
            setTextColor(0xFFFFD166.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }

        val you = Button(this).apply {
            text = "SALGO YO"
            textSize = 9f
            setOnClickListener {
                leader = "player"
                updateLeader()
            }
        }

        val rival = Button(this).apply {
            text = "SALE RIVAL"
            textSize = 9f
            setOnClickListener {
                leader = "rival"
                updateLeader()
            }
        }

        leaderRow.addView(
            you,
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )

        leaderRow.addView(
            rival,
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )

        val hand = createField("Fichas: 6-5, 2-2, 0-3")
        val left = createField("Extremo izquierdo: 0-6 o -")
        val right = createField("Extremo derecho: 0-6 o -")

        val calculate = Button(this).apply {
            text = "CALCULAR JUGADA"
            setOnClickListener {
                val result = DominoBot.suggest(
                    hand.text.toString(),
                    left.text.toString(),
                    right.text.toString()
                )

                resultStatus?.text =
                    if (result.suggestion == null) {
                        result.message
                    } else {
                        val move = result.suggestion
                        "${result.message}\n" +
                            "JUGAR: ${move.oriented} · ${move.side}\n" +
                            move.reason
                    }
            }
        }

        val pass = Button(this).apply {
            text = "PASAR"
            setOnClickListener {
                resultStatus?.text =
                    "Comprueba que no tengas ninguna jugada legal."
            }
        }

        resultStatus = TextView(this).apply {
            text = "Introduce tus fichas para probar el bot."
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(3), dp(8), dp(3), dp(8))
        }

        val close = Button(this).apply {
            text = "CERRAR ZEUS"
            setOnClickListener {
                stopSelf()
            }
        }

        expandedContent?.apply {
            addView(leaderStatus)
            addView(leaderRow)
            addView(hand)
            addView(left)
            addView(right)
            addView(calculate)
            addView(pass)
            addView(resultStatus)
            addView(close)
        }

        root.addView(header)
        root.addView(frameStatus)
        root.addView(expandButton)
        root.addView(expandedContent)

        val scroll = ScrollView(this).apply {
            addView(root)
            isFillViewport = true
        }

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        overlayParams = WindowManager.LayoutParams(
            dp(255),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(100)
        }

        // Arrastrar desde la barra superior.
        header.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = (
                        params.x - event.rawX.toInt() +
                            (header.width / 2)
                    ).coerceAtLeast(0)

                    params.y = (
                        event.rawY.toInt() - dp(25)
                    ).coerceAtLeast(0)

                    try {
                        windowManager?.updateViewLayout(scroll, params)
                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }

        try {
            windowManager?.addView(scroll, overlayParams)
            overlay = scroll
            setExpanded(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        expandedContent?.visibility =
            if (value) View.VISIBLE else View.GONE

        expandButton?.text =
            if (value) "MINIMIZAR" else "ABRIR CONTROLES"
    }

    private fun createField(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 11f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFB7C7D3.toInt())
            setSingleLine(true)
            setPadding(dp(7), dp(4), dp(7), dp(4))
            setBackgroundColor(0xFF304454.toInt())
        }
    }

    private fun updateLeader() {
        leaderStatus?.text =
            "Salida: ${if (leader == "player") "tú" else "rival"}"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
