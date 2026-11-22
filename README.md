# Uptime Loom for Android

Automatic, battery-friendly work-hours tracking. Set your work areas once:
Uptime Loom starts counting when you arrive and stops when you leave. No
buttons to remember, no account, and your data never leaves your phone.

The Android companion to [Uptime Loom for Windows](https://github.com/nabin47/uptime-loom).

**Website & downloads:** https://nabin47.github.io/uptime-loom-android/

---

## Features

- **Automatic** — counts time while you're inside any of your work areas.
  Short trips out (coffee, car park) don't split your day.
- **Multiple work areas** — office, other buildings, a second campus. Pick
  each on a map (search, your location, or drag the map under the pin), paste
  a Google Maps link, or **share a place from Google Maps** to the app.
- **Statistics** — today's live timer; this week and month against your
  target; hours still needed per remaining workday; ahead/behind; days
  present and missed; on-time arrivals and early departures; weekend work.
- **Charts** — daily hours, weekly hours vs. target, when you work,
  average by weekday, calendar.
- **Corrections** — edit/add/delete sessions, manual check-in/out, days off
  (leave, holidays) that lower the target and aren't counted as missed.
- **CSV export** and a **home-screen widget**.
- **Battery-friendly** — Android's geofencing watches your areas and wakes the
  app only when you arrive or leave. No always-on GPS, no permanent
  notification.
- **Private** — no servers, no ads, no analytics. The internet is used only
  while setting up an area (map tiles from OpenFreeMap / OpenStreetMap,
  address search, reading shared Google Maps links); tracking is offline.
- **Modern** — Kotlin, Jetpack Compose, Material 3 with Material You colours,
  light and dark themes.

## Compatibility

- Android 7.0 (API 24) and newer — about 97–98% of active Android phones.
- Phones with Google Play services use geofencing (accurate to a few
  minutes, almost no battery).
- Phones **without** Google Play services (e.g. recent Huawei) automatically
  use a 15-minute low-power location check instead (accurate to ~15 minutes).
- Some brands (Xiaomi, Oppo, Vivo, OnePlus, some Samsung settings) stop apps in
  the background. The app's setup checklist links to the right setting; see
  also [dontkillmyapp.com](https://dontkillmyapp.com/).

## How tracking works

| Event | What happens |
|---|---|
| You stay inside an area for the *arrive delay* (default 3 min) | A session starts, back-dated to when you entered |
| You leave all areas | After the *leave delay* (default 5 min) the session ends at the time you left, unless you came back |
| Phone restarts | Areas are re-armed; if you're no longer inside one within 10 minutes, the open session ends at the restart |
| You check in / out manually | A manual session starts / the running session ends |

Work areas are watched with `GeofencingClient` (dwell + exit transitions).
Everything is stored in a local Room database.

## Building

Open the project in Android Studio (Ladybug or newer), or:

```bash
./gradlew assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests for the statistics engine
```

If `gradlew` is missing, run the **Add Gradle wrapper** workflow once
(Actions tab), or use a local Gradle 8.13: `gradle wrapper --gradle-version 8.13`.

Every push is built by GitHub Actions (tests, lint, debug APK as an artifact).

## Releasing

Push a tag like `v1.0.0`. CI then builds the signed APK and App Bundle,
publishes a GitHub Release, optionally uploads to Google Play's internal
testing track, and updates the website. One-time setup (signing key, Play
Console, Pages) is in [RELEASING.md](RELEASING.md).

## Project layout

```
app/src/main/java/io/github/nabin47/uptimeloom/
  location/   map picker (MapLibre + OpenFreeMap), Google Maps link parsing, address search
  data/       Room database (areas, sessions, presence, days off, event log), settings
  tracking/   geofencing, fallback checks, receivers, background jobs, notifications
  stats/      statistics engine (pure Kotlin, unit-tested)
  ui/         Compose screens: Today, Stats, Log, Areas, Settings
  widget/     home-screen widget (Glance)
site/         website (GitHub Pages), built by .github/workflows/site.yml
```

## License

MIT — see [LICENSE](LICENSE).
