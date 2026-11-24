# unit-autostart

Small Android app for Chinese car head units (tested on a TopWay TS9, Android 8.1, x86_64).

On every wake it does three things, entirely on the device:

1. skips everything when the foreground app is the reverse camera, the DVR or a call;
2. starts music in [Ultrasonic](https://gitlab.com/ultrasonic/ultrasonic) — `play()` through its
   media browser to resume, otherwise a shuffle of the library — and stops when nothing plays;
3. sends the HOME intent, so the car launcher comes back on screen.

It also checks this repository's latest release and offers the update, so a head unit without
easy ADB access can keep itself current.

## Why

Head units sleep instead of rebooting (`BOOT_COMPLETED` never fires), and their IP on a phone
hotspot changes whenever tethering is toggled — so an automation living on another machine
cannot find them. Living on the unit removes both problems.

## Build

```bash
./gradlew :app:assembleRelease      # needs JDK 17+ and Android SDK platform 36
```

`targetSdk` is 27 on purpose: from 28 on, Android blocks a background app from starting an
activity, which is exactly what this app has to do.

## Permissions

- `PACKAGE_USAGE_STATS` — read-only, to know which app is in front. Grant it once:
  `adb shell appops set com.gsalem.unitautostart GET_USAGE_STATS allow`
- `INTERNET` — only for the update check against the GitHub API.
- Foreground service — the notification is intentionally minimal.

## Test without turning the car off

```bash
adb shell am broadcast -a com.gsalem.unitautostart.RUN -p com.gsalem.unitautostart --include-stopped-packages
```
