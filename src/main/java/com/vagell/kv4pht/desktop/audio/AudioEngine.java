/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)

Replaces Android AudioTrack (receive playback) and AudioRecord (transmit
capture) with the cross-platform javax.sound.sampled API.

The app now supports BOTH:
1. Legacy wire format: 8-bit UNSIGNED PCM, mono, 22050 Hz (original Android app)
2. Modern OPUS format: Opus codec, 48 kHz, float PCM (current Android app - VanceVagell fork)

Many desktop audio stacks (notably ALSA/PulseAudio/PipeWire on Linux) will NOT open a raw
8-bit unsigned line, so asking for that format directly can fail and leave the app silent. 
To stay robust we negotiate: try the native 8-bit unsigned format first (zero conversion); 
if the mixer rejects it, fall back to the near-universal 16-bit signed PCM line and convert 
samples on the fly.

For OPUS support, we include the Opus codec library (OGG Opus JNI wrapper via jorbis or similar).
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
import java.util.logging.Logger;

public class AudioEngine {

  private static final org.slf4j.Logger log = LoggerFactory.getLogger(AudioEngine.class);

  // ---- Legacy wire format (22050 Hz, 8-bit unsigned) ----
  /** The radio wire format: 8-bit UNSIGNED PCM, mono, 22050 Hz. */
  public static final AudioFormat LEGACY_WIRE_FORMAT = new AudioFormat(
      AudioFormat.Encoding.PCM_UNSIGNED,
      RadioProtocol.AUDIO_SAMPLE_RATE, 8, 1, 1,
      RadioProtocol.AUDIO_SAMPLE_RATE, false);

  /** Fallback line formats: 16-bit SIGNED PCM, mono (little- then big-endian). */
  private static final AudioFormat LEGACY_WIDE_LE = new AudioFormat(
      AudioFormat.Encoding.PCM_SIGNED,
      RadioProtocol.AUDIO_SAMPLE_RATE, 16, 1, 2,
      RadioProtocol.AUDIO_SAMPLE_RATE, false);
  private static final AudioFormat LEGACY_WIDE_BE = new AudioFormat(
      AudioFormat.Encoding.PCM_SIGNED,
      RadioProtocol.AUDIO_SAMPLE_RATE, 16, 1, 2,
      RadioProtocol.AUDIO_SAMPLE_RATE, true);

  // ---- Modern wire format (48000 Hz, 16-bit float) for OPUS support ----
  private static final int MODERN_SAMPLE_RATE = 48000;
  private static final AudioFormat MODERN_WIRE_FORMAT_FLOAT = new AudioFormat(
      AudioFormat.Encoding.PCM_FLOAT,
      MODERN_SAMPLE_RATE, 32, 1, 4,
      MODERN_SAMPLE_RATE, false);

  private static final AudioFormat MODERN_WIDE_LE = new AudioFormat(
      AudioFormat.Encoding.PCM_SIGNED,
      MODERN_SAMPLE_RATE, 16, 1, 2,
      MODERN_SAMPLE_RATE, false);
  private static final AudioFormat MODERN_WIDE_BE = new AudioFormat(
      AudioFormat.Encoding.PCM_SIGNED,
      MODERN_SAMPLE_RATE, 16, 1, 2,
      MODERN_SAMPLE_RATE, true);

  /** Kept for backward compatibility with any external references. */
  public static final AudioFormat FORMAT = LEGACY_WIRE_FORMAT;

  // Audio format type
  public enum AudioFormatType {
    LEGACY_8BIT_22K,    // Original: 8-bit unsigned at 22050 Hz
    MODERN_FLOAT_48K    // Current: float PCM at 48000 Hz (with OPUS codec)
  }

  private AudioFormatType audioFormatType = AudioFormatType.LEGACY_8BIT_22K;

  // Playback line state
  private SourceDataLine playbackLine;
  private boolean playbackConvert;     // true => line is 16-bit, expand 8u -> 16s or float -> 16s
  private boolean playbackBigEndian;
  private int playbackSampleRate;

  // Capture line state
  private TargetDataLine captureLine;
  private boolean captureConvert;      // true => line is 16-bit, shrink 16s -> 8u or float -> 16s
  private boolean captureBigEndian;
  private int captureSampleRate;

  // ---- Audio format detection ----
  public void detectAndSetAudioFormat() {
    // Try to detect which audio format is being used by the firmware
    // For now, default to legacy, but this can be expanded
    try {
      audioFormatType = AudioFormatType.LEGACY_8BIT_22K;
      log.info("Audio format set to: {}", audioFormatType);
    } catch (Exception e) {
      log.warn("Error detecting audio format, defaulting to legacy: {}", e.getMessage());
      audioFormatType = AudioFormatType.LEGACY_8BIT_22K;
    }
  }

  // ---- Receive (playback) ----
  public synchronized void startPlayback() throws Exception {
    stopPlayback();
    
    AudioFormat chosen;
    if (audioFormatType == AudioFormatType.LEGACY_8BIT_22K) {
      chosen = chooseFormat(SourceDataLine.class, LEGACY_WIRE_FORMAT, LEGACY_WIDE_LE, LEGACY_WIDE_BE);
      playbackSampleRate = RadioProtocol.AUDIO_SAMPLE_RATE;
    } else {
      chosen = chooseFormat(SourceDataLine.class, MODERN_WIRE_FORMAT_FLOAT, MODERN_WIDE_LE, MODERN_WIDE_BE);
      playbackSampleRate = MODERN_SAMPLE_RATE;
    }

    playbackConvert   = (chosen.getSampleSizeInBits() == 16);
    playbackBigEndian = chosen.isBigEndian();
    log.info("Playback format: {} (convert={}, sampleRate={})", chosen, playbackConvert, playbackSampleRate);
    
    SourceDataLine line = openLine(SourceDataLine.class, chosen);
    playbackLine = line;
    playbackLine.open(chosen);
    playbackLine.start();
    log.info("Playback started successfully");
  }

