# FT8 / FT4 reception

The toolbar FT8/FT4 presets select USB with a 3 kHz receive filter, retaining
frequency and source. Their lower information panel displays UTC receipt time,
audio frequency offset and decoded message. No transmission is implemented.

PCM is copied from the existing USB or WebSDR audio feed only while a digital
preset is selected. Playback and RF processing are unchanged. A bounded collector
resamples mono/stereo audio continuously to 12 kHz and retains a rolling window.
FT8 uses 15 seconds with a 2-second stride; FT4 uses 7.5 seconds with a 1-second
stride. A separate single native decoder worker avoids interrupting audio. At most
one decode runs; missed jobs are skipped rather than queued. Overlapping results
are deduplicated; history is capped at 100 lines. Tuning, mode, source and PCM gaps
invalidate stale windows/results. Timing is sample-relative, so reception does not
require the Android clock to be precisely aligned with transmitter slots.

The JNI wrapper uses ft8_lib with 2x time/frequency oversampling, 140 sync
candidates and 25 LDPC iterations. SNR is deliberately not shown: candidate sync
score is not a calibrated SNR. Unresolved hashed callsigns and unsupported message
formats remain limitations of the experimental upstream decoder. Decoder
sensitivity is not claimed to match WSJT-X.

Device tests cover independent FT8/FT4 encoder vectors, live collection plus 48 kHz stereo USB / 8 kHz mono WebSDR resampling and a real upstream WebSDR recording with a known decoded message.

## Station table

Decoded rows retain UTC receipt time, audio offset and the full message, and are
presented as a scrollable table with transmitter callsign, country/DXCC entity,
Maidenhead grid and approximate distance. The transmitter is the second callsign
in a standard addressed message, or the callsign following CQ and any qualifier.
RR73 is a completion token, not a Maidenhead locator. Free text and unresolved
hashes do not supply a reliable country or station position.

Country lookup is offline with AD1C CTY #3633: exact callsign exceptions precede
longest-prefix lookup. A country's location is never used to invent a distance.
Four/six-character Maidenhead locators resolve to cell centers; a bounded station
grid cache supplies locations for later signal-report/confirmation messages.
Distance is the great-circle distance in kilometers. WebSDR uses the selected
server coordinates from the cached directory; USB uses the user-entered RX GRID.
Unknown country, grid or receiving location is represented by a dash. Country/DXCC
is a callsign classification, not proof of the transmitter's physical position.

Rows use aligned fixed widths for time, audio frequency, callsign, locator and
kilometers, with remaining width allocated to country and message. Column headings
are omitted. Country labels have ISO regional-indicator flags; DXCC entities map
to their associated country/territory where known, otherwise a globe is shown.
Long formal country names are shortened for display without changing lookup data.
