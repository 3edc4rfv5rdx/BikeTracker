# Device testing safety

- Run Android and instrumentation tests only on an emulator.
- Never install, launch, test, uninstall, or otherwise modify anything on a physical Android device through `adb` unless the user explicitly authorizes that exact action.
- Do not run Gradle `connected*AndroidTest` tasks while a physical device is among the connected targets.
- If an emulator is unavailable or any device action is needed, stop and ask the user first.
