#pragma once

#include <cmath>

// Phase-continuous NCO. Used before IQ decimation, without converting back to 8-bit IQ.
class FrequencyMixer {
public:
    void reset() { real_ = 1.0f; imag_ = 0.0f; samples_ = 0; }

    void setOffset(int hz) {
        if (hz == offsetHz_) return;
        offsetHz_ = hz;
        const double angle = -6.28318530717958647692 * hz / 2400000.0;
        stepReal_ = static_cast<float>(std::cos(angle));
        stepImag_ = static_cast<float>(std::sin(angle));
    }

    void mix(float inputI, float inputQ, float& outputI, float& outputQ) {
        outputI = inputI * real_ - inputQ * imag_;
        outputQ = inputI * imag_ + inputQ * real_;
        const float nextReal = real_ * stepReal_ - imag_ * stepImag_;
        imag_ = real_ * stepImag_ + imag_ * stepReal_;
        real_ = nextReal;
        if (++samples_ == 1024) {
            const float scale = 1.0f / std::sqrt(real_ * real_ + imag_ * imag_);
            real_ *= scale;
            imag_ *= scale;
            samples_ = 0;
        }
    }

private:
    int offsetHz_ = 0;
    float real_ = 1.0f;
    float imag_ = 0.0f;
    float stepReal_ = 1.0f;
    float stepImag_ = 0.0f;
    int samples_ = 0;
};
