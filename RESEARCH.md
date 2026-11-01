# Index 01 ring: protocol research and plan for Wish Ring

Research done 2026-11-02 for a minimal Android app that only receives voice
recordings from a Pebble Index 01 ring and stores them as WAV files. Target:
Pixel 10 Pro Fold (codename rango), GrapheneOS build 2026102101, Android 17
(API 37), reachable over adb.

Sources read:

- The official app, `coredevices/mobileapp` (GitHub, shallow clone of `main`
  at 2026-10-30): `README.md`, `LICENSE`, `LICENSE-COMMERCIAL`, `CLAUDE.md`,
  and the `libindex` and `experimental` modules.
- Gravel, `david-cant-code/pebble-app-degoogled`: `master`, the
  `ring-support` branch (4 commits, 2026-11-01), pull request 38
  ("haversine-open", tannermeade, draft) in both of its commits, and issue 23
  ("Pebble Index", the protocol description, with all comments to
  2026-10-30).
- Maven Central metadata and artifact file listings for
  `io.github.coredevices.haversine`.
- The `coredevices` GitHub organisation (repository list and code search),
  `developer.repebble.com`, `help.repebble.com` (Index 01 collection), and
  `forum.repebble.com` (Index 01 AMA thread).

## 1. Where the ring code actually lives

