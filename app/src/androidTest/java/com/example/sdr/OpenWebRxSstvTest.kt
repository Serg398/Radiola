package com.example.sdr

import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sdr.sources.openwebrx.OpenWebRxSstv
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenWebRxSstvTest {
    private fun row(line: Int, vararg bytes: Int) = JSONObject().put("mode", "SSTV")
        .put("width", 2).put("height", 2).put("line", line)
        .put("pixels", Base64.encodeToString(ByteArray(bytes.size) { bytes[it].toByte() }, Base64.NO_WRAP))

    @Test fun assemblesBgrRowsWithoutFlippingOrCountingDuplicates() {
        val decoder = OpenWebRxSstv()
        val header = JSONObject("""{"mode":"SSTV","sstvMode":"Martin 1","width":2,"height":2,"timestamp":"12:34:56"}""")
        val initial = decoder.accept(header)!!
        assertEquals("Martin 1", initial.mode)
        assertEquals(Color.BLACK, initial.bitmap.getPixel(0, 0))
        val first = decoder.accept(row(0, 0, 0, 255, 0, 255, 0))!!
        assertEquals(Color.RED, first.bitmap.getPixel(0, 0))
        assertEquals(Color.GREEN, first.bitmap.getPixel(1, 0))
        assertEquals(1, first.lines)
        assertEquals(1, decoder.accept(row(0, 0, 0, 255, 0, 255, 0))!!.lines)
        val complete = decoder.accept(row(1, 255, 0, 0, 255, 255, 255))!!
        assertEquals(2, complete.lines)
        assertEquals(Color.BLUE, complete.bitmap.getPixel(0, 1))
        assertEquals(Color.WHITE, complete.bitmap.getPixel(1, 1))
        val next = decoder.accept(header)!!
        assertEquals(0, next.lines)
        assertNotSame(complete.bitmap, next.bitmap)
        assertEquals(Color.BLUE, complete.bitmap.getPixel(0, 1))
        decoder.clear()
        assertEquals(1, decoder.accept(row(1, 255, 0, 0, 255, 255, 255))!!.lines)
    }

    @Test fun rejectsMalformedAndUnboundedRows() {
        val decoder = OpenWebRxSstv()
        assertNull(decoder.accept(JSONObject("""{"width":100000,"height":100000}""")))
        assertNull(decoder.accept(row(-1, 0, 0, 0, 0, 0, 0)))
        assertNull(decoder.accept(row(2, 0, 0, 0, 0, 0, 0)))
        assertNull(decoder.accept(row(0, 0)))
        assertNull(decoder.accept(JSONObject("""{"mode":"SSTV","message":"Receiving"}""")))
    }
}
