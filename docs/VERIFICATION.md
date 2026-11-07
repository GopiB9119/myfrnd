# Upgrade verification

Verified locally on 2026-11-06 and 2026-11-07 against the upgraded working tree based on original commit `4873c66`. The second pass (2026-11-07) re-audited the release APK built from the project as found and a live Android 16 emulator with the REA reverse-engineering toolkit, fixed the defects it found, and re-measured release against release. This report distinguishes tested behavior from remaining release requirements. A third pass (iteration 2, below) closed six more audit items on the branch `upgrade/production-hardening-20261107`. That branch is committed locally and waits to be pushed by an account with write access to the repository (the push from this machine was refused with HTTP 403). Nothing was merged into `main`, signed or deployed.

## Result

| Check | Result |
| --- | --- |
| Scheduler unit tests | 10 passed |
| Storage/model unit tests | 11 passed |
| Notification-permission flow unit tests | 4 passed |
| Reminder preset unit tests (iteration 2) | 5 passed |
| Android dashboard/service tests | 17 passed |
| Total | **47 passed; zero failures, errors, or skipped tests** (iteration 2 clean build; 42 before it) |
| Android lint | **0 errors, 34 warnings** |
| Debug APK | Built and exercised on Android 16 |
| Release APK and AAB | Built successfully with R8 code and resource shrinking; **unsigned** |
| Release smoke test | Throwaway-signed release APK installed on the emulator; first run, enable, preview, notification prompt, reminders, lock/unlock delivery and reboot recovery with delivery exercised with no crash |
| APK 16 KB ZIP alignment | Release APK passed (no native libraries are bundled) |
| Packaged manifest | minSdk 26, targetSdk 36, non-exported overlay service, no INTERNET permission. `dumpsys package` on the installed release lists exactly six requested permissions: `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED` and the AndroidX-generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| Whitespace/error check | `git diff --check` passed |

Lint warnings remain; this is not a warning-free build. The 34 are 18 KTX suggestions, 9 newer-dependency notices, and one each for an unused drawable (`bg_card_dark`), a possibly unnecessary parent layout, an implicit-SAM argument, a `-v26` resource folder that is redundant at minSdk 26, a `null` inflate root (the overlay window), a plural-string suggestion, and the Android 12 backup-rules attribute. The API-level accessibility error found during development was corrected, and the seven launcher-icon warnings (shape and themed-icon) were removed by adding a monochrome layer and deleting the unused legacy PNG launcher icons (minSdk 26 always uses the adaptive icon).

## Reproducible build and tests

Environment: Windows, Android Studio JBR 21.0.9, Gradle 8.11.1, AGP 8.10.1, Android SDK 36. Instrumentation ran on dedicated Android 16 Google APIs x86_64 emulators: a 720 x 1600 display at density 280 with animations disabled or reduced in the first pass, and 1080 x 2400 at density 420 with the default animation scales in the second.

