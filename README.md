# myfrnd

A native Android companion for gentle eye breaks, hydration nudges, and personal reminders, shown by a real-time 3D human. Kotlin + Android Views; Android 8.0 (API 26) and newer. No account, backend, ads, analytics, microphone, or network permission.

## 3D human companion

- The dashboard hero and the on-screen overlay both draw the bundled 3D human (`app/src/main/assets/models/companion.glb`) with [Filament](https://github.com/google/filament) 1.75.1 (PBR renderer + glTF loader) into a transparent `TextureView`.
- The model is built from the source scan (`monochrome-moment.zip`, not committed) by a reproducible Node pipeline: 22.8 MB to 3.2 MB, auto-rigged with Hips/Spine/Chest/Head joints. See [tools/companion-model](tools/companion-model/README.md) to rebuild it or swap in another model.
- The model has no animation clips, so motion is procedural (in `HumanCompanionView`, with joints found by `HumanRig`): breathing, small weight shifts, nods while talking, a nod and head tilt when tapped, and mood poses such as leaning back to look into the distance for an eye break. Mood effects (sparkles, water drops, eye glow, sound waves) are drawn on top. If a model with named clips (idle, wave, talk...) is used instead, `AnimationDirector` plays and cross-fades those clips.
- One shared, reference-counted Filament engine (`CompanionEngine`) serves the dashboard and the overlay. It is released 20 s after the last user goes away.
- The overlay window stays fully transparent and non-touchable until the body has drawn 3 real frames, then fades in with its speech bubble. With **Reduce motion** it uses a short fade only, without movement.
- Drag the dashboard companion sideways to turn him; tap him to cycle moods.

## What changed

- A responsive, system-inset-aware dashboard with light/dark palettes, live countdowns, larger controls, accessible selection states, and a working tappable companion.
- Explicit opt-in, persistent Pause (including the notification action), contextual permission guidance, and status based on the actual service rather than permissions alone.
- Deadline-based scheduling instead of an eight-second polling loop. Water and eye intervals use monotonic elapsed time. Returning to the app, changing the entrance, or changing the other interval does not reset them.
- Due tasks are shown sequentially, not all consumed before displaying the first one. A task is marked delivered only after its window attaches. Overdue tasks stay visible until shown or deleted.
- Validated future dates, editable reminders, deletion confirmation, rotation-safe drafts, and explicit errors that preserve unreadable saved data.
- Optional offline speech, off by default; reduced motion; lifecycle-gated rendering; cancellable view animations instead of per-frame window-layout IPC; no forced garbage collection on dismissal.
- Compatible API 36 build tooling, private service entry points, release signing from environment variables or `~/.gradle/gradle.properties` only (no passwords in the build file), automated tests, and CI.
- Release builds use R8 code and resource shrinking. The old 2D canvas character and its WebP artwork were replaced by the 3D human above. The release APK is about 19.9 MB, mostly Filament native code for three ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`; 32-bit x86 is skipped). All native libraries are 16 KB page-aligned.
- The notifications button asks for permission on the first tap instead of jumping straight to Settings, and its label says what it will do (**Allow notifications** or **Open notification settings**).
- Reminder time chips: **In 15 min**, **In 1 hour** (selected by default), **Next 8 PM** and **Tomorrow 9 AM**. The selected chip is highlighted and announced, and the times are whole minutes, so the saved time matches the time shown. Chip rows that run off screen fade at the edge, and the overlay card says which switch to turn on in Settings.

This is a deterministic wellness utility, not an AGI or medical device. No unmeasured "100x faster" claim is made.

## Build and run

Use Android Studio Meerkat or newer, JDK 17 (or Android Studio's JDK 21), Android SDK platform 36, and build tools 35.0.0 or newer. The wrapper pins Gradle 9.3.0; AGP is 8.10.1, Kotlin 2.0.21.

On Windows PowerShell, from the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\sdk"
.\gradlew.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Alternatively, open this folder in Android Studio and run the `app` configuration. `local.properties` is machine-specific and ignored.

1. Open myfrnd and allow **Display over other apps**.
2. Turn on **Enable companion**. Notification permission is optional but makes Pause easy to reach.
3. Use **Preview on my screen**, choose intervals, and add reminders.
4. Voice is optional and requires an installed offline voice matching the device language. Text reminders still work without it.

## Timing and privacy contract

- Reminders are intentionally deferred while the screen is off or locked. No wake lock or exact alarm is requested.
- Locking resets continuous eye-use time, but does not discard pending tasks or the water deadline. Eye reminders repeat at the selected interval while unlocked.
- Tasks take priority over wellness reminders. Simultaneous tasks display one after another; the display timeout is 7-18 seconds depending on text length, or tap to dismiss.
- Pause disables all automatic reminders and survives boot. Enabled state and tasks persist locally; interval countdowns restart after process/service recreation.
- Android/OEM process limits, Doze, force-stop, and foreground-service restrictions can delay delivery. These are **not exact alarms**, and should not be used for medication, emergency, or other time-critical alerts.
- "Delivered" means the overlay attached, not that the user completed the task. This is not a transactional exactly-once guarantee across abrupt device/process failure.
- No reminders are spoken on the lock screen. Speech uses an installed voice reported by Android as not requiring a network connection; the chosen system speech engine remains a separate component.
- Existing preference and task storage keys are preserved. Existing enabled-state choices are respected; new installs start paused. Speech now defaults to off.

## Verification

See the [verification report](docs/VERIFICATION.md) for **63 passing tests** (45 unit, 16 device, 2 model-pipeline), packaging checks, measured release-to-release comparisons (cold start, installed size, overlay memory and stalls) with the method used, the APK checksum, and explicit release limitations. Emulator frame timing was too noisy to support any scroll-smoothness claim, and none is made.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# With a booted emulator or attached test device:
$env:ANDROID_SERIAL = 'emulator-5580'
.\gradlew.bat :app:connectedDebugAndroidTest
# Release packaging (unsigned unless all signing variables below are supplied):
.\gradlew.bat :app:assembleRelease :app:bundleRelease
```

Local reports: [unit tests](app/build/reports/tests/testDebugUnitTest/index.html), [device tests](app/build/reports/androidTests/connected/debug/index.html), [lint](app/build/reports/lint-results-debug.html).

The [CI workflow](.github/workflows/android.yml) builds the debug and (unsigned, R8-shrunk) release variants, unit-tests, lints, and uploads debug APK/reports. Device tests are run separately on a test emulator; CI does not claim a device run.

## Release checklist

- Set all four environment variables: `MYFRND_KEYSTORE` (absolute path), `MYFRND_STORE_PASSWORD`, `MYFRND_KEY_ALIAS`, `MYFRND_KEY_PASSWORD`. None should be committed.
- The original revision contained signing passwords. Treat those credentials as exposed; the signing-key owner must rotate/reset them as appropriate for Play App Signing. This upgrade does not replace your signing identity.
- Increment `versionCode`/`versionName` for an actual store release; this working copy preserves the existing release identity.
- Validate on physical low-end devices, API 26, OEM battery-management variants, TalkBack, large text, landscape, and an installed offline TTS voice.
- Test the 3D companion on real phones, especially low-end ones. On the emulator the first frame after a cold start can take 5-14 s because the emulator does not cache compiled GPU shaders; phones normally cache them after the first run, but this is not yet measured. Keep each release's `mapping.txt` (the AAB embeds it for Play Console) so R8-shrunk crash traces can be read.
- Confirm you have the rights to ship the 3D human model in a store build.
- The six store screenshots in `play_store_assets/` are real captures of the release build. The 512 px store icon and the feature graphic were not changed and show different artwork from the launcher icon and the in-app companion; choose one brand identity before publishing. Review the special-use foreground-service declaration and Data Safety answers, sign with your own key, and obtain Play review. No store approval or deployment has been performed. The work is on the branch `upgrade/production-hardening-20261107` and has not been merged into `main`.

## Research and implementation plan

1. **Baseline:** inspect UI, timers, storage, lifecycle, build, and permissions; identify reproducible regressions before adding features.
2. **Reliability:** separate elapsed-time scheduling from calendar tasks; acknowledge one displayed task at a time; make pause and permission behavior consistent.
3. **Experience:** preserve the native product, add live status, accessible controls, theme-aware resources, editing, privacy controls, and animation lifecycle cleanup.
4. **Evidence:** run scheduler/storage regressions, Android UI tests, lint, packaging, native-library alignment checks, and visual/device checks; document remaining release gates rather than claiming production readiness.

Primary sources:

- [AGP 8.10 compatibility: API 36, Gradle 8.11.1, JDK 17](https://developer.android.com/build/releases/agp-8-10-0-release-notes)
- [Android edge-to-edge and system insets](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
- [Foreground service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android accessibility: contrast and 48 dp touch targets](https://developer.android.com/guide/topics/ui/accessibility/apps)
- [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
