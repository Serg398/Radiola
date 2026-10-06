#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include "frequency_mixer.h"
#include "iq_decimator.h"
#include "fm_stereo_decoder.h"

class WfmDemodulator {
public:
    static constexpr int inputSampleRate = DspProfiles::iqRate;
    static constexpr int channelBandwidthHz = DspProfiles::modes[0].bandwidthHz;
    void reset();
    void setFrequencyOffset(int hz) { mixer_.setOffset(hz); }
    // Keep the audio filters running; ignore only the first cross-tuning phase difference.
    void retune() { discardPhaseOnce_ = true; }
    void setStereo(bool enabled) { stereoEnabled_ = enabled; }
    bool stereoLocked() const { return decoder_.locked(); }
    // Input: interleaved unsigned 8-bit IQ at 2.4 Msps. Output: interleaved L/R at 48 kHz.
    // Capacity and return value are int16 sample counts (two per audio frame).
    size_t process(const uint8_t* iq, size_t byteCount, int16_t* output, size_t capacity);

private:
    FrequencyMixer mixer_;
    IqDecimator decimator_;
    float previousI_ = 0.0f;
    float previousQ_ = 0.0f;
    bool havePrevious_ = false;
    bool discardPhaseOnce_ = false;
    float lastPhase_ = 0.0f;
    FmStereoDecoder decoder_;
    bool stereoEnabled_ = false;
};
