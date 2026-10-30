# DeepCW integration

CW card: select **Classic** or **DeepCW**, then enable **DECODE**. Selection is saved in application preferences. CLEAR, tuning, source interruptions and queue overflow reset the decoding context. Classic remains the default.

## Runtime

- Fully offline. Model and metadata are bundled in `app/src/main/assets/deepcw/`.
- Same played PCM input for USB, WebSDR and OpenWebRX; audio output is unaffected.
- ONNX Runtime Android 1.31.1, CPU, two intra-op threads and one inter-op thread.
- Lazy model initialization on the existing bounded CW worker. Model is released when decoding/source is disabled or Classic is selected, reused with a cleared context when tuning.
- Mono/stereo PCM is converted continuously to 3200 Hz with phase preserved between blocks (linear interpolation, as in the upstream minimal example).
- Periodic 256-point Hann, reflected edges, hop 48, 65 magnitude bins from 400 to 1200 Hz, reference q90 noise normalization and log1p.
- Inference starts after 2 seconds and updates once per second, retaining about 12 seconds of context, aligned to the 96-sample output stride.
- Greedy CTC with an approximately 2-second uncommitted tail. Pending text can change; final history is bounded to 512 characters. This initial integration does not implement upstream multi-channel decoding or weighted posterior stitching.
- WPM, tone and character confidence from Classic are not invented for the neural backend.
- Failures are displayed in the CW card; audio continues. Switching backend or restarting decode retries initialization.

## Model provenance

Source: https://github.com/e04/deepcw-engine
Revision: `79104fbe1ea0abd09eb27c6ebf9c6bab364d0d7f`.
Model SHA-256: `cd5ee38106348ad86a8f2d40ef1ce7dccea0d7f7fbfd0f2dee0fd1a8a28773ae`.
Model size: 15,054,698 bytes. License: MIT, included alongside the model.

The full model is used because it accepts ordinary CW tones across 400-1200 Hz; the upstream narrow model expects a tone near 800 Hz and requires frequency translation.

## Verification

`DeepCwAndroidTest` runs the actual bundled model on generated CQ/TEST/DE PCM at USB and online input rates, across multiple inference windows and buffer rollover. It checks exact text, reset/silence and that inference keeps up with real time. `CwAndroidTest` retains the Classic regression check. Real weak-signal performance must be evaluated on received audio; synthetic accuracy is not evidence of superiority to Classic.