From the project directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_SERIAL = 'emulator-5580' # Replace with your booted test device.
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleRelease :app:bundleRelease --console=plain --no-daemon --max-workers=2
```

Three full runs were made, in this order.

1. **Private working copy, before the notification-stacking fix** (fresh `--no-daemon` process): `BUILD SUCCESSFUL in 9m 9s`, 135 actionable tasks (56 executed, 79 up to date). `testDebugUnitTest` executed (25 tests, 0 failures), `connectedDebugAndroidTest` ran 16 tests with 0 failures or skips, and lint reported 0 errors. The connected-test task also builds and installs the debug app and test APK.
2. **This directory, after the first copy of the changes** (fresh `--no-daemon` process, `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease`, no emulator tests): `BUILD SUCCESSFUL in 6m 41s`, 112 actionable tasks (78 executed, 34 up to date), 25 unit tests with 0 failures, and the same lint result (0 errors, the same 34 warnings).
3. **This directory, after the stacking fix and its regression test were copied in** (fresh `--no-daemon` process, the command above with `--rerun` added after `:app:testDebugUnitTest` so that the unit tests ran again, an Android 16 emulator attached): `BUILD SUCCESSFUL in 6m 39s`, 135 actionable tasks (39 executed, 96 up to date). 25 unit tests with 0 failures, **17 connected tests with 0 failures, errors or skips** (suite time 123 s) and lint with 0 errors. `lintDebug` ran, but its report task was up to date, so the XML the build kept is the one from run 2: byte-identical, 34 warnings. In the private working copy the new test was first seen failing (`expected:<1> but was:<4>`, 2 m 9 s) and then passing together with the other 16 (17 of 17, 3 m 24 s); a full working-copy build after the fix took 2 m 7 s (25 unit tests, 0 lint errors, 34 warnings). The logs are in `builds/` of the evidence pack (`real-tree/real-tree-build.log`, `stacking-red-connected.log`, `stacking-green-connected.log`, `stacking-fix-build.log`).

Generated reports are local build outputs and can be regenerated:

- [Unit results](../app/build/reports/tests/testDebugUnitTest/index.html)
- [Android test results](../app/build/reports/androidTests/connected/debug/index.html)
- [Lint report](../app/build/reports/lint-results-debug.html)
- [Unit sources](../app/src/test/java/com/wellness/overlay3d)
- [Android test sources](../app/src/androidTest/java/com/wellness/overlay3d)

The connected XML suite uses the name `CompanionServiceTest`, but contains all 17 cases from both Android test classes (10 dashboard, 7 service). The [GitHub workflow](../.github/workflows/android.yml) has been added, not run remotely; it covers the debug and release builds, unit tests and lint, not emulator tests.

## Iteration 2: open audit items closed (2026-11-07)

Six items from the open-item table of the evidence pack's `GAP-REPORT.md` were fixed on the branch `upgrade/production-hardening-20261107`. Every row below was checked on the Android 16 emulator (1080 x 2400, density 420, hardware GPU). Screenshots, logs and app data are in the folder `myfrnd-evidence-iteration2`, next to the evidence pack.

| Item | Change | Evidence |
| --- | --- | --- |
| U3: the editor's time chips showed no selection | New reminders start on **In 1 hour**, and that chip is shown as selected (`isSelected` plus a "Selected"/"Not selected" state description for TalkBack). Choosing a custom date and time clears the selection, and the choice survives activity recreation | UI dump: `pillIn1Hour selected=true`, the others false; after tapping **In 15 min** only that chip is selected (`screens-it2-u3-editor-default.png`, `screens-it2-u6-in15-selected.png`). `fifteenMinutePresetSchedulesTheMinuteItShowsAndStaysSelected` and `taskDraftSurvivesActivityRecreation` assert the same |
| U6: preset times kept the current seconds, so the editor showed 11:26 while the reminder was due at 11:26:47 | `ReminderPresets.dueTime` rounds every preset down to a whole minute. It also covers next 8 PM (rolling over to tomorrow) and tomorrow at 9 AM | 5 unit tests in `ReminderPresetsTest`, including the year boundary and New York's daylight-saving change on 2026-11-01. Live: the saved reminder's `timestampMillis` was `1794030960000` = 11:26:00.000 IST, `% 60000 == 0` (`u6-task-prefs.xml`) |
| U1: Android's *Display over other apps* list did not say which entry to switch on | A line in the overlay card: "On the next screen, tap myfrnd, then switch on “Allow display over other apps”." It is shown **before** the tap. A toast after the tap was tried and removed: that Settings window has the `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` flag, so Android hides every app toast there (`u1-settings-window-flags.txt`; logcat "Toast already killed. pkg=com.myfrnd.app" in `u1-toast-killed-logcat.txt`) | `screens-it2-dashboard-top.png` |
| U2: the notifications button always said "Manage notifications" | It reads **Allow notifications** while the system prompt can still be shown and **Open notification settings** after a permanent denial | Live on a fresh install: "Allow notifications", the system prompt after tap 1 and tap 2, both denied, then "Open notification settings" with `POST_NOTIFICATIONS: granted=false, flags=[USER_SET|USER_FIXED…]` (`screens-it2-u2-*.png`) |
| U5: cut-off chip rows looked clipped | The four chip rows on the dashboard and the editor's chip row fade at the edges (`requiresFadingEdge`, 32 dp) | The cut-off **Drop**, **Rise** and **Tomorrow 9 AM** chips fade out (`screens-it2-dashboard-top.png`, `screens-it2-u3-editor-default.png`) |
| T8: a layout-pass warning and a duplicate constant | The dashboard padding is now read from `@dimen/dashboard_padding`, so the code rounds it the same way as the XML (52 px against 53 px at density 2.625 had made it re-pad during layout). `POSITION_RANDOM`, a second name for the same "random" value, was removed | Three cold starts with a scroll each: **3** `requestLayout() improperly called` lines with the iteration-1 debug APK, **0** with the final iteration-2 APK, and no crash or ANR (`t8-before-logcat.txt`, `t8-after-final-logcat.txt`). On content wider than 760 dp the padding is still adjusted once per width change during layout. That is intentional, to avoid a one-frame jump |

Test-suite changes: `ReminderPresetsTest` is new (5 tests). `CompanionServiceTest.allSimultaneousTasksAreActuallyDisplayedSequentially` now schedules its three simultaneous reminders 5 s ahead instead of 1 s. On a loaded emulator the third add arrived after the shared due time and was rejected ("Choose a future reminder time"), so the test failed before it checked anything. The three reminders still share one due time, so the test still checks sequential delivery.

Build runs, all from the project directory with `--no-daemon --max-workers=2`:

1. `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`: successful; 30 unit tests, 0 lint errors, the same 34 warnings.
2. `connectedDebugAndroidTest` on an emulator started by mistake with software rendering (`-gpu swiftshader_indirect`): 10 of 17 failed. The causes were the environment: a System UI "isn't responding" dialog held focus, and the load delayed the reminder test above. The emulator was restarted on the hardware GPU and its display override (720 x 1600, density 280) was reset. That run's emulator log was overwritten by the restart.
3. `connectedDebugAndroidTest` on the hardware GPU: **17 of 17 passed**.
4. A full clean build with the documented command plus `clean` and `:app:assembleDebug`: 1 failure, the reminder-test timing described above.
5. The same build after the test fix: **BUILD SUCCESSFUL in 7 m 29 s; 30 unit tests and 17 connected tests passed; lint 0 errors, 34 warnings** (the same 18 KTX, 9 dependency and 7 single warnings listed above). The logs are `build-1-unit-lint.log` to `build-6-full-clean.log`; the files named `build-5` and `build-6` hold runs 4 and 5.

A debug APK built incrementally during this pass was 3,928,712 B, but its entries differed from the iteration-1 build by only 3.5 KB. The rest was zip dead space left by incremental repackaging. The clean build gives 3,616,999 B (+1,299 B).

## Regression coverage

| Original problem or requirement | Evidence in the upgraded app |
| --- | --- |
| Multiple due tasks were consumed while only the first was shown | Native service test queues three simultaneous tasks and checks successive delivery; only successfully attached overlays acknowledge tasks |
| Reopening/configuring the app reset countdowns | Scheduler tests and native activity recreation/configuration test preserve the unaffected deadlines |
| Paused settings could restart the service | Dashboard interval changes stay paused; notification Pause, boot handling, and background activity restoration are tested |
| Locking could consume pending work | Native lock/unlock test preserves the task until unlock; scheduler tests cover wellness behavior |
| Status could be disconnected from actual service state | Runtime state drives the dashboard; a native test reads countdown text before and after elapsed time and verifies it decreases |
| Invalid/corrupt task data could be hidden or lost | Model/storage tests reject invalid data; native UI test shows an explicit error and disables writes to unreadable storage |
| Task editing/drafts were incomplete | Native CRUD, delete cancellation/confirmation, persistent identity, and draft recreation tests pass |
| Character taps bypassed normal click behavior | Espresso performs real taps and verifies the response text changes |
| Density enlarged bitmap allocations | Native test verifies both images remain 896 x 1200 and each uses at most 4,300,800 bytes |
| Scroll content overlapped status-bar icons | Inset padding moved to an outer container; visual inspection and the full scroll-dependent UI suite pass |
| Overlay drew hard-edged black polygons and froze the UI for up to 1.8 s whenever it appeared | Found by live capture of the release build of the project as found: a stray CC0 `.glb` model switched the overlay to the Filament 3D renderer, which does not render correctly on the test emulator. The experimental Filament/GLB path was removed; the canvas character is the only renderer. Controlled A/B below |
| First tap on "Manage notifications" jumped to system Settings without ever showing the permission prompt | Decision logic extracted to `NotificationPermissionFlow` with 4 unit tests. Verified live: tap 1 shows the system prompt on the fixed build, while the original went straight to Settings. Since iteration 2 the button says what it will do: **Allow notifications** or **Open notification settings** |
| Hero artwork carried leftover opaque white/grey matte (between the legs, between arm and torso, floor glare) that showed as bright patches on dark backgrounds | Matte removed and art re-encoded as WebP; `heroArtworkKeepsItsCollarButHasNoLeftoverBackgroundMatte` asserts those regions are transparent and the real white collar and trousers stay opaque |
| Quick reminders needed the date and time pickers | **In 15 min** preset; `fifteenMinutePresetSchedulesTheMinuteItShowsAndStaysSelected` (renamed and tightened in iteration 2) checks the saved time. On the emulator the editor showed exactly +15:00 and the reminder fired at its stored time (below) |
| Tapping the persistent notification stacked another copy of the dashboard on top of the open one | First noticed as 5 `MainActivity` records, 417 views and 70,779 KB total PSS (1 activity, 83 views and 41,952 KB before) after a monkey run that another app's ANR then aborted (`device-tests/monkey/activities-after-aborted-run.txt` in the evidence pack). Reproduced on purpose with real notification-shade taps on the release build: **2, 3 and 4 records after taps 1, 2 and 3, and 4 Back presses to leave the app**. The records added by the taps carried `flg=0x10000000` (`FLAG_ACTIVITY_NEW_TASK`, with the app in the foreground) or `flg=0x10400000` (app in the background) and no MAIN/LAUNCHER action, unlike the launcher-started base record; the notification's `PendingIntent` is a bare explicit intent, and `MainActivity` was launch mode `standard`, so each tap added an instance. It is now `singleTask`; the activity never reads its launch intent, so nothing else changes. `tappingTheNotificationReusesTheOpenDashboard` fires the real posted notification's `contentIntent` three times and asserts one activity: it failed before the fix (`expected:<1> but was:<4>`) and passes after. The same shade test on the fixed build gives **1, 1 and 1 records and one Back press** (emulator; `device-tests/notif-stack-before-fix` and `notif-stack-after-fix` in the evidence pack), and again on the release APK built in this directory, signed with a throwaway key (1, 1 and 1 records after the three taps, one Back press, no `MainActivity` record left; `device-tests/notif-stack-realtree`) |

Live reminder checks on the R8 release build (Android 16 emulator, 2026-11-07, device clock):

- A reminder due at 01:03:00 while the device was asleep produced no app log lines while the screen was off (`rem-lock-logcat-while-locked.txt`). The wake key reached the system at 01:03:30.986; a screenshot written at 01:03:35 already showed the clean overlay ("Your reminders / Drink a glass of water") over the home screen, and a minute later the dashboard listed one upcoming reminder, so the delivered one had left the list. Both frames are in the evidence pack (`screenshots/10-asleep-reminder-delivered.png`).
- A reminder created with **In 15 min** was stored with the due time 01:11:53.286. With the phone unlocked and the app in the background, the overlay window was absent at 01:11:52.9 and present at 01:11:54.0 in a once-per-second window poll. A screen recording puts the first overlay frame about 0.3 s (plus or minus 0.3 s) after the due time, calibrated against the status-bar clock flipping at 01:12:00. The overlay showed "Your reminders / Stand up and stretch" for about 7 s, the 736 log lines captured around it contained no stall, EGL, crash or Filament lines, and the app process and foreground service stayed alive.

The inset fix was cross-checked after an intermediate clipping change broke five scroll tests. That approach was replaced, not accepted. Earlier emulator System UI ANR/focus failures were resolved before the successful full run; they were not treated as passing tests.

## Visual and interaction checks

Every image below is a real capture of the final release build (the R8-shrunk release APK, signed with a throwaway test key) on the Android 16 emulator (1080 x 2400, 420 dpi), taken after the last code change and inspected at full size. They replace the earlier 720 x 1600 captures of an older debug build. Files are stored as 540 px-wide palette PNGs, about 230 KB for the six (the previous set was 1.52 MB).

| Scenario | Evidence | SHA-256 of the raw capture (first 16 hex) |
| --- | --- | --- |
| Light dashboard | [Screenshot](screenshots/dashboard-light.png) | `423A24976E810213` |
| Live reminder timers (Water and Eyes countdowns running) | [Screenshot](screenshots/live-timers.png) | `57E71EE9CD89BAAB` |
| Dark theme (hero art shows no matte patches) | [Screenshot](screenshots/dashboard-dark.png) | `729ACD3D34266FFF` |
| 200% system font scale | [Screenshot](screenshots/large-text.png) | `2D2758E6FCCBD34C` |
| Overlay at 200% font scale (the bubble wraps and nothing is clipped) | [Screenshot](screenshots/overlay-large-text.png) | `311509DD5D6C76BA` |
| Landscape (a 2400 x 1080 capture, stored at 960 x 432) | [Screenshot](screenshots/landscape.png) | `D76EE8286E288F0A` |

Five were taken with REA's `capture-adb-screen`. The overlay image is a plain `adb exec-out screencap`: an REA capture takes 10-14 s and the overlay dismisses itself after 7-18 s, so two REA attempts missed it (a pixel difference against the no-overlay frame confirmed the miss). In landscape, scrolling showed all five pose chips and the whole **Preview on my screen** button. Rotation, font scale, display size and night mode were restored after each capture.

Manual checks also exercised overlay settings, notification denial, live service status, and an animated overlay preview. The 15 text/background colour pairs that the layouts and styles take from the app's own colour resources (`res/values/colors.xml`, `res/values-night/colors.xml`) were computed with the WCAG 2.x relative-luminance formula. All 15 are above AA's 4.5:1 normal-text threshold in both themes. The lowest are 5.00:1 (light) and 5.43:1 (dark), both for secondary text on the darker end of the hero gradient; the next lowest light pair is 5.58:1 (secondary text on a reminder row). This is a calculation on declared colours, not a measurement of rendered pixels. It does not cover non-text contrast, the framework's default dialog background, the date and time pickers, or anything drawn over artwork. It is not a complete accessibility audit or a TalkBack certification. Touch-target sizes were measured on the live release build (1080 x 2400, 420 dpi, font scale 1.0) from the framework's view-hierarchy dump (`dumpsys activity top`, saved in `static-checks/` of the evidence pack and parsed by `tools/touch-targets.py`): all 27 visible clickable views of the dashboard with two reminders listed are at least 48 dp tall and 64 dp wide (the smallest views, including the Edit buttons, are 64.0 x 48.0 dp), and the two permission-card buttons, measured in the first-run state, are 331.4 x 48.0 dp each. These are view bounds at the default font scale; the reminder editor dialog and the date and time pickers were not measured.

The Play Store screenshots in `play_store_assets/` were captured the same way, at 1080 x 2160, from the same release build. The three reminders they show were typed by hand through the app's own editor.

## Device behaviour checks on the final release build

All of these ran on the Android 16 emulator with the throwaway-signed release build.

- **Fresh first run, including the Settings round trip.** `pm clear` reset the notification permission but not the overlay app-op, so the app-op was also reset with `appops set ... SYSTEM_ALERT_WINDOW default` to reach a true first-run state. The status pill then read **Setup needed** and the dashboard showed the **Allow display over apps** card. Tapping it opened Android's *Display over other apps* screen. The app passes a `package:` URI (`requestOverlay` in `MainActivity.kt`), but on this emulator image the screen lists every app, so the user has to pick myfrnd and then switch the toggle on. That changed the app-op to `allow`, and after Back the card was gone (`llMandatoryOverlayBanner` GONE in the view hierarchy). The first-ever tap on **Manage notifications** showed the system prompt "Allow myfrnd to send you notifications?"; **Allow** granted it, and **Enable companion** then started the foreground service (`isForeground=true`, id 1001) and the pill read **Active**. Five real frames are in `screenshots/11-first-run-flow.png` of the evidence pack, and the timestamps are in `device-tests/first-run/first-run-run.log`.
  - The log of the whole flow has no `FATAL`, `AndroidRuntime` or ANR line for the app. The app's first frame after clearing its data logged "Skipped 40 frames" and a 711 ms `Davey!` (`TotalTime` 2,793 ms); a second cold start after `force-stop` logged 49 frames, 879 ms and `TotalTime` 1,863 ms. On that second, fresh process, tapping **Preview on my screen** attached the overlay within 1.6 s of the tap command (polled about every 0.9 s) and it stayed 7-9 s. The overlay period contains no `Davey`, skipped-frame, `EGL_BAD`, `freeAllBuffers`, `FATAL` or ANR line; the only two such lines are the launch's first frame, five seconds earlier. So the 1.2 s first-overlay stall seen once in boot test 1 did not recur here.
  - Both cold starts of this test also logged `requestLayout() improperly called by ... dashboardContent during layout: running second layout pass` (a warning, not an error). The view it names is the one whose padding the layout listener at `MainActivity.kt:80-84` sets from inside a layout callback, which is the likely source (the layout file gives that view `20dp` padding, and the listener computes its own pixel value with a truncating conversion). That was inferred from the source and not tested by changing it, its cost was not measured, and the block is byte-identical to the one in the pre-session snapshot, so it was left alone.
- **Themed launcher icon.** The adaptive icon gained a `<monochrome>` layer (the droplet and check in one colour) and the ten legacy `mipmap-*dpi/ic_launcher*.png` files were removed (`minSdk` 26 always uses the adaptive icon). On the emulator's Pixel Launcher with themed icons switched on, the myfrnd icon on the home screen rendered as the wallpaper-tinted monochrome droplet; it is also visible in `play_store_assets/screenshot_3_hydration.png`. Only the home-screen icon was themed in this check.
- **Reminders typed into the real editor.** Three reminders ("Drink a glass of water", "Evening walk", "Submit assignment") were entered through the app's dialog with the quick-time pills (In 1 hour, Next 8 PM, Tomorrow 9 AM). Each save showed the **Reminder saved.** toast and the list then read **3 upcoming**. The stored due times, read from the app's preferences file (epoch milliseconds in `device-tests/final-state/myfrnd_task_prefs.xml`, decoded in `reminders-decoded.txt` next to it with the device's Asia/Calcutta time), were 03:21:03 (the In 1 hour pill, which keeps the seconds of the moment it was tapped), 20:00:00 (Next 8 PM) and 09:00:00 on the next day (Tomorrow 9 AM).
- **Boot recovery and delivery after a reboot.** Two tests, each started with `adb reboot` on an emulator that had the companion enabled and the overlay permission granted. In both, the system started the app's process only to run `BootReceiver` (`am_proc_start ... broadcast {com.myfrnd.app/com.wellness.overlay3d.receiver.BootReceiver}`), the special-use foreground service was allowed to start from `BOOT_COMPLETED`, and the ongoing notification (id 1001) was posted.
  - *Test 1, device asleep at the due time.* A reminder due at 02:07:20 came due while the screen was off. The overlay attached 1.25 s after the wake key (delivery requires an interactive, unlocked screen) and the task was then stored as delivered. That first overlay in a process that had never drawn a window logged one 1,211 ms `Davey!` frame and "Skipped 40 frames". The screenshot taken in that run showed a leftover System UI "isn't responding" dialog from the emulator's cold boot, not the overlay, so it is not visual evidence.
  - *Test 2, screen on and unlocked, app never opened after the reboot.* The reminder was created with **In 15 min** before the reboot (due 02:49:18.639). The emulator reported boot completed at 02:37:36, but `BOOT_COMPLETED` only reached the app at 02:40:56 (`am_proc_start ... BootReceiver`); the cold-booted emulator's System UI was in its own ANR at 02:38:28 (`am_anr`, waited 64,174 ms), which is the likely reason the broadcast queue was slow. The foreground service started at 02:40:59 and notification 1001 appeared at 02:41:01. At the due time the overlay was inflated at 02:49:18.816 (0.18 s late) and finished drawing its first frame at 02:49:19.204 (+0.57 s). Screenshots taken over the next 8 s show the "Your reminders / Stand up and stretch" bubble and the character over the Pixel Launcher; the third frame, at 02:49:27.8, shows the overlay gone (it dismisses itself after 7-18 s). The same process (pid 3462, 8 min 44 s old) served the whole run, its 33 log lines contain no `Davey`, skipped-frame, `EGL_BAD`, `freeAllBuffers`, `FATAL` or ANR line (the only EGL lines are the normal first-window initialisation messages), the foreground service was still `isForeground=true`, and the reminder was stored as delivered.
  - The 1.2 s first-frame stall of test 1 did not recur in test 2, so on this emulator it is intermittent (load-dependent) rather than a fixed cost, and it is unmeasured on a physical device. The System UI ANR dialogs seen after both reboots came from the emulator's `-no-snapshot` cold boot, not from the app.
- **Process kill while a reminder is pending.** With the companion on and one reminder pending (typed into the editor with **In 15 min**, due 06:03:12.489), the app was sent to the background and its process (pid 6706) was killed with `kill -9` at 05:51:51. The system logged `am_proc_died` and `am_schedule_service_restart` for `OverlayService` with a 1,000 ms delay (the service returns `START_STICKY`); a new process (pid 7335) started 1.03 s after the old one died, notification 1001 was posted again, and `dumpsys` showed `isForeground=true`, `restartCount=1` and `crashCount=0`. The saved main log has no `FATAL EXCEPTION` line. At the due time the overlay window appeared within 0.69 s (polls at 06:03:12.407 and 06:03:13.179 bracket it), showed "Your reminders / Process death test" over the launcher, was gone about 7 s later, and the reminder was stored as delivered by the restarted process. The interval countdowns do not survive a kill: before a second kill (pid 7335, 06:04:49) the dashboard read water 17:05 and eyes 02:05, and after the restart (pid 12762) 29:54 and 14:54, so both intervals start over in full. The evidence is in `device-tests/process-death/` of the evidence pack (`tools/process-death.ps1`, `tools/countdown-reset.ps1`). This is two kills on one emulator (the first with the app in the background, the second with the dashboard on screen): Force Stop from Settings, OEM task killers and a long Doze period were not tested.
- **Interval reminders firing by themselves.** After the second kill above, the restarted app (pid 12762) was left alone with the dashboard on screen and the default settings: the preferences file held only `key_companion_enabled`, so Eyes every 15 min and Water every 30 min. The Eyes overlay ("Rest your eyes") was drawn at 06:19:51.095, 15 min 0.13 s after the restarted service posted notification 1001 (06:04:50.967), and the system removed its alert window at 06:19:59.27 (about 8.2 s). The Water overlay followed at 06:34:51.112 (30 min 0.145 s after that notification) and was removed at 06:34:58.334 (about 7.2 s). The restarted Eyes interval came due at the same moment; its overlay was drawn 0.58 s after Water's was removed (06:34:58.909 to 06:35:07.084, about 8.2 s), because the service shows one overlay at a time and checks Water first. The app process's log (44 lines) holds two stall lines, both at 06:04:55 (the dashboard's first frame after the restart: "Skipped 43 frames" and one 729 ms `Davey!`), and none during either overlay. The other presets (Eyes 20, 30 and 45 min; Water 15, 45 and 60 min) were not run to their time, and this is one emulator. The evidence is in `device-tests/interval-natural/` and `device-tests/interval-natural/second-cycle/` of the evidence pack (`tools/interval-watch.ps1`, `tools/interval-watch-multi.ps1`).

## Performance evidence and limits

### Changes with directly checkable effects

- Reminder evaluation is deadline-driven rather than polling every eight seconds.
- Overlay entrance/exit animates view transforms rather than sending per-frame window-layout updates.
- Drawing reuses paints, paths, rectangles, gradients, and matrices. Forced garbage collection on dismissal was removed.
- Canvas animation stops with lifecycle/visibility changes and honors reduced motion/system-disabled animation (measured: see the idle-redraw table below).
- Bitmap resources are density-independent. Two 896 x 1200 ARGB8888 images require 8,601,600 bytes of pixel storage. At a hypothetical 3x resource-density scaling, the same pair would require 77,414,400 bytes: **9x as much bitmap pixel storage**, not a 9x whole-app memory or speed improvement.
- The experimental Filament 3D renderer, its native libraries (three ABIs) and the bundled 444 KB stock GLB model were removed. Release builds now use R8 code and resource shrinking, and the hero art is WebP.

### Release-versus-release measurements (second pass)

Both builds are release builds of this repository. The **baseline** is the project exactly as found at the start of the second pass (R8 off, Filament native libraries and an untracked stock `.glb` model bundled); the **upgraded** build is the current tree. Both were signed with the same throwaway test key, installed over each other with `adb install -r`, and run on the same Android 16 Google APIs x86_64 emulator (1080 x 2400, 420 dpi, host GPU). Emulator timing is noisy, so only effects far larger than the run-to-run spread are reported.

| Measurement | Baseline release | Upgraded release | Method |
| --- | ---: | ---: | --- |
| Release APK (unsigned) | 26,254,912 B | 993,466 B (-96%) | `assembleRelease` |
| Release AAB | 17,667,429 B | 1,446,490 B (-92%) | `bundleRelease` |
| Debug APK | 28,477,018 B | 3,615,700 B (-87%) | `assembleDebug` (not shrunk; debug builds keep all dependency code). The baseline file also holds about 0.8 MB of dead space left by incremental packaging, which does not change the rounded percentage |
| Installed footprint | 32,248 KB | 1,848 KB (-94%) | `du -sk` of the installed code directory (APK plus compiled code, no user data), read about 40 s after `adb install -r` and a first launch, both builds signed with the same throwaway key. Baseline: 26,301,675 B APK + 6,496 KB vdex. Upgraded: 1,031,685 B APK + 788 KB vdex. Repeated installs of each build gave the same value. The app's data directory was 208 KB (baseline) and 144 KB (upgraded) after one launch each; that figure moves with use, because most of it is the runtime's cache image (`cache/oat_primary/.../base.art`): it read 196 KB on the final build after five launches. Re-measured on the final release build of this directory (signed with a fresh throwaway key), the code directory is again 1,848 KB (1,031,685 B APK, 788 KB vdex, 24 KB odex). Raw output: `device-tests/footprint-baseline-release.txt`, `footprint-final-release.txt` and `footprint-realtree-release.txt` in the evidence pack, produced by `tools/footprint.sh` |
| Cold start, 6 runs per build | Session 1: mean 1,681 ms (1,587-1,835). Replicate after an emulator restart: mean 973 ms (809-1,203) | Session 1: mean 1,408 ms (1,211-1,573), about -16%, ranges do not overlap. Replicate: mean 892 ms (771-1,141), about -8%, ranges overlap | `am start -W` TotalTime, `LaunchState: COLD` on every run. **Only same-session comparisons mean anything:** the same upgraded build averaged 1,408 ms in the first emulator session and 874-892 ms after the emulator was restarted (37-38% lower; the baseline fell 42% the same way, 1,681 to 973 ms). The direction agreed in both sessions, but the replicate is not statistically distinguishable, so read this as a modest, not a proven, startup gain. Raw: `device-tests/coldstart-baseline-release.csv`, `coldstart-new-release.csv`, `coldstart-ab-same-session-after-restart.csv`, `coldstart-realtree-release.csv` |
| PSS while the overlay is attached, 3 Preview rounds | 125.8-131.1 MB | 60.4-61.7 MB | `dumpsys meminfo` sampled repeatedly during each show, accepted only when it reported two `ViewRootImpl` (overlay window present), leaving out the first sample of each round (overlay still being built) and the last two (overlay closing). Raw: `device-tests/ab2-baseline-memseries.csv` and `device-tests/ab2-new-memseries.csv` in the evidence pack |
| PSS after the overlay is dismissed | about 66.9-67.3 MB | about 49.5-50.0 MB | same, leaving out the first sample after the overlay closed (memory still being released) |
| UI stalls per overlay show | 4-10 log lines per show: HWUI `Davey!` frames of 756-1,782 ms, Choreographer "Skipped 38-57 frames", `freeAllBuffers` errors | **0** in all 3 rounds | `logcat` per round, matched against `Davey`, `Skipped N frames`, `EGL_BAD`, `freeAllBuffers`, `no connected producer`, `FATAL`, `ANR in` |
| Dashboard idle PSS | about 46.9 MB | about 41.3 MB | `dumpsys meminfo` after the same scroll protocol, 3 runs each, builds alternated |
| Classes in the release APK | 2,304 | 935 | REA `inspect-android-package` (JADX) |

The attached-overlay ranges are steady-state values. They leave out each show's first sample (the window exists but its memory is not yet populated: 57.1, 85.2 and 83.5 MB on the baseline, 53.4 MB on the upgraded build) and the three late samples that were already dropping as the overlay was being removed (110.3 MB on the baseline's third round; 60.2 and 58.1 MB on the upgraded build's first round). Every sample is in `device-tests/ab2-baseline-memseries.csv` and `device-tests/ab2-new-memseries.csv` of the evidence pack. The dismissed ranges skip the first sample after each dismissal, which is taken while memory is still being released. The stall counts above (4-10 per show on the baseline) are the Davey, skipped-frame, `EGL_BAD`, `freeAllBuffers` and "no connected producer" lines in `device-tests/ab2-baseline-round1..3-logcat.txt` (10, 8 and 4); Filament's own start-up log lines, which also match the word "Filament", are not counted.

On the baseline the overlay also drew hard-edged black polygons instead of the character (timed screen captures of the release build). The upgraded build draws the canvas character and speech bubble cleanly.

**Scroll smoothness is deliberately not claimed.** Alternating the two builds three times each gave jank of 2.4 / 15.2 / 7.6% (baseline) and 5.0 / 6.3 / 33.6% (upgraded) with identical mean p99 of 82 ms. Runs of the same build varied by 6.2x (baseline, 2.4 to 15.2%) and 6.7x (upgraded, 5.0 to 33.6%) in the share of janky frames, so no difference can be attributed to the changes.

### Repeated overlay shows (stress, upgraded release only)

The Preview button was tapped 20 times in a row on the upgraded release build (one show every 15 s; each overlay dismisses itself after about 7 s). `dumpsys meminfo` was read about 6 s after every dismissal, then again 8, 20, 35, 50, 70 and 90 s after the last one.

- The overlay window was attached 3 s after every tap (20 of 20). The 8,018 log lines contained no `Davey`, skipped-frame, EGL, `freeAllBuffers`, crash or ANR line, and the app kept the same process id throughout.
- Object counts did not move: 83 views, one `ViewRootImpl` and one activity in every sample, and the Java heap stayed within 5,228-5,276 KB.
- Total PSS read shortly after a dismissal was 56.2 MB after round 1, 56.6 MB after round 10 and 58.6-59.2 MB for the other rounds 2-20 (least-squares slope 36 KB per round over rounds 2-20, 32 KB without round 10), against 49.1 MB before the first show. It fell back to 48.3 MB within 8 s of the last dismissal and stayed at 48.3-48.5 MB for the next 90 s, so the roughly 10 MB native-heap bump is transient and nothing is retained. An earlier 15-round run showed the same pattern while attached (15 of 15 shows, 4,835 log lines, no stall lines, same process; 48.6 MB before and 55.7-58.4 MB after dismissals, about 24 KB per round over rounds 4-15); it was not followed by an idle phase.
- This establishes no object leak and no retained memory growth across 20 shows on an emulator. It says nothing about thousands of shows, other devices, or memory pressure.

### Randomized input (monkey, upgraded release only)

Android's `monkey` was run to completion against the installed release build that already had the `singleTask` fix: 4,000 events, seed 20261107, 100 ms between events, `--ignore-timeouts`, weights touch 65 / motion 25 / major navigation 5 / app switch 5 with every other event type at 0, restricted to the app's package (`device-tests/monkey-run2/` in the evidence pack, driven by `tools/monkey-run.ps1`). An earlier attempt with the same seed and settings was aborted at event 325 when the Pixel Launcher stopped responding (an ANR in the launcher, not in the app). It left the five stacked `MainActivity` records described under Regression coverage and is kept in `device-tests/monkey/`.

- The monkey printed `// Monkey finished` after injecting 4,000 of 4,000 events in 207 s. The app kept the same process id (13411) from start to finish, and the 14,055 log lines (main, system, crash and events buffers) contain no crash, ANR or app-death line for the app or for any other process.
- The app's task held one `MainActivity` record at the end. The monkey's own launcher intents carry MAIN/LAUNCHER and would not have stacked even before the fix, so this run is not the evidence for the notification fix; the shade-tap test and the regression test above are. The monkey also switched the companion off during the run (`key_companion_enabled` went from true to false), so the foreground service and any overlay were gone at the end, and no reminder was created.
- The log holds 108 `Davey` or skipped-frame lines across all processes: SystemUI 75, the launcher 13, the app 13 (eight `Davey` lines of 828-3,959 ms and five skipped-frame lines of 35-200 frames), the keyboard 6 and Settings 1. Nine of the app's 13 fall within 1.5 s of such lines in SystemUI, the launcher or the keyboard, which points to the whole 2 GB emulator stalling, but that is an association, not a proof. The other four, and the longest stall (3,959 ms, about 13 s after the monkey started), were not isolated.
- Samples before and after: 46,391 to 50,731 KB total PSS, 5,280 to 5,656 KB Java heap, 83 to 128 views, 1 to 2 `ViewRootImpl`, one activity both times. When the windows were inspected about three minutes after the run, a second, centered, dialog-type window of the app was still open and the keyboard was showing, which accounts for the extra `ViewRootImpl` and views. This is one pair of samples taken after fuzzing, not a leak test.
- Also about three minutes after the run, the emulator's display returned an all-black frame to `screencap` and a garbled, stale frame (its clock read 2:34 when the device time was 04:53) to the REA capture, with no `EGL_BAD`, `freeAllBuffers`, `FATAL` or ANR line in the log. The emulator was restarted before the next device test and the frames are kept in `post-run-display/`. I read this as the emulator's display path failing after sustained input; that is not proven. The emulator's own console log for that session (`device-tests/emulator2.log` in the evidence pack) has a steady stream of graphics-translator errors (`glPixelStorei`, `glReadPixels`, `glTexImage2D`) from the second reboot test until the emulator was shut down, but it has no timestamps, so it does not show when the display failed.
- What this shows: the dashboard survived 4,000 random touch, motion, menu and launcher events on one emulator without a crash or ANR. What it does not cover: rotation and permission changes (weight 0), a reminder or overlay being delivered during the run, TalkBack, or any physical device.

