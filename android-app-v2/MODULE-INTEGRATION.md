# android-app-v2 — module integration

How this staging tree becomes a live module in the Guardianlink repo.
All paths below are relative to the repo root unless stated. This file
lives in the staging tree; once the module is pushed it can be deleted
or kept as module documentation.

## 1. Where the files go

Copy the staging directory `android-app-v2/` (this directory) into the
repo root as `android-app-v2/`, preserving layout:

- `build.gradle.kts` → `android-app-v2/build.gradle.kts`
- `src/main/AndroidManifest.xml` → `android-app-v2/src/main/AndroidManifest.xml`
- `src/main/kotlin/guardianlink/v2/core/*.kt` → same relative paths
- `src/main/kotlin/guardianlink/v2/app/*.kt` → same relative paths
- `src/test/kotlin/guardianlink/v2/core/*.kt` → same relative paths
- `README.md` → `android-app-v2/README.md`

Do NOT copy `.github-draft/` into the module — see §3.

The existing `android-app/` module is untouched by all of this.

## 2. settings.gradle.kts — the only repo file that changes

Add exactly one line (next to the existing `include("android-app")`):

```kotlin
include("android-app-v2")
```

Do **not** disturb the `pluginManagement.resolutionStrategy` block: the
AGP plugin-marker workaround there (portal 404s the marker; mapped to
Google's Maven module) is what lets the versionless
`id("com.android.application")` in this module's build file resolve.

No root `build.gradle.kts` change is needed: the root already declares
`kotlin("jvm")`, `kotlin("android")`, and
`id("com.android.application")` apply-false at the versions this module
inherits (Kotlin 2.0.21, AGP 8.5.2). No `gradle.properties` change is
needed either: the root file already sets `android.useAndroidX=true`
(required for the androidx/security-crypto artifacts).

## 3. Workflow wiring (draft included)

`.github-draft/apk-v0.2.yml` in this staging tree is a ready-to-install
copy of the repo's `apk.yml` pattern, adapted per ANDROID-PORT-MAP §e
step 4/5:

- triggers on `android-app-v2/**` (plus the shared engine/build paths),
- runs `./gradlew :android-app-v2:testDebugUnitTest` BEFORE the assemble
  (closes the scaffold's "android-app excluded from tests" hole for the
  new module),
- builds `./gradlew :android-app-v2:assembleDebug`,
- uploads artifact `guardianlink-v0.2-debug-apk` from
  `android-app-v2/build/outputs/apk/debug/*.apk`.

Install it as `.github/workflows/apk-v0.2.yml` in the repo (via the PAT
Contents-API route, which can write `.github/workflows/*`). A separate
file is deliberate: `apk.yml` keeps building the scaffold untouched
while both apps exist.

After the workflow lands, also consider adding
`:android-app-v2:testDebugUnitTest` to `ci.yml`'s gradle invocation in
a follow-up change — the draft workflow already runs the tests, so this
is consolidation, not coverage.

## 4. Exact push sequence (for the parent to execute, on Caleb's word)

1. Push `android-app-v2/**` (all files listed in §1) via `gh-put-file`.
2. Push the one-line `settings.gradle.kts` change.
3. Push `.github/workflows/apk-v0.2.yml` (content of `.github-draft/apk-v0.2.yml`).
4. `chmod +x gradlew` is already the workflow's first step (Contents-API
   pushes land non-executable — the existing workflows handle this).
5. Confirm the Actions run is green: unit tests + assemble.
6. Download the `guardianlink-v0.2-debug-apk` artifact; record the APK
   SHA-256 alongside the commit hash it was built from (the v0.1.0
   hash-publication discipline).
7. On-device smoke on the Galaxy A36 (cannot be done in CI): install,
   walk the conversational flows, start sensing, verify the FGS
   survives screen-off and a reboot (BootReceiver path), deny
   permissions in turn and verify honest degradation (spec §6.10/§6.11).

## 5. CI-verify-first list (unverified locally — no SDK/kotlinc here)

Everything in this module was written against the engine sources at
`main` @ `50ae09dfbf25107b967ed154d6a0af45f255db6c` and statically
checked, but NOT compiled. The first CI run is the first compiler. The
spots most worth watching, in order:

1. `testOptions`/JUnit platform wiring for local unit tests under
   AGP 8.5.2 (`tasks.withType<Test> { useJUnitPlatform() }` in the
   module build file) — if the platform is not picked up, tests
   silently run zero suites; the CI log must show the 26 tests.
2. `ProductionVerifiers` / `NineGates` / `ConciergeInterface` named-
   argument construction in `GuardianLinkV2App` (signatures read from
   source; any drift since 50ae09df shows here first).
3. The typed `startForeground(id, notification, type)` call guarded to
   API 34+ (`ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`).
4. `Context.registerReceiver(..., Context.RECEIVER_NOT_EXPORTED)` on
   API 33+ for system broadcasts.
