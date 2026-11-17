# CWformer

The CW decoder menu offers Classic, DeepCW and CWformer. The existing selection
preference also stores CWformer. DECODE, CLEAR and source/frequency changes use
the same worker lifecycle as the other backends. No layout changes are required.

## Model and features

- Upstream: https://github.com/parsimo2010/CWformer/releases/tag/v0.2.0
- Bundled INT8 ONNX model: 24,953,338 bytes, MIT license.
- SHA-256: `3f45231d4064fab08d96f0e328833128339fb0df1f8e2a883e1ca3cc3c124b4c`.
- Fully local CPU inference, two intra-op threads and one inter-op thread.
- Continuous mono conversion/resampling to 16 kHz; 500 ms audio chunks.
- 400-point periodic Hann, 160-sample hop, 40 mel bands. Original exported
  float32 filterbank/window tables are retained exactly; log(power + 1e-6).
- Initial half-window zero padding and continuous STFT overlap; no repeated
  right padding and no per-chunk gain normalization, matching upstream live input.
- Explicit position, per-layer interleaved K/V attention states, convolution
  states and subsampling buffers. Attention is capped at 250 frames (five
  seconds), matching the reference runtime default.
- Greedy CTC collapse is incremental across chunk boundaries, preserving blanks
  and word spaces. No ever-growing logits history. Transcript is capped at 512
  characters and uses the existing stable CW transcript view.
- After five seconds without new symbols following the first emission, only
  neural state is reset; transcript and STFT overlap remain intact.
- Audio playback is unaffected by model failure; the CW card displays the error.
  Disable decoding or select another backend to release the model.

## Verification

`CwFormerAndroidTest` exercises the actual bundled model with multi-chunk CW at
8, 12 and 48 kHz (including stereo), attention-cache rollover, reset and silence.
It checks exact synthetic text and that each 500 ms inference finishes within
real time. Real reception accuracy still requires comparison on recorded RF
conditions; synthetic tests are not evidence of superiority to DeepCW.

On the connected OnePlus CPH2769, the Android test passed at all three input
rates; maximum measured processing times were 56, 74 and 59 ms respectively
(including feature extraction and state copies). A short live WebSDR check
showed about 9% of one CPU core across the CW worker and its ONNX helper thread.
These are observations on this device, not performance guarantees for others.
