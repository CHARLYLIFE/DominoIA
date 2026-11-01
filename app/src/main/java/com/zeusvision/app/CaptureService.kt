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
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var frameCount = 0L
    private var lastVisionAt = 0L
    private var captureLabel: TextView? = null
    private var handLabel: TextView? = null
    private var suggestionLabel: TextView? = null
    private var handInput: EditText? = null
    private var leftInput: EditText? = null
    private var rightInput: EditText? = null
    private var controls: LinearLayout? = null
    private var toggle: Button? = null
    private var expanded = false
    private var leader = "TÚ"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification())

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }
        if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            startCapture()
            showPanel()
        } catch (e: Exception) { e.printStackTrace(); stopSelf() }
        return START_NOT_STICKY
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
                if (image != null) {
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastVisionAt >= 900) {
                        lastVisionAt = now
                        val bmp = imageToBitmap(image)
                        if (bmp != null) {
                            val result = DominoVision.detectHand(bmp)
                            bmp.recycle()
                            handler.post {
                                captureLabel?.text = "CAPTURA OK · $frameCount frames"
                                if (result.tiles.isNotEmpty()) {
                                    val text = result.tiles.joinToString(", ") { it.label() }
                                    handLabel?.text = "MANO: $text"
                                    handInput?.setText(text)
                                    calculateSuggestion()
                                } else handLabel?.text = result.message
                            }
                        }
                    } else if (frameCount % 30L == 0L) {
                        handler.post { captureLabel?.text = "CAPTURA OK · $frameCount frames" }
                    }
                }
            } catch (_: Exception) {
            } finally { image?.close() }
        }, handler)
        display = projection?.createVirtualDisplay("ZEUS-CAPTURE", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun showPanel() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(7)); setBackgroundColor(0xF0182938.toInt()) }
        val header = TextView(this).apply {
            text = "☰ ZEUS VISION  ·  ARRASTRAR"
            textSize = 12f; setTextColor(0xFF35E59A.toInt()); setPadding(dp(5), dp(9), dp(5), dp(9)); setBackgroundColor(0xFF102332.toInt())
        }
        captureLabel = TextView(this).apply { text = "Iniciando captura..."; textSize = 10f; setTextColor(-1); setPadding(dp(3), dp(5), 0, dp(3)) }
        handLabel = TextView(this).apply { text = "Buscando fichas en la franja inferior..."; textSize = 11f; setTextColor(0xFFFFD166.toInt()); setPadding(dp(3), dp(3), 0, dp(5)) }
        toggle = Button(this).apply { text = "CONTROLES / SUGERENCIA"; textSize = 10f; setOnClickListener { setExpanded(!expanded) } }
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val leaderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val you = Button(this).apply { text = "SALGO YO"; textSize = 9f; setOnClickListener { leader = "TÚ"; calculateSuggestion() } }
        val rival = Button(this).apply { text = "SALE RIVAL"; textSize = 9f; setOnClickListener { leader = "RIVAL"; calculateSuggestion() } }
        leaderRow.addView(you, LinearLayout.LayoutParams(0, dp(38), 1f)); leaderRow.addView(rival, LinearLayout.LayoutParams(0, dp(38), 1f))
        handInput = editField("Fichas detectadas automáticamente")
        leftInput = editField("Extremo izquierdo (0-6 o -)")
        rightInput = editField("Extremo derecho (0-6 o -)")
        val calc = Button(this).apply { text = "SUGERIR JUGADA"; setOnClickListener { calculateSuggestion() } }
        val refresh = Button(this).apply { text = "VOLVER A LEER FICHAS"; setOnClickListener { lastVisionAt = 0L } }
        suggestionLabel = TextView(this).apply { text = "Esperando lectura de fichas."; textSize = 11f; setTextColor(-1); setPadding(dp(3), dp(6), dp(3), dp(6)) }
        val close = Button(this).apply { text = "CERRAR ZEUS"; setOnClickListener { stopSelf() } }
        controls?.apply { addView(leaderRow); addView(handInput); addView(leftInput); addView(rightInput); addView(calc); addView(refresh); addView(suggestionLabel); addView(close) }
        root.addView(header); root.addView(captureLabel); root.addView(handLabel); root.addView(toggle); root.addView(controls)
        val scroll = ScrollView(this).apply { addView(root); isFillViewport = true }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(dp(245), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = dp(5); y = dp(90) }
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        header.setOnTouchListener { _, ev ->
            val p = params ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { startX = p.x; startY = p.y; downX = ev.rawX; downY = ev.rawY; true }
                MotionEvent.ACTION_MOVE -> { p.x = (startX - (ev.rawX - downX).toInt()).coerceAtLeast(0); p.y = (startY + (ev.rawY - downY).toInt()).coerceAtLeast(0); try { wm?.updateViewLayout(scroll, p) } catch (_: Exception) {}; true }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
        try { wm?.addView(scroll, params); overlay = scroll; setExpanded(false) } catch (e: Exception) { e.printStackTrace() }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        controls?.visibility = if (value) View.VISIBLE else View.GONE
        toggle?.text = if (value) "OCULTAR CONTROLES" else "CONTROLES / SUGERENCIA"
        params?.let { p ->
            if (value) p.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            else p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            try { overlay?.let { wm?.updateViewLayout(it, p) } } catch (_: Exception) {}
        }
    }

    private fun editField(hintText: String) = EditText(this).apply {
        hint = hintText; textSize = 11f; setTextColor(-1); setHintTextColor(0xFFB7C7D3.toInt()); setSingleLine(true); setPadding(dp(6), dp(3), dp(6), dp(3)); setBackgroundColor(0xFF304454.toInt())
    }

    private fun calculateSuggestion() {
        val hand = handInput?.text?.toString().orEmpty()
        val left = leftInput?.text?.toString()?.ifBlank { "-" } ?: "-"
        val right = rightInput?.text?.toString()?.ifBlank { "-" } ?: "-"
        val result = DominoBot.suggest(hand, left, right)
        val s = result.suggestion
        suggestionLabel?.text = if (s == null) result.message else "${result.message}\nJUGAR ${s.oriented} · ${s.side}\n${s.reason}\nSalida: $leader"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(CHANNEL_ID, "Captura de ZEUS", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
    }
    private fun notification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID).setContentTitle("ZEUS VISION activo").setContentText("Captura y análisis visual").setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(pi).setOngoing(true).build()
        else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("ZEUS VISION activo").setContentText("Captura y análisis visual").setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(pi).setOngoing(true).build()
        }
    }
    override fun onDestroy() {
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        display?.release(); reader?.close(); projection?.stop(); super.onDestroy()
    }
}