### Idle redraw of the dashboard companion (open item)

While the dashboard is on screen with animations allowed, the hero companion redraws continuously (`DevCompanionCanvasView.onDraw` asks for the next frame for as long as the view is attached, visible and animation is allowed). Measured on the release build on the Android 16 emulator (1080 x 2400), in 10 s windows: frames are the `dumpsys gfxinfo` counter after a reset, CPU is `top` for the app's process in percent of one core (four cores = 400%).

| Condition | Frames rendered | App CPU |
| --- | ---: | ---: |
| Dashboard on screen, defaults, fresh process (A1) | 551 in 10.6 s = 51.8 per second (13.79% janky) | 70.3% |
| Same process, repeated (A2) | 607 in 10.5 s = 57.8 per second (8.07% janky) | 75.6% |
| Same process, after switching in-app Reduce motion on and off again (A3) | 622 in 10.6 s = 58.9 per second (5.95% janky) | 70.8% |
| App in the background, foreground service running (D) | 0 | 0.0% |
| System animations off (`animator_duration_scale` 0), fresh process (B) | 0 | 1.1% |
| In-app **Reduce motion** on (C) | 0 | 0.9% |

SurfaceFlinger's own present history agrees with the gfxinfo counter: the gap between presented frames had a median of 0.017 s with the animation running (A3) and 0.997 s with Reduce motion on and the Daily rhythm countdowns scrolled into view (C2), where the only redraw is the live mm:ss countdown, one frame per second. So the continuous drawing stops when the app is in the background, when the system animation setting is off, and when the in-app Reduce motion switch is on; the countdown still ticks. The emulator had 2 GB of RAM and 1.86-1.89 GB in use during these windows (the `top` memory line), so the CPU percentages and jank rates are emulator figures, not battery figures. These runs were taken in the emulator session whose console log holds the graphics-translator errors described under the monkey run (`device-tests/emulator2.log` in the evidence pack) and were not repeated in a later session, so the conditions are compared with each other within that one session only. **Battery impact on a physical device is unmeasured.** The animation is intentional and unchanged; whether it should default to reduced motion or pause after a few seconds without interaction is an open product decision. Raw output: `device-tests/idle-redraw/` in the evidence pack, produced by `tools/idle-redraw.ps1`.

