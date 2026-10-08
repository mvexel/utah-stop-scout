# Developer guide

For an introduction and usage instructions, see the [README](../README.md). This guide covers local builds, authentication, campaign routing, and implementation boundaries. Read the [developer pitfalls](developer-pitfalls.md) before changing SDK or task behavior.

## Architecture

This is a separate app repository and a MapRoulette Mobile SDK consumer. The SDK handles challenge/task reads, bearer credentials, live choice checks, and task submission. AppAuth handles the browser-based OSM sign-in flow. The app itself never stores an OSM API key or directly uploads an OSM changeset; the selected MapRoulette backend applies an accepted choice edit. The map is [MapLibre](https://maplibre.org/) with the [OpenFreeMap](https://openfreemap.org/) Positron style, tinted to the app's colors.

## Current campaign routing

- **Stage** (`https://mr-stage.osm.lol`): challenge 1, Salt Lake County; challenge 2, outside Salt Lake County.
- **Production** (`https://mr-prod.osm.lol`): the campaign is not imported yet. Its allowlist is intentionally empty. The app explains this state and does not search production challenges or show unrelated tasks.

Challenge IDs are backend-local. Update `Backend.kt` only after the production campaign is imported and its IDs are verified. A challenge ID is never accepted from free-form user input.

The documented deployment starts with both backend Write Policy switches off; confirm the operator's current settings before any task-action validation. Stage and Production use separate MapRoulette data but both connect OSM edits to the live `openstreetmap.org` database. The app requires an explicit confirmation screen that lists the chosen answers and says the edit is public on OpenStreetMap. When policy is off, it presents a blocked message if the server refuses a live check or submission. It does not retry a write automatically. Keep both backend policies disabled during development unless the operator explicitly enables the selected environment.

## Build

Requirements: JDK 17, Android SDK (API 36), Android Studio with Android Gradle Plugin 9.4.1 support, and an emulator or device on Android 8.0/API 26 or later.

The standard consumer dependency is the published SDK artifact declared in `app/build.gradle.kts`:

```kotlin
implementation("com.github.mvexel:maproulette-mobile-sdk:0.1.0")
```

JitPack builds from SDK tags and the first request can take several minutes. In the author's workspace, Gradle automatically uses the sibling `maproulette-mobile-sdk/kotlin` checkout, including its live `questionIds` API. For a different checkout location, point Gradle at that Kotlin project:

```sh
./gradlew -PsdkCheckout=../maproulette-mobile-sdk/kotlin :app:assembleDebug
```

`settings.gradle.kts` substitutes a detected or explicitly configured local SDK checkout for the published coordinate. The published `0.1.0` artifact predates `questionIds`, so a standalone clone must use an SDK release that includes that API or supply `-PsdkCheckout=/path/to/maproulette-mobile-sdk/kotlin`. See [developer pitfalls](developer-pitfalls.md) for this version boundary.

OAuth client IDs are public identifiers, not secrets. Put the app-specific client IDs into the local `~/.gradle/gradle.properties` (do not commit them if your policy treats them as private configuration):

```properties
busStopStageClientId=your-stage-mobile-client-id
busStopProdClientId=your-production-mobile-client-id
```

Then build/install:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
```

A missing client ID disables sign-in for that backend but still permits public challenge/task reads where available. The app does not include an OAuth client secret.

## OAuth and write prerequisites

Register a **mobile/public OAuth client separately on each backend** with its app redirect URI. The backend uses its own confidential OpenStreetMap OAuth application and keeps those credentials server-side; this APK never receives an OSM client secret. Use the exact callback URI for the MapRoulette mobile client:

```
org.osmutah.utahbusstop:/oauth2redirect
```

The app uses AppAuth's authorization-code flow with PKCE. Request the MapRoulette grant scopes `tasks:read tasks:write osm:tagfix`; the backend may grant less, so verify the returned scopes before offering task actions. The callback scheme is also registered in `AndroidManifest.xml`. Do not use a confidential client secret in a mobile binary. The OSM OAuth app callback belongs to the backend's OAuth flow and is distinct from this app callback.

A working task submission requires all of these independently:

1. the backend knows this app's client ID and callback;
2. the user grant includes `tasks:write` and `osm:tagfix`;
3. the backend's per-environment Write Policy is enabled;
4. OSM authorization on that backend is valid for the requested tag edit.

The production selection is scaffolded for the future, but no production campaign IDs are configured. Do not test live OSM edits through either backend. Stage is a separate MapRoulette database, not a disposable OSM server; enabling its write policy can change the real map.

## UI and app behavior

The UI uses native Android Views, with screen state in `MainActivity.kt`. Shared controls, colors, and drawn bus art live in `Ui.kt`; `StopMap.kt` handles map presentation. `About.kt` builds the About screen.

`StopProximityStyle.kt` controls list emphasis: stops within 200 feet receive full green emphasis; colors blend to a neutral style at 1,000 feet. Text stays fully opaque and rows remain tappable. The approximate downtown fallback bypasses this treatment. Map marker styling is independent of list emphasis.

The app asks for location to sort campaign stops by distance. Without a usable fix, it uses downtown Salt Lake City. Stop names are looked up from OpenStreetMap. The compass rotates the list's arrows relative to the phone's heading.

Opening a stop performs a live eligibility check and filters the questions using `ChoiceEligibility.questionIds`. Questions appear one at a time, with no preselected answers. “I can't tell” omits that question. Review lists the selected answers and explains that edits are public on OpenStreetMap. Saving submits the answers to MapRoulette; the backend applies accepted OSM changes and closes the task. Partial answers also close a task.

About is available before and after sign-in and returns to welcome or nearby according to current session state. It displays the installed package version and project/map/library credits.

No auto-answering, background writes, offline queue, broad challenge search, or production campaign discovery is implemented.

## Screenshot maintenance

The images in [screenshots](screenshots/) are emulator captures. The nearby list and map use temporary local demo stops around downtown Salt Lake City, including illustrative distances that show the proximity treatment. They do not document live task availability or actual device location.

To refresh these images without exercising task actions:

1. Capture welcome and About from the normal signed-out app.
2. In a temporary local preview, populate nearby stops and names with demo data, set the search as already started, and prevent account observation from replacing the preview. Use a range of distances below 200 feet through at least 1,000 feet. Do not authenticate or open a stop.
3. Capture List and Map on an emulator. Basemap tiles can load normally. Keep OpenStreetMap attribution visible on the map.
4. Restore the original application source exactly, rebuild the final APK, and replace the emulator preview installation with the final build. Review the diff to ensure no demo data or sign-in bypass remains.
5. Resize PNG captures to a consistent width (540 px for the current set), inspect them, and label the demo data in the README.

Do not use screenshots as evidence that the survey submission flow has been validated.
