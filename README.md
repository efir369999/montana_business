<p align="center">
  <img src="https://montana.xxx/icon.png" width="132" alt="MT Business">
</p>
<h1 align="center">MT Business</h1>
<p align="center">2nd-Generation Communication. Your company's communication, centralized: every department, office and announcement in one place.</p>
<p align="center">
  <a href="https://testflight.apple.com/join/MSMfey6f"><img alt="iPhone and iPad on TestFlight" src="https://img.shields.io/badge/iPhone%20·%20iPad-TestFlight-D4AF37?style=for-the-badge&logo=apple&logoColor=white"></a>
</p>
<p align="center"><a href="https://montana.xxx">montana.xxx</a> · <a href="https://montana.xxx/support/">Support</a> · <a href="https://montana.xxx/privacy/">Privacy Policy</a> · <a href="https://montana.xxx/terms/">Terms of Use</a></p>
<p align="center">
  <img src="https://montana.xxx/screens/1.png" width="170" alt="Sign in">
  <img src="https://montana.xxx/screens/2.png" width="170" alt="Chats">
  <img src="https://montana.xxx/screens/3.png" width="170" alt="Identity">
  <img src="https://montana.xxx/screens/4.png" width="170" alt="Phone number">
</p>

## Download

| Where | How |
|---|---|
| iPhone, iPad (iOS 17.2 or later) | [TestFlight public link](https://testflight.apple.com/join/MSMfey6f) |
| Mac with Apple silicon | The same [TestFlight link](https://testflight.apple.com/join/MSMfey6f), opened on the Mac |
| App Store | Version 1.0 is being prepared for App Review |
| Files | [MT-Business.ipa](https://github.com/efir369999/montana_business/releases/latest/download/MT-Business.ipa): the App Store export, to read, compare with the source or re-sign |

## About

MT Business is the Montana messenger built for a company of about a hundred people: departments, offices, channels and
their administration, on top of everything the Montana messenger does. It is a separate application with its own identifier,
its own site ([montana.xxx](https://montana.xxx)) and its own sign-in service; the Montana messenger lives at
[montana.quest](https://montana.quest).

A person signs in with a phone number, with an e-mail address, or with a 24-word phrase generated on the phone that needs
neither. A confirmed number lets the people who have it in their address book find the person, and shows which of one's own
contacts are already here.

This repository holds the whole source of MT Business — its protocol core and its iPhone, iPad, Mac and Android clients — beside
the public record of its releases and changes. MT Business is part of the Montana Time ecosystem, whose other applications live in
[montana_messenger](https://github.com/efir369999/montana_messenger).

## What is in this repository

| Path | What it is |
|---|---|
| [core/](core/) | The protocol core: specification, reference implementation and the client core with `mt-business`; see [core/README.md](core/README.md) |
| [apps/business/ios/](apps/business/ios/) | MT Business for iPhone, iPad and Mac |
| [apps/business/android/](apps/business/android/) | MT Business for Android |
| [RELEASES.md](RELEASES.md) | The build and the source commit of every folder, written by the publisher with the folder itself; every published binary with its digest |
| [CHANGELOG.md](CHANGELOG.md) | The commits of the iOS source with their build, system and source tree, as the log stood on 2026-10-06, when it stopped being published |
| [SECURITY.md](SECURITY.md) | How to report a weakness, and what is in scope |
| [.github/](.github/ISSUE_TEMPLATE/bug_report.md) | The bug report template |

## Build from source

Xcode 26.2 on macOS 15.7 or later, Rust 1.92.0 through rustup with the device target
(`rustup target add aarch64-apple-ios --toolchain 1.92.0`). The core is built for the device, so the application is built for a
device: with your own team for signing, or with signing off to check that the source compiles.

```
cd apps/business/ios
MONTANA_CORE_SRC="$PWD/../../../core/Code/crates/mt-bindings" bash scripts/build-core.sh
MONTANA_PROTOCOL_CORE="$PWD/../../../core/Montana-Core" bash scripts/build-protocol-core.sh
bash fetch-webrtc.sh
xcodebuild build -project Montana.xcodeproj -scheme Montana -destination generic/platform=iOS CODE_SIGNING_ALLOWED=NO
```

MT Business also links the VPN's two engines, built by the scripts in `scripts/xray-ios` (Go) and `scripts/hev-ios`; those
scripts are published with the next build. Node endpoints in the tree are documentation addresses (RFC 5737); the released
build carries the live ones.

## Join the beta

| | |
|---|---|
| Platform | iPhone and iPad with iOS 17.2 or later; Apple silicon Mac |
| Distribution | TestFlight, public link; App Store after review |
| Link | https://testflight.apple.com/join/MSMfey6f |
| TestFlight | 1.0; the public link carries the newest build |
| Feedback | GitHub Issues in this repository, or contact@montana.quest |
| Privacy policy | https://montana.xxx/privacy/ |

1. Install TestFlight from the App Store.
2. Open the link above on the device and accept the invitation.
3. Install MT Business and choose how to sign in: Continue with phone number, Continue with email, or Continue with Montana.
   With Montana, write the 24 words down: they are the only way back into the identity, and nobody can restore them for you.
4. To talk to someone, open the Contacts tab: people of your address book who are already here stand there; anyone else is
   added by their Montana address or link.

A build appears on the public link after it has passed Apple's Beta App Review, which takes from a few hours to a day after
upload.

## What the application does

- **Organizations.** Departments, offices and teams each have their groups; the company and every office have channels for
  announcements, with a discussion under every post.
- **Administration.** Owners and administrators invite by link, pin posts, add and remove people and decide who writes in a
  channel. A newcomer joins every group of the department in one step, and a person who leaves is removed from all of them at once.
- **Everything of the Montana messenger.** Text, photos, video, files and voice messages; voice and video calls; replies,
  reactions, editing, forwarding, deletion for everyone.
- **Sign-in your way.** A phone number, an e-mail address, or 24 words; the Contacts tab shows who of your address book is here.

## What protects a conversation

| Layer | Primitive |
|---|---|
| Identity and signatures | ML-DSA-65 |
| Key agreement | ML-KEM-768 |
| Message content | ChaCha20-Poly1305 under the session key |
| Hashing | SHA-256 |
| Sign-in confirmations | The confirmation service signs every confirmed number or address with ML-DSA-65; the app accepts it only under the service key pinned in the Montana core |

ML-DSA-65 and ML-KEM-768 come from the Montana core, a Rust library checked against the NIST test vectors. The Montana nodes
forward sealed envelopes and never hold a key that opens them.

## The directory

Unlike the Montana messenger, MT Business keeps a directory on its confirmation service, so that colleagues find each other by
number. For each confirmed number it keeps the number, the address of the Montana identity, a hash of the latest confirmation
and the card the person publishes (name, username, the link that opens a conversation). Anyone who has the number in their
address book sees that the person is here; a search by username shows the card without the number. The address book is asked
by SHA-256 hashes of its numbers, and the questions are neither kept nor logged. Settings → Privacy → Forget this device takes
the entry away. The full statement is the [privacy policy](https://montana.xxx/privacy/).

## Diagnostics

The application sends its diagnostic journals (event records, timings, error codes, the device model, the iOS version and the
application build) to a Montana diagnostics node, where they are kept for seven days and then deleted. They never hold message
content, names, phrases or addresses.

## What to test

1. **Sign-in by phone.** Number, then Next: the confirmation opens, and the app comes back signed in. If the service does not
   answer at once, Next shows a spinner and asks again before it says anything is unavailable.
2. **Sign-in by e-mail.** A six-digit code arrives within a minute and works for 13 minutes.
3. **Contacts.** After a phone sign-in, people of your address book who are here appear in the Contacts tab.
4. **Organizations.** Create one, add a department and an office channel, invite by link, post and discuss under a post.
5. **Notifications.** A letter to a phone whose app is closed shows a notification. If notifications are off, the first slot
   of the bar shows a crossed speaker that opens the notifications page.
6. **Leaving.** Settings → Privacy → Forget this device: the number disappears from the directory.

## How to report

Open an issue with the *Bug report* template. The report is most useful when it names the build number (Settings → About), the
device model and iOS version, the network on each side, the exact time of the event and what was expected instead.

Do not paste recovery phrases, phone numbers, Montana addresses of other people, or message content into an issue. Weaknesses
go by e-mail, not into an issue: see [SECURITY.md](SECURITY.md).

## Known limits of this beta

- A phone number is confirmed through a third-party messaging service; where that service is unreachable, sign in by e-mail
  or with 24 words.
- History lives on the device; deleting the application erases the history on that device. A copy sealed under a key only your
  24 words open is kept by the people you write to (on by default; the application asks once, at the first opening), and can
  be kept in your own iCloud.
- The coins and the wallet are not yet shared with the Montana messenger.
- On Android the Montana Messenger carries the organisations; the Android source of MT Business is in `apps/business/android`.
