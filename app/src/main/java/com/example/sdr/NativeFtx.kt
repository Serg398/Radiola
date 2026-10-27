package com.example.sdr

internal object NativeFtx {
    init { System.loadLibrary("sdr_native") }
    external fun decode(samples: ShortArray, ft4: Boolean): Array<String>
}
