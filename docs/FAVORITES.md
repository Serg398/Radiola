# Favorites

The sidebar opens Favorites between Receiver and Settings without stopping reception.
The star next to the frequency adds the current station; a filled star edits a matching saved station.
The list supports source filters, rename and confirmed deletion.

Stored settings: exact frequency, basic mode, optional local FT8/FT4, server mode ID,
nullable bandwidth (`null` means Default), source, server URL/name, stable band/profile ID and label.
Gain, volume, squelch and display preferences remain receiver settings.

`FavoritesRepository` stores a versioned JSON collection in private SharedPreferences.
It is separate from the receiver and Compose screens; no database dependency is needed for this small collection.
`ReceiverPresentation` owns activation in its ViewModel coroutine scope. Online activation waits
for connection and profile configuration, then applies mode, frequency and bandwidth.
Activation has a 25-second deadline and never repeatedly forces a profile on the server.
Missing bands/modes show an error while keeping the saved entry.
USB presets tune the receiver but do not implicitly start USB capture or request permission.

Device verification: persistence/rename/default bandwidth/local versus server FT8 instrumentation test;
add via star, open list, restore OpenWebRX station from another profile.

USB filter and source labels are dimmed when no dongle is connected.
Only the spectrum shows favorite labels, in its top row instead of the tuned-frequency badge.
Clicking a label opens the saved station. Nearby or coincident labels combine into a menu
so every saved entry remains accessible. All sources are included within the visible RF window.
Anchors track true frequencies during motion and zoom; the waterfall has no favorite overlay.
