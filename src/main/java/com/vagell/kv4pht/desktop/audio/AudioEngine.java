/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)

Replaces Android AudioTrack (receive playback) and AudioRecord (transmit
capture) with the cross-platform javax.sound.sampled API.

The radio link itself is 22050 Hz, 8-bit UNSIGNED PCM, mono. Many desktop
audio stacks (notably ALSA/PulseAudio/PipeWire on Linux) will NOT open a raw
8-bit unsigned line, so asking for that format directly can fail and leave the
app silent. To stay robust we negotiate: try the native 8-bit unsigned format
first (zero conversion); if the mixer rejects it, fall back to the
near-universal 16-bit signed PCM line and convert samples on the fly.

Linux-specific improvements:
- Explicit listing of available audio devices with diagnostics
- Fallback through multiple 16-bit endianness options
- Better error messages for troubleshooting
*/
package com.vagell.kv4pht.desktop.audio;

import com.vagell.kv4pht.desktop.radio.RadioProtocol;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class AudioEngine {

  private static final org.slf4j.Logger log = LoggerFactory.getLogger(AudioEngine.class);
  /** The radio wire format: 8-bit UNSIGNED PCM, mono, 22050 Hz. */
  public static final AudioFormat WIRE_FORMAT = new AudioFormat(
      AudioFormat.Encoding.PCM_UNSIGNED,
      RadioProtocol.AUDIO_SAMPLE_RATE, 8, 1, 1,
      RadioProtocol.AUDIO_SAMPLE_RATE, false);

  /** Fallback line formats: 16-bit SIGNED PCM, mono (little- then big-endian). */
  private static final AudioFormat WIDE_LE = new AudioFormat(
      AudioFormat.Encoding.PCM_SIGNED,
      RadioProtocol.AUDIO_SAMPLE_RATE, 16, 1, 2,
      RadioProtocol.AUDIO_SAMPLE_RATE, false);
  private static final AudioFormat WIDE_BE = new AudioFormat(
      AudioFormat.Encoding.PCM_SIGNED,
      RadioProtocol.AUDIO_SAMPLE_RATE, 16, 1, 2,
      RadioProtocol.AUDIO_SAMPLE_RATE, true);

  /** Kept for backward compatibility with any external references. */
  public static final AudioFormat FORMAT = WIRE_FORMAT;

  private SourceDataLine playbackLine;
  private boolean playbackConvert;     // true => line is 16-bit, expand 8u -> 16s
  private boolean playbackBigEndian;

  private TargetDataLine captureLine;
  private boolean captureConvert;      // true => line is 16-bit, shrink 16s -> 8u
  private boolean captureBigEndian;

  // ---- Receive (playback) ----
  public synchronized void startPlayback() throws Exception {
    stopPlayback();
    AudioFormat chosen = chooseFormat(SourceDataLine.class);
    playbackConvert   = (chosen.getSampleSizeInBits() == 16);
    playbackBigEndian = chosen.isBigEndian();
    log.info("Playback format: {} (convert={})", chosen, playbackConvert);
    
    SourceDataLine line = openLine(SourceDataLine.class, chosen);
    playbackLine = line;
    playbackLine.open(chosen);
    playbackLine.start();
    log.info("Playback started successfully");
  }

  public synchronized void playAudio(byte[] pcm8) {
    if (playbackLine == null || !playbackLine.isOpen() || pcm8.length == 0) return;
    if (playbackConvert) {
      byte[] s16 = expand8uTo16s(pcm8, playbackBigEndian);
      playbackLine.write(s16, 0, s16.length);
    } else {
      playbackLine.write(pcm8, 0, pcm8.length);
    }
  }

  public synchronized void flushPlayback() {
    if (playbackLine != null) playbackLine.flush();
  }

  public synchronized void stopPlayback() {
    if (playbackLine != null) {
      try { playbackLine.drain(); } catch (Throwable ignored) {}
      playbackLine.stop();
      playbackLine.close();
      playbackLine = null;
    }
  }

  // ---- Transmit (capture) ----
  public synchronized void openCapture() throws Exception {
    if (captureLine != null && captureLine.isOpen()) return;
    AudioFormat chosen = chooseFormat(TargetDataLine.class);
    captureConvert   = (chosen.getSampleSizeInBits() == 16);
    captureBigEndian = chosen.isBigEndian();
    log.info("Capture format: {} (convert={})", chosen, captureConvert);
    
    TargetDataLine line = openLine(TargetDataLine.class, chosen);
    captureLine = line;
    captureLine.open(chosen);
    log.info("Capture opened successfully");
  }

  public synchronized void startCapture() {
    if (captureLine != null) captureLine.start();
  }

  /** Reads up to buf.length wire (8-bit unsigned) bytes; returns count produced. */
  public synchronized int readCapture(byte[] buf) {
    if (captureLine == null || !captureLine.isOpen()) return 0;
    int available = captureLine.available();
    if (available <= 0) return 0;
    if (!captureConvert) {
      return captureLine.read(buf, 0, Math.min(buf.length, available));
    }
    int toRead = Math.min(buf.length * 2, available);
    toRead -= (toRead % 2);                 // whole 16-bit frames only
    if (toRead <= 0) return 0;
    byte[] tmp = new byte[toRead];
    int n = captureLine.read(tmp, 0, toRead);
    if (n <= 0) return 0;
    return shrink16sTo8u(tmp, n, buf, captureBigEndian);
  }

  public synchronized void stopCapture() {
    if (captureLine != null) {
      captureLine.stop();
      captureLine.flush();
    }
  }

  public synchronized void closeCapture() {
    if (captureLine != null) {
      captureLine.stop();
      captureLine.close();
      captureLine = null;
    }
  }

  public synchronized void closeAll() {
    stopPlayback();
    closeCapture();
  }

  // ---- Format negotiation & conversion ----
  private static <T> AudioFormat chooseFormat(Class<T> lineClass) throws Exception {
    String lineType = lineClass.getSimpleName();
    log.info("Negotiating {} format...", lineType);
    
    // Try formats in order: 8-bit native, then 16-bit little-endian, then 16-bit big-endian
    for (AudioFormat fmt : new AudioFormat[] { WIRE_FORMAT, WIDE_LE, WIDE_BE }) {
      DataLine.Info info = new DataLine.Info(lineClass, fmt);
      if (AudioSystem.isLineSupported(info)) {
        log.info("  ✓ {} supported: {}", lineType, formatDescription(fmt));
        return fmt;
      } else {
        log.debug("  ✗ {} not supported: {}", lineType, formatDescription(fmt));
      }
    }
    
    // No format matched. Log available devices for diagnostics.
    logAvailableAudioDevices();
    
    throw new Exception("No supported audio line (tried 8-bit unsigned and 16-bit signed PCM, "
        + RadioProtocol.AUDIO_SAMPLE_RATE + "Hz mono). Check your system's audio output/input device.");
  }

  private static String formatDescription(AudioFormat fmt) {
    return String.format("%s, %d-bit, %.0f Hz, %s",
        fmt.getEncoding(),
        fmt.getSampleSizeInBits(),
        fmt.getSampleRate(),
        fmt.isBigEndian() ? "big-endian" : "little-endian");
  }

  private static <T> T openLine(Class<T> lineClass, AudioFormat fmt) throws Exception {
    DataLine.Info info = new DataLine.Info(lineClass, fmt);
    
    // Try to use the default device first
    try {
      Line line = AudioSystem.getLine(info);
      if (line != null) {
        return (T) line;
      }
    } catch (Exception e) {
      log.warn("Failed to get default line: {}", e.getMessage());
    }
    
    // Fall back to finding any available mixer with this format
    Mixer.Info[] mixers = AudioSystem.getMixerInfo();
    for (Mixer.Info mixerInfo : mixers) {
      try {
        Mixer mixer = AudioSystem.getMixer(mixerInfo);
        if (lineClass == SourceDataLine.class) {
          if (mixer.isLineSupported(info)) {
            Line line = mixer.getLine(info);
            if (line != null) {
              log.info("Using mixer: {}", mixerInfo.getName());
              return (T) line;
            }
          }
        } else if (lineClass == TargetDataLine.class) {
          if (mixer.isLineSupported(info)) {
            Line line = mixer.getLine(info);
            if (line != null) {
              log.info("Using mixer: {}", mixerInfo.getName());
              return (T) line;
            }
          }
        }
      } catch (Exception e) {
        log.debug("Mixer {} failed: {}", mixerInfo.getName(), e.getMessage());
      }
    }
    
    // Last resort: use default
    return (T) AudioSystem.getLine(info);
  }

  private static void logAvailableAudioDevices() {
    log.warn("=== Available Audio Devices ===");
    Mixer.Info[] mixers = AudioSystem.getMixerInfo();
    for (Mixer.Info mixerInfo : mixers) {
      try {
        Mixer mixer = AudioSystem.getMixer(mixerInfo);
        log.warn("Mixer: {} (vendor: {}, version: {})",
            mixerInfo.getName(), mixerInfo.getVendor(), mixerInfo.getVersion());
        
        // Log supported output formats
        Line.Info[] sourceLines = mixer.getSourceLineInfo(new DataLine.Info(SourceDataLine.class, null));
        if (sourceLines.length > 0) {
          log.warn("  Playback lines: {}", sourceLines.length);
          for (Line.Info lineInfo : sourceLines) {
            if (lineInfo instanceof DataLine.Info) {
              DataLine.Info dlInfo = (DataLine.Info) lineInfo;
              AudioFormat[] formats = dlInfo.getFormats();
              for (AudioFormat fmt : formats) {
                if (fmt.getSampleRate() >= 22050 && fmt.getSampleRate() <= 48000) {
                  log.warn("    - {}", formatDescription(fmt));
                }
              }
            }
          }
        }
        
        // Log supported input formats
        Line.Info[] targetLines = mixer.getTargetLineInfo(new DataLine.Info(TargetDataLine.class, null));
        if (targetLines.length > 0) {
          log.warn("  Capture lines: {}", targetLines.length);
          for (Line.Info lineInfo : targetLines) {
            if (lineInfo instanceof DataLine.Info) {
              DataLine.Info dlInfo = (DataLine.Info) lineInfo;
              AudioFormat[] formats = dlInfo.getFormats();
              for (AudioFormat fmt : formats) {
                if (fmt.getSampleRate() >= 22050 && fmt.getSampleRate() <= 48000) {
                  log.warn("    - {}", formatDescription(fmt));
                }
              }
            }
          }
        }
      } catch (Exception e) {
        log.warn("Error querying mixer {}: {}", mixerInfo.getName(), e.getMessage());
      }
    }
    log.warn("=== End of Audio Devices ===");
  }

  static byte[] expand8uTo16s(byte[] pcm8, boolean bigEndian) {
    byte[] out = new byte[pcm8.length * 2];
    for (int i = 0; i < pcm8.length; i++) {
      int signed8 = (pcm8[i] & 0xFF) - 128;   // -128..127
      int s16 = signed8 << 8;                 // scale into 16-bit range
      byte lo = (byte) (s16 & 0xFF);
      byte hi = (byte) ((s16 >> 8) & 0xFF);
      if (bigEndian) { out[2 * i] = hi; out[2 * i + 1] = lo; }
      else           { out[2 * i] = lo; out[2 * i + 1] = hi; }
    }
    return out;
  }

  static int shrink16sTo8u(byte[] lineBytes, int len, byte[] outWire, boolean bigEndian) {
    int frames = Math.min(len / 2, outWire.length);
    for (int i = 0; i < frames; i++) {
      int b0 = lineBytes[2 * i];
      int b1 = lineBytes[2 * i + 1];
      short s16 = bigEndian
          ? (short) ((b0 << 8) | (b1 & 0xFF))
          : (short) ((b1 << 8) | (b0 & 0xFF));
      int signed8 = s16 >> 8;                 // -128..127 (arithmetic)
      outWire[i] = (byte) (signed8 + 128);    // 0..255 unsigned
    }
    return frames;
  }
}