On the same emulator, `uiautomator dump` fails with "could not get idle state" on the dashboard only while the companion is enabled, which is when the countdown text and its description change every second, and succeeds while the companion is paused, with animations on or off (four single runs, `E-uiautomator-idle-matrix.txt`). That is consistent with the tool never seeing a quiet moment, but that was not tested further. The app's own Espresso tests are unaffected, and the touch-target measurements above use `dumpsys activity top` for this reason.

### First-pass frame-timing windows (debug APK, 720 x 1600)

These single windows predate the controlled comparison above and used a debug build at a different resolution, so they are not before/after evidence. The second pass showed identical code ranging from 2% to 34% jank between runs on this emulator, so none of these windows should be read as a guarantee.

Android 16 emulator, 720 x 1600, debug APK:

| Measurement window | Frames | Android-reported jank | Frame completion p50 / p95 / p99 |
| --- | ---: | ---: | --- |
| SwiftShader, mixed theme/font/rotation test session | 3,029 | 69.23% | 69 / 150 / 200 ms |
| Host Intel Iris Plus graphics, initial 15 seconds | 704 | 18.04% | 29 / 69 / 129 ms |
| Host graphics, warmed dashboard, reset stats, approximately 30 seconds | 1,801 | **0.28% (5 frames)** | 23 / 30 / 34 ms |

