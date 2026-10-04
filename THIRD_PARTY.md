# Встроенные библиотеки

- **RTL-SDR Blog driver** — commit `aed0ea19f3a273370a13c9009b96313c75d54c7b` из
  <https://github.com/rtlsdrblog/rtl-sdr-blog>. Лицензия GPL-2.0-or-later;
  текст лицензии: `app/src/main/cpp/third_party/rtl_sdr_blog/COPYING`.
  В `librtlsdr.c` и `rtl-sdr.h` добавлена функция `rtlsdr_open_fd`, которая
  открывает USB-дескриптор, переданный Android через JNI.
- **libusb** — v1.0.30 из <https://github.com/libusb/libusb>.
  Лицензия LGPL-2.1-or-later; текст лицензии:
  `app/src/main/cpp/third_party/libusb/COPYING`.

Драйвер RTL-SDR Blog V4 и libusb собираются из исходников вместе с приложением.
При распространении приложения необходимо соблюдать условия лицензий этих библиотек.
