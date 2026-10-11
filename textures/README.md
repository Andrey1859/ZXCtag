# ZXC Textures

Клиентский **Fabric-мод для Minecraft 1.21.1**: заменяет выбранные символы в любом тексте игры
(ник, префикс, чат, Tab, таблички) на картинки.

В моде нет кода: внутри только шрифт и PNG. Ставится игрокам, на сервер ничего класть не нужно.

## Куда что класть

| Что | Куда |
| --- | --- |
| Картинки (PNG) | `src/main/resources/assets/zxctextures/textures/font/` |
| Привязка символ → картинка | `src/main/resources/assets/minecraft/font/default.json` |

## Как привязать картинку к символу

Каждый символ — один блок в `default.json`:

```json
{
  "_symbol": "奤  U+5964",
  "_note": "ВПИШИ ИМЯ КАРТИНКИ В file -> zxctextures:font/ИМЯ.png",
  "type": "bitmap",
  "chars": ["\u5964"],
  "file": "",
  "height": 8,
  "ascent": 7
}
```

1. Положи PNG в папку для картинок.
2. В поле `file` впиши `zxctextures:font/ИМЯ.png`, например `"file": "zxctextures:font/us.png"`.
3. Пересобери мод.

Правила для имени файла: только латиница в нижнем регистре, цифры, `_`, `-` и расширение `.png`.

Пока `file` пустой, символ остаётся обычным, а в логе игры будет предупреждение
(`Failed to load builder`). Это нормально.

Поля `_symbol` и `_note` игра игнорирует, они только для подсказки.

Размер символа: `height` — высота картинки в игре (8 = как высота букв), `ascent` — подъём над строкой
(не больше `height`). Картинка любого размера до 256×256, лучше квадратная (16×16, 32×32).

## Что нужно игроку

- Fabric Loader для 1.21.1
- Fabric API
- сам мод `zxctextures-1.0.0.jar` в папку `mods`

## Сборка

В папке `textures`:

```
./gradlew build
```

(Windows PowerShell: `.\gradlew.bat build`)

Готовый файл: `build/libs/zxctextures-1.0.0.jar`
