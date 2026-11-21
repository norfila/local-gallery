# Development

## Build

Requirements: JDK 21 for Gradle (newer JDKs are not supported by this project's Gradle version) and the Android SDK with API level 36. The Gradle wrapper is included. Toolchain: AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21, compile and target SDK 36, minimum SDK 26.

Open the project folder in Android Studio and run the app configuration, or build from the command line:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
```

On macOS or Linux use `./gradlew` with the same tasks. Select the **fullOptimized** build variant when judging scrolling performance; the debug variants include development overhead.

`scripts\build.ps1` (Windows) builds the sideloaded APKs, runs unit tests and lint, and copies the results into `dist\`. `.\scripts\build.ps1 -Play` also builds the signed Google Play bundle.

All text lives in string resources (`app/src/main/res/values/strings.xml`), with a complete German translation in `values-de`.

## Two versions

One codebase builds two apps (Gradle product flavors):

| | **full** (sideloaded) | **play** (Google Play) |
|---|---|---|
| Name | Gallery | Local Gallery |
| Application ID | `com.luma.gallery` | `com.norfila.gallery` |
| Hidden page | All files access or chosen folders | Chosen folders only |
| Signing | Development certificate (`fullOptimized`) | Upload key, Play App Signing (`playRelease`, AAB) |

Both can be installed side by side; their albums and settings are separate.

**Adding features:** write them in `app/src/main`; both versions get them automatically. A flavor only holds what differs:
- `app/src/full/AndroidManifest.xml` adds `MANAGE_EXTERNAL_STORAGE`, which Google Play does not allow for gallery apps.
- `app/src/play/res/values/strings.xml` sets the Play name.
- `BuildConfig.ALL_FILES_ACCESS` is `true` only in full. Code checks it through `AllFilesAccess.offered`, so the Play version never shows "Allow all files access".

If a new feature needs a permission or service that Google Play restricts, put the declaration in `app/src/full/` and guard the code with a `BuildConfig` flag in the same way. Device tests run against the full version (`fullDebug`).

**Release signing:** `playRelease` is signed with an upload key named in `keystore.properties` next to `settings.gradle.kts` (keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). The file is ignored by git; without it, Play builds stay unsigned.

## Privacy policy

`docs/privacy/privacy-policy.html` (English) and `privacy-policy-de.html` (German) are the published policy. Both are bundled into the app and shown under **Settings / Privacy policy**. When the app changes what it does with data, update both files and their date.

## Country data

`app/src/main/assets/countries.geojson` is generated from Natural Earth by `scripts/build_countries.py`; source, license and simplification are described in `app/src/main/assets/countries-LICENSE.txt`.

## Tests

The test suite covers metadata formats and privacy, comparison and saved filters, album persistence and database migration, country grouping, external photo requests with library permissions denied, layout and selection gestures, start-page preferences, themes, photos and videos, folders, navigation, real map tiles and pins, and real Android widget hosting. Device tests create and clean up synthetic media and test albums. UI tests expect the English locale.
