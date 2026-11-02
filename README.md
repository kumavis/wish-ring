# Wish Ring

A small Android app that does one thing: it receives the voice recordings a
Pebble Index 01 ring holds and saves them on the phone as WAV files. No
Pebble account, no cloud, no transcription, no Google Play services, no
Firebase, no AndroidX. The only runtime dependency is kotlinx-coroutines.

The ring protocol was reimplemented from the public description on
[pebble-app-degoogled issue 23](https://github.com/david-cant-code/pebble-app-degoogled/issues/23);
see [RESEARCH.md](RESEARCH.md) for the protocol, the sources that were
read, the licensing reasoning and the design.

Built for a Pixel 10 Pro Fold on GrapheneOS (Android 17), but nothing in it
is specific to that phone beyond `minSdk 34`.

## What it does

- Scans for a ring, bonds with it (standard Bluetooth LE pairing) and
  remembers it.
- Runs a foreground service that keeps a filtered, low-power Bluetooth scan
  going. When the ring advertises a new collection count (it does so after a
  button press), the service connects, pulls every collection the phone has
  not stored yet, joins the parts into recordings and writes each one as
  `files/recordings/<ring>-<iteration>-<index>.wav` plus an entry in
  `files/recordings/catalog.json`.
- Shows the recordings, newest first; tap one to play it, long-press to
  delete it. "Export all" copies every WAV and the catalog into a folder you
  pick in the system document picker.
- Restarts the service after a reboot while a ring is paired.

It does not erase anything on the ring, never sends a firmware update, and
never programs a user id. Audio is kept at the ring's own sample rate
(about 10 kHz, 16-bit mono).

## Build

Requirements: JDK 17 and an Android SDK with platform 36 and build-tools 36
(`local.properties` points at `~/Library/Android/sdk`; adjust it for another
machine). The Gradle wrapper fetches Gradle 8.14.3.

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew assembleDebug testDebugUnitTest
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. The unit
tests cover the audio codec, the collection parser, the request and reply
framing, the range logic and the sync session against a fake ring.

## Install with adb

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To watch it work:

```bash
adb logcat -s RingService RingGatt RingSession AndroidRuntime
```

## Pair the ring

1. Open Wish Ring and grant the Bluetooth ("Nearby devices") and
   notification permissions it asks for. No location permission is needed.
2. Tap **Pair ring**, then press the ring's button once so it wakes up and
   advertises. It appears in the list as `Pebble Index …` with its address.
3. Tap it. Android shows its pairing dialog; accept it. When the bond
   completes the app returns to the main screen and starts the background
   service, which syncs anything already stored on the ring.
4. Tap **Battery** and allow the app to ignore battery optimisation, so the
   scan keeps running when the phone is idle (on this phone the exemption
   can also be set from `tome-config`).

Then hold the ring's button to record (1 s to 2 min). After you release it
the ring advertises the new count, the service connects, and the recording
shows up in the list within a few seconds. If the phone was away, the ring
keeps about five minutes of audio and hands it over the next time it is
heard; press the button once if the ring has gone to sleep (it does after
30 minutes).

A ring that is still bonded to another phone refuses to pair. Remove it
there first, or hard-reset the ring: press short 3 times, long 3 times,
short 3 times; the light flashes red, green and blue. Then forget it in
Android's Bluetooth settings and pair again.

**Forget ring** clears the pairing in the app only; remove the ring in
Android's Bluetooth settings as well before pairing it with anything else.
Pairing again starts a new "iteration", so recordings already on the phone
are kept and new ones never collide with them.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/kotlin/me/kumavis/wishring/protocol/` | The ring protocol: advertisement, requests, reply framing, stored range, collection records, audio codec, grouping into recordings. Pure Kotlin. |
| `.../sync/` | The sync session over an abstract connection, and the position it keeps. |
| `.../ble/RingGatt.kt` | The Bluetooth link on the platform `BluetoothGatt` API. |
| `.../store/` | WAV writer and the catalog. |
| `.../service/` | The foreground service, its status, the boot receiver. |
| `.../ui/` | The two screens. |
| `app/src/test/` | JVM unit tests, including a fake ring. |

## Status

Tested on 2026-11-02 against a real Index 01 from a Pixel 10 Pro Fold on
GrapheneOS: bonding completed through Android's pairing dialog, the first
session set the ring's clock and read an empty range, and a 2.2 s recording
made next to the phone arrived two seconds after the button was released as
six collections joined into one 9997 Hz WAV with the ring's own timestamp.
The ring hung up itself after the system-input subscription, as the
protocol description says. Not yet exercised: a recording made out of range
and synced on a later button press, and long periods in the background.

## License

[MIT](LICENSE).
