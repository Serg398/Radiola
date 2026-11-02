package com.example.sdr.sources.openwebrx

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.example.sdr.receiver.SstvImage
import org.json.JSONObject

/** OpenWebRX+ sends a header followed by base64-encoded top-down BGR scanlines. */
internal class OpenWebRxSstv {
    private var image: SstvImage? = null
    private var received = BooleanArray(0)
    private var revision = 0L

    fun clear() { image = null; received = BooleanArray(0) }

    fun accept(message: JSONObject): SstvImage? {
        val width = message.optInt("width")
        val height = message.optInt("height")
        if (width !in 1..1024 || height !in 1..1024) return null
        val hasLine = message.has("line")
        val line = message.optInt("line", -1)
        val pixels = if (hasLine) {
            if (line !in 0 until height) return null
            val encoded = message.optString("pixels")
            if (encoded.length > width * 4 + 16) return null
            val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: IllegalArgumentException) { return null }
            if (bytes.size != width * 3) return null
            IntArray(width) { x -> Color.rgb(bytes[x * 3 + 2].toInt() and 255,
                bytes[x * 3 + 1].toInt() and 255, bytes[x * 3].toInt() and 255) }
        } else null
        var current = image
        if (!hasLine || current == null || current.bitmap.width != width || current.bitmap.height != height) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            current = SstvImage(bitmap, message.optString("sstvMode", "SSTV"),
                message.optString("timestamp"), 0, ++revision)
            received = BooleanArray(height)
        }
        if (pixels != null) {
            current.bitmap.setPixels(pixels, 0, width, 0, line, width, 1)
            val extra = if (received[line]) 0 else 1
            received[line] = true
            current = current.copy(lines = current.lines + extra, revision = ++revision)
        }
        image = current
        return current
    }
}
