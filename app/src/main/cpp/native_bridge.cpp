#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <atomic>
#include <mutex>
#include <thread>
#include <chrono>
#include <array>
#include <algorithm>
#include <complex>
#include <cmath>
#include <rtl-sdr.h>
#include "wfm_demodulator.h"
#include "nfm_demodulator.h"
#include "ssb_demodulator.h"
#include "am_demodulator.h"
#include "audio_agc.h"
#include "tuning_plan.h"

namespace {
constexpr int spectrumSize = 8192;
constexpr int fftStages = 13;
struct StreamState {
    rtlsdr_dev_t *device = nullptr;
    int fd = -1;
    std::thread worker;
    std::atomic<uint64_t> bytes{0};
    std::atomic<uint64_t> blocks{0};
    std::atomic<int> running{0};
    std::atomic<int> last_error{0};
    std::array<float, spectrumSize> spectrum{};
    std::mutex spectrum_mutex;
    std::atomic<uint64_t> spectrum_generation{0};
    int64_t spectrum_center_hz = 0; // Protected by spectrum_mutex.
    std::atomic<uint64_t> tuning{0}; // Upper 32 bits: RF center; lower 32: signed channel offset.
    std::atomic<uint64_t> hardware_tunes{0};
    std::atomic<uint64_t> digital_tunes{0};
    WfmDemodulator wfm;
    NfmDemodulator nfm;
    SsbDemodulator ssb;
    AmDemodulator am;
    AudioAgc audio_agc;
    std::atomic<bool> audio_agc_enabled{true};
    bool rf_agc = true; // Protected by stream_mutex.
    int gain_tenths_db = 197;
    std::atomic<int> demodulation{0}; // 0=WFM, 1=AM, 2=NFM, 3=USB, 4=LSB, 5=CW
    int active_demodulation = 0; // IQ callback thread only
    std::atomic<bool> retune_audio{false};
    std::array<int16_t, 16384> audio{};
    std::atomic<uint32_t> audio_head{0};
    std::atomic<uint32_t> audio_tail{0};
    std::atomic<uint64_t> audio_produced{0};
    std::atomic<uint64_t> audio_dropped{0};
};

StreamState stream;
std::mutex stream_mutex;

uint64_t pack_tuning(const TuningPlan& plan) {
    return (static_cast<uint64_t>(plan.centerHz) << 32) | static_cast<uint32_t>(plan.offsetHz);
}

int apply_rf_gain() {
    if (!stream.device) return 0;
    int result = rtlsdr_set_tuner_gain_mode(stream.device, stream.rf_agc ? 0 : 1);
    if (result != 0 || stream.rf_agc) return result;
    std::array<int, 64> gains{};
    const int count = rtlsdr_get_tuner_gains(stream.device, nullptr);
    if (count <= 0 || count > static_cast<int>(gains.size())) return -103;
    rtlsdr_get_tuner_gains(stream.device, gains.data());
    const auto nearest = std::min_element(gains.begin(), gains.begin() + count, [](int a, int b) {
        return std::abs(a - stream.gain_tenths_db) < std::abs(b - stream.gain_tenths_db);
    });
    result = rtlsdr_set_tuner_gain(stream.device, *nearest);
    if (result == 0) stream.gain_tenths_db = *nearest;
    return result;
}

void on_iq(unsigned char *samples, uint32_t length, void *) {
    stream.bytes.fetch_add(length, std::memory_order_relaxed);
    stream.blocks.fetch_add(1, std::memory_order_relaxed);
    const int mode = stream.demodulation.load(std::memory_order_acquire);
    const uint64_t tuning = stream.tuning.load(std::memory_order_acquire);
    const int offsetHz = static_cast<int32_t>(tuning & 0xffffffffu);
    const bool retuned = stream.retune_audio.exchange(false);
    if (mode != stream.active_demodulation) {
        stream.wfm.reset();
        stream.am.reset();
        stream.nfm.reset();
        stream.ssb.reset();
        stream.audio_agc.reset();
        stream.active_demodulation = mode;
    } else if (retuned && mode == 0) {
        stream.wfm.retune();
    } else if (retuned && mode == 2) {
        stream.nfm.retune();
    }
    stream.wfm.setFrequencyOffset(offsetHz);
    stream.am.setFrequencyOffset(offsetHz);
    stream.nfm.setFrequencyOffset(offsetHz);
    stream.ssb.setFrequencyOffset(offsetHz);
    std::array<int16_t, 2048> pcm{};
    const size_t audio_count = mode == 1
        ? stream.am.process(samples, length, pcm.data(), pcm.size())
        : mode == 2 ? stream.nfm.process(samples, length, pcm.data(), pcm.size())
        : mode >= 3 ? stream.ssb.process(samples, length, mode, pcm.data(), pcm.size())
        : stream.wfm.process(samples, length, pcm.data(), pcm.size());
    stream.audio_agc.process(pcm.data(), audio_count, stream.audio_agc_enabled.load(std::memory_order_relaxed));
    uint32_t head = stream.audio_head.load(std::memory_order_relaxed);
    const uint32_t tail = stream.audio_tail.load(std::memory_order_acquire);
    for (size_t sample = 0; sample < audio_count; ++sample) {
        if (head - tail >= stream.audio.size()) {
            stream.audio_dropped.fetch_add(1, std::memory_order_relaxed);
        } else {
            stream.audio[head & (stream.audio.size() - 1)] = pcm[sample];
            ++head;
        }
    }
    stream.audio_head.store(head, std::memory_order_release);
    stream.audio_produced.fetch_add(audio_count, std::memory_order_relaxed);
    if (length < spectrumSize * 2) return;

    constexpr int n = spectrumSize;
    constexpr float pi = 3.14159265358979323846f;
    static const auto window = [] {
        std::array<float, n> result{};
        for (int i = 0; i < n; ++i) {
            result[i] = 0.5f - 0.5f * std::cos(2.0f * pi * i / (n - 1));
        }
        return result;
    }();
    static const auto stage_steps = [] {
        std::array<std::complex<float>, fftStages> result{};
        for (int stage = 0; stage < fftStages; ++stage) {
            const float angle = -2.0f * pi / (1 << (stage + 1));
            result[stage] = {std::cos(angle), std::sin(angle)};
        }
        return result;
    }();
    std::array<std::complex<float>, n> values;
    for (int i = 0; i < n; ++i) {
        values[i] = std::complex<float>(
            (static_cast<float>(samples[2 * i]) - 127.5f) / 128.0f,
            (static_cast<float>(samples[2 * i + 1]) - 127.5f) / 128.0f
        ) * window[i];
    }
    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(values[i], values[j]);
    }
    for (int len = 2, stage = 0; len <= n; len <<= 1, ++stage) {
        const std::complex<float> step = stage_steps[stage];
        for (int base = 0; base < n; base += len) {
            std::complex<float> phase(1.0f, 0.0f);
            for (int j = 0; j < len / 2; ++j) {
                auto even = values[base + j];
                auto odd = values[base + j + len / 2] * phase;
                values[base + j] = even + odd;
                values[base + j + len / 2] = even - odd;
                phase *= step;
            }
        }
    }
    std::array<float, n> frame;
    for (int i = 0; i < n; ++i) {
        const float power = std::norm(values[(i + n / 2) % n]) / (n * n);
        frame[i] = 10.0f * std::log10(power + 1.0e-18f);
    }
    std::lock_guard<std::mutex> lock(stream.spectrum_mutex);
    stream.spectrum = frame;
    stream.spectrum_center_hz = static_cast<int64_t>(tuning >> 32);
    stream.spectrum_generation.fetch_add(1, std::memory_order_relaxed);
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_sdr_NativeSdr_startStream(JNIEnv *, jobject, jint fd, jlong frequency_hz) {
    std::lock_guard<std::mutex> lock(stream_mutex);
    if (stream.device != nullptr) return -100;
    stream.fd = dup(fd);
    if (stream.fd < 0) return -101;
    int result = rtlsdr_open_fd(&stream.device, stream.fd);
    const auto plan = TuningPlan::forFrequency(frequency_hz, 0);
    if (result == 0) result = rtlsdr_set_sample_rate(stream.device, WfmDemodulator::inputSampleRate);
    if (result == 0) result = rtlsdr_set_center_freq(stream.device, static_cast<uint32_t>(plan.centerHz));
    if (result == 0) result = rtlsdr_set_agc_mode(stream.device, 0);
    if (result == 0) result = apply_rf_gain();
    if (result == 0) result = rtlsdr_reset_buffer(stream.device);
    if (result != 0) {
        if (stream.device) rtlsdr_close(stream.device);
        stream.device = nullptr;
        close(stream.fd);
        stream.fd = -1;
        return result;
    }
    stream.bytes = 0;
    stream.blocks = 0;
    stream.last_error = 0;
    stream.spectrum_generation = 0;
    stream.tuning.store(pack_tuning(plan), std::memory_order_release);
    stream.hardware_tunes = 1;
    stream.digital_tunes = 0;
    stream.wfm.reset();
    stream.am.reset();
    stream.nfm.reset();
    stream.ssb.reset();
    stream.audio_agc.reset();
    stream.active_demodulation = stream.demodulation.load();
    stream.retune_audio = false;
    stream.audio_head = 0;
    stream.audio_tail = 0;
    stream.audio_produced = 0;
    stream.audio_dropped = 0;
    stream.running = 1;
    stream.worker = std::thread([] {
        int result = rtlsdr_read_async(stream.device, on_iq, nullptr, 8, 32768);
        stream.last_error = result;
        stream.running = 0;
    });
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_sdr_NativeSdr_setFrequency(JNIEnv *, jobject, jlong frequency_hz) {
    std::lock_guard<std::mutex> lock(stream_mutex);
    if (!stream.device) return -1;
    const auto plan = TuningPlan::forFrequency(frequency_hz, static_cast<int64_t>(stream.tuning.load() >> 32));
    if (plan.hardwareRetune) {
        const int result = rtlsdr_set_center_freq(stream.device, static_cast<uint32_t>(plan.centerHz));
        if (result != 0) return result;
        stream.hardware_tunes.fetch_add(1, std::memory_order_relaxed);
        stream.retune_audio = true;
    } else {
        stream.digital_tunes.fetch_add(1, std::memory_order_relaxed);
    }
    stream.tuning.store(pack_tuning(plan), std::memory_order_release);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sdr_NativeSdr_setDemodulation(JNIEnv *, jobject, jint mode) {
    if (mode < 0 || mode > 5) return;
    stream.demodulation.store(mode, std::memory_order_release);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_sdr_NativeSdr_setGainControls(JNIEnv *, jobject, jboolean rf_agc, jint gain, jboolean audio_agc) {
    std::lock_guard<std::mutex> lock(stream_mutex);
    stream.rf_agc = rf_agc;
    stream.gain_tenths_db = std::clamp(static_cast<int>(gain), -100, 600);
    stream.audio_agc_enabled = audio_agc;
    return apply_rf_gain();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_sdr_NativeSdr_tunerGains(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(stream_mutex);
    std::array<int, 64> gains{};
    const int count = stream.device ? rtlsdr_get_tuner_gains(stream.device, nullptr) : 0;
    if (count <= 0 || count > static_cast<int>(gains.size())) return env->NewIntArray(0);
    rtlsdr_get_tuner_gains(stream.device, gains.data());
    jintArray result = env->NewIntArray(count);
    if (result) env->SetIntArrayRegion(result, 0, count, gains.data());
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_sdr_NativeSdr_readAudio(JNIEnv *env, jobject, jshortArray output) {
    if (!output) return 0;
    std::array<jshort, 2048> samples{};
    const uint32_t tail = stream.audio_tail.load(std::memory_order_relaxed);
    const uint32_t head = stream.audio_head.load(std::memory_order_acquire);
    const uint32_t available = head - tail;
    const uint32_t requested = static_cast<uint32_t>(env->GetArrayLength(output));
    const uint32_t count = std::min<uint32_t>({available, requested, samples.size()});
    for (uint32_t i = 0; i < count; ++i) {
        samples[i] = stream.audio[(tail + i) & (stream.audio.size() - 1)];
    }
    if (count) {
        env->SetShortArrayRegion(output, 0, count, samples.data());
        stream.audio_tail.store(tail + count, std::memory_order_release);
    }
    return static_cast<jint>(count);
}

// Called only by the audio consumer thread; drop samples from the previous tuning.
extern "C" JNIEXPORT void JNICALL
Java_com_example_sdr_NativeSdr_discardAudio(JNIEnv *, jobject) {
    const uint32_t head = stream.audio_head.load(std::memory_order_acquire);
    stream.audio_tail.store(head, std::memory_order_release);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_sdr_NativeSdr_spectrum(JNIEnv *env, jobject) {
    std::array<float, spectrumSize> copy;
    int64_t center;
    uint64_t generation;
    {
        std::lock_guard<std::mutex> lock(stream.spectrum_mutex);
        copy = stream.spectrum;
        center = stream.spectrum_center_hz;
        generation = stream.spectrum_generation.load(std::memory_order_relaxed);
    }
    jfloatArray bins = env->NewFloatArray(copy.size());
    if (!bins) return nullptr;
    env->SetFloatArrayRegion(bins, 0, copy.size(), copy.data());
    jclass frameClass = env->FindClass("com/example/sdr/SpectrumFrame");
    if (!frameClass) return nullptr;
    jmethodID constructor = env->GetMethodID(frameClass, "<init>", "([FJJ)V");
    if (!constructor) return nullptr;
    return env->NewObject(frameClass, constructor, bins, static_cast<jlong>(center), static_cast<jlong>(generation));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_sdr_NativeSdr_spectrumGeneration(JNIEnv *, jobject) {
    return static_cast<jlong>(stream.spectrum_generation.load(std::memory_order_relaxed));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_sdr_NativeSdr_stopStream(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(stream_mutex);
    if (!stream.device) return;
    for (int attempt = 0; attempt < 100 && stream.running.load(); ++attempt) {
        rtlsdr_cancel_async(stream.device);
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if (stream.worker.joinable()) stream.worker.join();
    rtlsdr_close(stream.device);
    stream.device = nullptr;
    close(stream.fd);
    stream.fd = -1;
    stream.running = 0;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_sdr_NativeSdr_streamStats(JNIEnv *env, jobject) {
    jlong values[] = {
        static_cast<jlong>(stream.bytes.load()),
        static_cast<jlong>(stream.blocks.load()),
        static_cast<jlong>(stream.running.load()),
        static_cast<jlong>(stream.last_error.load()),
        static_cast<jlong>(stream.audio_produced.load()),
        static_cast<jlong>(stream.audio_dropped.load()),
        static_cast<jlong>(stream.tuning.load() >> 32),
        static_cast<jlong>(static_cast<int32_t>(stream.tuning.load() & 0xffffffffu)),
        static_cast<jlong>(stream.hardware_tunes.load()),
        static_cast<jlong>(stream.digital_tunes.load())
    };
    jlongArray result = env->NewLongArray(10);
    if (result) env->SetLongArrayRegion(result, 0, 10, values);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_sdr_NativeSdr_receiverRates(JNIEnv *env, jobject) {
    const jint values[] = {
        WfmDemodulator::inputSampleRate,
        WfmDemodulator::channelBandwidthHz
    };
    jintArray result = env->NewIntArray(2);
    if (result) env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_sdr_NativeSdr_isUsbDescriptorOpen(JNIEnv *, jobject, jint fd) {
    return fd >= 0 && fcntl(fd, F_GETFD) != -1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_sdr_NativeSdr_captureIq(JNIEnv *env, jobject, jint fd, jlong frequency_hz) {
    int native_fd = dup(fd);
    if (native_fd < 0) return env->NewStringUTF("Не удалось скопировать USB-дескриптор");

    rtlsdr_dev_t *device = nullptr;
    int result = rtlsdr_open_fd(&device, native_fd);
    if (result < 0 || !device) {
        close(native_fd);
        char message[96];
        snprintf(message, sizeof(message), "Ошибка открытия RTL-SDR: %d", result);
        return env->NewStringUTF(message);
    }

    char message[128];
    result = rtlsdr_set_sample_rate(device, WfmDemodulator::inputSampleRate);
    if (result == 0) result = rtlsdr_set_center_freq(device, static_cast<uint32_t>(frequency_hz));
    if (result == 0) result = rtlsdr_reset_buffer(device);
    if (result == 0) {
        unsigned char samples[32768];
        int received = 0;
        result = rtlsdr_read_sync(device, samples, sizeof(samples), &received);
        if (result == 0 && received > 0) {
            unsigned char minimum = 255, maximum = 0;
            for (int i = 0; i < received; ++i) {
                if (samples[i] < minimum) minimum = samples[i];
                if (samples[i] > maximum) maximum = samples[i];
            }
            snprintf(message, sizeof(message), "IQ получены: %d байт, диапазон %u–%u", received, minimum, maximum);
        } else {
            snprintf(message, sizeof(message), "Ошибка чтения IQ: %d (%d байт)", result, received);
        }
    } else {
        snprintf(message, sizeof(message), "Ошибка настройки RTL-SDR: %d", result);
    }
    rtlsdr_close(device);
    close(native_fd);
    return env->NewStringUTF(message);
}
