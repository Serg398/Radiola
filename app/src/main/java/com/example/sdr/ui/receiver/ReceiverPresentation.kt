package com.example.sdr.ui.receiver

import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.receiver.ReceiverConnectionState
import androidx.compose.runtime.*
import com.example.sdr.DemodulationMode
import com.example.sdr.decoders.cw.CwInformation
import com.example.sdr.decoders.ftx.FtxInformation
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.plugins.PluginManifest
import com.example.sdr.sources.WebSdrRuntime
import com.example.sdr.sources.usb.ReceiverRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

internal val localTuningRange = 500_000L..1_766_000_000L

internal data class LocalReceiverState(val frequencyHz: Long, val mode: DemodulationMode,
    val receiving: Boolean, val bandwidthHz: Int)

internal data class ReceiverUiState(val source: ReceiverSource, val frequencyHz: Long,
    val mode: DemodulationMode, val bandwidthHz: Int, val receiving: Boolean)

/** ViewModel-owned commands and decoder presentation; sessions remain owned by the application. */
internal class ReceiverPresentation(
    val online: WebSdrRuntime, private val usb: ReceiverRuntime, private val scope: CoroutineScope,
    val favorites: com.example.sdr.favorites.FavoritesRepository,
    private val local: () -> LocalReceiverState,
    private val usbStep: (Long) -> Unit, private val usbMode: (DemodulationMode) -> Unit,
    private val usbBandwidth: (Int?) -> Unit,
    initialFtx: FtxMode? = null, private val onFtxChange: (FtxMode?) -> Unit = {},
) {
    var favoriteError by mutableStateOf<String?>(null); private set
    var applyingFavorite by mutableStateOf<String?>(null); private set
    private var favoriteJob: kotlinx.coroutines.Job? = null

    fun favoriteSnapshot(defaultBandwidth: Boolean): com.example.sdr.favorites.FavoriteStation? {
        val current = state
        val session = online.session
        if (current.source.online && !session.tuningReady) return null
        val band = if (current.source.online) session.bands.getOrNull(session.currentBand) else null
        return com.example.sdr.favorites.FavoriteStation(
            name = "%.3f MHz".format(java.util.Locale.US, current.frequencyHz / 1e6),
            frequencyHz = current.frequencyHz, mode = current.mode,
            bandwidthHz = if (if (current.source.online) session.bandwidthDefault else defaultBandwidth) null else current.bandwidthHz,
            source = current.source,
            serverUrl = if (!current.source.online) null else if (current.source == ReceiverSource.OPEN_WEB_RX) online.openWebRxReceiver?.url else online.receiverUrl,
            serverName = if (current.source.online) online.selectedName else "",
            bandId = if (current.source.online) session.bandIds.getOrNull(session.currentBand) else null,
            bandName = band?.name.orEmpty(), serverMode = if (current.source.online && ftxMode == null) session.serverModeId else null,
            ftx = ftxMode)
    }
    fun cancelFavorite() { favoriteJob?.cancel(); applyingFavorite = null }
    fun activateFavorite(value: com.example.sdr.favorites.FavoriteStation, onReady: () -> Unit) {
        cancelFavorite(); favoriteError = null
        favoriteJob = scope.launch {
            applyingFavorite = value.id
            try {
                kotlinx.coroutines.withTimeout(25_000) {
                    if (online.source != value.source) {
                        usb.stop(); online.selectSource(value.source)
                    }
                    if (value.source.online) {
                        val url = requireNotNull(value.serverUrl) { "No server saved" }
                        online.select(com.example.sdr.sources.websdr.WebSdrReceiver(url, value.serverName, "", 0f, 0f, null, emptyList()))
                        online.connect()
                        val session = online.session
                        while (!session.tuningReady || session.bands.isEmpty()) {
                            check(session.state != ReceiverConnectionState.Error) { session.error ?: "Connection failed" }
                            check(online.startError == null) { online.startError.orEmpty() }
                            delay(50)
                        }
                        val index = value.bandId?.let { session.bandIds.indexOf(it) } ?: -1
                        check(index >= 0) { "Saved band is no longer available" }
                        if (index != session.currentBand) {
                            session.selectBand(index)
                            while (!session.tuningReady || session.currentBand != index) delay(50)
                        }
                        check(session.bands[index].contains(value.frequencyHz)) { "Frequency outside saved band" }
                        if (value.serverMode != null) {
                            check(session.serverModes?.any { it.id == value.serverMode } == true) { "Saved mode is unavailable" }
                            selectServerMode(value.serverMode)
                        } else changeMode(value.mode)
                        session.tune(value.frequencyHz)
                        online.selectPage("Radio")
                    } else {
                        online.disconnect()
                        changeMode(value.mode)
                        usbStep(value.frequencyHz - local().frequencyHz)
                    }
                    if (value.ftx != null) selectFtx(value.ftx)
                    changeBandwidth(value.bandwidthHz)
                    onReady()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                favoriteError = "Server did not apply the saved band. Try again."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { favoriteError = e.message ?: "Cannot open station"
            } finally { applyingFavorite = null }
        }
    }
    private var selectedFtx by mutableStateOf(initialFtx)
    var ftxMode: FtxMode?
        get() = selectedFtx
        set(value) { selectedFtx = value; onFtxChange(value) }
    var ftxInformation by mutableStateOf(FtxInformation()); private set
    var cwInformation by mutableStateOf(CwInformation()); private set
    var faxMode by mutableStateOf(false)
    val faxSelected get() = faxMode && state.mode == DemodulationMode.USB &&
        !(online.source.online && online.session.serverDataMode)
    var faxInformation by mutableStateOf(com.example.sdr.decoders.fax.FaxInformation()); private set
    val faxDecoder get() = if (online.source.online) online.session.faxDecoder else usb.session.faxDecoder
    val state: ReceiverUiState get() {
        val source = online.source
        val local = local()
        val session = online.session
        return if (source.online) ReceiverUiState(source, session.frequencyHz, session.mode,
            session.bandwidthHz, session.state == ReceiverConnectionState.Connected)
        else ReceiverUiState(source, local.frequencyHz, local.mode, local.bandwidthHz, local.receiving)
    }
    val cwBackend get() = if (online.source.online) online.session.cwBackend else usb.session.cwDecoder.backend
    fun selectCwBackend(value: com.example.sdr.decoders.cw.CwBackend) {
        if (online.source.online) online.session.selectCwBackend(value) else usb.session.cwDecoder.selectBackend(value)
    }
    val cwEnabled get() = if (online.source.online) online.session.cwDecodingEnabled else usb.session.cwDecoder.decodingEnabled

    init {
        scope.launch {
            snapshotFlow { Triple(state, ftxMode, faxMode) }.collectLatest { (current, protocol, fax) ->
                val enabled = current.receiving && current.mode == DemodulationMode.USB
                val faxActive = enabled && fax && !(current.source.online && online.session.serverDataMode)
                usb.session.faxDecoder.configure(faxActive && !current.source.online, current.frequencyHz)
                online.configureFax(faxActive && current.source.online, current.frequencyHz)
                usb.session.ftxDecoder.configure(if (!current.source.online && enabled) protocol else null, current.frequencyHz)
                online.session.configureFtx(if (current.source.online && enabled) protocol else null, current.frequencyHz)
                cwInformation = CwInformation(); ftxInformation = FtxInformation()
                faxInformation = faxDecoder.snapshot()
                if (fax || current.receiving && (current.mode == DemodulationMode.CW || protocol != null)) {
                    while (true) {
                        if (current.mode == DemodulationMode.CW) cwInformation = if (current.source.online) online.session.cwInformation() else usb.session.cwDecoder.snapshot()
                        if (protocol != null) ftxInformation = if (current.source.online) online.session.ftxInformation() else usb.session.ftxDecoder.snapshot()
                        if (fax) faxInformation = faxDecoder.snapshot()
                        delay(if (current.mode == DemodulationMode.CW) 100 else 200)
                    }
                }
            }
        }
    }
    fun step(delta: Long) {
        if (online.source.online) online.session.tune(online.session.frequencyHz + delta) else usbStep(delta)
    }
    fun pluginReceiverCommand(plugin: PluginManifest, command: JSONObject): String? {
        val source = online.source
        if (source !in plugin.sources) return "Plugin does not support this receiver"
        val session = online.session
        if (source.online && !session.tuningReady) return "Connect the receiver first"
        return when (command.optString("type")) {
            "setFrequency" -> {
                val hz = (command.opt("value") as? Number)?.toDouble()
                if (hz == null || !hz.isFinite() || hz < 1 || hz > 9_007_199_254_740_991.0 ||
                    hz != kotlin.math.floor(hz)) return "Frequency must be a positive integer in Hz"
                val frequency = hz.toLong()
                val inRange = when (source) {
                    ReceiverSource.USB -> frequency in localTuningRange
                    ReceiverSource.WEB_SDR -> session.bands.any { it.contains(frequency) }
                    ReceiverSource.OPEN_WEB_RX -> session.bands.getOrNull(session.currentBand)
                        ?.contains(frequency) == true
                }
                if (!inRange) return "Frequency outside receiver range"
                cancelFavorite()
                if (source.online) session.tune(frequency) else usbStep(frequency - local().frequencyHz)
                null
            }
            "setMode" -> {
                val requested = (command.opt("value") as? String)?.trim()?.lowercase().orEmpty()
                val id = if (requested == "fm") "nfm" else requested
                if (source == ReceiverSource.OPEN_WEB_RX) {
                    val option = session.serverModes?.firstOrNull { it.id.equals(requested, true) }
                        ?: session.serverModes?.firstOrNull { it.id.equals(id, true) }
                        ?: return "Mode is not available on this server"
                    cancelFavorite()
                    selectServerMode(option.id)
                } else {
                    val mode = DemodulationMode.entries.firstOrNull { it.name.equals(id, true) }
                    if (mode == null || source.online && mode == DemodulationMode.WFM) return "Mode is not available"
                    cancelFavorite()
                    changeMode(mode)
                }
                null
            }
            else -> "Unknown receiver command"
        }
    }
    fun changeMode(value: DemodulationMode) {
        faxMode = false
        ftxMode = null
        if (online.source.online) online.session.requestMode(value) else usbMode(value)
    }
    fun changeBandwidth(value: Int?) {
        if (online.source.online) online.session.setBandwidth(value) else usbBandwidth(value)
    }
    fun selectFtx(value: FtxMode) {
        changeMode(DemodulationMode.USB); changeBandwidth(3000); ftxMode = value
    }
    fun selectFax() { changeMode(DemodulationMode.USB); changeBandwidth(3000); faxMode = true }
    fun selectServerMode(id: String) { faxMode = false; ftxMode = null; online.session.requestServerMode(id) }
    fun selectBand(index: Int) {
        faxMode = false
        val session = online.session
        if (online.source == ReceiverSource.OPEN_WEB_RX && session.active &&
            index in session.bands.indices && index != session.currentBand) ftxMode = null
        session.selectBand(index)
    }
    fun clearFtx() { if (online.source.online) online.session.clearFtx() else usb.session.ftxDecoder.clear() }
    fun clearCw() { if (online.source.online) online.session.clearCw() else usb.session.cwDecoder.clear() }
    fun enableCw(value: Boolean) { if (online.source.online) online.session.enableCw(value) else usb.session.cwDecoder.requestDecoding(value) }
}
