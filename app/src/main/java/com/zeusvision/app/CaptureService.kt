package com.zeusvision.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
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
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastAnalysisAt = 0L
    private var busy = false
    private var neuralModelLoadAttempted = false
    @Volatile private var leader = "TÚ"
    private var handView: TextView? = null
    private var suggestionView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification())

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }
        if (code == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            prepareNeuralModel()
            startCapture()
            showPanel()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
    }


    /** Carga el modelo local si existe. Si falta o es inválido, IS-MCTS sigue funcionando. */
    private fun prepareNeuralModel() {
        if (neuralModelLoadAttempted) return
        neuralModelLoadAttempted = true
        try {
            assets.open(DominoNeuralModel.ASSET_FILE).use { stream ->
                DominoBot.installNeuralModel(DominoNeuralModel.load(stream))
            }
        } catch (_: Exception) {
            DominoBot.installNeuralModel(null)
        }
    }

    private fun startCapture() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ r ->
            var image: Image? = null
            try {
                image = r.acquireLatestImage()
                val now = System.currentTimeMillis()
                if (image != null && !busy && now - lastAnalysisAt >= 650L) {
                    busy = true
                    lastAnalysisAt = now
                    val bitmap = imageToBitmap(image)
                    if (bitmap != null) {
                        Thread {
                            val result = try { DominoVision.analyze(bitmap) } catch (e: Exception) { null }
                            bitmap.recycle()
                            // La busqueda IS-MCTS es pesada: se ejecuta en este hilo de fondo,
                            // nunca en el hilo principal de Android.
                            val leaderSnapshot = leader
                            val boardUsable = result != null && (result.boardKnown || result.boardIsEmpty)
                            val aiResult = if (result != null && result.handConfidence >= 55 &&
                                result.turnConfidence >= 45 && result.activePlayer != DominoVision.ActivePlayer.UNKNOWN && boardUsable) {
                                try {
                                    DominoBot.suggest(
                                        result.hand, result.leftEnd, result.rightEnd,
                                        leaderSnapshot, result.boardTiles, result.activePlayer
                                    )
                                } catch (_: Exception) { null }
                            } else null
                            handler.post {
                                if (result != null) updatePanel(result, aiResult)
                                else suggestionView?.text = "CAPTURA NO DISPONIBLE"
                                busy = false
                            }
                        }.start()
                    } else busy = false
                }
            } catch (_: Exception) {
                busy = false
            } finally {
                image?.close()
            }
        }, handler)

        display = projection?.createVirtualDisplay(
            "ZEUS-CAPTURE", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler
        )
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun showPanel() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // Panel compacto: conserva mano, selector de salida y sugerencia automática.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(0xF0182938.toInt())
        }

        val header = TextView(this).apply {
            text = "☰ ZEUS · TURNO AUTOMÁTICO"
            textSize = 11f
            includeFontPadding = false
            setTextColor(0xFF35E59A.toInt())
            setPadding(dp(4), dp(5), dp(4), dp(5))
            setBackgroundColor(0xFF102332.toInt())
        }

        val handRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(5), dp(2), dp(3))
        }
        val handLabel = TextView(this).apply {
            text = "MANO:"
            textSize = 10f
            includeFontPadding = false
            setTextColor(0xFFFFD166.toInt())
        }
        handView = TextView(this).apply {
            text = "Leyendo..."
            textSize = 11f
            includeFontPadding = false
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(5), 0, 0, 0)
        }
        handRow.addView(handLabel)
        handRow.addView(handView)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val you = Button(this).apply {
            text = "SALGO YO"
            textSize = 9f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener {
                leader = "TÚ"
            }
        }
        val rival = Button(this).apply {
            text = "SALE RIVAL"
            textSize = 9f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener {
                leader = "RIVAL"
            }
        }
        row.addView(you, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
            marginEnd = dp(3)
        })
        row.addView(rival, LinearLayout.LayoutParams(0, dp(30), 1f))

        suggestionView = TextView(this).apply {
            text = "Esperando lectura..."
            textSize = 11f
            includeFontPadding = false
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(2), dp(5), dp(2), dp(2))
            maxLines = 3
        }

        root.addView(header)
        root.addView(handRow)
        root.addView(row)
        root.addView(suggestionView)

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            dp(175), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(5)
            y = dp(85)
        }

        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        header.setOnTouchListener { _, ev ->
            val p = params ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    downX = ev.rawX
                    downY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (startX - (ev.rawX - downX).toInt()).coerceAtLeast(0)
                    p.y = (startY + (ev.rawY - downY).toInt()).coerceAtLeast(0)
                    try { overlay?.let { wm?.updateViewLayout(it, p) } } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }

        try {
            wm?.addView(root, params)
            overlay = root
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updatePanel(a: DominoVision.Analysis, aiResult: DominoBot.Result?) {
        val handText = if (a.hand.isEmpty()) "NO LEÍDA" else a.hand.joinToString(" · ") { "${it.a}-${it.b}" }
        handView?.text = handText
        updateSuggestion(a, aiResult)
    }

    private fun updateSuggestion(a: DominoVision.Analysis, result: DominoBot.Result?) {
        suggestionView?.text = when {
            a.handConfidence < 55 -> "NO PUEDO LEER TU MANO"
            a.activePlayer == DominoVision.ActivePlayer.UNKNOWN -> "NO PUEDO IDENTIFICAR EL TURNO"
            a.activePlayer == DominoVision.ActivePlayer.OPPONENT -> "TURNO DEL RIVAL · ESPERANDO"
            !a.boardKnown && !a.boardIsEmpty -> "NO PUEDO CONFIRMAR LA MESA"
            result == null -> "NO SE PUDO CALCULAR"
            else -> {
                val s = result.suggestion
                if (s == null) result.message
                else "${s.tile.label} · ${s.side}\n${s.reason}"
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(CHANNEL_ID, "Captura de ZEUS", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
    }

    private fun notification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ZEUS VISION activo")
                .setContentText("Reconocimiento visual del dominó")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ZEUS VISION activo")
                .setContentText("Reconocimiento visual del dominó")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true).build()
        }
    }

    override fun onDestroy() {
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
