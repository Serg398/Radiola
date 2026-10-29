package com.example.sdr

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*

/** Process-wide online receiver owner. Activity recreation never closes the stream. */
internal class WebSdrRuntime(private val context: Context) {
    private val preferences = context.getSharedPreferences("websdr", Context.MODE_PRIVATE)
    private val webSdrSession = WebSdrSession(context.applicationContext)
    private val openWebRxSession = OpenWebRxSession(context.applicationContext)
    val session: OnlineReceiverSession get() = if (source == "OpenWebRX") openWebRxSession else webSdrSession
    var source by mutableStateOf(preferences.getString("source", "USB") ?: "USB")
        private set
    var receiverUrl by mutableStateOf(preferences.getString("server_url", null))
        private set
    var receiverName by mutableStateOf(preferences.getString("server_name", "") ?: "")
        private set
    var page by mutableStateOf(if (source == "OpenWebRX" || receiverUrl == null) "MAP" else "Radio")
        private set
    var pending by mutableStateOf(false)
        private set
    var startError by mutableStateOf<String?>(null)
        private set
    var openWebRxReceiver by mutableStateOf(preferences.getString("openwebrx_url", null)?.let {
        WebSdrReceiver(it, preferences.getString("openwebrx_name", "").orEmpty(), "", 0f, 0f, null, emptyList())
    })
        private set
    internal var serviceOwner: Any? = null
    fun selectSource(value: String) {
        if (value != source) disconnect()
        page = "MAP"
        source = value; preferences.edit().putString("source", value).apply()
        (context.applicationContext as? RadiolaApplication)?.receiver?.updateAudioWarmup()
    }
    fun selectPage(value: String) { page = value }
    fun select(receiver: WebSdrReceiver) {
        if (source == "OpenWebRX") {
            if (openWebRxReceiver?.url != receiver.url) disconnect()
            openWebRxReceiver = receiver
            preferences.edit().putString("openwebrx_url", receiver.url).putString("openwebrx_name", receiver.name).apply()
            return
        }
        selectServer(receiver.url, android.text.Html.fromHtml(receiver.name, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim())
    }
    fun selectServer(url: String?, name: String) {
        if (receiverUrl != url) disconnect()
        receiverUrl = url; receiverName = name
        preferences.edit().putString("server_url", receiverUrl).putString("server_name", receiverName).apply()
    }
    private val selectedUrl get() = if (source == "OpenWebRX") openWebRxReceiver?.url else receiverUrl
    val selectedName get() = if (source == "OpenWebRX") openWebRxReceiver?.name.orEmpty() else receiverName
    fun connect() {
        if (selectedUrl == null || pending || session.active) return
        pending = true; startError = null; page = "Radio"
        try { context.startForegroundService(Intent(context, WebSdrService::class.java).setAction(WebSdrService.START)) }
        catch (error: Exception) { pending = false; startError = "Cannot start playback: ${error.message}" }
    }
    fun startFromService() {
        if (!pending) return
        pending = false
        selectedUrl?.let { session.connect(it) }
    }
    fun disconnect() { pending = false; session.disconnect() }
}
