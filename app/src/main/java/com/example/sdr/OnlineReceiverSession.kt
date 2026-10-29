package com.example.sdr

/** Controls shared by native clients of the two independent online protocols. */
internal interface OnlineReceiverSession {
    val serverModes: List<OpenWebRxMode>? get() = null
    val serverModeId: String? get() = null
    val modeLabel: String get() = mode.label
    val passbandOffsetHz: Int get() = mode.offsetForBandwidth(bandwidthHz)
    val decodedText: String get() = ""
    val serverDataMode: Boolean get() = false
    val bandwidthAdjustable: Boolean get() = true
    val bandwidthOptions: BandwidthOptions get() = WebSdrBandwidth.options(mode, bandwidthLimit)
    fun requestServerMode(id: String) = Unit
    fun clearDecodedText() = Unit
    val state: WebSdrConnectionState
    val error: String?
    val waterfallWarning: String?
    val frequencyHz: Long
    val mode: DemodulationMode
    val bandwidthHz: Int
    val bandwidthDefault: Boolean
    val bands: List<WebSdrTunerBand>
    val currentBand: Int
    val audioSampleRate: Int
    val squelchOpen: Boolean
    val signalDbm: Float?
    val frame: SpectrumFrame?
    val waterfallHistory: WaterfallHistory
    val visualFps: Int
    val active: Boolean
    val audioMetrics: WebSdrAudioMetrics
    val bandwidthLimit: Int
    val cwDecoder: CwDecodeWorker
    val ftxDecoder: FtxDecodeWorker
    var squelchSetting: ReceiverSquelch
    fun connect(url: String)
    fun disconnect()
    fun tune(hz: Long)
    fun selectBand(index: Int)
    fun requestMode(value: DemodulationMode)
    fun setBandwidth(hz: Int?)
    fun setWaterfallViewport(centerHz: Long, spanHz: Int)
}
