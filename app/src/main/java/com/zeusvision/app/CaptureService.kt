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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "zeus_capture"
        private const val NOTIFICATION_ID = 303
        private const val CAPTURE_INTERVAL_MS = 650L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ZEUS-analysis").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val pendingFrame = AtomicBoolean(false)
    private val frameGeneration = AtomicLong(0L)
    private val temporalTracker = DominoTemporalTracker(requiredStableFrames = 2)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastAnalysisAt = 0L
    private var neuralModelLoadAttempted = false
    private var passTextRecognizer: DominoPassTextRecognizer? = null
    @Volatile private var leader = "TÚ"

    private var handView: TextView? = null
    private var suggestionView: TextView? = null
    private var handRowView: View? = null
    private var selectorRowView: View? = null
    private var collapsed = false

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
            passTextRecognizer = try { DominoPassTextRecognizer() } catch (_: Exception) { null }
            startCapture()
            showPanel()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** El modelo actual se conserva; si falta, IS-MCTS y la inferencia siguen funcionando. */
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
        reader?.setOnImageAvailableListener({ source ->
            var image: Image? = null
            try {
                image = source.acquireLatestImage()
                val now = System.currentTimeMillis()
                val availableImage = image
                if (availableImage != null && now - lastAnalysisAt >= CAPTURE_INTERVAL_MS) {
                    lastAnalysisAt = now
                    if (busy.compareAndSet(false, true)) {
                        image = null // la propiedad pasa al hilo de analisis
                        val leaderSnapshot = leader
                        val generation = frameGeneration.incrementAndGet()
                        suggestionView?.text = "LEYENDO PARTIDA..."
                        analysisExecutor.execute {
                            processCapture(availableImage, leaderSnapshot, now, generation)
                        }
                    } else {
                        // Una captura mas nueva invalida cualquier busqueda estrategica anterior.
                        frameGeneration.incrementAndGet()
                        pendingFrame.set(true)
                        DominoBot.cancelPendingSearch()
                    }
                }
            } catch (_: Exception) {
                busy.set(false)
            } finally {
                image?.close()
            }
        }, handler)

        display = projection?.createVirtualDisplay(
            "ZEUS-CAPTURE", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler
        )
    }

    private fun processCapture(image: Image, leaderSnapshot: String, capturedAt: Long, generation: Long) {
        var bitmap: Bitmap? = null
        var analysis: DominoVision.Analysis? = null
        var update: DominoTemporalTracker.Update? = null
        var aiResult: DominoBot.Result? = null
        try {
            bitmap = imageToBitmap(image)
            if (bitmap != null) {
                analysis = DominoVision.analyze(bitmap)
                val recognizedText = passTextRecognizer?.recognize(bitmap).orEmpty()
                update = temporalTracker.update(analysis, leaderSnapshot, recognizedText, capturedAt)
                val state = update.gameState
                val userOpening = state != null && state.boardIsEmpty &&
                    state.roundStarter == "TÚ" && state.ownHand.size == 7 &&
                    !(state.currentTurn == DominoVision.ActivePlayer.OPPONENT && state.turnConfidence >= 65)
                val canAnalyze = state != null &&
                    state.confidence == DominoGameState.Confidence.CONFIRMED &&
                    state.ownHand.size in 1..7 && state.boardConfirmed &&
                    (userOpening || (state.currentTurn == DominoVision.ActivePlayer.USER && state.turnConfirmed))
                if (canAnalyze && !pendingFrame.get() && frameGeneration.get() == generation) {
                    aiResult = try { DominoBot.suggest(state!!) } catch (_: Exception) { null }
                }
            }
        } catch (_: Exception) {
            analysis = null
        } finally {
            try { bitmap?.recycle() } catch (_: Exception) {}
            try { image.close() } catch (_: Exception) {}
        }

        val supersededBeforePost = pendingFrame.getAndSet(false) || frameGeneration.get() != generation
        handler.post {
            val hadNewerFrame = supersededBeforePost || frameGeneration.get() != generation ||
                pendingFrame.getAndSet(false)
            when {
                analysis == null -> suggestionView?.text = "CAPTURA NO DISPONIBLE"
                hadNewerFrame -> {
                    val currentHand = analysis!!.hand.joinToString(" · ") { "${it.a}-${it.b}" }
                    handView?.text = currentHand.ifBlank { "NO LEÍDA" }
                    suggestionView?.text = "ACTUALIZANDO PARTIDA..."
                }
                update?.gameState == null -> {
                    val currentHand = analysis!!.hand.joinToString(" · ") { "${it.a}-${it.b}" }
                    handView?.text = currentHand.ifBlank { "NO LEÍDA" }
                    suggestionView?.text = update?.status ?: "CONFIRMANDO LECTURA..."
                }
                else -> updatePanel(analysis!!, update!!.gameState, aiResult, update!!.status)
            }
            busy.set(false)
            if (hadNewerFrame) lastAnalysisAt = 0L
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0) return null
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        return try {
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } catch (e: Exception) {
            padded.recycle()
            null
        }
    }

    private fun showPanel() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(0xF0182938.toInt())
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF102332.toInt())
        }
        val header = TextView(this).apply {
            text = "☰ ZEUS"
            textSize = 10f
            includeFontPadding = false
            setTextColor(0xFF35E59A.toInt())
            setPadding(dp(4), dp(6), dp(2), dp(6))
        }
        val minimize = Button(this).apply {
            text = "−"
            textSize = 12f
            isAllCaps = false
            minHeight = 0; minimumHeight = 0
            minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                collapsed = !collapsed
                handRowView?.visibility = if (collapsed) View.GONE else View.VISIBLE
                selectorRowView?.visibility = if (collapsed) View.GONE else View.VISIBLE
                suggestionView?.visibility = if (collapsed) View.GONE else View.VISIBLE
                header.text = if (collapsed) "☰ ZEUS · COMPACTO" else "☰ ZEUS"
                text = if (collapsed) "+" else "−"
                try {
                    val currentOverlay: View? = overlay
                    val currentParams: WindowManager.LayoutParams? = params
                    if (currentOverlay != null && currentParams != null) {
                        wm?.updateViewLayout(currentOverlay, currentParams)
                    }
                } catch (_: Exception) {}
            }
        }
        headerRow.addView(header, LinearLayout.LayoutParams(0, dp(28), 1f))
        headerRow.addView(minimize, LinearLayout.LayoutParams(dp(25), dp(26)))

        val handRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(2))
        }
        val handLabel = TextView(this).apply {
            text = "MI MANO"
            textSize = 8f
            includeFontPadding = false
            setTextColor(0xFFFFD166.toInt())
        }
        handView = TextView(this).apply {
            text = "Leyendo..."
            textSize = 9f
            includeFontPadding = false
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 3
        }
        handRow.addView(handLabel)
        handRow.addView(handView)
        handRowView = handRow

        val selectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val you = Button(this).apply {
            text = "SALGO YO"
            textSize = 8f
            isAllCaps = false
            minHeight = 0; minimumHeight = 0
            minWidth = 0; minimumWidth = 0
            setPadding(dp(1), 0, dp(1), 0)
            setOnClickListener {
                leader = "TÚ"
                temporalTracker.reset()
                DominoBot.cancelPendingSearch()
                suggestionView?.text = "SALIDA: TÚ · CONFIRMANDO..."
                lastAnalysisAt = 0L
            }
        }
        val rival = Button(this).apply {
            text = "SALE RIVAL"
            textSize = 8f
            isAllCaps = false
            minHeight = 0; minimumHeight = 0
            minWidth = 0; minimumWidth = 0
            setPadding(dp(1), 0, dp(1), 0)
            setOnClickListener {
                leader = "RIVAL"
                temporalTracker.reset()
                DominoBot.cancelPendingSearch()
                suggestionView?.text = "SALIDA: RIVAL · ESPERANDO"
                lastAnalysisAt = 0L
            }
        }
        selectorRow.addView(you, LinearLayout.LayoutParams(0, dp(27), 1f).apply { marginEnd = dp(2) })
        selectorRow.addView(rival, LinearLayout.LayoutParams(0, dp(27), 1f))
        selectorRowView = selectorRow

        suggestionView = TextView(this).apply {
            text = "Esperando lectura..."
            textSize = 9f
            includeFontPadding = false
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(2), dp(3), dp(2), dp(2))
            maxLines = 4
        }

        root.addView(headerRow)
        root.addView(handRow)
        root.addView(selectorRow)
        root.addView(suggestionView)

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            dp(140), WindowManager.LayoutParams.WRAP_CONTENT, type,
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
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

    private fun updatePanel(
        analysis: DominoVision.Analysis,
        state: DominoGameState?,
        result: DominoBot.Result?,
        temporalStatus: String
    ) {
        val handText = if (analysis.hand.isEmpty()) "NO LEÍDA"
            else analysis.hand.joinToString(" · ") { "${it.a}-${it.b}" }
        handView?.text = handText
        suggestionView?.text = when {
            state == null -> temporalStatus
            state.confidence != DominoGameState.Confidence.CONFIRMED -> "LECTURA NO CONFIRMADA"
            state.boardIsEmpty && state.roundStarter == "RIVAL" -> "SALE EL RIVAL · ESPERANDO"
            state.currentTurn == DominoVision.ActivePlayer.OPPONENT -> "TURNO DEL RIVAL · ESPERANDO"
            !state.turnConfirmed &&
                !(state.boardIsEmpty && state.roundStarter == "TÚ" && state.ownHand.size == 7 &&
                    !(state.currentTurn == DominoVision.ActivePlayer.OPPONENT && state.turnConfidence >= 65)) ->
                "TURNO NO CONFIRMADO"
            result == null -> temporalStatus
            result.suggestion == null -> result.message
            else -> {
                val s = result.suggestion
                "${s.tile.label} · ${s.side}\n${s.reason}"
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
        try { analysisExecutor.shutdownNow() } catch (_: Exception) {}
        try { passTextRecognizer?.close() } catch (_: Exception) {}
        temporalTracker.reset()
        display?.release()
        reader?.close()
        projection?.stop()
        DominoBot.cancelPendingSearch()
        super.onDestroy()
    }
}