The final warm window ran without a concurrent build, with system animator duration scale 1, normal companion animation, and `dumpsys gfxinfo` reset immediately before sampling. The recent 120-frame sample reported UI work p50/p90 of 0.53/1.27 ms and render-thread work p50/p90 of 1.26/2.92 ms. Frame-completion latency includes the graphics/display pipeline and is not the same as UI-thread work or FPS.

After returning to the home screen and allowing the transition to finish, resetting statistics and waiting five seconds produced **zero app-rendered frames**. No app fatal exception, app ANR, or window-attachment exception appeared in the inspected recent logcat.

Reproduce a comparable foreground sample with animation enabled:

```powershell
adb -s emulator-5580 shell settings put global animator_duration_scale 1
adb -s emulator-5580 shell am start -W -n com.myfrnd.app/com.wellness.overlay3d.ui.MainActivity
# Allow the emulator/app to warm up before the measured interval.
adb -s emulator-5580 shell dumpsys gfxinfo com.myfrnd.app reset
Start-Sleep -Seconds 30
adb -s emulator-5580 shell dumpsys gfxinfo com.myfrnd.app
adb -s emulator-5580 shell dumpsys gfxinfo com.myfrnd.app framestats
```

The earlier poor software-rendering results are retained above deliberately. Graphics backend, emulator warmup, host load, and workload materially affect these numbers. Observed cold launches ranged from 3,683 ms (software graphics) to 6,363 ms (host graphics); an earlier build-loaded run took 13,134 ms. These are uncontrolled observations; the controlled cold-start comparison is the release-versus-release table above. A 130,296 KB PSS snapshot from the first pass had no comparable baseline; the second pass measured memory only while the overlay window was proven attached (table above).

