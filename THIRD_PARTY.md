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
- **libusb** — v1.0.30 из <https://github.com/libusb/libusb>.
  Лицензия LGPL-2.1-or-later; текст лицензии:
  `app/src/main/cpp/third_party/libusb/COPYING`.

Драйвер RTL-SDR Blog V4 и libusb собираются из исходников вместе с приложением.
При распространении приложения необходимо соблюдать условия лицензий этих библиотек.
