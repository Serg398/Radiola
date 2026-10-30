#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <new>
#include "denoise_audio.h"

extern "C" const denoise_model_t *radiola_hamnoise_model(int model);

namespace {
constexpr int Rate = DENOISE_SAMPLE_RATE;
constexpr int Taps = 63, Half = Taps / 2, Phases = 128;
constexpr int Delay = 432; // 45 ms at model rate; includes a complete hop's scheduling slack.
constexpr double Pi = 3.14159265358979323846;

template<size_t N> struct Ring {
    std::array<float, N> data{};
    int64_t count = 0;
    void put(float value) { data[count++ % N] = value; }
    float get(int64_t index) const {
        return index < 0 || index >= count || count - index > static_cast<int64_t>(N) ? 0.f : data[index % N];
    }
};

struct Filter {
    std::array<std::array<float, Taps>, Phases> coefficients{};
    explicit Filter(double cutoff) {
        for (int phase = 0; phase < Phases; ++phase) {
            double sum = 0;
            for (int j = 0; j < Taps; ++j) {
                const double x = j - Half - phase / double(Phases);
                const double sinc = std::abs(x) < 1.e-9 ? 2 * cutoff : std::sin(2 * Pi * cutoff * x) / (Pi * x);
                const double window = .42 - .5 * std::cos(2 * Pi * j / (Taps - 1)) + .08 * std::cos(4 * Pi * j / (Taps - 1));
                coefficients[phase][j] = static_cast<float>(sinc * window);
                sum += coefficients[phase][j];
            }
            for (float &value : coefficients[phase]) value /= static_cast<float>(sum);
        }
    }
    template<size_t N> float at(const Ring<N> &ring, double position) const {
        const auto center = static_cast<int64_t>(std::floor(position));
        const int phase = std::clamp(static_cast<int>((position - center) * Phases), 0, Phases - 1);
        float value = 0;
        for (int j = 0; j < Taps; ++j) value += ring.get(center + j - Half) * coefficients[phase][j];
        return value;
    }
};

struct Processor {
    const int rate, channels;
    int model;
    denoise_stream_t stream{};
    Ring<8192> input;
    Ring<2048> wet;
    Filter down, up;
    std::array<float, DENOISE_HOP_LENGTH> hop{}, output{};
    int hopCount = 0;
    double nextSample = 0;
    float amount = 0, strength = .6f;
    bool failed = false;
    Processor(int rate, int channels, int model) : rate(rate), channels(channels), model(model),
        down(std::min(.46, 4400. / rate)), up(std::min(.46, .46 * rate / Rate)) {
        failed = denoise_stream_init(&stream) != 0;
    }
    void reset(int selected) {
        model = selected; denoise_stream_reset(&stream);
        input = {}; wet = {}; hopCount = 0; nextSample = 0; amount = 0; failed = false;
    }
    bool process(jshort *pcm, int count, int selected, float targetStrength) {
        if (selected != 0 && selected != model) reset(selected);
        const double dryDelay = Delay * double(rate) / Rate + Half;
        const float step = 1.f / (rate * .02f);
        for (int i = 0; i < count; i += channels) {
            const float left = pcm[i] / 32768.f;
            const float right = channels == 2 ? pcm[i + 1] / 32768.f : left;
            const int64_t n = input.count;
            input.put((left + right) * .5f);
            while (nextSample <= n) {
                hop[hopCount++] = down.at(input, nextSample - Half);
                nextSample += rate / double(Rate);
                if (hopCount == DENOISE_HOP_LENGTH) {
                    bool produced = false;
                    const int status = denoise_stream_process_hop(&stream, radiola_hamnoise_model(model), hop.data(), output.data(), &produced);
                    if (status != 0) failed = true;
                    if (produced && !failed) for (float value : output) wet.put(value);
                    hopCount = 0;
                }
            }
            const double position = n * double(Rate) / rate - Delay;
            const auto dryIndex = static_cast<int64_t>(std::floor(n - dryDelay));
            const float fraction = static_cast<float>(n - dryDelay - dryIndex);
            const float dry = input.get(dryIndex) * (1 - fraction) + input.get(dryIndex + 1) * fraction;
            const float cleaned = up.at(wet, position);
            const float target = selected != 0 && !failed && n > dryDelay + Half ? 1.f : 0.f;
            amount += std::clamp(target - amount, -step, step);
            strength += std::clamp(targetStrength - strength, -step, step);
            const float mixed = dry + strength * (cleaned - dry);
            auto sample = [&](float raw) {
                const float value = raw + amount * (mixed - raw);
                return static_cast<jshort>(std::clamp(std::lround(value * 32768.f), -32768L, 32767L));
            };
            pcm[i] = sample(left);
            if (channels == 2) pcm[i + 1] = sample(right);
        }
        return selected != 0 || amount > 0;
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_sdr_audio_HamNoiseNative_create(JNIEnv *, jobject, jint rate, jint channels, jint model) {
    if (rate < 4000 || rate > 48000 || channels < 1 || channels > 2 || model < 1 || model > 2) return 0;
    return reinterpret_cast<jlong>(new(std::nothrow) Processor(rate, channels, model));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_sdr_audio_HamNoiseNative_process(JNIEnv *env, jobject, jlong handle, jshortArray samples, jint count, jint model, jfloat strength) {
    auto *processor = reinterpret_cast<Processor *>(handle);
    if (!processor || count < 0 || count > env->GetArrayLength(samples) || count % processor->channels != 0) return false;
    auto *pcm = env->GetShortArrayElements(samples, nullptr);
    if (!pcm) return false;
    const bool active = processor->process(pcm, count, model, std::clamp(strength, 0.f, 1.f));
    env->ReleaseShortArrayElements(samples, pcm, 0);
    return active;
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_sdr_audio_HamNoiseNative_destroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Processor *>(handle);
}