**Physical-device startup, frame pacing, memory, and battery improvements remain unverified.** There is no supported "100x faster" claim or production frame-rate guarantee.

## Deliverables

| Local output | Bytes | Signing |
| --- | ---: | --- |
| [Debug APK](../app/build/outputs/apk/debug/app-debug.apk) | 3,616,999 | Development/debug signature |
| [Release APK](../app/build/outputs/apk/release/app-release-unsigned.apk) | 995,270 | Unsigned |
| [Release AAB](../app/build/outputs/bundle/release/app-release.aab) | 1,448,383 | Unsigned |

SHA-256 of the artifacts from the iteration 2 clean build (run 5 above):

```text
debug APK    CBB1127F63D94F2CE764833C3944D883608D949FB704804CEB2EC5D8B87EC519
release APK  C880B72298F8684FED0D5A8AE6CC867DFF1D6F2892EF7EBABE6FE33C28408FEC
release AAB  281D0B0B2A42B97920F9B9EBAE1D8CB3706DC67FE1D56179AFC40B74281739E3
```

The rest of this section, and the evidence pack, describe the iteration 1 artifacts: debug 3,615,700 B, release 993,466 B and AAB 1,446,490 B, with these SHA-256 values from the third full run described above, after the stacking fix:

```text
debug APK    7655C5E84125305F369235AD9839B65E3DFD25A0207899AA5BEF6E19752C781B
release APK  DB1C8B529749D14CC639B183B0857898B20E1FA65DBB765B114FB8F5EDFB1F27
release AAB  2FA48C10C6CEFBDDB1E9D6035524C53DDBB84C6C96FBECD1DEF894BCCA8EAA47
```

