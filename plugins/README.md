# Плагины Радиолы

У каждого плагина своя папка. Внутри лежат исходники в `src/` и готовый пакет для импорта.

| Плагин | Исходники | Пакет |
|---|---|---|
| JS Gain | [gain/src](gain/src/) | [gain.radiolaplugin](gain/gain.radiolaplugin) |
| Audio Spectrum | [audio-spectrum/src](audio-spectrum/src/) | [audio-spectrum.radiolaplugin](audio-spectrum/audio-spectrum.radiolaplugin) |
| FT8 JS | [ft8/src](ft8/src/) | [ft8.radiolaplugin](ft8/ft8.radiolaplugin) |
| Tube S-Meter | [tube-meter/src](tube-meter/src/) | [tube-meter.radiolaplugin](tube-meter/tube-meter.radiolaplugin) |

Скопируйте пакет на телефон, импортируйте через **Plugins → Import plugin**, затем выберите плагин в меню **PLUGINS** приёмника. Повторный импорт пакета с тем же `id` обновляет плагин.

Для сборки JS Gain, Audio Spectrum и Tube S-Meter запустите `python plugins/build.py` из корня проекта. Можно указать один плагин, например `python plugins/build.py tube-meter`. Для FT8 JS используйте [инструкцию в папке плагина](ft8/README.md).

## Формат v1

Пакет представляет собой ZIP с файлами в корне.

- `manifest.json` задаёт `id`, `name`, `version`, `apiVersion: 1`, `input: "PCM"`, `output` и необязательное `initialSize`.
- `sources` перечисляет источники, для которых плагин виден в меню приёмника: `rtl-sdr`, `websdr`, `openwebrx`. Для старых пакетов без поля доступны оба сетевых источника, а для `output: "CARD"` также RTL-SDR. Выбор плагина не меняет режим демодуляции.
- `process.js` выполняется в Rhino. Для `output: "PCM"` функция `processPcm(samples, gain)` меняет отсчёты на месте. Для `output: "CARD"` функция `analyzePcm(samples)` возвращает 257 значений для окна из 512 отсчётов.
- `index.html` задаёт содержимое карточки. Опциональные файлы интерфейса — `common.css`, `api.js` и `view.js`. START/STOP находится в шапке «Радиолы». Плагин следит за полем `playing` и запускает или останавливает свою обработку по его изменению.
- Необязательное `startMode` задаёт модуляцию, которую «Радиола» запрашивает перед запуском. Например, FT8 JS указывает `"startMode": "USB"`.

### Поток PCM в Web Worker

Пакет с `runtime: "web-worker"` и `output: "CARD"` содержит `worker.js`. Страница получает через тот же порт сообщения с `type: "pcm"`, когда плагин запущен. Поле `samples` содержит монофонический PCM16 little-endian в Base64, `rate` — частоту дискретизации, `epoch` — идентификатор непрерывного участка, `index` — индекс первого отсчёта, а `startMs` — оценку UTC для отсчёта с индексом 0. Если индекс перескочил или сменился `epoch`, накопленное окно нужно сбросить. Страница передаёт блоки в свой Web Worker.

Пример живого декодера описан в [FT8 JS](ft8/README.md).

### IQ настроенного канала

Для получения IQ укажите в манифесте `"input": "CHANNEL_IQ"`, `"output": "CARD"`, `"runtime": "web-worker"` и `"sources": ["rtl-sdr"]`. Поток доступен во время приёма с RTL-SDR после команды `start`. Он снимается до фильтра выбранной модуляции, поэтому плагин может самостоятельно фильтровать и анализировать сигнал.

Через порт приходят сообщения с `type: "channelIq"`:

- `samples` содержит пары `I, Q` в Base64, формат `s16le`. Один блок содержит 4096 комплексных отсчётов. Для перевода компоненты в диапазон около ±1 делите её на 32768.
- `rate` — комплексных отсчётов в секунду. Текущий USB-тракт выдаёт 240000.
- `frequencyHz` — частота настройки, соответствующая 0 Гц в этом потоке.
- `bandwidthHz` — текущая ширина полосы в «Радиоле». `passbandOffsetHz` — смещение центра этой полосы относительно частоты настройки.
- `epoch` и `index` обозначают непрерывный участок и индекс первого комплексного отсчёта блока. Смена участка или скачок индекса означает разрыв. Очередь ограничена, при отставании чтения возможен пропуск блоков.
- `timestampNs` — приблизительное монотонное время USB-колбэка, в котором начался блок.

Страница плагина может декодировать Base64 и передать буфер в свой Web Worker:

```js
if (message.type === "channelIq") {
  const bytes = Uint8Array.from(atob(message.samples), c => c.charCodeAt(0));
  const { rate, frequencyHz, epoch, index } = message;
  worker.postMessage({ bytes, rate, frequencyHz, epoch, index }, [bytes.buffer]);
}
```

### Управление приёмником

Открытая карточка может отправлять команды через порт `radiola-plugin` даже до запуска обработки.

```js
port.postMessage(JSON.stringify({
  type: "setFrequency", value: 7074000, requestId: "tune-1"
}));
port.postMessage(JSON.stringify({
  type: "setMode", value: "USB", requestId: "mode-1"
}));
```

Частота задаётся целым числом в герцах. Для USB-донгла и WebSDR в `setMode` используются названия режимов приёмника, например `AM`, `USB`, `LSB`, `CW` и `NFM` (`FM` также принимается). Для OpenWebRX можно передать идентификатор режима сервера. Радиола проверяет диапазон и доступность режима. Для сетевого источника нужно сначала подключиться.

С `requestId` карточка получит ответ вида `{type: "receiverResult", requestId: "tune-1", ok: true}`. При отклонении `ok` будет `false`, а поле `error` объяснит причину. Успешный ответ означает, что Радиола приняла команду, а выполнение сервером происходит асинхронно.