The official README maps the ring to two modules: `experimental` ("Pebble
Index 01 (ring) support: continuous BLE scanning, voice-note recording and
ingestion") and `libindex` ("Index device plumbing: pairing, transfer,
storage"), with `index-ai` as the assistant. Reading them shows that neither
module contains the Bluetooth protocol. Both import
`coredevices.haversine.*` from a Maven artifact:

```
gradle/libs.versions.toml:
  coredevices-haversine = "9c2cdf1"
  coredevices-haversine = { group = "io.github.coredevices.haversine", name = "haversine", ... }
libindex/build.gradle.kts and experimental/build.gradle.kts:
  implementation(libs.coredevices.haversine)
```

What that artifact is (checked on Maven Central, latest version `2263387`,
22 git-hash versions published):

- The Android AAR holds `classes.jar` with packages `coredevices.haversine`
  (108 classes, the Kotlin Multiplatform wrapper), `com.wtlp.haversinesatellitelibrary`
  (66 classes plus `operations`, `transport`, `logging` subpackages) and
  `com.wtlp.ppcommon` (83 classes), and native libraries
  `libhaversinesatellitelibrary.so` and `libppcommon.so` for arm64-v8a,
  armeabi-v7a, x86 and x86_64.
- The `-sources.jar` on Maven Central contains only `META-INF/MANIFEST.MF`.
- The POM declares "The Apache Software License, Version 2.0" and an SCM URL
  of `https://github.com/coredevices/haversine-kmp`, which returns 404 and is
  not in the organisation's public repository list.
- The official `CLAUDE.md` confirms the native side: "the Haversine native
  library resolves `com.wtlp.haversinesatellitelibrary.logging.HaversineLog`
  by exact JVM class name".

So the ring protocol in the official app is a closed binary ("haversine", the
"satellite library") wrapped by a thin Kotlin API. The open modules only
drive it.

### What `libindex` and `experimental` do with it (visible in source)

- `libindex/.../RealScanning.kt`: a Kable `Scanner` filtered on service UUID
  `607B5C9B-3700-4E94-F44A-2DF900BCB0C3`, 30-second scan; manufacturer data
  is handed to `KMPHaversineAdvertisement.parseToStateFingerprint`, and the
  fingerprint classifies the image: `fingerprintMatchesFailsafe` ->
  Failsafe, `fingerprintMatchesPTF` -> ProductionTest, else Primary.
- `libindex/.../IndexPairing.kt` + `IndexPairing.android.kt`: pairing is a
  plain `BluetoothDevice.createBond()` with a 30 s timeout, watching
  `ACTION_BOND_STATE_CHANGED`. Unbond reasons map to results: auth failed or
  rejected -> "ring already paired to another phone", cancelled -> user
  rejected. Only a ring on its Primary image may bond. After a successful
  bond the app resets its collection index, marks previous transfers as an
  old "index iteration", calls `satellite.eraseCollections()` (wipes stored
  recordings on the ring, "in case factory recordings survive") and stores
  the ring address.
- `libindex/.../IndexPlatformBluetoothAssociations.android.kt`: reads the
  bonded-device list and warns with a notification if the app has no
  CompanionDeviceManager association ("Index 01 background access limited").
- `libindex/.../database/*`: a Room table `RingTransfer` keyed by
  `collectionStartIndex`, `isCurrentIndexIteration`, with statuses Started,
  Discarded, Failed, Completed, Saving.
- `experimental/.../service/RingPairing.kt`: on top of bonding, requires a
  Firebase user id and programs it into the ring for firmware below 3.62.0
  (`SATELLITE_FW_NO_USER_ID = "3.62.0"`); newer firmware skips this.
- `experimental/.../service/RingSync.kt`: collects
  `satelliteManager.startScanning()` and reacts to `SatelliteStatus`:
  `TransferStarted(rollover, willTransferRange)`,
  `TransferTypeDetermined(isAudio, buttonSequence, collectionStartIndex,
  final, lifetimeCollectionCount)`, `TransferComplete(samples, sampleRate,
  ...)`, `TransferFailed`, `IrrecoverableDataDetected`. On completion it
  runs `removeDCBias`, resamples to 16 kHz (`TARGET_SAMPLE_RATE = 16000`),
  writes PCM, and queues transcription. Expected hardware version is
  `(11, 0)`; scan retry interval 3 s. Firmware updates (`RingOta.kt`) fetch
  images from Core's servers.

Everything about bytes on the wire is inside the binary.

## 2. The BLE protocol

The usable description is the clean-room spec on Gravel issue 23 (jan-leila,
2026-09-22), "derived from static analysis of
`io.github.coredevices.haversine:haversine-android:2263387` ... then checked
against HCI snoop logs" of the official app syncing a firmware 3.75 ring, and
tannermeade's addition (2026-10-30) from an HCI snoop of a first pairing, a
3.67 to 3.86 firmware update and a first sync. Two independent clients were
written from that text (section 3) and both sync real rings. All integers
are little-endian.

### 2.1 Discovery and advertising

| Item | Value |
| --- | --- |
| Scan filter | service UUID `607B5C9B-3700-4E94-F44A-2DF900BCB0C3` |
| Manufacturer company id | `0x0EEA` |
| Advertised name | `Pebble Index XXX` (last hex digits of the address) |

Manufacturer data after the company id, 6 bytes:

| Byte | Field |
| --- | --- |
| 0-3 | Fingerprint, u32. `0xDEADDEAD` = failsafe image, `0xDEADBEEF` = factory-test image: do not pair with either. Low 16 bits `0xFFFF` = normal image with no user id programmed. |
| 4 | Collection count, low 8 bits of the ring's counter. This is the "new data" signal. |
| 5 bit 6 | Needs servicing: the ring wants its clock set. |
| 5 bit 5 | Recording in progress: set while the button is held. |
| 5 bits 7, 4, 3 | Moving / has debug info / unknown; not needed. |

The ring advertises after a button press and, per the help pages, goes to
sleep 30 minutes after the last recording; a button press wakes it. The
official app connects within about a second of a new item.

### 2.2 Pairing and bonding

Pairing is a standard LE bond (`createBond()`); the phone starts LE
encryption with the bond key before any protocol traffic. The spec does not
say whether the ring refuses an unencrypted link. Android 16+ encrypts a
bonded LE link on connection (flag `le_enc_on_reconnect` in the Bluetooth
module), so on Android 17 this is taken care of by the stack. Disconnect
status 137 means the ring forgot the bond; GATT status 5 or 15 means
authentication failed; both mean "pair again". The official app also records
the ring with CompanionDeviceManager, but the protocol does not need it.

The first-pairing erase (type 1 request at `0x40030000`, length 2) was
captured once and succeeds; it wipes everything stored on the ring. It is
optional and destructive, so Wish Ring does not send it: everything on the
ring is imported instead.

### 2.3 GATT link

| Role | UUID | Write type |
| --- | --- | --- |
| Service | `0000FCC9-0000-1000-8000-00805F9B34FB` (the 128-bit UUID above also appears) | |
| Control | `C0EF558A-2058-FABF-A140-8D5ACDE50B39` | without response |
| Data | `DAAD3D52-237C-90A7-B54B-8854A134D801` | without response |
| System input | `1D1F4039-23F5-33B2-C24E-704351F20585` | with response |

Handles observed on 3.75 were control `0x000F`/CCCD `0x0010`, data
`0x000C`/`0x000D`, system input `0x0012`/`0x0013`; discover by UUID.

Sequence: connect, (encryption), enable notifications on control then data,
the ring requests MTU 177 (the phone does not request one; a second request
can fail the link), run requests, then enable notifications on system input,
which the ring answers by disconnecting (reason `0x13`, about 30 ms later).
If that write fails, wait 200 ms and disconnect. Outgoing bytes are split
into MTU - 3 chunks, one write in flight at a time; the ring asks for a
12.5 ms connection interval and 1.25 s supervision timeout. GATT 133 while
connecting: close and retry, up to 5 times.

### 2.4 Request and reply

One request outstanding at a time. The phone writes a 13-byte request to
control; the ring answers with a 12-byte reply on control; bulk bytes travel
on data.

Request: `u8 type` (0 no-op, 1 erase, 2 write, 3 read, 4 cancel), `u32
address`, `u32 offset`, `u32 length` (0 on a read = whole object). A write
sends exactly `length` bytes on the data channel after the request.

Reply: `u32 status`, `u32 0` (always zero), `u32 data length`. It may arrive
split across notifications. A read's data bytes may arrive before or after
the reply; the read is done when the reply is in and received bytes >= data
length. A write's reply counts the bytes written (firmware-update capture).
Cancel (type 4, same address/offset/length) makes the original complete with
status `0x41`.

Status codes: `0x00` ok, `0x01` unknown, `0x02` firmware configuration
error, `0x03` overflow, `0x04`/`0x05` missing data, `0x06` log data already
saved, `0x07` version, `0x08` unexpected data size, `0x40` protocol base
error, `0x41` cancelled by request, `0x42` cancelled by write, `0x43` bad
request.

### 2.5 Address map

Needed for audio:

| Address | Request | Holds |
| --- | --- | --- |
| `0x40030005` | read, len 4 | stored range: `u16 start`, `u16 end` (exclusive, wraps at 65536) |
| `0x40020000 \| index` | read, len 0 | one collection, at most 4096 bytes (one flash page) |
| `0x4003000E` | read, len 10 | current advertisement as a raw AD structure: `09 FF EA 0E` + the 6 bytes of 2.1 |
| `0x40030001` | write, len 4 | ring clock, u32 Unix seconds; written when "needs servicing" |

Others (not needed): `0x40030006` firmware version (4 bytes `[flags, 0,
major, minor]`, e.g. `0b 00 03 56` = 3.86), `0x40030008` 6-byte hardware id,
`0x40000004` length-prefixed programmed serial string, `0x4003000F` RSSI
seen by the ring, `0x40050000` 44-byte capability descriptor, `0x40060002`
reboot log, `0x40010000` core dump, `0x40000002` firmware staging (erase
4096) and `0x40060000` firmware image (erase then write; erasing it alone
forces failsafe, never send).

### 2.6 Sync loop, sequencing and de-duplication

1. If the advertisement says needs servicing, write the clock first.
2. Read the range. Resume from the saved position if it lies in
   `[start, end]`, else from `start` (the ring wrapped or was erased).
3. Read each collection from the position up to `end - 1`, parse it, and
   move the position past it only once the recording it belongs to has been
   stored. The ring never deletes after a sync: the phone owns the position.
4. At `end`, read the advertisement (`0x4003000E`). If recording in
   progress, read the range again (the official app polls every 40-50 ms and
   streams audio live); otherwise done. Record the collection count the
   session ended at.
5. Connect again when the advertised count differs from that value, or
   needs servicing or recording is set. The count is 8 bits, so exactly 256
   new collections between syncs would be missed until the next change;
   Gravel adds a 6-hourly sync as a fallback.
6. On a failed read, stop without advancing; the next sync retries.

There are no acknowledgements: the collection index is the sequence number,
and de-duplication is the saved position. A re-pair resets the ring's
counter, so stored recordings are keyed by ring, "iteration" (incremented on
each pairing) and first collection index.

### 2.7 Collection format

Header: if byte 3 is `0x00`, bytes 0-2 are a 24-bit LE length equal to the
whole collection and records start at byte 4 (the only form seen on
hardware; the binary also accepts `0xFF` + u16 and a big-endian 24-bit
form with records at byte 3). A first collection of 4094 bytes was seen as
`fe 0f 00 00`.

Records: `u8 tag`, length (`u32` for tags `0x50` and `0x51`, else `u16`),
payload. Observed order in every collection: `35 52 53 51 02 25 01 54 37`.

| Tag | Payload |
| --- | --- |
| `0x51` | compressed audio (2.8) |
| `0x50` | u32 sample rate, then i16 samples (never seen on hardware) |
| `0x52` | u32 start index, u8 multi-part, u8 final |
| `0x02` | u32 ring clock, seconds |
| `0x35` | u32 clock offset (`0xFFFFFFFF` once the clock is set) |
| `0x53` | u32 bitmask (bit i = press i was long), u32 press count; gestures, running across the session |
| `0x54` | u32 lifetime collection count |
| `0x37` | u16 battery, mV |
| `0x25` | platform versions (hardware 11.0, firmware 3.75 observed) |
| `0x01` | 6-byte device id |

Timestamp: `unix = (tag02 + tag35 + 1) mod 2^32`. Before the phone sets the
clock, `0x02` counts from zero and `0x35` carries the offset; after, `0x35`
is all ones and `0x02` is Unix time.

Grouping: collections sharing a `0x52` start index are one recording, in
index order; `final = 1` closes it. A single-collection recording has
multi-part 0, final 1. A middle part is 4093-4096 bytes; the last part is
shorter. A tap or double tap produces its own small collection (80-404
bytes) that still carries a `0x51` record with 0-265 samples; decode it
normally and drop recordings shorter than about a second.

### 2.8 Audio codec (tag `0x51`)

| Offset | Field |
| --- | --- |
| 0 | config: high nibble `H` escape threshold, low nibble `L` dropped low bits; observed `0x84` |
| 1 | u32 bit count `B` (may be 0) |
| 5 | u32 sample rate; observed 9997 Hz |
| 9 | bitstream, most significant bit of each byte first |

With `W = 16 - L` and `M = 2^W`, each collection decodes from zero state:

```
delta = 0; word = 0
while bits remain (stop at bit B):
    if readBit() == 1: v = 0
    else:
        n = 0; escaped = false
        loop:
            if H <= n + 1: escaped = true; break
            b = readBit(); n += 1
            if b == 1: break
        if escaped: v = readBits(W)                 # raw W-bit value
        else:       v = readBit() == 1 ? M - n : n  # sign bit
    delta = (delta + v) mod M
    word  = (word + delta) mod M
    emit (word << L) as an unsigned 16-bit sample
```

With `H = 4`: `1` -> 0, `010` -> +1, `011` -> -1, `0010` -> +2, `00011` ->
-3, `0000` + W bits -> escape. Samples are unsigned; join the parts, then
subtract the mean (the official app's `removeDCBias`). Each 4 KiB collection
holds 0.2-0.8 s of audio; across 39 captured collections every bitstream
ended exactly at its bit count and each collection's first sample continued
the previous one's last. The official app resamples to 16 kHz for
transcription; for a plain recorder the native 9997 Hz can be written to the
WAV as is.

### 2.9 Firmware update

Not needed for a first version. The capture shows a 3.67 ring syncing
before its update and the protocol unchanged at 3.86; 3.75 was the
verification firmware; the only firmware-dependent step (user-id
programming) applies below 3.62.0 and the official app skips it above. The
update itself is a system-input write `07 00 00 00 00 00 00`, erase
`0x40000002` (4096), erase then write `0x40060000` with the image, and a
system-input write `0c 00 00 00 00 00 00`. Images come from Core's servers
and are not published.

### 2.10 Verified on hardware (2026-11-02)

Wish Ring's implementation of sections 2.1 to 2.8 was run against a real
ring from the target phone: bonding, the clock write, the advertisement and
range reads, a six-collection recording at 9997 Hz with a ring timestamp
consistent with the clock just written, and the system-input hang-up
(disconnect reason 0x13). No deviation from the description was seen.

## 3. The community clients

### 3.1 Gravel `ring-support` (david-cant-code, 2026-11-01)

A new `:ring` module (about 1,300 lines with tests) written "from the
description alone, without the library's code or decompiled output"
(`DESIGN_NOTES.md`). Layout:

- `protocol/`: `RingBytes` (LE helpers), `RingAdvertisement`, `RingRequest`
  (only ReadRange, ReadCollection, ReadAdvertisement and SetClock; "it has
  no erase or firmware request"), `RingExchange` (reply assembly, data
  before or after the reply, refusal statuses), `RingRange` (`resumeAt`),
  `RingCollection` (record parser, strict), `RingAudioCodec`,
  `RingRecordings` (grouping into recordings, 1% sample-rate tolerance,
  mean removal, signed output).
- `sync/RingSession`: one sync over a `RingConnection` interface: clock
  write when asked, then rounds of advertisement read, range read and
  collection reads, repeating while recording; the position advances past a
  recording once the sink stored it; a `FakeRing` test double plays the ring
  side from the spec.
- `link/RingLink` (Android, Kable 0.43.1): refuses an unbonded device,
  finds the three characteristics by UUID, subscribes control then data,
  watches the connection state, hangs up by subscribing to system input.
  Maps status 133 to retry and 137/5/15 to pair again.
- App side: upstream's Devices tab and pairing (CompanionDeviceManager
  registration plus `createBond`) are reused through the `LibIndex` seam;
  recordings are resampled to 16 kHz and stored by the Notebook feature;
  `RingSyncRunner` scans continuously while the background service runs,
  with backoff and a 6-hourly sync.

What it reuses from the official app: the service UUID, the Devices UI and
pairing flow, Kable. What it reimplements: every byte of the protocol.
Test reports: david-cant-code built and installed it on 2026-11-01 and
reported pairing, initial sync, live recordings and recordings made away
from the phone all working; tannermeade reported "the ring-support build
works well" the same day.

### 3.2 Pull request 38, "haversine-open" (tannermeade, draft)

A different approach: a `:haversine-open` module that reimplements the
closed artifact's Kotlin API (`KMPHaversineSatelliteManager`,
`KMPHaversineAdvertisement`, `SatelliteStatus`, `TransferStatus`,
`removeDCBias`, ...) over a pure-Kotlin engine (`gravel.ring.*`: request
codec, record parser, "RiceDelta2" decoder, sync engine, Kable transport),
so that upstream's `libindex` and `experimental` call sites compile
unchanged and the open or stub module is chosen with `-Pgravel.ring=open`.
Pairing stays in upstream `libindex`. Writes are chunked at MTU - 3, the
phone never calls `requestMtu`, a read times out after 5 s and is cancelled
with type 4, status `0x42` is retried once, `0x40060000` is never sent.
Recordings go to `files/index-recordings` as WAV plus `catalog.json`, keyed
`ring|iteration|collectionIndex`, with export as a zip through the document
picker. Its README carries a table of assumptions still unconfirmed on
hardware (stored-index semantics, tag `0x50`, tag `0x53` meaning, reply
timing, timeouts).

