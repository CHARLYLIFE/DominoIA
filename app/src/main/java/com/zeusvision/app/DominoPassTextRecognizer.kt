package com.zeusvision.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** OCR local con modelo latino incluido en la APK (no requiere descargarlo al jugar). */
class DominoPassTextRecognizer : Closeable {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Se invoca desde el hilo de analisis, nunca desde el hilo principal. */
    fun recognize(bitmap: Bitmap, timeoutMs: Long = 450L): String {
        if (bitmap.isRecycled || bitmap.width < 120 || bitmap.height < 120) return ""
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            Tasks.await(recognizer.process(image), timeoutMs, TimeUnit.MILLISECONDS)
                .text.take(500)
        } catch (_: Exception) {
            ""
        }
    }

    override fun close() {
        try { recognizer.close() } catch (_: Exception) {}
    }
}
