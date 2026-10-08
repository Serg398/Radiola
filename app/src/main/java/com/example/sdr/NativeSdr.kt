package com.example.sdr

object NativeSdr {
    init {
        System.loadLibrary("sdr_native")
    }

    external fun isUsbDescriptorOpen(fd: Int): Boolean
    external fun captureIq(fd: Int, frequencyHz: Long): String
    external fun startStream(fd: Int, frequencyHz: Long): Int
    external fun stopStream()
    external fun streamStats(): LongArray
    external fun receiverRates(): IntArray
    external fun setFrequency(frequencyHz: Long): Int
    external fun setWfmStereo(enabled: Boolean)
    external fun wfmStereoLocked(): Boolean
    external fun setBandwidth(mode: Int, hz: Int)
    external fun setDemodulation(mode: Int)
    external fun setGainControls(rfAgc: Boolean, gainTenthsDb: Int, audioAgc: Boolean, biasT: Boolean): Int
    external fun tunerGains(): IntArray
    external fun spectrum(): SpectrumFrame
    external fun spectrumGeneration(): Long
    external fun readAudio(output: ShortArray): Int
    external fun setSquelch(enabled: Boolean, thresholdDbfs: Float)
    external fun squelchOpen(): Boolean
    external fun discardAudio()
}
