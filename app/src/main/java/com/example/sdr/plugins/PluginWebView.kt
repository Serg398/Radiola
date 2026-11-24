package com.example.sdr.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import androidx.core.net.toUri
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.UUID
import org.json.JSONObject

/** Loads the imported UI from an origin unique to this view. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor") // Imported content is required; never inflated from XML.
internal class PluginWebView(context: Context, plugin: PluginManifest) : WebView(context) {
    private val origin = "https://${UUID.randomUUID()}.plugin.radiola.invalid".toUri()
    private val page = "$origin/index.html"
    private val files = mapOf(
        "/index.html" to "text/html",
        "/view.js" to "text/javascript",
        "/common.css" to "text/css",
        "/api.js" to "text/javascript",
        "/worker.js" to "text/javascript",
    )
    private var port: WebMessagePort? = null
    private var snapshot: String? = null
    var onCommand: (JSONObject) -> Unit = {}

    init {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        isVerticalScrollBarEnabled = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                val path = url.encodedPath.orEmpty()
                val type = files[path]
                val bytes = plugin.webFiles[path.removePrefix("/")]
                if (request.method == "GET" && url.scheme == origin.scheme && url.authority == origin.authority && type != null && bytes != null) {
                    return WebResourceResponse(type, "UTF-8", 200, "OK", mapOf(
                        "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self'; worker-src 'self'; img-src data:; base-uri 'none'; form-action 'none'",
                        "X-Content-Type-Options" to "nosniff",
                        "Cache-Control" to "no-store",
                    ), ByteArrayInputStream(bytes))
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url != page || port != null) return
                val channel = createWebMessageChannel()
                port = channel[0]
                channel[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                        if (port !== this@PluginWebView.port) return
                        val data = message.data ?: return
                        if (data.length > 4096) return
                        val command = runCatching { JSONObject(data) }.getOrNull() ?: return
                        onCommand(command)
                    }
                })
                postWebMessage(WebMessage("radiola-plugin", arrayOf(channel[1])), origin)
                snapshot?.let { channel[0].postMessage(WebMessage(it)) }
            }
        }
    }

    fun publish(state: JSONObject, command: (JSONObject) -> Unit) {
        onCommand = command
        val payload = state.toString()
        if (payload == snapshot) return
        snapshot = payload
        port?.postMessage(WebMessage(payload))
    }

    fun open() { loadUrl(page) }

    fun publishChannelIq(payload: String) {
        port?.postMessage(WebMessage(payload))
    }

    fun replyReceiverCommand(request: JSONObject, error: String?) {
        val requestId = (request.opt("requestId") as? String)?.takeIf { it.length in 1..64 } ?: return
        val payload = JSONObject().put("type", "receiverResult").put("requestId", requestId)
            .put("ok", error == null)
        if (error != null) payload.put("error", error)
        port?.postMessage(WebMessage(payload.toString()))
    }

    fun publishPcm(block: PluginManager.PcmBlock) {
        val channel = port ?: return
        val payload = JSONObject().put("type", "pcm").put("rate", block.rate)
            .put("startMs", block.startMs).put("index", block.index)
            .put("epoch", block.epoch)
            .put("samples", android.util.Base64.encodeToString(block.bytes, android.util.Base64.NO_WRAP))
        channel.postMessage(WebMessage(payload.toString()))
    }

    fun release() {
        port?.close()
        port = null
        stopLoading()
        destroy()
    }
}