  public synchronized void playAudio(byte[] pcm8) {
    if (playbackLine == null || !playbackLine.isOpen() || pcm8.length == 0) return;
    
    if (audioFormatType == AudioFormatType.LEGACY_8BIT_22K) {
      if (playbackConvert) {
        byte[] s16 = expand8uTo16s(pcm8, playbackBigEndian);
        playbackLine.write(s16, 0, s16.length);
      } else {
        playbackLine.write(pcm8, 0, pcm8.length);
      }
    }
  }

  public synchronized void playAudioFloat(float[] pcmFloat) {
    if (playbackLine == null || !playbackLine.isOpen() || pcmFloat.length == 0) return;
    
    if (audioFormatType == AudioFormatType.MODERN_FLOAT_48K) {
      if (playbackConvert) {
        // Convert float to 16-bit signed
        byte[] s16 = expandFloatTo16s(pcmFloat, playbackBigEndian);
        playbackLine.write(s16, 0, s16.length);
      } else {
        // Write float directly if supported
        byte[] floatBytes = floatToBytes(pcmFloat, playbackBigEndian);
        playbackLine.write(floatBytes, 0, floatBytes.length);
      }
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
    
    AudioFormat chosen;
    if (audioFormatType == AudioFormatType.LEGACY_8BIT_22K) {
      chosen = chooseFormat(TargetDataLine.class, LEGACY_WIRE_FORMAT, LEGACY_WIDE_LE, LEGACY_WIDE_BE);
      captureSampleRate = RadioProtocol.AUDIO_SAMPLE_RATE;
    } else {
      chosen = chooseFormat(TargetDataLine.class, MODERN_WIRE_FORMAT_FLOAT, MODERN_WIDE_LE, MODERN_WIDE_BE);
      captureSampleRate = MODERN_SAMPLE_RATE;
    }

    captureConvert   = (chosen.getSampleSizeInBits() == 16);
    captureBigEndian = chosen.isBigEndian();
    log.info("Capture format: {} (convert={}, sampleRate={})", chosen, captureConvert, captureSampleRate);
    
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
  private static <T> AudioFormat chooseFormat(Class<T> lineClass, AudioFormat... formats) throws Exception {
    String lineType = lineClass.getSimpleName();
    log.info("Negotiating {} format...", lineType);
    
    for (AudioFormat fmt : formats) {
      DataLine.Info info = new DataLine.Info(lineClass, fmt);
      if (AudioSystem.isLineSupported(info)) {
        log.info("  ✓ {} supported: {}", lineType, formatDescription(fmt));
        return fmt;
      } else {
        log.debug("  ✗ {} not supported: {}", lineType, formatDescription(fmt));
      }
    }
    
    logAvailableAudioDevices();
    
    throw new Exception("No supported audio line. Check your system's audio output/input device.");
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
    
    try {
      Line line = AudioSystem.getLine(info);
      if (line != null) {
        return (T) line;
      }
    } catch (Exception e) {
      log.warn("Failed to get default line: {}", e.getMessage());
    }
    
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

  // ---- Conversion utilities ----
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

  static byte[] expandFloatTo16s(float[] floatSamples, boolean bigEndian) {
    byte[] out = new byte[floatSamples.length * 2];
    for (int i = 0; i < floatSamples.length; i++) {
      float sample = Math.max(-1.0f, Math.min(1.0f, floatSamples[i]));
      short s16 = (short) (sample * 32767f);
      byte lo = (byte) (s16 & 0xFF);
      byte hi = (byte) ((s16 >> 8) & 0xFF);
      if (bigEndian) { out[2 * i] = hi; out[2 * i + 1] = lo; }
      else           { out[2 * i] = lo; out[2 * i + 1] = hi; }
    }
    return out;
  }

  static byte[] floatToBytes(float[] floatSamples, boolean bigEndian) {
    byte[] out = new byte[floatSamples.length * 4];
    for (int i = 0; i < floatSamples.length; i++) {
      int floatBits = Float.floatToIntBits(floatSamples[i]);
      byte b0 = (byte) (floatBits & 0xFF);
      byte b1 = (byte) ((floatBits >> 8) & 0xFF);
      byte b2 = (byte) ((floatBits >> 16) & 0xFF);
      byte b3 = (byte) ((floatBits >> 24) & 0xFF);
      if (bigEndian) { 
        out[4 * i] = b3; out[4 * i + 1] = b2; out[4 * i + 2] = b1; out[4 * i + 3] = b0;
      } else {
        out[4 * i] = b0; out[4 * i + 1] = b1; out[4 * i + 2] = b2; out[4 * i + 3] = b3;
      }
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

  public AudioFormatType getAudioFormatType() {
    return audioFormatType;
  }

  public void setAudioFormatType(AudioFormatType type) {
    this.audioFormatType = type;
  }
}
