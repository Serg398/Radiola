# HamNoise audio noise reduction

## Controls

NR sits beside the mode/bandwidth controls. Its popup offers ON/OFF and a 0вЂ“100% strength slider. Settings persist across launches; default is OFF / 60%. CW selects the CW model; AM, NFM and SSB select Voice. WFM and OpenWebRX secondary data modes bypass NR.

This first integration uses the compact classic CW and classic Voice models from HamNoise's shared C core. The repository also contains larger v2 models; those are not included here.

## Signal path

Only listening PCM is processed. USB FT input and played-PCM CW input retain their original samples, as do the two online sessions. NR does not alter IQ, waterfall data, decoder configuration or network traffic.

Each playback worker owns its processor and native state. OFF allocates no model state and bypasses exactly. Turning OFF crossfades to the original input over 20 ms, then releases the state. Model/rate changes reset streaming context; audio route recreation and stop release it.

The wrapper converts mono/stereo input continuously to 9600 Hz, using 63-tap, 128-phase windowed-sinc interpolation with antialias filtering. HamNoise processes 144-sample hops with its 256-point FFT and recurrent model. Output is reconstructed at the original rate. Supported input range is 4000вЂ“48000 Hz, including fractional online rates such as 7997 Hz.

Strength mixes filtered output with delayed original audio. Added delay while active is `432 / 9600 + 31 / inputRate` seconds: approximately 45.6 ms at 48 kHz, 47.6 ms at 12 kHz and 48.9 ms at 7997 Hz. This excludes the existing player and Bluetooth latency. Enabling/disabling adds a short crossfade; normal bypass has no added delay. NR is mono; supported receiver modes already carry mono content on the USB stereo output. Music WFM bypasses it.

## Verification

- Build and JVM unit tests passed.
- `HamNoiseAndroidTest`: model processing on the actual phone, CW noise attenuation and tone preservation, classic Voice execution, exact disabled/unsupported bypass, unchanged decoder PCM, resampling across block boundaries and aligned dry mixing at 7997, 12000 and 48000 Hz (USB stereo included).
- Four seconds of generated noisy CW took approximately 14.4 ms in the test, including the harness. This is not a measurement of total application CPU or battery drain.
- Optional `nr_fixture=true` test processes `files/nr-fixture.raw` (12 kHz mono PCM16) and writes `files/nr-result.raw`, without bundling recordings into the app.
- A generated speech recording bandlimited to 200вЂ“2800 Hz with additive white noise improved SI-SDR from approximately -0.68 dB to 10.44 dB at 100% Voice strength, aligned by the processing delay. This is a single synthetic audio test, not a measurement of real RF reception or intelligibility. Listening evaluation remains necessary.

## Provenance

Source: https://github.com/e04/HamNoise

Revision: `178265862264212d7c5f7dda4d162cb9b4b876eb`.

Shared C core, headers and classic generated weights are copied unmodified into `app/src/main/cpp/third_party/hamnoise/`. MIT license is included there and in application assets. The JNI resampling/mixing wrapper is specific to Radiola.