Status: the author calls it "mostly AI generated ... haven't reviewed most
of it yet". jan-leila tested the first build on a GrapheneOS Pixel 8 on
2026-10-23 ("Seems to work as expected"). The 2026-10-30 push replaced the
branch with Gravel's master plus notebook work, so the `haversine-open`
module now exists only in commit `729efbf7`; david-cant-code pointed this
out on 2026-11-01 and asked whether the code was fully clean-room; the
author answered that the protocol came from Wireshark captures and that he
never looked at decompiled code.

Note on the brief: the WAV-plus-`catalog.json` store belongs to PR 38, not
to the `ring-support` branch, which stores through Gravel's Notebook.

## 4. Firmware and documentation availability

- No Index 01 firmware repository exists under `coredevices`. The
  organisation's 59 public repositories include `PebbleOS` (watch firmware,
  Apache-2.0), `pebbleos-nonfree` (a few vendor drivers: cst816, gh3x2x,
  npm1300, sf32lb52), `pblboot`, `SiFli-SDK` and `hardware`. Code search
  for `nenya`, `index01`, `satellite`, `haversine` and the service UUID
  finds nothing outside `mobileapp`.
- `hardware/ring/pebble-index-01` holds only ring-sizer STL files and a
  dimensions image.
- `developer.repebble.com/index-01` documents webhooks, MCP servers and
  plugins and states: "This section documents the app side only; there is
  no ring firmware SDK."
