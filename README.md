# MileLog

An Android mileage, expense and revenue tracker for self-employed drivers. Everything
stays on the phone — no account, no server, no subscription.

Built for one driver's actual working day: delivery and rideshare work across several
platforms, with a spreadsheet at the end of the year that a tax preparer can read.

## What it does

- **Tracks drives** by GPS, by odometer readings, or by typing the miles in.
- **Detects driving automatically** using the phone's motion sensor, so a trip records
  itself without you remembering to press anything.
- **Runs on a schedule** — set your working hours and detection arms and disarms itself.
- **Classifies trips** with a swipe: right for work, left for personal.
- **Tracks expenses and revenue** with receipt photos, and works out profit.
- **Handles the IRS rate correctly**, including mid-year rate changes. 2026 splits at
  July 1st: 72.5 cents a mile through June, 76 cents from July.
- **Builds a year-end spreadsheet** with summary, trips, expenses, revenue and receipts
  on separate sheets, and hands it to your mail app.
- **Imports from Everlance** by reading the column headers rather than assuming a fixed
  layout.

## Notes on how it treats your data

- All records live in a local SQLite database. The only network traffic the app makes is
  fetching OpenStreetMap tiles to draw trip routes, which necessarily tells that server
  roughly where you drove. Turn off the map previews if that matters to you.
- `allowBackup` is on, so Android copies the database — including recorded GPS routes —
  into your Google account backup. Set `android:allowBackup="false"` in the manifest if
  you would rather it did not.
- Automatic tracking needs Location set to "Allow all the time". Without it Android
  refuses to let the app record a drive that starts while the app is closed.

## Building

Needs JDK 17+ and the Android SDK with platform 36.

```bash
./gradlew assembleDebug
```

For a signed release build, copy `keystore.properties.example` to `keystore.properties`
and point it at your own keystore, or set `MILELOG_KEYSTORE`, `MILELOG_STORE_PASSWORD`,
`MILELOG_KEY_ALIAS` and `MILELOG_KEY_PASSWORD`. Without either the release build comes
out unsigned. **The signing key is not in this repository and must never be.**

```bash
./gradlew assembleRelease
./gradlew testDebugUnitTest
```

## Layout

```
app/src/main/java/com/milelog/
  data/       Room entities, DAOs, the IRS rate table, period maths
  tracking/   Foreground service, drive detection, geocoding
  export/     xlsx writer, CSV import/export, backup and restore
  work/       Scheduled background jobs
  ui/         Compose screens and view models
```

## Stack

Kotlin, Jetpack Compose, Material 3, Room, WorkManager, Play Services Location,
osmdroid. minSdk 29, targetSdk 36.
