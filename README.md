# Marble Maze

Tilt-controlled marble maze for Android — procedurally generated (6 styles),
multi-marble physics, ghosts, replays, QR sharing, custom modes, boss arenas.

## Build
    ./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
    ./gradlew test                   # JVM + Robolectric unit tests
    ./gradlew connectedDebugAndroidTest   # app-flow tests (device/emulator)

## Feature map
| Feature | Where |
|---|---|
| 6 maze generators + connectivity guarantee | Maze.kt |
| Physics + collisions (pure, tested) | Physics.kt |
| Full engine: rendering, sim, ghost, spectate, rewind, restore | GameView.kt |
| Balance knobs (single source of truth) | Tuning.kt |
| Custom modes, MM8 codes, Mode of the Week | RunMode.kt, ModeCode.kt, ModeOfWeek.kt |
| QR generation | QrCode.kt |
| Settings snapshot (hot-path safe) | GameSettings.kt |
