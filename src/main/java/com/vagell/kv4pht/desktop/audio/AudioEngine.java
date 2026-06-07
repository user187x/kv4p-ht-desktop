/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)

Replaces Android AudioTrack (receive playback) and AudioRecord (transmit
capture) with the cross-platform javax.sound.sampled API. The audio format is
identical to the radio link: 22050 Hz, 8-bit UNSIGNED PCM, mono. The unsigned
8-bit bytes coming off the wire can be written straight to a PCM_UNSIGNED line.
*/
package com.vagell.kv4pht.desktop.audio;

import com.vagell.kv4pht.desktop.radio.RadioProtocol;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public class AudioEngine {

    public static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_UNSIGNED,
            RadioProtocol.AUDIO_SAMPLE_RATE, // sample rate
            8,                               // bits per sample
            1,                               // channels (mono)
            1,                               // frame size (bytes)
            RadioProtocol.AUDIO_SAMPLE_RATE, // frame rate
            false);                          // little endian (irrelevant for 8-bit)

    private SourceDataLine playbackLine; // RX -> speakers
    private TargetDataLine captureLine;  // mic -> TX

    // ---- Receive (playback) ----
    public synchronized void startPlayback() throws Exception {
        stopPlayback();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new Exception("Audio output line (8-bit unsigned 22050Hz mono) not supported on this system.");
        }
        playbackLine = (SourceDataLine) AudioSystem.getLine(info);
        playbackLine.open(FORMAT);
        playbackLine.start();
    }

    /** Plays a chunk of received audio. No-op if playback isn't running. */
    public synchronized void playAudio(byte[] pcm8) {
        if (playbackLine != null && playbackLine.isOpen() && pcm8.length > 0) {
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
    /** Opens the microphone capture line. */
    public synchronized void openCapture() throws Exception {
        if (captureLine != null && captureLine.isOpen()) return;
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new Exception("Audio input line (8-bit unsigned 22050Hz mono) not supported on this system.");
        }
        captureLine = (TargetDataLine) AudioSystem.getLine(info);
        captureLine.open(FORMAT);
    }

    public synchronized void startCapture() {
        if (captureLine != null) captureLine.start();
    }

    /** Reads up to buf.length captured bytes; returns number read (may be 0). */
    public synchronized int readCapture(byte[] buf) {
        if (captureLine == null || !captureLine.isOpen()) return 0;
        int available = captureLine.available();
        if (available <= 0) return 0;
        return captureLine.read(buf, 0, Math.min(buf.length, available));
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
}
