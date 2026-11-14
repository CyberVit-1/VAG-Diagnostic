# AGENTS.md — VAG Diagnostic

These are repository-wide instructions for coding agents and contributors. Read
the workspace-local project instructions, the relevant Android/Kotlin source files, and
`.agents/skills/vag-diagnostic/SKILL.md` **before** modifying the VAG diagnostic
application, its build workflow, transports, protocols, storage, or UI.

## Non-negotiable project rules

1. **Real vehicle evidence is required.** A successful build, parser test,
   emulator session, or static review is not proof that any diagnostic feature
   works with a physical ELM327 on a car. State verification levels explicitly.
2. **Read-only diagnostics.** Do not implement DTC clearing, coding,
   adaptations, programming, ECU actuation, Security Access, or bypasses.
3. **No fake data.** Do not invent vehicles, available control units, DTCs,
   responses, status bytes, descriptions, timing, or hardware test results.
   Missing, partial, or negative answers must remain visible as such; never
   relabel `NO DATA`, a timeout, or an unanswered ECU as "no faults".
4. **Do not confuse OBD-II with VAG Auto-Scan.** A generic OBD responder does
   not prove the presence of VAG ECU addresses or a complete gateway
   installation list. Do not scan arbitrary CAN IDs.
5. **Preserve transport isolation.** Keep Wi-Fi TCP, Bluetooth Classic SPP,
   and BLE implementations independent and regression-safe. A transport
   connection is not evidence of diagnostic communication with the vehicle.
6. **Treat Wi-Fi ELM327 as potentially internet-less.** Use the relevant
   Android `Network.socketFactory` for the adapter TCP socket; do not
   globally bind the app process to adapter Wi-Fi. Diagnostics must continue
   offline, even when online DTC descriptions cannot load.
7. **Preserve diagnostic evidence.** Persist raw requests, raw responses,
   parse outcomes, timestamps, timeouts, and partial scan results; never
   silently overwrite logs or delete them on an upgrade. Redact VIN and
   personal data when exporting or sharing logs.
8. **Respect the existing architecture.** UI must not drive wire protocols;
   protocol handling must not own Compose state. Confirm current source
   behavior before editing; prefer a minimal change over a broad rewrite.
9. **Do not discuss vehicle battery/board voltage in test analysis unless
   explicitly requested.**
10. **Report each significant change** with version, exact changes, confirmed
    capabilities, *not-yet-confirmed* capabilities, limitations, GitHub
    build/CI evidence, **"Что требуется от пользователя сейчас"**
    (or **"Ничего не требуется"**), and the next development step.

## Mandatory data-loss warning before user actions

- **Latest user agreement, 11.11.2026:** routine test builds are installed
  cleanly after the user sends the exported TXT in chat. Do not recommend
  installing over the existing app or repeat the standard backup/update
  boilerplate on every build. The user knows the clean-install workflow.
  The warnings below apply when a **new material data-loss risk** is introduced,
  not as a reason to repeat already acknowledged routine instructions.
- **Warn BEFORE recommending any potentially destructive step.** This includes
  uninstalling/reinstalling an APK, clearing app storage or cache when data may
  be affected, changing Android package ID/applicationId/signing identity,
  database migrations, importing/restoring backups, or switching test builds.
- State explicitly whether existing scans, Room history, settings, and raw
  diagnostic journals will survive the action. Say when preservation is
  unknown; do not promise continuity without checking Android package/signature
  compatibility and migration implementation.
- **Export and verify a backup FIRST** before proposing an action with data-loss
  risk. A successfully exported external TXT is independent of application
  storage; exporting raw logs does not necessarily back up the entire history.
- Keep **one permanent Android applicationId** `com.vagdiagnostic.app` for
  future candidate and production versions; do **not** generate new
  `.pilot2`, `.pilot3` or per-release package IDs. When CI debug signing
  differs, warn about potential uninstall/reinstall and data loss first.
  A saved TXT is independent evidence, not a complete Room backup.
  The user routinely removes previous experimental builds after exporting TXT;
  do not insist on retaining outdated applications.
- No deletion or reset instruction until the warning is delivered and the user
  has had a chance to preserve evidence. Do not assume the user can recover
  data after an uninstall. Historical logs supplied in chat remain a separate
  source, but do not constitute recovery of the original Room database.
- Every APK handoff must state: **install over existing or alongside it?**
  **Will historical data be retained?** **What must be backed up first?**

## Full-path diagnostic reliability gate

- Follow the full workspace-local product specification,
  including accurate OBD-II/CAN/ISO-TP/UDS/KWP limits, Auto-Scan roadmap,
  Android UI requirements, research, test evidence and release criteria.
- Before recommending a new vehicle test, proactively analyze and improve
  connection **and** diagnostics from end to end: Android Wi-Fi routing,
  socket lifecycle, AT reset/initialization, prompt parsing, packed CAN,
  protocol negotiation, pauses and timeouts, retries/recovery, DTC 03/07/0A,
  bad/partial responses, state transitions and exported logs.
- Test the happy path and plausible adjacent failures with repeatable tests,
  not only the latest visible stack trace or false green status indicator.
- Preserve Bluetooth Classic and BLE transports while modifying Wi-Fi code.
  Clearly distinguish code verified in GitHub CI from real car operation.
- Do not request another vehicle visit after each tiny change; bundle fixes
  into a consolidated candidate with demonstrated software checks. Do not
  claim stable hardware diagnostics without a corresponding physical test.

## Repository workflow

- **Standing user instruction, 12.11.2026:** when asked to continue, work
  autonomously through research, fixes, regression checks and packaging until
  the next necessary real-car test has a concrete verified APK. Do not end at
  an internal milestone waiting for another "continue". Bundle changes into
  one vehicle trial; stop earlier only for a genuine blocker requiring input.
- Base changes on the latest verified branch/version, read
  the workspace-local project context and `RESEARCH.md`, and respect the current PR.
- Avoid committing directly to `main` during unverified development.
- Run or inspect GitHub Actions (`.github/workflows/android.yml`) after
  behavior-changing changes. Never claim a successful APK without a successful
  workflow run and a real APK artifact.
- Update the workspace-local project context after meaningful implementation or
  verification changes. Separate **implemented in code**, **compiled/checked**,
  and **physically verified** states.
- The complete project-specific development procedure is in
  `.agents/skills/vag-diagnostic/SKILL.md`. Use it whenever a task concerns
  Android development, ELM327 transport, DTC parsing, diagnostics, or releases.