Most emulator tests ran on builds from an identical private copy of the sources that had no `.git` folder (debug 3,939,744 B `3D94BF58AECBA6BB006A5EAA714FAF5D08AB22A464A98F753EF1CEAFEA354979`, release 993,398 B `66DFD125B0BD46C9CF54704CBD5C9BF05C0AFC59F159B69342F935E1D13DDFCB`, AAB 1,446,420 B `84C6C804687B48FA30FC556C505B6064DCE2AE7186AA02E950B3EC839C80848E`), so the hashes differ. Compared entry by entry (name, size and CRC): the debug APKs hold the same 454 entries with identical content, and the private copy's file is 324,044 bytes larger only because of dead space left by many incremental repackagings. The release APK differs in one entry, `META-INF/version-control-info.textproto`, where the Android Gradle plugin records the git revision when it builds inside a git checkout (120 B here, 46 B there). The AAB differs in that file too and in `BUNDLE-METADATA/com.android.tools/r8.json`, whose only changed field is `compilation.buildTimeNs`, R8's own build-duration record. `classes.dex`, `resources.arsc` and the manifest are identical. To close the remaining gap, the release APK from this directory was also signed with a throwaway key and run on the emulator: the notification-tap test again gave 1, 1, 1 `MainActivity` records after three taps and one Back press to leave the app, and six cold starts averaged 874 ms (`device-tests/notif-stack-realtree/`, `device-tests/coldstart-realtree-release.csv`). The monkey run described above was done on the private copy's release build only, not repeated on this directory's build.

