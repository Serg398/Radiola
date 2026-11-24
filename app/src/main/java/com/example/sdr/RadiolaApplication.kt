package com.example.sdr

import android.app.Application
import com.example.sdr.sources.WebSdrRuntime
import com.example.sdr.sources.usb.ReceiverRuntime

class RadiolaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (android.os.Process.myUid() == applicationInfo.uid) {
            com.example.sdr.audio.HamNoiseSettings.initialize(this)
        }
    }
    val receiver by lazy { ReceiverRuntime(this) }
    internal val webSdr by lazy { WebSdrRuntime(this) }
    internal val plugins by lazy { com.example.sdr.plugins.PluginManager(this) }
}
