# CW regression capture

`websdr-3544khz-8000.pcm.gz` contains 12 seconds of mono signed PCM16,
little endian, 8000 Hz. Recorded on 2026-10-21 from the public Tambov
WebSDR (`https://80m.radiorubka.org/`), tuned to 3.544 MHz in CW,
500 Hz bandwidth and 750 Hz BFO. No server JavaScript is included.

The noisy transmission includes the standard `RST` report prefix.
Independent envelope measurements in the longer recording showed dot lengths
around 55–75 ms and dashes around 165–195 ms. The original detector filled
pauses with noise, produced many unknown combinations, and drifted toward
8 WPM. This fixture checks recognition of the report prefix and guards against
timing collapse; it is not a verified transcript of the complete transmission.
