# Developer pitfalls from the SDK and examples

This list records the less obvious setup and behavior a developer can hit when turning a narrow MapRoulette requirement into an Android app.

## Dependency and platform setup

- The documented Android dependency comes from JitPack. The first build may wait while JitPack builds the requested SDK tag. The version in the Gradle coordinate is the git tag, not a Maven Central release.
- The current source checkout has the `ChoiceEligibility.questionIds` field for server-side live-question filtering. If a published tag predates that field, the app may compile against an API that cannot expose the live filter. Use the `sdkCheckout` composite build during development, then pin a published SDK version that includes the field.
- The Kotlin SDK is JDK 17 bytecode. A newer Android Studio install can still be configured to run Gradle with an older JDK; set Gradle JDK to 17.
- The SDK is a library, not an Android UI or login kit. The app owns Android lifecycle, screen state, callback deep links, encrypted token persistence, account switching, and error presentation. AppAuth is an app dependency for browser PKCE, not a transitive SDK feature.

## OAuth callback and scopes

- Register the redirect URI exactly, including the custom scheme and path: `org.osmutah.utahbusstop:/oauth2redirect`. A manifest callback that differs by a slash, host, or path causes the browser to finish without returning a usable grant.
- Configure a public/native OAuth client, not a confidential client. A client ID is public; a client secret cannot be kept secret in an APK.
- Register an app client on each backend and request `tasks:read tasks:write osm:tagfix`. Successful OSM login alone does not imply the MapRoulette grant includes task writes or OSM tag-fix permission. Check the granted scopes before enabling actions.
- Stage and production have separate origins and client registrations. Keep token stores bound to both origin and client ID, and do not reuse a token after switching backend.
- Never log access tokens, refresh tokens, authorization codes, or raw `whoami` responses. A legacy whoami response can contain an API key.

## API and choice task behavior

- Read endpoints are public, but a public task read is not enough to submit. Live `choice/check` requires a configured choice-task backend and may be blocked when its Write Policy is off. Show that state clearly; do not surface raw server bodies.
- Treat `choice/check` as its documented purpose-built live check. Do not probe lifecycle or write routes (`start`, `refreshLock`, `release`, skip, choice submission, or status changes) using GET requests.
- A static choice payload can list questions that are no longer relevant. Use `ChoiceEligibility.questionIds` from the fresh check to display only missing questions; `null` means the server did not provide a filter, while an empty set means no questions remain.
- `Can't tell` means omit that question from the answer map. A partial answer still closes the task; tell users before they submit. Never invent a default answer.
- `mobileSupport()` is the guard before task actions. Standard/tag-fix tasks can be readable while still not being supported for mobile choice completion.
- Use the same client to decode the task's choice outcomes. The SDK validates submitted question and option IDs against that task payload.
- The SDK does not directly edit OSM. The backend receives the submission and applies OSM changes. `tasks:write`, `osm:tagfix`, the backend's per-environment Write Policy, and valid OSM authorization are distinct gates.
- Do not blindly retry an uncertain write. Re-read/check the task and follow the SDK's typed error guidance; a repeat submission can have different safety properties from a read.

## Challenge and environment boundaries

- Challenge IDs are local to each backend. Do not assume stage ID 1/2 also means production ID 1/2.
- Keep the campaign challenge IDs in a verified allowlist. General search methods can surface unrelated projects and weaken a single-purpose app's boundary.
- Production has no Utah campaign in this setup yet. An empty allowlist is an intentional “not configured” state, not a reason to query every production challenge.
- The SDK write opt-in and backend Write Policy are separate. A client may be configured for writes while the server rejects them because its administrative policy is off. That should produce a clear no-change message and no automatic retry. In this deployment, both stage and production backend edits target the live `openstreetmap.org` API; stage is separate MapRoulette data, not a disposable OSM server.
- Validate the Android build and SDK contract with mock transports. A stage read/live-check can be a deployment check, but it is not an isolated OSM test; `choice/check` can record task staleness in MapRoulette. Do not submit OSM edits through either backend without explicit environment authorization. Never use production for task lifecycle or write validation.
