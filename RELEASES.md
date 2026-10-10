# Releases

The binary is attached to the releases of this repository; on iPhone, iPad and Mac MT Business installs through
TestFlight. Each row names the folder that holds the source and the commit of the project's history that folder is.

## Sources

Each folder of this repository is the tree of one commit of the project's history, staged as it is built. The publisher writes this table in the same commit as the folder it names; the message of that commit names the source commit in full.

<!-- sources:start -->
| Folder | Build | Source commit | Staged |
|---|---|---|---|
| `apps/business/android` | 39 | `f45fc3b2639e` | 2026-10-10 11:53 UTC |
| `apps/business/ios` | 77 | `e9a8f8905954` | 2026-10-10 11:53 UTC |
| `core` | core line | `449a706c2752` | 2026-10-10 11:53 UTC |
<!-- sources:end -->

## 2026-10-08: MT Business 1.0 (68)

| File | Application | Version | SHA-256 | Source |
|---|---|---|---|---|
| `MT-Business.ipa` | MT Business, iPhone, iPad, Mac | 1.0 (68) | `2fdb4bfd0255915adf64d3485211a8aca0703191aa18fc8ff41bfe51d5222c14` | `apps/business/ios`, commit `dce5cc52` |

The protocol core of this build is [`core/`](core/), commit `7c93a6c6` of the core's history. The Android source in
`apps/business/android` is commit `f45fc3b2`; on Android the Montana Messenger carries the whole picture, so no separate
Android package of MT Business is released.

The IPA file is the export uploaded to the App Store, signed for distribution there: it is published to be read, compared
with the source or re-signed.

## TestFlight

| Build | Date (UTC) |
|---|---|
| 1.0 (68) | 2026-10-08 |
| 1.0 (46) | 2026-10-06 |
| 1.0 (45) | 2026-10-06 |
| 1.0 (44) | 2026-10-06 |
