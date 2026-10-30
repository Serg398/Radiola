package com.example.sdr

import android.app.Application
import com.example.sdr.sources.WebSdrRuntime
import com.example.sdr.sources.usb.ReceiverRuntime

class RadiolaApplication : Application() {
    val receiver by lazy { ReceiverRuntime(this) }
    internal val webSdr by lazy { WebSdrRuntime(this) }
}
