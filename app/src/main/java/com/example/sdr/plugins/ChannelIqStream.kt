package com.example.sdr.plugins

import android.util.Base64
import com.example.sdr.NativeSdr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal suspend fun streamChannelIq(publish: (String) -> Unit) {
    val samples = ByteArray(4096 * 4) // 4096 interleaved signed 16-bit I/Q pairs.
    val info = LongArray(7)
    NativeSdr.enableChannelIq(true)
    try {
        while (true) {
            val packets = withContext(Dispatchers.Default) {
                buildList {
                    repeat(8) {
                        if (!NativeSdr.readChannelIq(samples, info)) return@buildList
                        add(JSONObject().put("type", "channelIq").put("format", "s16le")
                            .put("rate", info[0]).put("frequencyHz", info[1])
                            .put("epoch", info[2]).put("index", info[3])
                            .put("timestampNs", info[4])
                            .put("bandwidthHz", info[5]).put("passbandOffsetHz", info[6])
                            .put("samples", Base64.encodeToString(samples, Base64.NO_WRAP))
                            .toString())
                    }
                }
            }
            packets.forEach(publish)
            delay(20)
        }
    } finally {
        NativeSdr.enableChannelIq(false)
    }
}
