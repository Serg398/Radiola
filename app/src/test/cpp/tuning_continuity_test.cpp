#include "am_demodulator.h"
#include "wfm_demodulator.h"
#include "tuning_plan.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <type_traits>
#include <vector>

constexpr double pi = 3.14159265358979323846;

template <typename Demodulator>
bool check(const char* name, const std::vector<uint8_t>& iq, bool fm, bool digital = false) {
    Demodulator demodulator;
    demodulator.reset();
    std::vector<int16_t> pcm;
    int16_t block[2048];
    for (size_t offset = 0; offset < iq.size(); offset += 32768) {
        if (digital) {
            demodulator.setFrequencyOffset(250000 + (static_cast<int>(offset / 32768) % 3 - 1) * 1000);
        } else {
            if constexpr (std::is_same_v<Demodulator, WfmDemodulator>) {
                if (offset > 0) demodulator.retune();
            }
        }
        // AM deliberately retains its envelope/filter state on every hardware retune.
        const size_t count = demodulator.process(iq.data() + offset,
            std::min<size_t>(32768, iq.size() - offset), block, 2048);
        pcm.insert(pcm.end(), block, block + count);
    }
    size_t longestSilentRun = 0, silentRun = 0;
    double energy = 0;
    for (size_t i = 9600; i < pcm.size(); ++i) {
        silentRun = pcm[i] == 0 ? silentRun + 1 : 0;
        longestSilentRun = std::max(longestSilentRun, silentRun);
        energy += static_cast<double>(pcm[i]) * pcm[i];
    }
    const double rms = std::sqrt(energy / (pcm.size() - 9600));
    const bool passed = pcm.size() == (fm ? 95998u : 48000u) && longestSilentRun < 10 && rms > 1000;
    std::printf("%s %s: samples=%zu longest_silence_samples=%zu rms=%.1f\n",
        passed ? "PASS" : "FAIL", name, pcm.size(), longestSilentRun, rms);
    return passed;
}

int main() {
    std::vector<uint8_t> am(AmDemodulator::inputSampleRate * 2), fm(am.size());
    std::vector<uint8_t> digitalAm(am.size()), digitalFm(am.size());
    for (size_t i = 0; i < am.size() / 2; ++i) {
        const double t = static_cast<double>(i) / AmDemodulator::inputSampleRate;
        const double envelope = 25 * (1 + 0.5 * std::sin(2 * pi * 1000 * t));
        const double carrier = 2 * pi * 1500 * t;
        const double fmPhase = -40 * std::cos(2 * pi * 1000 * t);
        am[2 * i] = static_cast<uint8_t>(std::round(127.5 + envelope * std::cos(carrier)));
        am[2 * i + 1] = static_cast<uint8_t>(std::round(127.5 + envelope * std::sin(carrier)));
        fm[2 * i] = static_cast<uint8_t>(std::round(127.5 + 60 * std::cos(fmPhase)));
        fm[2 * i + 1] = static_cast<uint8_t>(std::round(127.5 + 60 * std::sin(fmPhase)));
        const double digitalCarrier = 2 * pi * 250000 * t;
        digitalAm[2 * i] = static_cast<uint8_t>(std::round(127.5 + envelope * std::cos(digitalCarrier)));
        digitalAm[2 * i + 1] = static_cast<uint8_t>(std::round(127.5 + envelope * std::sin(digitalCarrier)));
        digitalFm[2 * i] = static_cast<uint8_t>(std::round(127.5 + 60 * std::cos(digitalCarrier + fmPhase)));
        digitalFm[2 * i + 1] = static_cast<uint8_t>(std::round(127.5 + 60 * std::sin(digitalCarrier + fmPhase)));
    }
    const bool amPassed = check<AmDemodulator>("AM", am, false);
    const bool fmPassed = check<WfmDemodulator>("WFM", fm, true);
    const bool digitalAmPassed = check<AmDemodulator>("digital AM", digitalAm, false, true);
    const bool digitalFmPassed = check<WfmDemodulator>("digital WFM", digitalFm, true, true);
    const auto initial = TuningPlan::forFrequency(100000000, 0);
    const auto smallStep = TuningPlan::forFrequency(100001000, initial.centerHz);
    const auto same = TuningPlan::forFrequency(initial.centerHz, initial.centerHz);
    const auto maximum = TuningPlan::forFrequency(1766000000, 0);
    const bool planPassed = initial.hardwareRetune && initial.centerHz == 100000000 && initial.offsetHz == 0
        && smallStep.hardwareRetune && smallStep.centerHz == 100001000 && smallStep.offsetHz == 0
        && !same.hardwareRetune && same.offsetHz == 0
        && maximum.centerHz == 1766000000 && maximum.offsetHz == 0;
    std::printf("%s tuning plan: full capture follows selected frequency\n", planPassed ? "PASS" : "FAIL");
    return amPassed && fmPassed && digitalAmPassed && digitalFmPassed && planPassed ? 0 : 1;
}
