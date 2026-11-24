# Рывки USB-водопада при 30 FPS

Проверено 25 ноября 2026 года на OnePlus 15R, RTL-SDR Blog V4, WFM 102,199 МГц.

Средние 30 FPS скрывали неравномерные интервалы вывода. FFT был готов примерно каждые 34 мс, однако главный поток иногда задерживал получение до 13 мс. При публикации сразу после готовности буфер периодически пропускал ближайший VSync. SurfaceFlinger подтверждал чередование интервалов 16 и 50 мс, с редкими паузами около 66 мс.

Отключение S-метра, уменьшение захвата с 2,88 до 2,4 МГц, предварительная ART-компиляция, оптимизация контуров спектра, изоляция состояния S-метра и отдельная SurfaceView сами по себе рывки не устранили.

Рабочая связка для USB:

- Нативный FFT будит ожидающий фоновый поток через condition_variable.
- Один SpectrumFrame используется для спектра и водопада.
- Публикация идёт с темпом 30 Гц по Choreographer/VSync с начальным запасом в один кадр.
- Очередь ограничена тремя кадрами. При остановке и уходе Activity с экрана callback и очередь очищаются.
- Водопад использует отдельную SurfaceView, обновляемую из истории на главном потоке.

В сопоставимых 12-секундных записях интервалы обновления слоя композитором изменились: p95 с 49,86 до 33,50 мс, p99 с 50,04 до 34,04 мс, максимум с 66,52 до 50,16 мс. Пауз от 45 мс стало 4 вместо 37. Средний возраст отображаемого FFT около 40 мс. Редкие рывки остаются.

Это измерения слоя SurfaceFlinger, а не только счётчика FPS. Для повторной проверки нужны одинаковые источник, частота, режим, масштаб и прогрев. WebSDR и OpenWebRX этой правкой не переводятся на новый график публикации.

## Current implementation after rollback

The experimental combination described above was reverted. Only USB visual publication through Choreographer at 30 Hz remains, with one frame of initial headroom and a queue capped at three frames. FFT acquisition is checked every VSync. JNI, DSP and the ordinary waterfall View match HEAD. Earlier measurements describe the reverted experiment and do not establish the results of this simplified implementation.

VSync-only verification: debug build, 109 unit tests and Lint passed. Installed on OnePlus 15R with USB at the committed 2.88 MHz span. A 12-second trace matched 359 displayed waterfall frames: median 33.03 ms, p95 46.79 ms, p99 51.07 ms, max 54.94 ms. Publication is paced, but ordinary View/Compose presentation still misses some deadlines. This does not establish that the remaining stutters are fixed.

## VSync plus separate USB surface

Added an optional SurfaceView inside WaterfallView for USB only. History publication notifies the attached view directly on the main thread; remote sources retain their ordinary View path. The listener is removed on detach. RF-coordinate rendering, overlays and gestures remain unchanged.

Debug build, 109 tests and Lint passed. Screenshot confirmed waterfall and passband overlays. Background/foreground restored USB reception at 30 FPS without AndroidRuntime errors.

The first 12-second trace including receiver startup had 321 surface buffer updates, p95 49.60 ms and max 66.84 ms. After warm-up, a second trace had 359 updates: median 33.17 ms, p95 33.49 ms, p99 35.19 ms, max 49.86 ms, four gaps of at least 45 ms. Warm-up matters. The earlier ordinary-View measurement used frame presentation timestamps, while this surface measurement uses SurfaceFlinger setBuffer timestamps; the comparison supports improved steady pacing but is not an identical measurement of final display presentation. Rare pauses remain.
