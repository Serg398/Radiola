#include "wfm_demodulator.h"

#include <algorithm>
#include <cmath>

void WfmDemodulator::reset() {
    mixer_.reset();
    decimator_.reset();
    channel_.reset();
    previousI_ = 0.0f;
    previousQ_ = 0.0f;
    havePrevious_ = false;
    discardPhaseOnce_ = false;
    lastPhase_ = 0.0f;
    decoder_.reset();
    rds_.reset();
}

size_t WfmDemodulator::process(
    const uint8_t* iq, size_t byteCount, int16_t* output, size_t capacity
) {
    size_t produced = 0;
    for (size_t byte = 0; byte + 1 < byteCount; byte += 2) {
        float mixedI, mixedQ;
        mixer_.mix(static_cast<float>(iq[byte]) - 127.5f,
            static_cast<float>(iq[byte + 1]) - 127.5f, mixedI, mixedQ);
        float currentI, currentQ;
        if (!decimator_.push(mixedI, mixedQ, currentI, currentQ)) continue;

        channel_.push(currentI, currentQ);
        if (bandwidth_ < channelBandwidthHz) channel_.output(currentI, currentQ);

        if (havePrevious_) {
            const float cross = previousI_ * currentQ - previousQ_ * currentI;
            const float dot = previousI_ * currentI + previousQ_ * currentQ;
            const float phase = discardPhaseOnce_ ? lastPhase_ : std::atan2(cross, dot);
            discardPhaseOnce_ = false;
            lastPhase_ = phase;
            rds_.process(phase);
            int16_t left, right;
            if (decoder_.process(phase, stereoEnabled_, left, right) && produced + 2 <= capacity) {
                output[produced++] = left;
                output[produced++] = right;
            }
        }
        previousI_ = currentI;
        previousQ_ = currentQ;
        havePrevious_ = true;
    }
    return produced;
}
