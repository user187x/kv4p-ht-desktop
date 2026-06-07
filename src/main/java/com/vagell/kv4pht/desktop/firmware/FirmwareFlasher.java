/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)

Desktop port of com.vagell.kv4pht.firmware.FirmwareUtils. Flashes the four
images that make up a kv4p HT install at the standard ESP32 Arduino offsets:

    bootloader   -> 0x1000
    partitions   -> 0x8000
    boot_app0    -> 0xE000   (the Arduino OTA selector; never changes)
    application  -> 0x10000

On Android the binaries were res/raw resources. Here they're loaded from the
classpath (bundled under /firmware in the jar) with a fallback to
~/.kv4p-desktop/firmware so a user can drop in a newer release without
rebuilding. The bundled set is the official v17 release pulled from the kv4p-ht
repository; the flash offsets are identical to the Android version.

The progress curve mirrors the original: 10/20 for connect+init, then the four
writes are spread across 20..100%.
*/
package com.vagell.kv4pht.desktop.firmware;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class FirmwareFlasher {

  /** Version of the firmware bundled with this build. */
  public static final int PACKAGED_FIRMWARE_VER = 17;

  // (resource/file name, flash offset) in flash order.
  private static final String[] FILE_NAMES = {
    "bootloader.bin", "partitions.bin", "boot_app0.bin", "firmware_v17.bin"
  };
  private static final int[] FILE_OFFSETS = {0x1000, 0x8000, 0xE000, 0x10000};

  public interface Callback {
    void connectedToBootloader();

    void reportProgress(int percent);

    void doneFlashing(boolean success);

    default void info(String message) {}
  }

  private volatile boolean flashing = false;
  private int progressPercent = 0;

  public boolean isFlashing() {
    return flashing;
  }

  /**
   * Runs the full flash sequence on the calling thread (callers run this off the EDT). {@code io}
   * must be a serial channel to a board already in bootloader mode.
   */
  public void flashFirmware(EspFlasher.SerialIO io, Callback callback) {
    if (flashing) return;
    flashing = true;
    progressPercent = 0;
    boolean failed = false;

    try {
      byte[][] images = new byte[FILE_NAMES.length][];
      for (int i = 0; i < FILE_NAMES.length; i++) {
        images[i] = loadFirmware(FILE_NAMES[i]);
        if (images[i] == null || images[i].length == 0) {
          callback.info("Could not find firmware file: " + FILE_NAMES[i]);
          callback.doneFlashing(false);
          return;
        }
      }

      EspFlasher.Progress progress =
          new EspFlasher.Progress() {
            @Override
            public void onInfo(String msg) {
              callback.info(msg);
            }

            @Override
            public void onUploading(int value) {
              // Spread each file's 0..100% into the current 10% band of the bar.
              if (progressPercent >= 20 && progressPercent < 30) {
                track(callback, Math.min(50, (int) (20 + (10 * (value / 100.0f)))));
              } else if (progressPercent >= 50) {
                track(callback, Math.min(100, (int) (50 + (50 * (value / 100.0f)))));
              }
            }
          };

      EspFlasher cmd = new EspFlasher(io, progress);

      callback.info("Attempting to init ESP32 for firmware flash");
      if (!cmd.initChip()) {
        callback.info("ESP32 failed to init (is it in bootloader mode?)");
        failed = true;
      }

      if (!failed) {
        callback.connectedToBootloader();
        track(callback, 10);
        cmd.init();
        track(callback, 20);

        callback.info("Flashing firmware");
        cmd.flashData(images[0], FILE_OFFSETS[0], 0);
        track(callback, 30);
        cmd.flashData(images[1], FILE_OFFSETS[1], 0);
        track(callback, 40);
        cmd.flashData(images[2], FILE_OFFSETS[2], 0);
        track(callback, 50);
        cmd.flashData(images[3], FILE_OFFSETS[3], 0);

        cmd.reset();
        callback.info("Done flashing firmware");
      }
    } catch (Throwable t) {
      failed = true;
      callback.info("Flashing error: " + t.getMessage());
    } finally {
      callback.doneFlashing(!failed);
      progressPercent = 0;
      flashing = false;
    }
  }

  private void track(Callback callback, int newPercent) {
    progressPercent = newPercent;
    callback.reportProgress(progressPercent);
  }

  /**
   * Loads a firmware image: first from the classpath (/firmware/&lt;name&gt;, i.e. bundled in the
   * jar), then from ~/.kv4p-desktop/firmware/&lt;name&gt;.
   */
  private byte[] loadFirmware(String name) {
    try (InputStream in = FirmwareFlasher.class.getResourceAsStream("/firmware/" + name)) {
      if (in != null) return readAll(in);
    } catch (IOException ignored) {
      // fall through to filesystem
    }
    Path p = Path.of(System.getProperty("user.home"), ".kv4p-desktop", "firmware", name);
    if (Files.isReadable(p)) {
      try {
        return Files.readAllBytes(p);
      } catch (IOException ignored) {
      }
    }
    return null;
  }

  private static byte[] readAll(InputStream stream) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = stream.read(buf)) != -1) bos.write(buf, 0, n);
    return bos.toByteArray();
  }
}