- On the forum AMA the question "Will the firmware be open source as well?"
  has no staff answer in the thread; another post notes the product page's
  "open source" link goes to the app repository.
- So the only protocol documentation is issue 23 (plus the two clients built
  from it). No firmware images are published, and nothing a first version
  needs depends on them.

## 5. Behaviour from the help pages

From `help.repebble.com`, Index 01 collection (FAQ updated 2026-09-14):

- "Your ring is only 'connected' during transfers." Phone settings show
  "Not Connected", switch to "Connected" during a transfer, then back.
- "All recordings must be longer than 1 second." "A recording cannot be
  longer than 2 minutes."
- Recordings take "around 10-15 seconds to transfer, transcribe and appear"
  in the official app's feed; actions such as music control take 3-5 s.
- "About 5 minutes of total storage on the ring itself"; when full it
  "will record over the oldest messages until you offload to your phone".
- The ring "goes to sleep 30 minutes after last recording"; press the
  button to sync afterwards.
- The official Android flow requires a Pebble account ("Signing into an
  account is required to use Index 01") and asks for Location (while in use,
  then all the time: "We need location enabled in order for your ring to
  connect properly"), Nearby devices, Notifications, and "Background
  access". None of that is a protocol requirement: the location grant is the
  official app's choice of scan permission model, and the account exists for
  its cloud features and the pre-3.62 user-id programming.
- Hard reset ("Hard resets / How to fix most problems on Index 01"): forget
  the ring in the phone's Bluetooth settings, remove it in the app, then
  press the button short 3 times, long 3 times, short 3 times; the light
  flashes red, green and blue. Re-pair afterwards. The page does not say what
  happens to recordings stored on the ring.
- Pairing: wake the ring with its button, then tap Pair in the app.
- Default gestures in the official app: click nothing, double click play
  music, triple click next track, hold Index agent, double click and hold web
  search. Only "hold" records audio; the others arrive as small gesture
  collections.

## 6. Licensing

The official app is "dual-licensed by Core Devices LLC": GPLv3 (the
`LICENSE` file) or a paid commercial licence (`LICENSE-COMMERCIAL`:
"Licensees holding a valid commercial license with Core Devices may use
this software in accordance with the terms contained in a written agreement
between you and Core Devices"). What that means here:

- Reusing `libindex` or `experimental` code under GPLv3 is allowed if the
  whole app is GPLv3 and ships its source. But those modules do not contain
  the protocol; their value is the scanning, pairing and CDM glue, which is
  a few hundred lines of ordinary Android code. The protocol they call is
  in the closed AAR.
- The AAR's POM says Apache-2.0, but the artifact is binary-only (empty
  sources jar, private SCM, native libraries, vendor `com.wtlp` classes).
  Depending on it would make the app a wrapper around an unauditable blob,
  which is the opposite of the goal, and would drag in the Kotlin
  Multiplatform wrapper's dependency set.
- Gravel is GPLv3 as well, so porting its `:ring` module literally would
  make Wish Ring GPLv3. Its design is worth following; its text is not
  needed.
- A clean-room implementation from the issue 23 text takes constants, byte
  layouts and the decoding algorithm from a published description, which
  is how both community clients were written. It carries no licence
  obligation from the official app and can be released under any licence.

Recommendation: reimplement from the protocol description, keep the module
boundaries Gravel uses (they map one to one onto the spec and have a fake
ring for tests), and cite issue 23. Do not depend on the AAR, do not copy
GPL code, and leave the first-pairing erase and firmware update out.

## 7. Plan

### Approach

Reimplement (option 3). The protocol core is about 600 lines of pure Kotlin
plus tests, and it keeps the app free of GPL obligations, Firebase, Kable,
Koin, Room and the closed binary. The structure mirrors the spec: bytes,
advertisement, request, exchange, range, collection, codec, recordings,
session; a `RingConnection` interface separates the session from Android so
a fake ring exercises the sync logic in JVM unit tests.

BLE goes through the platform `BluetoothGatt` API with a small coroutine
wrapper instead of Kable: the link needs only connect, service discovery,
two CCCD writes, writes without response of 13 and 4 bytes (no chunking
needed, both fit the 23-byte default MTU), notifications of up to 174 bytes,
and the system-input subscription that hangs up.

### Android pieces (Android 17, GrapheneOS)

- Permissions: `BLUETOOTH_SCAN` with
  `android:usesPermissionFlags="neverForLocation"` (no location permission,
  no location toggle), `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. No
  Google Play services, no Firebase, no AndroidX: the app uses the platform
  SDK and kotlinx-coroutines only.
- Pairing: scan for the service UUID, show rings on their primary image
  (fingerprint not `0xDEADDEAD`/`0xDEADBEEF`), then `createBond()` and watch
  `ACTION_BOND_STATE_CHANGED`. CompanionDeviceManager is not required by
  the protocol; it would only add system-managed presence wake-ups and is a
  possible later improvement.
- Background: a foreground service of type `connectedDevice` keeps one
  filtered low-power BLE scan running (hardware-filtered scans survive the
  screen turning off). When the paired ring advertises a changed collection
  count, needs servicing or recording in progress, the service connects and
  runs a session, then records the count. Backoff on failures; 133 retried;
  137/5/15 reported as "pair again". The service restarts on boot while a
  ring is paired. WorkManager is AndroidX and unnecessary here.
- Battery: the app offers the system "ignore battery optimizations" dialog;
  on this phone the exemption can also be managed from `tome-config`.
- Storage: `files/recordings/<ring>-<iteration>-<index>.wav` (16-bit PCM
  mono at the ring's sample rate) plus `files/recordings/catalog.json`.
  App-private, with "Export all" copying everything to a folder chosen in
  the system document picker (`ACTION_OPEN_DOCUMENT_TREE`), so no storage
  permission is needed.
- Sync position: per ring address, the next collection index and the
  last advertised count, in SharedPreferences; an iteration counter bumps
  on every pairing so de-duplication keys stay unique after the ring's
  counter resets.
