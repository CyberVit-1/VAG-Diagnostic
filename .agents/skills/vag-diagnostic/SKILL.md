---
name: vag-diagnostic
description: >-
  Develop, review, debug, test, and release the VAG Diagnostic Android app:
  Kotlin, Jetpack Compose, ELM327 Wi-Fi/Bluetooth/BLE transports,
  OBD-II/ISO-TP/UDS/KWP diagnostics, fault-code parsing, GitHub Actions APK
  builds, persistent logs, and real-car validation. Use for any change or
  investigation involving VAG Diagnostic behavior, reliability, or versions.
---

# VAG Diagnostic Engineering

Apply this skill when working on the Android app for Volkswagen, Audi, Škoda,
SEAT, and CUPRA diagnosis. The primary outcome is *reliable, real communication
with a physical adapter and a vehicle*, not a convincing simulation.

## 1. Establish the baseline first

1. Read the repository root `AGENTS.md`, `PROJECT_CONTEXT.md`,
   `RESEARCH.md`, `BUILDING.md`, and the source files involved in this
   change. Prefer the newest Git commit and actual source over outdated
   summaries. Treat earlier archives only as historical evidence.
2. Record the version, branch, last **confirmed** hardware behavior,
   available logs, unresolved regression, and intended smallest change.
3. Determine whether the task is a transport problem, ELM initialization,
   protocol/addressing issue, response parsing problem, persistence issue,
   or Compose UI problem. Isolate it before changing unrelated layers.
4. Preserve working behavior. Never silently replace or broadly refactor
   Wi-Fi, Bluetooth Classic, or BLE while fixing another transport.
5. Make no capability claim without identifiable evidence. If the user
   mentions an earlier successful car test, preserve that as historical
   evidence, not proof of a changed current build.

## 2. Verification labels — use these exact distinctions

| Label | Meaning |
| --- | --- |
| Implemented | Code exists and can be inspected |
| Compile-checked | Android/Kotlin CI built the relevant code |
| Software-tested | Unit, parser, mock, emulator, or simulator check passed |
| Vehicle-confirmed | The **specific build, adapter, transport, car, command and result** have a real physical test/log |
| Unsupported/unverified | Cannot be established from the available interface, hardware, logs, or tests |

A green GitHub Action and an APK artifact are **not** vehicle validation.
A real car session for Wi-Fi says nothing conclusive about SPP or BLE.

## 3. Architecture boundaries

Keep the following separations (actual code paths may evolve):

- **UI:** Compose Material 3 + ViewModel + StateFlow; render state, never issue
  raw AT/UDS commands in Composables.
- **Domain:** ECU discovery queue, scan progress, DTC/state models, cancellation.
- **Transport:** independent Wi-Fi TCP, Bluetooth Classic SPP, and BLE GATT
  stream adapters implementing a common contract.
- **Diagnostic core:** sequential ELM327 command engine, frame parsing,
  protocol-aware OBD-II, ISO-TP, UDS and any supported KWP implementation.
- **Data:** Room history and event logs, DataStore settings, description cache.
- **Network:** online DTC sources and Yandex intent, separate from vehicle TCP.

Do not let transport or protocol modules import UI state. Preserve stable
interfaces unless an explicit compatibility migration is planned.

## 4. ELM327 connection and transaction discipline

1. Distinguish **physical transport connected**, **ELM command channel ready**,
   and **vehicle diagnostic response validated**. Do not collapse these states.
2. Set up a single in-flight command/request queue per adapter. Avoid
   competing readers and overlapping requests.
3. Normalize line endings, echo, whitespace and optional header formatting.
   Wait for a complete response ending with an ELM `>` prompt or a bounded
   timeout. Assemble fragmented TCP, SPP and BLE streams, not individual
   packet boundaries.
4. Check each AT command's actual response. The reported ELM327 version is
   not evidence that a clone supports the command. Handle `?`,
   `NO DATA`, `UNABLE TO CONNECT`, `STOPPED`, `BUFFER FULL`,
   partial frames, malformed hex and timeouts as distinct outcomes.
