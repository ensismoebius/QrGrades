# QrGrades (QrNotas)

An Android app for teachers to track student grades/points per discipline and class group, scan a student's QR code to award points on the spot, and — optionally — open a small password-protected web page on the same WiFi so the same data can be viewed/edited from a computer.

## Features

- **Students, disciplines, class groups, goals** — full CRUD management screens for each.
- **QR-code scanning** — every student has a QR code; scanning it (or picking a student manually) opens a quick "award points" flow, which always records a `delta + note` entry in a points-history log rather than overwriting a raw total.
- **Progress screen** — per-student points, goal progress bars, and history, grouped by discipline.
- **CSV / JSON import & export** — bulk-import a class roster from CSV, export data as JSON, Markdown, or PDF.
- **Backups** — manual or automatic zip snapshots of the database + student photos, restorable from within the app.
- **LAN web server mode** — toggle on a small embedded HTTP server (NanoHTTPD) so a browser on the same WiFi can view/edit the same data through a password-protected page. Plain HTTP, intended for trusted LAN use only.
- **Light/dark theme**, Portuguese + English strings.

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

Unit tests cover the non-UI logic layer (`util/`, `model/`, database access) — Activities/Dialogs/Adapters are UI glue and are exercised manually / would need instrumented (Espresso) tests on a real device.

## Project layout

```
app/src/main/java/org/dedira/qrnotas/
├── activities/     screens (Main, StudentList, ClassGroupList, DisciplineList, GoalList, BackupList, ...)
├── dialogs/        modal dialogs (QrCodeDialog, NoteDialog, StudentPickerDialog, ...)
├── model/          plain data classes + the IDatabaseOnX callback interfaces
├── services/       background services (QR quick-settings tile, LAN web server)
└── util/           database access, CSV/JSON import-export, backups, misc helpers
    └── adapters/   RecyclerView adapters for the list screens

app/src/main/assets/web/   the LAN web server's browser-side SPA (HTML/CSS/JS)
app/src/main/res/          layouts, drawables, strings (values/ = English, values-pt/ = Portuguese)
```

## Data & privacy

All data (students, grades, photos) is stored locally in a SQLite database on the device — nothing is sent to any external server. The optional web server mode only serves data to other devices on the same local network, and only while explicitly toggled on.

## License

GPL-3.0 — see [LICENSE](LICENSE).
