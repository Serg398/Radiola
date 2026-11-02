package com.example.sdr.receiver

import com.example.sdr.DemodulationMode
import com.example.sdr.SpectrumFrame
import com.example.sdr.decoders.cw.CwInformation
import com.example.sdr.decoders.ftx.FtxInformation
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.receiver.BandwidthOptions
import com.example.sdr.receiver.WaterfallHistory

/** Controls shared by native clients of the two independent online protocols. */
internal interface OnlineReceiverSession {
    val serverModes: List<ReceiverModeOption>? get() = null
    val serverModeId: String? get() = null
    val modeLabel: String get() = mode.label
    val passbandOffsetHz: Int get() = mode.offsetForBandwidth(bandwidthHz)
    val decodedText: String get() = ""
    val sstvImage: SstvImage? get() = null
    val serverDataMode: Boolean get() = false
    val bandwidthAdjustable: Boolean get() = true
    val bandwidthOptions: BandwidthOptions
    fun requestServerMode(id: String) = Unit
    fun clearDecodedText() = Unit
    val state: ReceiverConnectionState
    val error: String?
    val waterfallWarning: String?
    val frequencyHz: Long
    val mode: DemodulationMode
    val bandwidthHz: Int
    val bandwidthDefault: Boolean
    val bands: List<ReceiverBand>
    val currentBand: Int
    val audioSampleRate: Int
    val squelchOpen: Boolean
    val signalDbm: Float?
    val frame: SpectrumFrame?
    val waterfallHistory: WaterfallHistory
    val visualFps: Int
    val active: Boolean
    val audioMetrics: ReceiverAudioMetrics
    val bandwidthLimit: Int
    fun configureFtx(mode: FtxMode?, frequency: Long)
    fun ftxInformation(): FtxInformation
    fun clearFtx()
    fun cwInformation(): CwInformation
    val cwBackend: com.example.sdr.decoders.cw.CwBackend get() = com.example.sdr.decoders.cw.CwBackend.CLASSIC
    fun selectCwBackend(value: com.example.sdr.decoders.cw.CwBackend) = Unit
    val cwDecodingEnabled: Boolean
    fun clearCw()
    fun enableCw(value: Boolean)
    var squelchSetting: ReceiverSquelch
    fun connect(url: String)
    fun disconnect()
    fun tune(hz: Long)
    fun selectBand(index: Int)
    fun requestMode(value: DemodulationMode)
    fun setBandwidth(hz: Int?)
    fun setWaterfallViewport(centerHz: Long, spanHz: Int)
}
