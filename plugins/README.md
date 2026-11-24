# Плагины Радиолы

Готовые пакеты лежат в этой папке. Для установки скопируйте `gain.radiolaplugin` или `audio-spectrum.radiolaplugin` на телефон и выберите файл через **Plugins → Import plugin**. Затем выберите плагин в меню **PLUGINS** на экране приёмника.

Исходники лежат в соседних папках. После правок запустите `python plugins/build.py` из корня проекта. Повторный импорт пакета с тем же `id` обновляет плагин.

## Формат v1

Пакет представляет собой ZIP с файлами в корне.

- `manifest.json` задаёт `id`, `name`, `version`, `apiVersion: 1`, `input: "PCM"`, `output` и необязательное `initialSize`.
- `process.js` выполняется в Rhino. Для `output: "PCM"` функция `processPcm(samples, gain)` меняет отсчёты на месте. Для `output: "CARD"` функция `analyzePcm(samples)` возвращает 257 значений для окна из 512 отсчётов.
- `index.html` задаёт содержимое карточки. Опциональные файлы интерфейса — `common.css`, `api.js` и `view.js`. Примеры используют `radiola.onState(callback)` для получения состояния и `radiola.send(type, value)` для команд `start`, `stop`, `gain`, `layout`.
