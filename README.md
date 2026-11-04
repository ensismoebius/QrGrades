# QrGrades (QrNotas)

An Android app for teachers to track student grades/points per discipline and class group, scan a student's QR code to award points on the spot, and — optionally — open a small password-protected web page on the same WiFi so the same data can be viewed/edited from a computer.

## Features

- **Students, disciplines, class groups, goals** — full CRUD management screens for each. A goal is a point target with a **start and an end date**: only the points given between those two days (both included) count towards it, so it shows as *in progress*, *upcoming* or *ended* (with its final result). Goals created before dates existed keep judging the whole period's total until they are edited.
- **QR-code scanning** — every student has a QR code; scanning it (or picking a student manually) opens a quick "award points" flow, which always records a `delta + note` entry (plus an optional category such as "Participation" or "Exam") in a points-history log rather than overwriting a raw total.
- **New QR code for a student who lost theirs** — from the QR dialog, from the *QR codes* screen or from the web page; the old printed code stops working immediately (codes that were never replaced keep working).
- **QR code sheet** — a PDF with every student's code (or one discipline's), four by five per A4 page, each labelled with the first name in full and the rest as initials; print it straight from the phone or share the file.
- **Attendance** — "attendance mode" on the scan screen marks each scanned student present in today's roll call. The *Attendance* screen takes the roll by hand for any day (present, absent or excused, one student or the whole class), lists the days, and totals presences, absences and excused absences per period, flagging whoever is over the absence limit.
- **Alerts and weekly summary** — students over the absence limit, under the discipline's lowest goal still in progress, a few points from the next one, or without points lately (with the goal's deadline); the week's points against the week before.
- **Batch points** — the same points, note and category for several students at once (everyone, nobody, invert, or just those present today), undone as a batch if needed.
- **History per student** — current and closed periods, goals, points per category, attendance; entries of the open period can be corrected or cancelled (the log keeps both the entry and its reversal) and a printable report card (PDF) is one tap away.
- **Overview** — a discipline's top ten, how its points built up over time, and every enrollment with its points.
- **Period comparison** — each student's score in every closed period side by side, with the class's average, lowest and highest.
- **CSV / JSON import & export** — bulk-import a class roster from CSV, export one student or all of them as JSON, Markdown, or PDF.
- **Backups** — manual snapshots of the database + student photos, restorable from within the app, plus an automatic daily or weekly one (taken when the app opens or the web server starts; the newest seven are kept apart from your own).
- **LAN web server mode** — toggle on a small embedded HTTP server (NanoHTTPD) so a browser on the same WiFi can view/edit the same data through a password-protected page. Plain HTTP, intended for trusted LAN use only. The page offers the same screens as the phone, so anything done on one shows up on the other.
- **Light/dark theme**, Portuguese + English strings.

The four bottom tabs are *Scan*, *Students*, *Disciplines* and *More*; *More* is the hub for the rest (attendance, batch points, alerts, history, overview, period comparison, QR codes, web server, backups).

## Requirements

- Android Studio (recent version) or the command-line Gradle wrapper.
- JDK 17.
- An Android device or emulator running **API 21 (Android 5.0) or newer**.

## Building

```bash
./gradlew assembleDebug      # build a debug APK
./gradlew installDebug       # build + install on a connected device/emulator
```

The debug APK lands in `app/build/outputs/apk/debug/`.

## Testing

```bash
./gradlew testDebugUnitTest   # unit tests (JVM, via JUnit + Robolectric)
./gradlew lintDebug           # Android Lint
./gradlew jacocoTestReport    # coverage report → app/build/reports/jacoco/jacocoTestReport/html/index.html
```

Unit tests cover the non-UI logic layer (`util/`, `model/`, database access) and the web API, which is exercised over real HTTP against a server started on a free local port (`WebServerApiTest`). Activities/Dialogs/Adapters are UI glue and are exercised manually / would need instrumented (Espresso) tests on a real device.

## Project layout

```
app/src/main/java/org/dedira/qrnotas/
├── activities/     Main, the single activity: navigation graph + floating bottom bar
├── fragments/      the screens (Scan, Students, Disciplines, More, Attendance, BatchPoints, Alerts, StudentProgress, Overview, PeriodCompare, QrSheet, ...)
├── dialogs/        modal dialogs (QrCodeDialog, NoteDialog, StudentPickerDialog, ...)
├── model/          plain data classes + the IDatabaseOnX callback interfaces
├── services/       background services (QR quick-settings tile, LAN web server and its API classes)
└── util/           database access, CSV/JSON import-export, backups, and the code both the phone and the web page report from
    │               (Insights, AttendanceReports, ReportLoader, QrSheet, ...) plus the Ui/Background/PdfPrint helpers
    └── adapters/   RecyclerView adapters for the list screens

app/src/main/assets/web/   the LAN web server's browser-side SPA (index.html, app.js with the shared parts, js/ with one file per screen)
app/src/main/res/          layouts, drawables, strings (values/ = English, values-pt/ = Portuguese)
```

## Data & privacy

All data (students, grades, photos) is stored locally in a SQLite database on the device — nothing is sent to any external server. The optional web server mode only serves data to other devices on the same local network, and only while explicitly toggled on.

## License

GPL-3.0 — see [LICENSE](LICENSE).