The current release APK of this directory passed `zipalign -c -P 16 -v 4` (16 KB page alignment; output in `builds/real-tree/zipalign-check.txt` of the evidence pack, 383 lines ending "Verification successful"), contains no native code (`builds/real-tree/apk-composition-realtree-release.txt`: 381 entries, no `lib/` folder, no `.so` or `.glb`), and its manifest has no INTERNET permission (REA `inspect-android-package` on it, evidence id `ev_e53e47fb9107b806ff2714b0bafb84f528f35f9665203cb86d3e2b137024db83`, subject SHA-256 `db1c8b52...1f27`: 935 classes, 381 resources, six permissions, `MainActivity` with `launchMode="singleTask"`). Besides the app's own `MainActivity`, the merged manifest contains AndroidX's `ProfileInstallReceiver`, which is exported but guarded by the `android.permission.DUMP` permission.

The artifacts are ignored build outputs, not committed binaries. A clean checkout must build them again. Install the debug APK on a test device; it is not an owner-signed store update.

### Phone-test build (clean rebuild)

`Myfrnd-1.0.2-debug.apk` was rebuilt from a clean copy of these sources, without build caches and without the untracked `app/src/main/assets/models/dev_companion.glb` that appeared in the working tree later, so it contains the verified bundled canvas companion. The Deliverables table above describes the newer debug build of the current tree (Filament removed, 3.6 MB); this phone-test APK predates the removal of the unused Filament renderer and still bundles its three-ABI native libraries (9 `lib/*` entries, 21.3 MB uncompressed; no GLB).

| Check on this exact APK | Result |
| --- | --- |
| Size / SHA-256 | 27,241,488 bytes / `3CDA9B1BBF6CBC340062E3C222208D5A2873FD7F4A64C678F4A12C34EC8628A3` |
| Unit tests (Gradle, same clean copy) | 21 passed |
| Instrumentation tests (APK installed with `adb` on an API 36 x86_64 emulator) | 14 passed |
| Cold launch and log scan | Launched without a crash or ANR entry in the system, main and crash logs |
| Signature, alignment, manifest | `apksigner verify` passes (v2, Android Debug certificate); 16 KB zip alignment passes; `com.myfrnd.app` versionCode 3 / 1.0.2, minSdk 26, targetSdk 36 |

## Remaining release gates

1. **Signing ownership:** the original revision exposed signing passwords. The owner must rotate/reset the affected credentials appropriately, configure private signing, and increment the release version. No signing identity was replaced and no history was rewritten.
2. **Real-device coverage:** every measurement in this report comes from an x86_64 Android 16 emulator. Profile startup, animation, memory and battery on representative physical devices, including low-end hardware, API 26, and OEM battery-management variants.
3. **Accessibility and speech:** complete TalkBack checks and actual offline TTS playback with the installed engine/voice. Large-text/theme screenshots and contrast calculations are not substitutes. In particular, the two countdown views change their text and spoken description every second while the companion is on (they are not live regions), and how a screen reader treats that was not tested.
4. **3D companion is a product decision:** the experimental Filament/GLB renderer was removed because the untracked stock model that switched the overlay onto it made the release build draw black polygons and stall the UI (measured above). If a 3D companion is a goal, reintroduce it deliberately: a licensed model, physical-device testing, and the roughly 21 MB of native libraries it costs. Copies of the three files as they were found (the renderer, `models/README.txt` and the stray model, which was never tracked in git) are in `removed-3d/` of the evidence folder. The committed renderer and README are also in git history (`4873c66`), but the committed renderer differs from the one that was built (13,814 B against 13,325 B).
5. **Distribution:** review current Play target-API requirements, the special-use foreground-service declaration and the Data Safety answers; sign, upload, and obtain review with the owner's account. The six store screenshots are now real captures (see `play_store_assets/play_store_metadata.md`); the 512 px store icon and the feature graphic were not changed, and they show different artwork from the launcher icon and the in-app companion. Choose one brand identity before publishing.
6. **Delivery limitations:** Android can delay or stop background work. Reminders are scheduled inside the foreground service (there is no `AlarmManager` fallback), so a device that kills the service and does not restart it will not deliver them until the app or a reboot restarts it. A plain process kill was recovered by the system on the emulator (see "Process kill while a reminder is pending"); Force Stop, OEM task killers and Doze were not tested. Tasks are one-shot: they are acknowledged on overlay attachment, not user completion, and there is no snooze, completion or repeat. Abrupt process failure is not an exactly-once transaction. Interval countdowns restart in full after process recreation (measured above). This remains a wellness aid, not a medical or emergency alarm.
7. **Startup tuning:** no app-specific baseline profile was added (the release APK carries only the small AndroidX library profile that the build merges in, `assets/dexopt/baseline.prof`, 1,316 bytes). Forcing full ahead-of-time compilation (the upper bound a profile can reach) gave a 1,643 ms mean cold start (1,251-2,321 ms) against 2,022 ms (1,585-2,312 ms) for the default verify-only install on this emulator, and the ranges overlap. A Macrobenchmark on a physical device is the right way to decide.
8. **CI:** the workflow builds, unit-tests and lints, but it does not run the emulator tests; run `connectedDebugAndroidTest` before releasing. It has not run on GitHub yet, because the branch has not been pushed.
9. **Idle animation policy:** the dashboard companion redraws about 52-59 times a second at about 70-76% of one emulator core while it is on screen with animations allowed (table above). It stops in the background and with Reduce motion or the system's animation setting. Decide the default (for example reduced motion, or pausing the hero after a few idle seconds) and measure battery on a physical device before changing it.

The local implementation and verification are complete within this scope. The gates above require hardware, assets, credentials, or account-level actions not supplied with the repository.
