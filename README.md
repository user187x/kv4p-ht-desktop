# kv4p HT — Desktop (offline) port

A self-contained Java desktop application that controls a **kv4p HT** VHF ham‑radio
transceiver over USB serial. It is a port of the original Android app, rebuilt with
**JDK 26 + Maven + Swing** and no Android/Google dependencies.

The on‑the‑wire protocol to the ESP32 firmware is reproduced **byte‑for‑byte** from the
original app, so this desktop build talks to the same hardware/firmware unchanged.

> Amateur‑radio note: transmitting requires an appropriate license. TX is gated to the
> 2 m band (144 MHz–`maxFreq`) exactly as in the original app.

---

## Requirements

- **JDK 26** (to build and to run from source). The packaged fat‑jar runs on any JRE ≥ 26.
- **Maven 3.9+** (only to build).
- A sound card with an 8‑bit, 22.05 kHz, mono line (see *Known limitations*).
- OS serial drivers for the kv4p board's CP210x USB‑UART bridge (Silicon Labs VCP driver
  on Windows/macOS; built into the Linux kernel).

## Build

```bash
mvn clean package
```

This produces a single self‑contained jar: `target/kv4p-desktop.jar`. It bundles the app,
**jSerialComm** (with native serial libs for Windows/macOS/Linux) and **sqlite-jdbc**
(with native SQLite). Maven needs internet **once** to download these; after that the jar
runs fully offline.

## Run

```bash
java -jar target/kv4p-desktop.jar
```

1. Plug in the kv4p HT.
2. Pick the serial port (the app pre‑selects a likely CP210x port) and click **Connect**.
3. Type a frequency and **Tune**, or pick a memory.
4. Hold **PUSH TO TALK** to transmit (or enable **Sticky PTT** to toggle).

User data (memories + settings) is stored in `~/.kv4p-desktop/kv4p.db` (SQLite).

---

## How the Android app was converted

| Concern | Android original | Desktop port |
|---|---|---|
| USB serial | `usb-serial-for-android` + `UsbManager` | **jSerialComm** (`serial/SerialRadio.java`) |
| RX audio | `AudioTrack` (8‑bit PCM) | `javax.sound.sampled.SourceDataLine` (`audio/AudioEngine.java`) |
| TX audio | `AudioRecord` | `javax.sound.sampled.TargetDataLine` |
| Database | Room (`@Entity`, DAOs) | **SQLite via JDBC** (`data/AppDatabase.java`), same schema |
| Settings | `app_settings` Room table | same key/value table in SQLite |
| UI | Activities + XML layouts | **Swing** (`ui/MainWindow`, `SettingsDialog`, `MemoryDialog`) |
| Service/lifecycle | bound `Service`, `Handler`/`Looper` | plain threads + `ScheduledExecutorService` (`radio/RadioAudioService`) |
| Background threads | `android.os.Handler` | `java.util.concurrent` |

### Ported faithfully (verified by unit tests)
The entire wire protocol lives in `radio/RadioProtocol.java` and is reproduced exactly:

- 8‑byte command delimiter `FF 00 FF 00 FF 00 FF 00` and command frame layout
  (`DELIMITER + cmd + paramLen + params`).
- `ESP32Command` bytes: `PTT_DOWN=1, PTT_UP=2, TUNE_TO=3, FILTERS=4, STOP=5, GET_FIRMWARE_VER=6`.
- Serial params: 230400 baud, 8N1, RTS+DTR asserted.
- Audio: 22050 Hz, **8‑bit unsigned** PCM, mono; `SILENT_BYTE` handling for scan.
- `makeSafe2MFreq` (134–174 MHz clamp, shorthand normalization), repeater offset (±0.600),
  CTCSS tone index table, `TUNE_TO` / `FILTERS` param strings.
- RX stream de‑framer that separates audio from embedded command frames, including the
  split‑across‑reads partial‑delimiter handling.
- S‑meter 0–255 → S1–S9 scaling, software mic‑gain with clipping.

A standalone test (`/test/TestProto.java` in the build notes) exercises 35 assertions
covering all of the above, including the split‑delimiter edge case.

### ESP32 firmware flashing (ported)
Firmware flashing **is** supported. The `firmware/` package is a pure‑Java port of the
Android `bearconsole`/esptool bootloader code (`CommandInterfaceESP32` → `EspFlasher`,
`FirmwareUtils` → `FirmwareFlasher`):

- Full ESP32 ROM‑bootloader protocol: SLIP framing/escaping, `SYNC`, `SPI_ATTACH` /
  `SPI_SET_PARAMS`, and DEFLATE‑compressed `FLASH_DEFL_BEGIN`/`FLASH_DEFL_DATA` writes,
  with the original checksum seed (`0xEF`), 0x400 block size, and per‑block retry.
- The four images are flashed at the standard ESP32 Arduino offsets:
  `bootloader → 0x1000`, `partitions → 0x8000`, `boot_app0 → 0xE000`, `application → 0x10000`.
- The official **v17** binaries are bundled under `src/main/resources/firmware/` and end up
  inside the fat jar. To flash a different release without rebuilding, drop the four `.bin`
  files into `~/.kv4p-desktop/firmware/` (the bundled jar copy is tried first, then this
  directory).
- Like the Android app, the board must be put into bootloader mode by hand (the automatic
  DTR/RTS reset is unreliable across USB bridges): **hold BOOT, tap RESET, release BOOT**, then
  start the flash. The **Flash Firmware…** button and the "firmware not responding" prompt both
  open a dialog with these instructions and a progress bar.

A standalone test exercises 13 assertions over the flasher: SLIP escaping, checksum, the
DEFLATE round‑trip, the `SYNC`/`SPI_*`/`FLASH_DEFL_*` command sequence, and block‑count math.

### Deferred / not ported
These are hardware‑ or platform‑specific and are intentionally left out of this first
desktop cut (the architecture leaves clean seams to add them):

- **APRS / AFSK1200 modem and packet parsing** — the original `javAX25`/`aprs` packages.
  The RX command path and a `setCallsign`/settings hook are in place to add this later.
- **Push notifications** — not applicable on desktop.

## Known limitations

- **8‑bit audio line support:** the app opens the line in the radio's native 8‑bit unsigned
  format. If a system's mixer can't provide an 8‑bit line, the app reports it clearly. A
  straightforward enhancement is to convert to 16‑bit PCM for playback/capture and translate
  to/from the 8‑bit wire format in `AudioEngine`.
- **Port auto‑detect** uses the CP210x description (jSerialComm doesn't expose USB VID/PID
  uniformly across OSes), so you can always pick the port manually.

## Project layout

```
src/main/java/com/vagell/kv4pht/desktop/
  Main.java
  radio/    ESP32Command, MicGainBoost, RadioProtocol, RadioAudioService, RadioAudioServiceCallbacks
  serial/   SerialRadio        (jSerialComm)
  audio/    AudioEngine         (javax.sound.sampled)
  data/     ChannelMemory, AppDatabase  (SQLite/JDBC)
  firmware/ EspFlasher, FirmwareFlasher (ESP32 ROM bootloader)
  ui/       MainWindow, SettingsDialog, MemoryDialog, FirmwareDialog  (Swing)
src/main/resources/firmware/
  bootloader.bin, partitions.bin, boot_app0.bin, firmware_v17.bin  (bundled v17 firmware)
```

Licensed GPLv3, consistent with the original kv4p HT project (http://kv4p.com).
