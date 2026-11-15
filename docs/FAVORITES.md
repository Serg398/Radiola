# Favorites

The sidebar opens Favorites between Receiver and Settings without stopping reception.
The star above MHz adds the current station; a filled star edits a matching saved station.
The compact editor shows the frequency in its header and a Name label above the field, with the frequency as a muted placeholder. Saving an empty name uses that frequency; typing starts with an empty field.
Holding a filled star for one second deletes the matching station with haptic feedback; a shorter hold opens the editor.
The table supports source filters, rename and confirmed deletion through a row action menu.
Columns: Name, Frequency, Mode, BW, Source, Server / Band. Name, Frequency and Mode headers toggle ascending/descending sorting.
The header stays fixed during vertical scrolling; narrow screens share horizontal scrolling for header and rows.

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
