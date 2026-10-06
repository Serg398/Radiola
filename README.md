# Радиола ? SDR для Android

Приложение SDR приёмника для Android с подключением RTL-SDR Blog V4 по USB OTG.
Интерфейс написан на Kotlin и Jetpack Compose, драйвер и обработка сигнала — на C++ через Android NDK.

![Интерфейс приёмника](docs/images/receiver.png)

## Что работает

- Встроенный драйвер RTL-SDR Blog V4: внешний SDR Driver не требуется.
- Непрерывный поток IQ 2,4 MSPS.
- WFM и AM с монофоническим звуком 48 кГц, переключение во время приёма.
- Спектр с частотой обновления 30 кадров/с и водопад с выбором 30 или 60 кадров/с.
- Отображение полосы приёма на спектре и водопаде: WFM 240 кГц, AM 12 кГц.
- Вертикальный валкодер с инерцией, ручным шагом 1 кГц–1 МГц и режимом AUTO до 500 кГц.
- RF AGC, ручное усиление тюнера и AUDIO AGC для AM и FM.
- Сохранение настроек усиления, шага настройки и частоты обновления водопада.
- Ландшафтный интерфейс, скрытые системные панели и включённый экран во время работы.

USB, LSB, CW, избранное и запись пока не реализованы. Масштаб спектра и улучшение водопада находятся в планах.

## Сборка

Откройте корень проекта в Android Studio и выполните синхронизацию Gradle.
Нужны Android SDK Platform 37, NDK `28.2.13676358` и CMake.
Для запуска используйте JDK из установленной Android Studio. Gradle daemon настроен на JVM 25
в `gradle/gradle-daemon-jvm.properties`; Gradle может загрузить её автоматически.
Gradle Wrapper включён в проект.

В Windows:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

В Linux и macOS:

```sh
./gradlew :app:assembleDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`.
Минимальная версия Android — 8.0 (API 26). Для приёма нужен телефон с USB Host и донгл через OTG.
После подключения предоставьте приложению разрешение на доступ к USB и нажмите START.

## Структура

- `app/src/main/java/com/example/sdr` — интерфейс, сессия приёмника, настройки и вывод звука.
- `app/src/main/cpp` — JNI, демодуляторы WFM/AM и AUDIO AGC.
- `app/src/main/cpp/third_party` — исходники RTL-SDR Blog и libusb.
- `app/src/test/cpp` — тесты AM демодуляции и AUDIO AGC на синтетических сигналах.

## Встроенные библиотеки

Версии, изменения и лицензии зависимостей перечислены в [THIRD_PARTY.md](THIRD_PARTY.md).
Тексты лицензий библиотек сохранены рядом с их исходниками.
