package com.example.sdr.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import org.json.JSONObject

/** Loads only the bundled UI package. The message port is scoped to its local origin. */
@SuppressLint("SetJavaScriptEnabled")
internal class PluginWebView(context: Context, directory: String) : WebView(context) {
    private val origin = Uri.parse("https://plugin.radiola.invalid")
    private val page = "$origin/$directory/index.html"
    private val files = mapOf(
        "/$directory/index.html" to "text/html",
        "/$directory/view.js" to "text/javascript",
        "/common.css" to "text/css",
        "/api.js" to "text/javascript",
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
                if (request.method == "GET" && url.scheme == origin.scheme && url.authority == origin.authority && type != null) {
                    try {
                        return WebResourceResponse(type, "UTF-8", context.assets.open("plugins/ui$path"))
                    } catch (_: FileNotFoundException) {
                        // A missing package resource is handled like an unsupported request.
                    }
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

    fun release() {
        port?.close()
        port = null
        stopLoading()
        destroy()
    }
}
