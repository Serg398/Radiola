#pragma once

// Owned by the USB control thread under stream_mutex. Applied state, not UI intent.
class RfGainController {
public:
    void reset() { known_ = false; }

    template<class SetMode, class SetGain>
    int apply(bool automatic, int gain, bool combinedManualGain, SetMode setMode, SetGain setGain) {
        if (known_ && automatic_ == automatic && (automatic || gain_ == gain)) return 0;
        int result = 0;
        if (automatic) {
            result = setMode(0);
        } else {
            // R82xx set_gain already selects manual mode with the requested gains.
            // set_mode(1) would first select zero gain and cause a signal dip.
            if (!combinedManualGain && (!known_ || automatic_)) result = setMode(1);
            if (result == 0) result = setGain(gain);
        }
        if (result != 0) { known_ = false; return result; }
        known_ = true;
        automatic_ = automatic;
        gain_ = gain;
        return 0;
    }
private:
    bool known_ = false;
    bool automatic_ = true;
    int gain_ = 0;
};
