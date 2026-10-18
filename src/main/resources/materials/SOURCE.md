# Vanilla material labels

Only plain `block.minecraft.*` and `item.minecraft.*` display-name entries are bundled from the official Minecraft Java 1.21.11 language assets. They are resolved locally before the shared dialog table measures the text; no runtime network access is needed.

- English: `assets/minecraft/lang/en_us.json` from client JAR SHA-1 `ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd`.
- Russian: `minecraft/lang/ru_ru.json`, asset SHA-1 `5cdd2a7d6d5c2c5753065f56246024e706b5d8ca`.

The selected language follows ArcCivs force-language / client-language / default-locale policy. Unknown labels retain the localized “unknown material” fallback rather than exposing a technical material identifier.
