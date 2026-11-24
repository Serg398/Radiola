# Experimental FT8 JS

Import [ft8.radiolaplugin](ft8.radiolaplugin) in Radiola and select it in the receiver. Tune to an FT8 frequency and press **START**. The plugin selects USB through the receiver API, requests PCM and waits for the next complete 15-second slot. Its displayed UTC time is estimated from the start of the incoming PCM stream. Server and audio buffering can shift this estimate.

The decoder is [ft8ts](https://github.com/e04/ft8ts/tree/c4d1c10e65f6213f938e4781fec66232286ed569), under GPL-3.0. The browser worker is bundled only in the optional plugin package. To rebuild, check out that revision, run `npm ci` and `npm run build` in the ft8ts checkout, then run `python plugins/ft8/build.py PATH_TO_FT8TS` from the Radiola repository root. The package includes the upstream license.
