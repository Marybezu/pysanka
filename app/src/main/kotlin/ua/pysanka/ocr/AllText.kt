package ua.pysanka.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import kotlin.math.max
import kotlin.math.min

/**
 * Pysanka: all the text on a picture, for keeping with it in the clipboard history (to find it
 * later). A tall one (a scrolling screenshot) is read in parts of twice its width, so small text
 * stays readable; at most [MAX_TILES] parts.
 */
internal object AllText {
    private const val TILE_RATIO = 2
    private const val MAX_TILES = 12

    suspend fun read(context: Context, uri: Uri): String? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val recognizer = TextRecognizer.get(context)
        if (h <= w * TILE_RATIO * 5 / 4) {
            var sample = 1
            while (max(w, h) / (sample * 2) >= 2400) sample *= 2
            val bitmap = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            return try {
                recognizer.recognize(bitmap).joinedText()
            } finally {
                bitmap.recycle()
            }
        }
        val input = cr.openInputStream(uri) ?: return null
        return input.use { stream ->
            val decoder = if (Build.VERSION.SDK_INT >= 31) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            } ?: return null
            try {
                var sample = 1
                while (w / (sample * 2) >= 1200) sample *= 2
                val tile = w * TILE_RATIO
                val parts = ArrayList<String>()
                var top = 0
                var n = 0
                while (top < h && n < MAX_TILES) {
                    val part = decoder.decodeRegion(
                        Rect(0, top, w, min(h, top + tile)), BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                    if (part != null) {
                        try {
                            recognizer.recognize(part).joinedText().takeIf { it.isNotBlank() }?.let { parts.add(it) }
                        } finally {
                            part.recycle()
                        }
                    }
                    top += tile
                    n++
                }
                parts.joinToString("\n")
            } finally {
                decoder.recycle()
            }
        }
    }
}