5. Adapt timeouts to the transport and measured behavior with finite,
   bounded retries. Preserve cancellation and clean socket/GATT shutdown.
6. Never force the entire app onto an internet-less Wi-Fi ELM adapter;
   use Android `Network.socketFactory` for diagnostic TCP. If a mobile
   validated network exists, online DTC lookups may use it independently.
7. Test reconnection and cancellation without corrupting results or logs.
8. A successfully opened Wi-Fi socket or GATT characteristic alone is
   not proof that an ECU responded.

## 5. Protocol and ECU-scanning safety

- **OBD-II**: recognize Mode 03 confirmed, 07 pending and 0A permanent DTC
  responses only with verified positive service IDs and complete frames.
  Interpret any CAN DTC-count field only in its appropriate framing context.
  Count unique faults according to source semantics; do not fabricate codes.
- **ISO-TP**: validate single/first/consecutive-frame lengths, sequence,
  completion and timeouts; reject truncated multi-frame replies.
- **UDS**: treat `0x19` ReadDTCInformation as a **service ID**; VAG gateway
  address `19` is a different, logical ECU address. Decode subfunctions,
  status masks, negative responses and data completeness explicitly.
- **KWP2000/KWP1281/VW TP 2.0**: implement only for documented, verified
  addressing and compatible hardware. Do not extrapolate ELM327 CAN
  pass-through capabilities to full VAG protocol support.
- **Discovery**: prefer genuine gateway Installation List when accessible
  through a verified protocol. Otherwise use a confirmed platform profile
  and a bounded, documented address list. Do not blindly sweep CAN IDs.
- Never convert unavailable, security-denied, timed-out, `NO DATA` or
  undecodable ECU communication into `Ошибок нет`.
- Keep this app **read-only**. Never add Mode 04 DTC clearing, DTC erase,
  Security Access, adaptation, coding, programming, actuator controls,
  transport-layer bypasses, or other writes to ECUs.

## 6. Logs, descriptions, and offline behavior

- Persist the raw adapter command/response bytes or faithful text, timestamp,
  latency, selected transport, parser outcome, timeouts and reason for failure.
- Save partial successes as the scan progresses. Persist between launches.
  Do not overwrite older car-test logs, erase data on update, or reset Room
  schemas without an explicit migration.
- Mask VIN and similar identifiers before export/sharing when requested or
  when an export is meant for wider technical review.
- Do not dwell on electrical system or battery voltage unless user asks.
- Resolve DTC descriptions only from a traceable local source or a lawful,
  attributable online source. Display `Описание не найдено` when unknown,
  and `Нет интернета` when an online lookup cannot run; keep the code visible.
- Tapping an error with verified internet uses external
  `Intent.ACTION_VIEW` and URL-encoded query
  `VAG fault code <DTC>` on Yandex. Without usable internet, don't launch
  the browser; show `Поиск недоступен: нет подключения к сети`.
- Offline/no-mobile-data conditions must never interrupt ECU diagnosis.

## 7. Compose UX and scan-state integrity

- Maintain live counts: discovered, polled, successfully read, ECUs with
  faults and total valid DTCs. Never report a completion percentage before
  the total scope is known.
- Keep statuses separate: not scanned, successfully read/no errors,
  successfully read/with errors, unavailable, partial or failed.
- ECU tiles should expand/collapse smoothly without losing scroll position,
  flickering or resetting expansion on incoming updates.
- Use Russian UI strings, accessible labels, dark/light themes and deliberate
  Material 3 spacing; do not trade away diagnostic correctness for polish.

## 7a. Prevent accidental data loss during APK upgrades

Before giving a download/install/test instruction:

1. Inspect the old and new `applicationId` (including any debug
   `applicationIdSuffix`), signing certificate compatibility, Room schema and
   migration, and storage/export behavior. Confirm what is known and unknown.
2. **Warn in advance, prominently**, if an action may erase local history,
   raw ELM journals or settings: uninstall, reinstall, clear storage, incompatible
   signer, different package, or destructive database migration.
3. Ask the user to export the existing TXT diagnostic log and verify the saved
   file in external storage *before* any such action. Explicitly explain that
   TXT export preserves shared evidence but does not recreate the Room
   database, history screen, or preferences.
