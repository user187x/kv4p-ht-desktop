package com.vagell.kv4pht.desktop.radio;
import com.fazecast.jSerialComm.SerialPort;
import com.vagell.kv4pht.desktop.audio.AudioEngine;
import com.vagell.kv4pht.desktop.data.ChannelMemory;
import com.vagell.kv4pht.desktop.firmware.EspFlasher;
import com.vagell.kv4pht.desktop.serial.SerialRadio;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class RadioAudioService implements SerialRadio.DataListener, RadioProtocol.CommandHandler {

    public static final Logger LOGGER = Logger.getLogger(RadioAudioService.class.getName());

    public static final int MODE_STARTUP = -1;
    public static final int MODE_RX = 0;
    public static final int MODE_TX = 1;
    public static final int MODE_SCAN = 2;
    public static final int MODE_BAD_FIRMWARE = 3;
    public static final int MODE_FLASHING = 4;
    private static final int RUNAWAY_TX_TIMEOUT_SEC = 180;
    private static final float SEC_BETWEEN_SCANS = 0.5f;
    private static final int TX_AUDIO_CHUNK_SIZE = 512;
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(RadioAudioService.class);
    private final SerialRadio serial = new SerialRadio();
    private final AudioEngine audio = new AudioEngine();
    private final RadioProtocol.RxParser rxParser = new RadioProtocol.RxParser();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);
    private volatile int mode = MODE_STARTUP;
    private RadioAudioServiceCallbacks callbacks = (new RadioAudioServiceCallbacks() {});
    private SerialPort connectedPort = null;
    private String activeFrequencyStr = "144.0000";
    private int activeMemoryId = -1;
    private int squelch = 0;
    private String bandwidth = "Wide";
    private MicGainBoost micGainBoost = MicGainBoost.NONE;
    private int maxFreq = 148;
    private boolean txAllowed = true;
    private boolean emphasis = true, highpass = true, lowpass = true;
    private List<ChannelMemory> channelMemories = null;
    private int consecutiveSilenceBytes = 0;
    private String versionStrBuffer = "";
    private Thread txThread;
    private volatile boolean transmitting = false;
    private long startTxTimeSec = -1;
    public void setCallbacks(RadioAudioServiceCallbacks cb) {
        this.callbacks = (cb == null) ? new RadioAudioServiceCallbacks() {} : cb;
    }
    public int getMode() { return mode; }
    public boolean isTxAllowed() { return txAllowed; }
    public boolean isConnected() { return serial.isOpen(); }
    public String getActiveFrequencyStr() { return activeFrequencyStr; }
    public int getActiveMemoryId() { return activeMemoryId; }
    public void setSquelch(int s) { this.squelch = s; }
    public int getSquelch() { return squelch; }
    public void setBandwidth(String b) { this.bandwidth = b; }
    public String getBandwidth() { return bandwidth; }
    public void setMicGainBoost(String s) { this.micGainBoost = MicGainBoost.parse(s); }
    public void setMaxFreq(int f) { this.maxFreq = f; }
    public int getMaxFreq() { return maxFreq; }
    public void setChannelMemories(List<ChannelMemory> mems) { this.channelMemories = mems; }
    public void setFilterSettings(boolean emphasis, boolean highpass, boolean lowpass) {
        this.emphasis = emphasis; this.highpass = highpass; this.lowpass = lowpass;
    }
    private boolean wide() { return bandwidth.equalsIgnoreCase("Wide"); }
    private void setMode(int m) {
        if (mode != m) {
            mode = m;
            callbacks.modeChanged(m);
        }
    }
    public void connect(SerialPort chosenPort) {
        String audioWarning = null;
        try {
            audio.startPlayback();
            audio.openCapture();
        } catch (Exception e) {
            // Non-fatal: the radio link can still connect; we just won't have sound.
            audioWarning = "audio unavailable: " + e.getMessage();
        }

        SerialPort p = (chosenPort != null) ? chosenPort : SerialRadio.guessRadioPort();
        if (p == null) {
            callbacks.radioMissing();
            return;
        }
        try {
            serial.open(p, this);
            connectedPort = p;
        } catch (Exception e) {
            // Surface the actual reason instead of the generic "No radio found",
            // and do NOT call radioMissing() (which would overwrite this message).
            callbacks.status("Couldn't open " + p.getSystemPortName() + ": " + e.getMessage()
                + " \u2014 on Linux, make sure you're in the 'dialout' group and that "
                + "ModemManager isn't holding the port.");

            log.error("Couldn't open {}: {}", p.getSystemPortName(), e.getMessage());

            return;
        }
        setMode(MODE_STARTUP);
        versionStrBuffer = "";
        String msg = "Connected to " + serial.portName() + ", checking firmware...";
        if (audioWarning != null) msg += "  [" + audioWarning + "]";
        callbacks.status(msg);
        exec.schedule(this::checkFirmwareVersion, 2, TimeUnit.SECONDS);
    }
    public void disconnect() {
        transmitting = false;
        try { sendCommand(ESP32Command.STOP); } catch (Throwable ignored) {}
        serial.close();
        audio.closeAll();
        setMode(MODE_STARTUP);
        callbacks.disconnected();
    }
    private void checkFirmwareVersion() {
        setMode(MODE_STARTUP);
        sendCommand(ESP32Command.STOP);
        sendCommand(ESP32Command.GET_FIRMWARE_VER);
        exec.schedule(() -> {
            if (mode == MODE_STARTUP) {
                setMode(MODE_BAD_FIRMWARE);
                callbacks.missingFirmware();
            }
        }, 4, TimeUnit.SECONDS);
    }
    private void initAfterConnected() {
        setMode(MODE_RX);
        setRadioFilters(emphasis, highpass, lowpass);
        callbacks.connected();
        if (activeMemoryId >= 0 && channelMemories != null) {
            tuneToMemory(activeMemoryId, squelch, true);
        } else {
            tuneToFreq(activeFrequencyStr, squelch, true);
        }
    }
    public void sendCommand(ESP32Command command) {
        if (mode == MODE_BAD_FIRMWARE || mode == MODE_FLASHING) return;
        serial.write(RadioProtocol.buildCommand(command));
    }
    public void sendCommand(ESP32Command command, String params) {
        if (mode == MODE_BAD_FIRMWARE || mode == MODE_FLASHING) return;
        serial.write(RadioProtocol.buildCommand(command, params));
    }
    private void setRadioFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        sendCommand(ESP32Command.FILTERS, RadioProtocol.filtersParams(emphasis, highpass, lowpass));
    }
    public void tuneToFreq(String frequencyStr, int squelchLevel, boolean forceTune) {
        if (mode == MODE_STARTUP) return;
        setMode(MODE_RX);
        if (!forceTune && activeFrequencyStr.equals(frequencyStr) && squelch == squelchLevel) return;
        activeFrequencyStr = RadioProtocol.makeSafe2MFreq(frequencyStr);
        activeMemoryId = -1;
        squelch = squelchLevel;
        sendCommand(ESP32Command.TUNE_TO,
            RadioProtocol.tuneToParams(activeFrequencyStr, squelchLevel, wide()));
        audio.flushPlayback();
        updateTxAllowed(activeFrequencyStr);
    }
    public void tuneToMemory(int memoryId, int squelchLevel, boolean forceTune) {
        if (mode == MODE_STARTUP || channelMemories == null) return;
        for (ChannelMemory m : channelMemories) {
            if (m.memoryId == memoryId) { tuneToMemory(m, squelchLevel, forceTune); return; }
        }
    }
    public void tuneToMemory(ChannelMemory memory, int squelchLevel, boolean forceTune) {
        if (mode == MODE_STARTUP || memory == null) return;
        if (!forceTune && activeMemoryId == memory.memoryId && squelch == squelchLevel) return;
        activeFrequencyStr = RadioProtocol.makeSafe2MFreq(memory.frequency);
        activeMemoryId = memory.memoryId;
        squelch = squelchLevel;
        sendCommand(ESP32Command.TUNE_TO,
            RadioProtocol.tuneToMemoryParams(memory.frequency, memory.offset,
                memory.tone, squelchLevel, wide()));
        audio.flushPlayback();
        String txFreq = RadioProtocol.getTxFreq(memory.frequency, memory.offset);
        updateTxAllowed(txFreq);
    }
    private void updateTxAllowed(String txFreqStr) {
        try {
            float txFreq = Float.parseFloat(txFreqStr);
            float offsetMaxFreq = maxFreq - (wide() ? 0.025f : 0.0125f);
            txAllowed = !(txFreq < 144.0f || txFreq > offsetMaxFreq);
        } catch (NumberFormatException nfe) {
            txAllowed = false;
        }
        callbacks.txAllowed(txAllowed);
    }
    public void startPtt() {
        if (!txAllowed || mode == MODE_TX) return;
        setMode(MODE_TX);
        callbacks.sMeterUpdate(0);
        startTxTimeSec = System.currentTimeMillis() / 1000;
        sendCommand(ESP32Command.PTT_DOWN);
        audio.stopPlayback();
        callbacks.txStarted();
        transmitting = true;
        txThread = new Thread(this::transmitLoop, "kv4p-tx");
        txThread.setDaemon(true);
        txThread.start();
    }
    private void transmitLoop() {
        audio.startCapture();
        byte[] buf = new byte[TX_AUDIO_CHUNK_SIZE];
        while (transmitting && mode == MODE_TX) {
            long elapsed = (System.currentTimeMillis() / 1000) - startTxTimeSec;
            if (elapsed > RUNAWAY_TX_TIMEOUT_SEC) { break; }
            int n = audio.readCapture(buf);
            if (n > 0) {
                byte[] chunk = (n == buf.length) ? buf : java.util.Arrays.copyOf(buf, n);
                byte[] gained = RadioProtocol.applyMicGain(chunk, micGainBoost);
                serial.write(gained);
            } else {
                try { Thread.sleep(2); } catch (InterruptedException e) { break; }
            }
        }
        audio.stopCapture();
        if (transmitting) {
            javax.swing.SwingUtilities.invokeLater(this::endPtt);
        }
    }
    public void endPtt() {
        if (mode == MODE_RX) return;
        transmitting = false;
        setMode(MODE_RX);
        sendCommand(ESP32Command.PTT_UP);
        try { audio.startPlayback(); } catch (Exception ignored) {}
        callbacks.txEnded();
    }
    public void setScanning(boolean scanning) {
        if (scanning && channelMemories != null && !channelMemories.isEmpty()) {
            setMode(MODE_SCAN);
            nextScan();
        } else {
            setMode(MODE_RX);
        }
    }
    private void nextScan() {
        if (channelMemories == null || channelMemories.isEmpty()) { setMode(MODE_RX); return; }
        int startIdx = 0;
        for (int i = 0; i < channelMemories.size(); i++) {
            if (channelMemories.get(i).memoryId == activeMemoryId) { startIdx = i + 1; break; }
        }
        ChannelMemory next = channelMemories.get(startIdx % channelMemories.size());
        tuneToMemory(next, squelch > 0 ? squelch : 1, true);
        setMode(MODE_SCAN);
        callbacks.scannedToMemory(next.memoryId);
    }
    private void checkScanDueToSilence() {
        if (consecutiveSilenceBytes >= (RadioProtocol.AUDIO_SAMPLE_RATE * SEC_BETWEEN_SCANS)) {
            consecutiveSilenceBytes = 0;
            nextScan();
        }
    }
    @Override
    public void onData(byte[] data) {
        if (mode == MODE_STARTUP) {
            handleStartupData(data);
            return;
        }
        if (mode == MODE_RX || mode == MODE_SCAN) {
            byte[] audioBytes = rxParser.parse(data, this);
            if (audioBytes.length > 0) {
                audio.playAudio(audioBytes);
                if (mode == MODE_SCAN) {
                    for (byte b : audioBytes) {
                        if (b == RadioProtocol.SILENT_BYTE) {
                            consecutiveSilenceBytes++;
                            checkScanDueToSilence();
                        } else {
                            consecutiveSilenceBytes = 0;
                        }
                    }
                }
            }
        }
    }
    private void handleStartupData(byte[] data) {
        versionStrBuffer += new String(data, StandardCharsets.UTF_8);
        int idx = versionStrBuffer.indexOf(RadioProtocol.VERSION_PREFIX);
        if (idx >= 0) {
            int startIdx = idx + RadioProtocol.VERSION_PREFIX.length();
            if (startIdx + RadioProtocol.VERSION_LENGTH > versionStrBuffer.length()) {
                return;
            }
            String verStr = versionStrBuffer.substring(startIdx, startIdx + RadioProtocol.VERSION_LENGTH);
            versionStrBuffer = "";
            try {
                int verInt = Integer.parseInt(verStr.trim());
                callbacks.status("ESP32 firmware version " + verInt + " detected.");
            } catch (NumberFormatException nfe) {
                callbacks.status("ESP32 firmware detected.");
            }
            initAfterConnected();
        }
    }
    @Override
    public void onCommand(byte cmd, byte[] params) {
        if (cmd == RadioProtocol.COMMAND_SMETER_REPORT && params.length >= 1) {
            int raw = params[0] & 0xFF;
            callbacks.sMeterUpdate(RadioProtocol.scaleSMeter(raw));
        }
    }
    @Override
    public void onDisconnected() {
        audio.closeAll();
        setMode(MODE_STARTUP);
        callbacks.disconnected();
    }
    public void shutdown() {
        disconnect();
        exec.shutdownNow();
    }
    public boolean isConnectedForFlashing() {
        return serial.isOpen();
    }
    public EspFlasher.SerialIO beginFirmwareFlash() {
        setMode(MODE_FLASHING);
        transmitting = false;
        try { audio.closeAll(); } catch (Throwable ignored) {}
        return serial.beginFlashing();
    }
    public void finishFirmwareFlash(boolean success) {
        serial.endFlashing();
        SerialPort p = connectedPort;
        serial.close();
        setMode(MODE_STARTUP);
        if (success && p != null) {
            callbacks.status("Firmware flashed. Reconnecting...");
            exec.schedule(() -> connect(p), 2, TimeUnit.SECONDS);
        } else {
            callbacks.disconnected();
        }
    }
}