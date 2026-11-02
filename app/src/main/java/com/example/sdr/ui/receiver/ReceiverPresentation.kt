package com.example.sdr.ui.receiver

import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.receiver.ReceiverConnectionState
import androidx.compose.runtime.*
import com.example.sdr.DemodulationMode
import com.example.sdr.decoders.cw.CwInformation
import com.example.sdr.decoders.ftx.FtxInformation
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.sources.WebSdrRuntime
import com.example.sdr.sources.usb.ReceiverRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal data class LocalReceiverState(val frequencyHz: Long, val mode: DemodulationMode,
    val receiving: Boolean, val bandwidthHz: Int)

internal data class ReceiverUiState(val source: ReceiverSource, val frequencyHz: Long,
    val mode: DemodulationMode, val bandwidthHz: Int, val receiving: Boolean)

/** ViewModel-owned commands and decoder presentation; sessions remain owned by the application. */
internal class ReceiverPresentation(
    val online: WebSdrRuntime, private val usb: ReceiverRuntime, scope: CoroutineScope,
    private val local: () -> LocalReceiverState,
    private val usbStep: (Long) -> Unit, private val usbMode: (DemodulationMode) -> Unit,
    private val usbBandwidth: (Int?) -> Unit,
    initialFtx: FtxMode? = null, private val onFtxChange: (FtxMode?) -> Unit = {},
) {
    private var selectedFtx by mutableStateOf(initialFtx)
    var ftxMode: FtxMode?
        get() = selectedFtx
        set(value) { selectedFtx = value; onFtxChange(value) }
    var ftxInformation by mutableStateOf(FtxInformation()); private set
    var cwInformation by mutableStateOf(CwInformation()); private set
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
            snapshotFlow { state to ftxMode }.collectLatest { (current, protocol) ->
                val enabled = current.receiving && current.mode == DemodulationMode.USB
                usb.session.ftxDecoder.configure(if (!current.source.online && enabled) protocol else null, current.frequencyHz)
                online.session.configureFtx(if (current.source.online && enabled) protocol else null, current.frequencyHz)
                cwInformation = CwInformation(); ftxInformation = FtxInformation()
                if (current.receiving && (current.mode == DemodulationMode.CW || protocol != null)) {
                    while (true) {
                        if (current.mode == DemodulationMode.CW) cwInformation = if (current.source.online) online.session.cwInformation() else usb.session.cwDecoder.snapshot()
                        if (protocol != null) ftxInformation = if (current.source.online) online.session.ftxInformation() else usb.session.ftxDecoder.snapshot()
                        delay(if (current.mode == DemodulationMode.CW) 100 else 200)
                    }
                }
            }
        }
    }
    fun step(delta: Long) {
        if (online.source.online) online.session.tune(online.session.frequencyHz + delta) else usbStep(delta)
    }
    fun changeMode(value: DemodulationMode) {
        ftxMode = null
        if (online.source.online) online.session.requestMode(value) else usbMode(value)
    }
    fun changeBandwidth(value: Int?) {
        if (online.source.online) online.session.setBandwidth(value) else usbBandwidth(value)
    }
    fun selectFtx(value: FtxMode) {
        changeMode(DemodulationMode.USB); changeBandwidth(3000); ftxMode = value
    }
    fun selectServerMode(id: String) { ftxMode = null; online.session.requestServerMode(id) }
    fun selectBand(index: Int) {
        val session = online.session
        if (online.source == ReceiverSource.OPEN_WEB_RX && session.active &&
            index in session.bands.indices && index != session.currentBand) ftxMode = null
        session.selectBand(index)
    }
    fun clearFtx() { if (online.source.online) online.session.clearFtx() else usb.session.ftxDecoder.clear() }
    fun clearCw() { if (online.source.online) online.session.clearCw() else usb.session.cwDecoder.clear() }
    fun enableCw(value: Boolean) { if (online.source.online) online.session.enableCw(value) else usb.session.cwDecoder.requestDecoding(value) }
}
