package com.example.sdr.ui.receiver

internal fun frequencyDigitStep(text: String, index: Int): Long? {
    if (index !in text.indices || text[index] !in '0'..'9') return null
    var step = 1L
    for (i in index + 1 until text.length) if (text[i] in '0'..'9') step *= 10
    return step
}
