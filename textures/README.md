# ZXC Textures

Клиентский **Fabric-мод для Minecraft 1.21.1**: заменяет выбранные символы в любом тексте игры
(ник, префикс, чат, Tab, таблички) на картинки.

В моде нет кода: внутри только шрифт и PNG. Ставится игрокам, на сервер ничего класть не нужно.

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

## Какие символы заменяются

| Символ | Код | Картинка |
| --- | --- | --- |

## Как добавляется символ

1. PNG кладётся в `src/main/resources/assets/zxctextures/textures/font/uXXXX.png`
   (`XXXX` — код символа).
2. В `src/main/resources/assets/minecraft/font/default.json` добавляется запись `bitmap`
   с этим символом.
3. Символ вносится в таблицу выше.
