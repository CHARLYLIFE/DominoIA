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
    private val frameGeneration = AtomicLong(0L)
    private val temporalTracker = DominoTemporalTracker(requiredStableFrames = 2)
    // Serializa reset del selector con la actualización temporal de capturas antiguas.
    private val temporalLock = Any()

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var params: WindowManager.LayoutParams? = null
    // Rectangulo exacto del panel dentro de la captura; evita que ZEUS se lea a si mismo.
    @Volatile private var overlayExclusion: DominoVision.ExclusionRect? = null
    private var lastAnalysisAt = 0L
    private var consecutiveCaptureErrors = 0
    private var neuralModelLoadAttempted = false
    private var passTextRecognizer: DominoPassTextRecognizer? = null
    @Volatile private var leader = "TÚ"
    @Volatile private var manualOpeningArmed = false

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

        // Evita crear una segunda proyección, ImageReader y ventana flotante si
        // Android vuelve a entregar onStartCommand mientras el servicio ya funciona.
        if (projection != null || display != null || reader != null) return START_NOT_STICKY
        DominoOpponentProfile.reset()
        DominoExpertHeuristics.setMatchScore(null, null)
        manualOpeningArmed = false

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
                if (availableImage != null && !busy.get() &&
                    now - lastAnalysisAt >= CAPTURE_INTERVAL_MS && busy.compareAndSet(false, true)) {
                    // Solo cuenta una captura cuando realmente empieza a procesarse.
                    // Las capturas que llegan durante el cálculo se descartan sin cancelar
                    // la búsqueda actual ni borrar la sugerencia visible.
                    lastAnalysisAt = now
                    image = null // la propiedad pasa al hilo de análisis
                    val leaderSnapshot = leader
                    val manualOpeningSnapshot = manualOpeningArmed
                    val overlaySnapshot = overlayExclusion
                    val generation = frameGeneration.incrementAndGet()
                    try {
                        analysisExecutor.execute {
                            processCapture(availableImage, leaderSnapshot, manualOpeningSnapshot,
                                overlaySnapshot, now, generation)
                        }
                    } catch (e: Exception) {
                        try { availableImage.close() } catch (_: Exception) {}
                        busy.set(false)
                    }
                }
            } catch (_: Exception) {
                // No tocar busy aquí: el servicio puede estar procesando otra imagen.
                // La ruta que adquiere el bloqueo es la responsable de liberarlo.
            } finally {
                image?.close()
            }
        }, handler)

        display = projection?.createVirtualDisplay(
            "ZEUS-CAPTURE", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler
        )
    }

    private fun processCapture(
        image: Image,
        leaderSnapshot: String,
        manualOpeningSnapshot: Boolean,
        overlaySnapshot: DominoVision.ExclusionRect?,
        capturedAt: Long,
        generation: Long
    ) {
        var bitmap: Bitmap? = null
        var analysis: DominoVision.Analysis? = null
        var update: DominoTemporalTracker.Update? = null
        var aiResult: DominoBot.Result? = null
        try {
            bitmap = imageToBitmap(image)
            if (bitmap != null) {
                analysis = DominoVision.analyze(bitmap, overlaySnapshot)
                val recognizedText = passTextRecognizer?.recognize(bitmap).orEmpty()
                update = synchronized(temporalLock) {
                    if (frameGeneration.get() == generation)
                        temporalTracker.update(analysis, leaderSnapshot, recognizedText, capturedAt)
                    else null
                }
                val state = update?.gameState
                // SALGO YO arma una ruta independiente. Solo mesa vacia confirmada
                // + siete fichas; no consulta ni el aro dorado ni el velo verde.
                val openingCheck = DominoOpeningGate.evaluate(manualOpeningSnapshot, state)
                val canAnalyze = if (manualOpeningSnapshot) {
                    openingCheck.allowed
                } else {
                    state != null &&
                        state.confidence == DominoGameState.Confidence.CONFIRMED &&
                        state.boardConfirmed && state.ownHand.size in 1..7 &&
                        state.currentTurn == DominoVision.ActivePlayer.USER && state.turnConfirmed
                }
                if (canAnalyze && frameGeneration.get() == generation) {
                    aiResult = try {
                        if (manualOpeningSnapshot) DominoBot.suggestOpening(state!!)
                        else DominoBot.suggest(state!!)
                    } catch (e: Exception) {
                        DominoBot.Result(null, "ERROR AL CALCULAR", e.javaClass.simpleName)
                    }
                } else if (manualOpeningSnapshot) {
                    aiResult = DominoBot.Result(null, openingCheck.message)
                }
            }
        } catch (_: Exception) {
            analysis = null
        } finally {
            try { bitmap?.recycle() } catch (_: Exception) {}
            try { image.close() } catch (_: Exception) {}
        }

        handler.post {
            val superseded = frameGeneration.get() != generation
            if (!superseded) {
                when {
                    analysis == null -> {
                        consecutiveCaptureErrors++
                        // No borrar una sugerencia por un solo fotograma defectuoso.
                        if (consecutiveCaptureErrors >= 3) {
                            suggestionView?.text = "CAPTURA NO DISPONIBLE · REINTENTANDO"
                        }
                    }
                    update?.gameState == null -> {
                        consecutiveCaptureErrors = 0
                        val currentHand = analysis!!.hand.joinToString(" · ") { "${it.a}-${it.b}" }
                        handView?.text = currentHand.ifBlank { "NO LEÍDA" }
                        val rawStatus = update?.status.orEmpty()
                        suggestionView?.text = when {
                            manualOpeningSnapshot && analysis!!.hand.isEmpty() -> "NO RECONOCE TU MANO · 0/7"
                            manualOpeningSnapshot && analysis!!.hand.size < 7 ->
                                "RECONOCIENDO MANO ${analysis!!.hand.size}/7"
                            manualOpeningSnapshot && analysis!!.hand.size > 7 -> "MANO INCONSISTENTE"
                            manualOpeningSnapshot && analysis!!.hand.map { minOf(it.a, it.b) to maxOf(it.a, it.b) }.distinct().size != analysis!!.hand.size ->
                                "MANO INCONSISTENTE"
                            manualOpeningSnapshot && (analysis!!.boardTiles.isNotEmpty() ||
                                analysis!!.leftEnd != null || analysis!!.rightEnd != null) -> "MESA NO VACÍA · NO SALIR"
                            manualOpeningSnapshot && analysis!!.handConfidence < 55 -> "LECTURA DE MANO INESTABLE"
                            manualOpeningSnapshot && !analysis!!.boardIsEmpty -> "CONFIRMANDO MESA VACÍA..."
                            analysis!!.hand.isEmpty() -> "NO RECONOCE TU MANO"
                            "CONTRADICTORIO" in rawStatus || "INCONSISTENTE" in rawStatus ->
                                "VERIFICANDO MESA..."
                            else -> "CONFIRMANDO LECTURA..."
                        }
                    }
                    else -> {
                        consecutiveCaptureErrors = 0
                        val confirmedState = update!!.gameState!!
                        if (!confirmedState.boardIsEmpty && confirmedState.boardTiles.isNotEmpty()) {
                            manualOpeningArmed = false
                        }
                        updatePanel(analysis!!, confirmedState, aiResult, manualOpeningSnapshot)
                    }
                }
            }
            busy.set(false)
            if (superseded) lastAnalysisAt = 0L
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
                    val currentOverlay: View? = this@CaptureService.overlay
                    val currentParams: WindowManager.LayoutParams? = params
                    if (currentOverlay != null && currentParams != null) {
                        wm?.updateViewLayout(currentOverlay, currentParams)
                        currentOverlay.post { refreshOverlayExclusion(currentOverlay) }
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
                manualOpeningArmed = true
                synchronized(temporalLock) {
                    frameGeneration.incrementAndGet() // invalida cualquier captura anterior al selector
                    temporalTracker.reset()
                }
                DominoBot.cancelPendingSearch()
                suggestionView?.text = "SALIDA: TÚ · LEYENDO 7 FICHAS..."
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
                manualOpeningArmed = false
                synchronized(temporalLock) {
                    frameGeneration.incrementAndGet()
                    temporalTracker.reset()
                }
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
                    try {
                        overlay?.let { current ->
                            wm?.updateViewLayout(current, p)
                            current.post { refreshOverlayExclusion(current) }
                        }
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        try {
            wm?.addView(root, params)
            overlay = root
            // Se actualiza despues del layout para obtener coordenadas reales en pantalla.
            root.post { refreshOverlayExclusion(root) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Guarda el rectangulo real del overlay en pixeles de pantalla/captura. */
    private fun refreshOverlayExclusion(view: View) {
        try {
            if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
                overlayExclusion = null
                return
            }
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val pad = dp(3)
            overlayExclusion = DominoVision.ExclusionRect(
                (location[0] - pad).coerceAtLeast(0),
                (location[1] - pad).coerceAtLeast(0),
                (location[0] + view.width + pad),
                (location[1] + view.height + pad)
            )
        } catch (_: Exception) {
            // Si Android no entrega coordenadas fiables, no se inventa una region.
            overlayExclusion = null
        }
    }

    private fun updatePanel(
        analysis: DominoVision.Analysis,
        state: DominoGameState,
        result: DominoBot.Result?,
        manualOpeningSnapshot: Boolean
    ) {
        val handText = if (analysis.hand.isEmpty()) "NO LEÍDA"
            else analysis.hand.joinToString(" · ") { "${it.a}-${it.b}" }
        handView?.text = handText
        val userOpening = state.boardIsEmpty && state.roundStarter == "TÚ" && state.ownHand.size == 7
        suggestionView?.text = when {
            manualOpeningSnapshot && result?.suggestion == null && result != null -> when {
                result.message.contains("MANO INCOMPLETA") -> result.message.take(34)
                result.message.contains("MANO INCONSISTENTE") -> "MANO INCONSISTENTE"
                result.message.contains("MESA") -> "MESA NO CONFIRMADA COMO VACÍA"
                result.message.contains("ESTADO") -> "ESTADO INICIAL NO CONFIRMADO"
                result.message.contains("REPARTO") -> "ERROR DE REPARTO"
                else -> result.message.substringBefore("·").trim().take(34)
            }
            state.confidence != DominoGameState.Confidence.CONFIRMED -> "LECTURA NO CONFIRMADA"
            state.boardIsEmpty && state.roundStarter == "RIVAL" -> "EL RIVAL ABRE · ESPERANDO"
            state.boardIsEmpty && state.roundStarter == "TÚ" && state.ownHand.size < 7 ->
                "RECONOCIENDO MANO ${state.ownHand.size}/7"
            !userOpening && state.currentTurn == DominoVision.ActivePlayer.OPPONENT ->
                "TURNO DEL RIVAL · ESPERANDO"
            !userOpening && !state.turnConfirmed -> "ESPERANDO CONFIRMACIÓN DEL TURNO"
            result?.suggestion != null -> {
                val s = result.suggestion
                "${s.tile.label} · ${s.side}"
            }
            result?.suggestion == null && result?.message?.contains("NO TIENES JUGADA LEGAL") == true -> "SIN JUGADA LEGAL · PASAR"
            result?.suggestion == null && result?.message?.contains("NO QUEDAN FICHAS") == true -> "MANO VACÍA"
            result?.suggestion == null && result?.message?.startsWith("MANO INCOMPLETA") == true -> result.message
            result?.suggestion == null && result?.message?.contains("MESA NO CONFIRMADA") == true -> "MESA NO CONFIRMADA COMO VACÍA"
            result?.suggestion == null && result?.message?.contains("REPARTO") == true -> "ERROR DE REPARTO"
            result?.suggestion == null && result?.message?.contains("TURNO") == true -> "TURNO NO CONFIRMADO"
            result?.suggestion == null && result != null -> "${result.message.substringBefore("·").trim().take(34)}"
            result == null -> "PREPARANDO SUGERENCIA..."
            else -> "NO SE PUDO CALCULAR · REVISANDO"
        }
        // El texto/mano puede cambiar la altura del panel; refrescar su rectangulo
        // despues del siguiente layout para que la mascara siga siendo exacta.
        overlay?.let { current -> current.post { refreshOverlayExclusion(current) } }
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
        overlayExclusion = null
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
