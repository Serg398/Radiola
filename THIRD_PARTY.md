# Встроенные библиотеки

- **RTL-SDR Blog driver** — commit `aed0ea19f3a273370a13c9009b96313c75d54c7b` из
  <https://github.com/rtlsdrblog/rtl-sdr-blog>. Лицензия GPL-2.0-or-later;
  текст лицензии: `app/src/main/cpp/third_party/rtl_sdr_blog/COPYING`.
  В `librtlsdr.c` и `rtl-sdr.h` добавлена функция `rtlsdr_open_fd`, которая
  открывает USB-дескриптор, переданный Android через JNI.
  В `tuner_r82xx.c` оптимизирована перестройка: для V4/V4L на КВ обход tracking-фильтра
  сохраняется при настройке PLL; неизменившиеся параметры RF-тракта и VGA не записываются
  повторно. Последовательность настройки и проверки PLL сохранена. Кэш регистров
  обновляется только после успешной записи I²C. Проверка с моделью шины:
  `app/src/test/cpp/hf_tuning_test.c`.
  Ручное усиление R82xx применяется сразу вместе с флагами ручного режима,
  без промежуточного нулевого gain. Неизменившиеся параметры усиления не
  записываются повторно; настройка VGA сохраняется при смене частоты в
  соответствии с выбранным ручным или автоматическим режимом.
- **libusb** — v1.0.30 из <https://github.com/libusb/libusb>.
  Лицензия LGPL-2.1-or-later; текст лицензии:
  `app/src/main/cpp/third_party/libusb/COPYING`.

- **RDS basic alphabet table** — таблица G0 из `src/text/rdsstring.cc`
  проекта [windytan/redsea](https://github.com/windytan/redsea).
  Copyright (c) Oona Räisänen; разрешение на использование и полный текст
  уведомления сохранены в `app/src/main/java/com/example/sdr/RdsCharset.kt`.
  Таблица преобразована в Kotlin `CharArray`; остальной код redsea не встроен.

## Карта WebSDR

- Контуры суши: Natural Earth, `ne_110m_land.geojson` из
  <https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_110m_land.geojson>.
  Public domain: <https://www.naturalearthdata.com/about/terms-of-use/>.
  Файл хранится в `app/src/main/assets/map/land.geojson`; карта рисуется собственным Canvas.
- Названия и координаты городов: Natural Earth `ne_110m_populated_places_simple.geojson`
  из того же репозитория, public domain. В `app/src/main/assets/map/cities.json`
  сохранены названия, координаты, население и уровень появления подписей.
- Список серверов загружается из каталога WebSDR:
  <http://websdr.ewi.utwente.nl/~~websdrlistk?v=1&fmt=2&chseq=0>.
  Сохраняется последний корректный ответ. Число слушателей относится ко времени
  обновления каталога и не является проверкой доступности приёмника.
  Ответ источника содержит уведомление автора PA3FWM о предварительном разрешении
  на повторное использование данных; оно сохраняется вместе с ответом в кэше.

## Сетевой клиент

- OkHttp 4.12.0 — HTTP и WebSocket, <https://github.com/square/okhttp>.
  Apache-2.0, текст в `app/src/main/assets/licenses/okhttp.txt`.
- Okio 3.6.0 — зависимость OkHttp, <https://github.com/square/okio>.
  Apache-2.0, текст в `app/src/main/assets/licenses/okio.txt`.
- WebSDR: собственные Kotlin-декодеры бинарных аудио/водопадных сообщений.
  JavaScript сервера и браузерный движок в реализацию приёмника не включены.
  Сетевые фикстуры тестов и эталонные результаты находятся в `app/src/test/resources/websdr`.

Драйвер RTL-SDR Blog V4 и libusb собираются из исходников вместе с приложением.
При распространении приложения необходимо соблюдать условия лицензий этих библиотек.

## FT8 / FT4 decoder

- **ft8_lib**, Karlis Goba, MIT license, source commit
  `9fec6ca39886edbf96f4f5e71edc76da5074e871` from
  <https://github.com/kgoba/ft8_lib>. Unmodified DSP, message and monitor sources
  are vendored in `app/src/main/cpp/third_party/ft8_lib`.
- The bundled KISS FFT sources are copyright Mark Borgerding, BSD-3-Clause.
  Complete license texts are included beside the sources and in APK assets.
- `app/src/androidTest/assets/ft8-websdr.wav` is the upstream
  `test/wav/websdr_test1.wav` reference recording, distributed with ft8_lib.
  Its reference transcript includes `LZ1LZ G4UJS IO83`.

## Callsign country reference

- AD1C Contest Country Files #3633, released 23 October 2026, from
  <https://www.country-files.com/cty/cty.dat>. The unmodified dataset is bundled
  as `app/src/main/assets/radio/cty.dat`. The accompanying permission and copyright
  notice from <https://www.country-files.com/cty/copyright.txt> is included in
  `app/src/main/assets/licenses/country-files.txt`.
