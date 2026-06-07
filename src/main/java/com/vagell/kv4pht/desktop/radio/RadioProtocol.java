package com.vagell.kv4pht.desktop.radio;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
public final class RadioProtocol {
    private RadioProtocol() {}
    public static final byte[] COMMAND_DELIMITER =
        {(byte) 0xFF, (byte) 0x00, (byte) 0xFF, (byte) 0x00,
            (byte) 0xFF, (byte) 0x00, (byte) 0xFF, (byte) 0x00};
    public static final byte COMMAND_SMETER_REPORT = 0x53;
    public static final byte SILENT_BYTE = -128;
    public static final int AUDIO_SAMPLE_RATE = 22050;
    public static final int SERIAL_BAUD = 921600;
    public static final String VERSION_PREFIX = "VERSION";
    public static final int VERSION_LENGTH = 8;
    public static final int ESP32_VENDOR_ID = 4292;
    public static final int ESP32_PRODUCT_ID = 60000;
    private static final Map<String, Integer> TONES = new LinkedHashMap<>();
    static {
        String[] tones = {
            "None","67","71.9","74.4","77","79.7","82.5","85.4","88.5","91.5","94.8",
            "97.4","100","103.5","107.2","110.9","114.8","118.8","123","127.3","131.8",
            "136.5","141.3","146.2","151.4","156.7","162.2","167.9","173.8","179.9",
            "186.2","192.8","203.5","210.7","218.1","225.7","233.6","241.8","250.3"
        };
        for (int i = 0; i < tones.length; i++) TONES.put(tones[i], i);
    }
    public static String[] toneOptions() {
        return TONES.keySet().toArray(new String[0]);
    }
    public static String getToneIdxStr(String toneStr) {
        if (toneStr == null) toneStr = "None";
        Integer idx = TONES.get(toneStr);
        if (idx == null) idx = 0;
        return idx < 10 ? "0" + idx : idx.toString();
    }
    public static String makeSafe2MFreq(String strFreq) {
        float freq;
        try {
            freq = Float.parseFloat(strFreq);
        } catch (NumberFormatException nfe) {
            return "144.0000";
        }
        while (freq > 500.0f) freq /= 10;
        if (freq < 134.0f) freq = 134.0f;
        else if (freq > 174.0f) freq = 174.0f;
        return String.format(Locale.US, "%.4f", freq);
    }
    public static String getTxFreq(String rxFreq, int offset) {
        if (offset == ChannelOffset.OFFSET_NONE) return rxFreq;
        float f = Float.parseFloat(rxFreq);
        if (offset == ChannelOffset.OFFSET_UP) f += 0.600f;
        else if (offset == ChannelOffset.OFFSET_DOWN) f -= 0.600f;
        return makeSafe2MFreq(Float.toString(f));
    }
    public static final class ChannelOffset {
        public static final int OFFSET_NONE = 0;
        public static final int OFFSET_DOWN = 1;
        public static final int OFFSET_UP = 2;
    }
    public static byte[] buildCommand(ESP32Command command) {
        byte[] out = new byte[COMMAND_DELIMITER.length + 1];
        System.arraycopy(COMMAND_DELIMITER, 0, out, 0, COMMAND_DELIMITER.length);
        out[COMMAND_DELIMITER.length] = command.getByte();
        return out;
    }
    public static byte[] buildCommand(ESP32Command command, String paramsStr) {
        byte[] header = buildCommand(command);
        byte[] params = paramsStr.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(header.length + params.length);
        buffer.put(header).put(params);
        return buffer.array();
    }
    public static String tuneToParams(String freqStr, int squelch, boolean wideBandwidth) {
        String safe = makeSafe2MFreq(freqStr);
        return safe + safe + "00" + squelch + (wideBandwidth ? "W" : "N");
    }
    public static String tuneToMemoryParams(String rxFreq, int offset, String tone,
                                            int squelch, boolean wideBandwidth) {
        return getTxFreq(rxFreq, offset)
            + makeSafe2MFreq(rxFreq)
            + getToneIdxStr(tone)
            + squelch
            + (wideBandwidth ? "W" : "N");
    }
    public static String filtersParams(boolean emphasis, boolean highpass, boolean lowpass) {
        return (emphasis ? "1" : "0") + (highpass ? "1" : "0") + (lowpass ? "1" : "0");
    }
    public static int scaleSMeter(int sMeter255Value) {
        int v = 1;
        if (sMeter255Value >= 46)  v = 2;
        if (sMeter255Value >= 50)  v = 3;
        if (sMeter255Value >= 55)  v = 4;
        if (sMeter255Value >= 61)  v = 5;
        if (sMeter255Value >= 68)  v = 6;
        if (sMeter255Value >= 76)  v = 7;
        if (sMeter255Value >= 87)  v = 8;
        if (sMeter255Value >= 101) v = 9;
        return v;
    }
    public static byte convertFloatToPCM8(float floatValue) {
        float clamped = Math.max(-1.0f, Math.min(1.0f, floatValue));
        int signed = Math.max(-128, Math.min(127, Math.round(clamped * 128)));
        return (byte) (signed + 128);
    }
    public static float[] convertPCM8ToFloatArray(byte[] pcm8) {
        float[] out = new float[pcm8.length];
        for (int i = 0; i < pcm8.length; i++) {
            int signed = (pcm8[i] & 0xFF) - 128;
            out[i] = signed / 128.0f;
        }
        return out;
    }
    public static byte[] applyMicGain(byte[] audioBuffer, MicGainBoost boost) {
        if (boost == MicGainBoost.NONE) return audioBuffer;
        float gain = MicGainBoost.toFloat(boost);
        byte[] out = new byte[audioBuffer.length];
        for (int i = 0; i < audioBuffer.length; i++) {
            int s = (audioBuffer[i] & 0xFF) - 128;
            s = (int) (s * gain);
            if (s > 127) s = 127;
            if (s < -128) s = -128;
            out[i] = (byte) (s + 128);
        }
        return out;
    }
    public interface CommandHandler {
        void onCommand(byte cmd, byte[] params);
    }
    public static final class RxParser {
        private final ByteArrayOutputStream leftoverBuffer = new ByteArrayOutputStream();
        public synchronized byte[] parse(byte[] newData, CommandHandler handler) {
            leftoverBuffer.write(newData, 0, newData.length);
            byte[] buffer = leftoverBuffer.toByteArray();
            ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
            int parsePos = 0;
            while (true) {
                int startDelim = indexOf(buffer, COMMAND_DELIMITER, parsePos);
                if (startDelim == -1) {
                    int partialLen = findPartialDelimiterTail(buffer, parsePos, buffer.length);
                    int pureAudioEnd = buffer.length - partialLen;
                    if (pureAudioEnd > parsePos) {
                        audioOut.write(buffer, parsePos, pureAudioEnd - parsePos);
                    }
                    leftoverBuffer.reset();
                    if (partialLen > 0) {
                        leftoverBuffer.write(buffer, pureAudioEnd, partialLen);
                    }
                    return audioOut.toByteArray();
                }
                if (startDelim > parsePos) {
                    audioOut.write(buffer, parsePos, startDelim - parsePos);
                }
                int neededBeforeParams = COMMAND_DELIMITER.length + 2;
                if (startDelim + neededBeforeParams > buffer.length) {
                    storeTailForNextTime(buffer, startDelim);
                    return audioOut.toByteArray();
                }
                int cmdPos = startDelim + COMMAND_DELIMITER.length;
                byte cmd = buffer[cmdPos];
                int paramLen = (buffer[cmdPos + 1] & 0xFF);
                int paramStart = cmdPos + 2;
                int paramEnd = paramStart + paramLen;
                if (paramEnd > buffer.length) {
                    storeTailForNextTime(buffer, startDelim);
                    return audioOut.toByteArray();
                }
                byte[] param = Arrays.copyOfRange(buffer, paramStart, paramEnd);
                if (handler != null) handler.onCommand(cmd, param);
                parsePos = paramEnd;
            }
        }
        private void storeTailForNextTime(byte[] buffer, int startIndex) {
            leftoverBuffer.reset();
            leftoverBuffer.write(buffer, startIndex, buffer.length - startIndex);
        }
    }
    public static int indexOf(byte[] data, byte[] pattern, int start) {
        if (pattern.length == 0 || start >= data.length) return -1;
        for (int i = start; i <= data.length - pattern.length; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) { found = false; break; }
            }
            if (found) return i;
        }
        return -1;
    }
    public static int findPartialDelimiterTail(byte[] data, int start, int end) {
        final int dataLen = end - start;
        for (int checkSize = COMMAND_DELIMITER.length - 1; checkSize >= 1; checkSize--) {
            if (checkSize > dataLen) continue;
            boolean match = true;
            for (int j = 0; j < checkSize; j++) {
                if (data[end - checkSize + j] != COMMAND_DELIMITER[j]) { match = false; break; }
            }
            if (match) return checkSize;
        }
        return 0;
    }
}