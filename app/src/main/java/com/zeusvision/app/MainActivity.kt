package com.zeusvision.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val captureRequest = 4102
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(0xFF07131D.toInt())
        }
        val title = TextView(this).apply {
            text = "♟  ZEUS VISION"
            textSize = 30f
            setTextColor(0xFF35E59A.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "ASISTENTE VISUAL PARA DOMINÓ\n\nVersión nativa 0.3.0\n\nZEUS necesita permiso para capturar la pantalla del juego y mostrar un panel flotante. La captura solo comienza cuando tú la autorizas."
            textSize = 16f
            setTextColor(0xFFEAF2F7.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 28)
        }
        val start = Button(this).apply {
            text = "ACTIVAR ZEUS"
            setOnClickListener { activateZeus() }
        }
        val stop = Button(this).apply {
            text = "DETENER CAPTURA"
            setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)); Toast.makeText(this@MainActivity, "ZEUS detenido", Toast.LENGTH_SHORT).show() }
        }
        val note = TextView(this).apply {
            text = "Nota: esta primera compilación comprueba la captura nativa y la ventana flotante. El reconocimiento automático de fichas y el cálculo avanzado de jugadas se incorporarán y calibrarán después con capturas reales."
            textSize = 13f
            setTextColor(0xFF9FB2C0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(start, LinearLayout.LayoutParams(-1, -2))
        root.addView(stop, LinearLayout.LayoutParams(-1, -2))
        root.addView(note)
        setContentView(root)
    }

    private fun activateZeus() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Activa ‘Mostrar sobre otras apps’ y vuelve a ZEUS", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Android activity result API kept for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
            Toast.makeText(this, "ZEUS está activo. Abre tu dominó.", Toast.LENGTH_LONG).show()
        } else if (requestCode == captureRequest) {
            Toast.makeText(this, "No se autorizó la captura", Toast.LENGTH_SHORT).show()
        }
    }
}
