# AGENTS.md

## Project overview

- This is a single-module Android app (`:app`) for monitoring Wi-Fi and mobile traffic.
- The UI is written in Kotlin with Jetpack Compose and Material 3.
- The app also provides home-screen widgets, traffic-limit alerts, and a foreground service that shows current network speed in an ongoing notification.
- Package and application ID: `com.adam.app_monitoring`.
- Minimum SDK: 28. Target and compile SDK: 36 (minor API level 1).
- User-facing copy is currently Russian. Keep new UI text consistent with the surrounding Russian copy unless the task explicitly requests localization.

## Important code areas

- `app/src/main/java/com/adam/app_monitoring/ui/` — Compose UI and `TrafficViewModel`.
- `app/src/main/java/com/adam/app_monitoring/data/` — traffic collection, settings, permissions, persistence, and repositories.
- `app/src/main/java/com/adam/app_monitoring/background/` — foreground speed service, notifications, restart logic, and background work.
- `app/src/main/java/com/adam/app_monitoring/widget/` — widget providers, rendering, and refresh work.
- `app/src/main/java/com/adam/app_monitoring/core/` — models and pure formatting/time/calculation utilities.
- `app/src/test/` — local JVM unit tests.
- `app/src/androidTest/` — device/emulator tests.
- `gradle/libs.versions.toml` — centralized dependency and plugin versions.

## Working rules

- Inspect the relevant implementation and tests before editing. Preserve unrelated user changes in a dirty worktree.
- Prefer small, focused changes that follow the existing package structure and Kotlin style.
- Keep calculations and decision logic in pure functions where practical, and add JVM tests for them.
- Do not perform network, database, package-manager, or long-running work on the main thread.
- Preserve Android version guards and permission checks. Pay particular attention to notification permission (Android 13+), exact alarms (Android 12+), and foreground-service requirements.
- Treat OEM-specific behavior as an explicit compatibility layer. Isolate checks based on `Build.MANUFACTURER`, `Build.BRAND`, `Build.MODEL`, or SDK level; provide a safe default path; and add unit tests for selection logic.
- For notifications, prefer system templates. Custom `RemoteViews` must remain readable under system tinting, different themes, display densities, Android versions, and OEM System UI implementations.
- Notification channel behavior is persistent after channel creation and remains under user control. Do not assume that changing importance, sound, or vibration in code will update an existing channel.
- Small notification icons must remain monochrome/mask-compatible. Verify dynamic status-bar icons on a physical device because emulator rendering is not representative of every OEM.
- Put user-visible text in resources when adding reusable or localized copy. Do not introduce new hard-coded UI strings without a task-specific reason.
- Do not edit generated output under `build/`, `.gradle/`, or `app/build/`.

## Build and verification

Run commands from the repository root on Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

For UI, notification, foreground-service, widget, permission, boot, or OEM-specific changes, also test the affected flow on an emulator or physical device. When a device is connected, useful checks include:

```powershell
adb shell am force-stop com.adam.app_monitoring
adb logcat -d -t 1000 AndroidRuntime:E System.err:W *:S
```

There is no configured lint/format task specific to this repository; do not claim lint coverage unless an appropriate task was actually run.

## Definition of done

- The requested behavior is implemented without unrelated rewrites.
- Relevant unit tests are added or updated and pass.
- `assembleDebug` passes for production-code or resource changes.
- Android/OEM limitations and any device-only verification still needed are called out clearly.
- Changed files are reviewed with `git diff`; generated files and secrets are not included.
