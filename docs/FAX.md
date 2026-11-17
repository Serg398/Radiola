# Local WEFAX / Radiofax decoder

Select **LOCAL → FAX**. The receiver uses USB with a 3 kHz passband; audio is
decoded locally for USB, WebSDR and OpenWebRX. The top-level layout is unchanged.
This is separate from server-side SSTV/FAX decoding.

## Receiving

- Default: 120 lines/minute, IOC 576 (1809 pixels). The format menu also offers
  60/90/240 LPM and IOC 288 (904 pixels).
- Subcarrier center 1900 Hz, black 1500 Hz, white 2300 Hz. Tune USB so the
  received audio lies at those frequencies. A station's listed RF carrier and
  the required USB dial frequency are not necessarily the same.
- DECODE starts receiving immediately, including in the middle of a transmission.
  It cannot recover lines transmitted before reception began. No separate START
  button is required. Decoding is off initially.
- DECODE off pauses processing and preserves the image. Re-enabling starts a
  new image immediately. CLEAR resets the image and continues if DECODE is on.
- Automatic start/phasing/stop detection remains active during reception. The
  300 Hz start and 450 Hz stop patterns must persist across two half-second
  analysis windows. Manual alignment/slant correction controls are omitted.
- The image grows from the top at a fixed width-based scale, on the card's
  background with a thin border. Drag to review long images; it does not shrink
  or recenter as new rows arrive.
- When the viewport fills, it follows new rows automatically. Dragging toward
  earlier rows pauses following; scrolling back to the bottom resumes it.
- The expand button at the image's top right opens an animated viewer within the spectrum/waterfall/card
  area. Sidebar, tuning controls and encoder remain visible. Pinch to zoom and
  drag to move; CLOSE or Android Back returns to the receiver.
- SAVE exports the currently published image as PNG through Android's document
  picker from the small card. The expanded viewer has no SAVE button. No storage
  permission or automatic external upload is required.

## Runtime

The PCM producers copy into a bounded 64-block queue. A dedicated FaxDecoder
thread continuously resamples to 8 kHz, mixes the audio subcarrier to complex
baseband, applies three low-pass stages and computes phase differences for FM
discrimination. Gray levels are averaged into pixels on the selected line clock.
Automatic phasing locates the short black pulse in otherwise white scan lines.

The renderer publishes immutable 64-row bitmap tiles; completed tiles are
reused, without retaining raw rows. Reception is bounded to 2500
rows, after which it stops with an explicit status. Frequency/source changes
reset decoding, stopping reception preserves the last picture, and queue
overflow stops with `Audio gap — restart Decode` instead of silently shifting lines.

FAX does not use a neural model or add an audio playback buffer. It receives
the same played raw PCM used by CW, independently of audible noise reduction.

## Verification and limitations

- `FaxDecoderTest`: black/white polarity, scan-line timing at 8/12/48 kHz,
  stereo input and arbitrary PCM boundaries; automatic start/phasing/stop;
  alternate line rate and IOC width.
- `FaxAndroidTest`: immediate DECODE without a header, immutable bitmap
  publication, pause/stop and frequency-reset lifecycle on the phone.
- Live WebSDR UI was checked on the connected phone. The user's transmission
  had already ended, so a recognizable live fax image has not yet been verified.
- First version: grayscale HF WEFAX with an 800 Hz black-to-white shift. Color,
  alternative shifts, automatic fine frequency correction and reconstruction
  across missing network audio are not implemented.

Format reference: https://www.w1hkj.org/FldigiHelp/wefax_page.html
DSP implementation in this project is original; no fldigi source is embedded.
