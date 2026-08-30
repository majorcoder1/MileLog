# MileLog

MileLog is a privacy-first Android app for independent drivers who need a clear mileage and expense record for tax time.

It keeps the data on the phone, records trips with GPS, tracks expenses and revenue, and creates a year-end Excel workbook for a tax preparer. The app is designed around a light gray interface with blue controls and readable green income totals.

## What it does

- Records a trip manually or with automatic drive detection.
- Sorts trips into business, personal, medical, charity, moving, commute, or a custom purpose.
- Tracks vehicles, odometer readings, work schedules, service reminders, receipts, expenses, and revenue.
- Suggests merchants such as Murphy USA while entering an expense, and remembers how they were categorized.
- Shows mileage, revenue, expenses, and profit over today, week, month, year, prior periods, or a custom span.
- Calculates the standard mileage deduction from date-based IRS mileage rates, including the 2026 mid-year change.
- Exports trips, transactions, totals, and receipt references to an `.xlsx` workbook.
- Imports Everlance CSV exports one file at a time, previewing results and skipping likely duplicates.
- Makes a daily local backup and participates in Android's built-in device backup.

## Requirements

- Android 10 (API 29) or newer; Android 13+ recommended.
- Location permission for trip recording.
- **Allow all the time** location permission for automatic drive detection while the app is closed.
- Physical activity permission for automatic drive detection.

## Build

1. Install Android SDK Platform 36 and Java 17 or later.
2. Add a `local.properties` file with your Android SDK path.
3. Build a debug APK:

   ```bash
   ./gradlew assembleDebug
   ```

4. Find it at `app/build/outputs/apk/debug/app-debug.apk`.

## Release signing

Never commit a signing key or its passwords. Copy `keystore.properties.example` to `keystore.properties`, fill it in locally, and keep both the properties file and `.jks` file outside version control.

## Data and privacy

MileLog does not require an account. Trips, expenses, receipts, backups, and exports live on the device. Map tiles are fetched only when a map is displayed. Emailing a spreadsheet opens the phone's mail app with the attachment; MileLog does not send it on its own.

## Tax note

This app organizes records; it is not tax advice. Confirm your deduction method and eligibility with a qualified tax professional. Standard mileage rates can change, so they are editable in the app's settings.

## Repository safety

This repository intentionally excludes local Android SDK settings, release signing keys, passwords, APKs, build output, and any user data.
