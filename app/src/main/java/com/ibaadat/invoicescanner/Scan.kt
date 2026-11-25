package com.ibaadat.invoicescanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Scan {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** On-device OCR. No network, no per-scan cost. */
    suspend fun read(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /**
     * Downscale, greyscale, then stretch contrast between the 2% and 98%
     * brightness points. This is what rescues shots taken at an angle under
     * shop lighting — it pushes faded print back to near-black on near-white.
     */
    fun clean(src: Bitmap, maxEdge: Int = 2200): Bitmap {
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(src.width, src.height))
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createScaledBitmap(src, w, h, true)

        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        val grey = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val g = ((Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000)
                .coerceIn(0, 255)
            grey[i] = g; hist[g]++
        }

        val total = px.size
        var acc = 0; var lo = 0; var hi = 255
        for (i in 0..255) { acc += hist[i]; if (acc > total * 0.02) { lo = i; break } }
        acc = 0
        for (i in 255 downTo 0) { acc += hist[i]; if (acc > total * 0.02) { hi = i; break } }
        val span = maxOf(1, hi - lo)

        for (i in px.indices) {
            val v = (((grey[i] - lo) * 255) / span).coerceIn(0, 255)
            px[i] = Color.rgb(v, v, v)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (bmp != src) bmp.recycle()
        return out
    }

    /** A4 PDF holding the cleaned photo, centred and fitted. */
    fun toPdf(bitmap: Bitmap): ByteArray {
        val landscape = bitmap.width > bitmap.height
        val pw = if (landscape) 842 else 595
        val ph = if (landscape) 595 else 842

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
        val s = minOf(pw.toFloat() / bitmap.width, ph.toFloat() / bitmap.height)
        val dw = (bitmap.width * s).toInt()
        val dh = (bitmap.height * s).toInt()
        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawBitmap(
            bitmap, null,
            android.graphics.Rect((pw - dw) / 2, (ph - dh) / 2, (pw + dw) / 2, (ph + dh) / 2),
            null
        )
        doc.finishPage(page)

        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