4. If package IDs differ, say explicitly that both apps may be installed
   side by side and that the new installation has an independent empty
   database. If signing prevents an in-place update, never recommend
   uninstalling the old build as the first fix.
5. Include a short section **`Внимание: сохранность данных`** directly
   before any user installation/upgrade steps, even if the update is believed
   to be safe. Describe install mode, expected preservation and backup plan.
6. Treat prior deletion as an established fact and do not continue asking
   the user to preserve an app they have already uninstalled.

## 7b. Master prompt and proactive troubleshooting requirements

- Read the **entire original 15-section master prompt** inside root
  `AGENTS.md`, with an unchanged standalone copy at `MASTER_PROMPT.md`.
  The first-line role description is only one part of the specification.
- User expectations are engineering requirements: diagnose beyond the last
  reported error, preserve the protocol/transport architecture, prevent
  repeated field trips for minor fixes, confirm every claimed feature on a
  real vehicle, and avoid specific car/adapter coupling.
- Before proposing an APK to test on a car, review every stage from transport
  and AT initialization to OBD `0100` confirmation, idle socket handling,
  ISO-TP response parsing, the `03/07/0A` DTC sequence, cancellation,
  connection flags and RAW journal export. Correct plausible failure paths
  and run repeatable software checks first.
- Future releases have a single stable package name
  `com.vagdiagnostic.app`. Never create extra `pilotN` applications as
  a substitute for proper installation/signature handling. Before a required
  uninstall, warn that app-internal journals, settings and history are lost;
  preserve user-exported TXT, then let the user remove obsolete test apps.
- Different versions of a **debug** APK may still have incompatible signing
  keys; package-name consistency does not guarantee automatic updating.
  Do not commit release signing keys. State this briefly when relevant.

## 8. Change → check → release loop

1. Define the targeted behavior, preserved behavior and negative cases.
2. Implement the smallest cohesive change; do not change transports not
   involved unless a documented interface contract requires it.
3. Add or update parser, timeout, incomplete-frame, cancellation,
   storage and UI-state software checks as relevant.
4. Run the GitHub Android debug build and relevant checks. The reference
   workflow is `.github/workflows/android.yml`. Inspect the job conclusion,
   failure logs, test outcomes and **artifact**. Confirm APK identity before
   describing it as built.
5. Keep all unknown hardware properties *unverified*. Ask for a real test
   only when a specific APK and a simple, safe test procedure exist.
6. Real-car test record should identify APK version/commit, adapter model and
   transport, car generation/platform if safely known, exact action, command
   exchange, observed result, and exported raw log. Never synthesize results.
7. Update `PROJECT_CONTEXT.md` with baseline, changes, checks, supported
   capabilities, limitations and next stage. Preserve previous decisions.
8. Prefer work in a feature branch/PR. Avoid changing `main` while hardware
   regression risk remains or without a merge decision.

## 9. Mandatory update report (Russian)

Every significant delivery must include all seven entries (in prose or a
short table, no empty headings):

1. **Версия** (do not bump for documentation-only changes without reason).
2. **Изменения** (specific files/behavior and GitHub commit or PR).
3. **Реально подтверждено** (clearly distinguish CI from physical car tests).
4. **Не подтверждено** (per transport/protocol).
5. **Ограничения и известные проблемы**.
6. **Что требуется от пользователя сейчас** — an exact action, or
   **Ничего не требуется**.
7. **Что дальше** — one concrete next engineering step.

If the task is a question and no release is made, answer it directly and
give only the actions actually required.

## 10. Reference files

Read from repository root:
- `PROJECT_CONTEXT.md` — source of truth for version, history and evidence.
- `RESEARCH.md` — documented algorithm limits and sources.
- `BUILDING.md` — current installation/build guidance.
- `GITHUB_BOOTSTRAP.md` — initial GitHub source import and CI records.
- `AGENTS.md` — mandatory repository-wide invariants.

If any reference disagrees with actual source or current CI results, flag the
disagreement explicitly and update docs only after checking evidence.
