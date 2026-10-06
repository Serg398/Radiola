#pragma once

#include <array>
#include "dsp_profiles.h"
#if defined(__ARM_NEON) && !defined(DSP_SCALAR)
#include <arm_neon.h>
#endif

// 2.4 MHz -> 1.2 MHz -> 240 kHz. Evaluate FIRs only for retained samples.
// Mirrored histories give contiguous symmetric pairs without division/modulo.
class IqDecimator {
    template<size_t N, int Factor, int Stride = 1> class Stage {
    public:
        void reset() { i_.fill(0); q_.fill(0); position_ = count_ = 0; }
        bool push(float i, float q, const std::array<float, N>& taps, float& oi, float& oq) {
            i_[position_] = i_[position_ + N] = i;
            q_[position_] = q_[position_ + N] = q;
            if (++position_ == N) position_ = 0;
            if (++count_ != Factor) return false;
            count_ = 0;
            const float* hi = i_.data() + position_;
            const float* hq = q_.data() + position_;
            constexpr size_t middle = N / 2;
            oi = taps[middle] * hi[middle];
            oq = taps[middle] * hq[middle];
            size_t k = 0;
#if defined(__ARM_NEON) && !defined(DSP_SCALAR)
            auto reverse = [](float32x4_t x) {
                x = vrev64q_f32(x);
                return vcombine_f32(vget_high_f32(x), vget_low_f32(x));
            };
            float32x4_t si = vdupq_n_f32(0), sq = si;
            for (; k + 3 * Stride < middle; k += 4 * Stride) {
                float32x4_t c, ai, aq, bi, bq;
                if constexpr (Stride == 2) {
                    c = vld2q_f32(taps.data() + k).val[0];
                    ai = vld2q_f32(hi + k).val[0]; aq = vld2q_f32(hq + k).val[0];
                    bi = reverse(vld2q_f32(hi + N - 1 - k - 6).val[0]);
                    bq = reverse(vld2q_f32(hq + N - 1 - k - 6).val[0]);
                } else {
                    c = vld1q_f32(taps.data() + k);
                    ai = vld1q_f32(hi + k); aq = vld1q_f32(hq + k);
                    bi = reverse(vld1q_f32(hi + N - 1 - k - 3));
                    bq = reverse(vld1q_f32(hq + N - 1 - k - 3));
                }
                si = vmlaq_f32(si, vaddq_f32(ai, bi), c);
                sq = vmlaq_f32(sq, vaddq_f32(aq, bq), c);
            }
            auto sum = [](float32x4_t v) {
                auto h = vadd_f32(vget_low_f32(v), vget_high_f32(v));
                return vget_lane_f32(h, 0) + vget_lane_f32(h, 1);
            };
            oi += sum(si); oq += sum(sq);
#endif
            for (; k < middle; k += Stride) {
                oi += taps[k] * (hi[k] + hi[N - 1 - k]);
                oq += taps[k] * (hq[k] + hq[N - 1 - k]);
            }
            return true;
        }
    private:
        std::array<float, 2 * N> i_{}, q_{};
        size_t position_ = 0;
        int count_ = 0;
    };
public:
    void reset() { half_.reset(); fifth_.reset(); }
    bool push(float i, float q, float& oi, float& oq) {
        float hi, hq;
        return half_.push(i, q, DspProfiles::halfBand, hi, hq) &&
            fifth_.push(hi, hq, DspProfiles::decimateFive, oi, oq);
    }
private:
    Stage<DspProfiles::halfBand.size(), 2, 2> half_;
    Stage<DspProfiles::decimateFive.size(), 5> fifth_;
};
