
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
            text = "♟  ZEUS VISION 0.4"
            textSize = 28f
            setTextColor(0xFF35E59A.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "PRUEBA DE CAPTURA + BOT BÁSICO\n\n1. Activa el permiso para mostrar sobre otras apps.\n2. Autoriza la grabación de pantalla.\n3. Abre Dominó Online.\n4. En el panel flotante introduce tus fichas y los extremos para recibir una sugerencia.\n\nFormato de mano: 6-5, 2-2, 0-3. Mesa vacía: '-' en ambos extremos."
            textSize = 15f
            setTextColor(0xFFEAF2F7.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 26)
        }
        val start = Button(this).apply {
            text = "ACTIVAR ZEUS"
            setOnClickListener { activateZeus() }
        }
        val stop = Button(this).apply {
            text = "DETENER CAPTURA"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                Toast.makeText(this@MainActivity, "ZEUS detenido", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(start, LinearLayout.LayoutParams(-1, -2))
        root.addView(stop, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun activateZeus() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Activa ‘Mostrar sobre otras apps’ y vuelve a ZEUS para activarlo", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "ZEUS está activo. Abre tu dominó y usa el panel flotante.", Toast.LENGTH_LONG).show()
        } else if (requestCode == captureRequest) {
            Toast.makeText(this, "No se autorizó la captura", Toast.LENGTH_SHORT).show()
        }
    }
}
