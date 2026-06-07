package com.vagell.kv4pht.desktop.firmware;
import java.util.zip.Deflater;
public class EspFlasher {
    public interface SerialIO {
        int read(byte[] buf, int len, int timeoutMs);
        void write(byte[] data, int timeoutMs);
        void setBaud(int baud);
        void setRTS(boolean on);
        void setDTR(boolean on);
    }
    public interface Progress {
        default void onInfo(String msg) {}
        default void onUploading(int percent) {}
    }
    public static final int ESP_FLASH_BLOCK = 0x400;
    private static final int ESP_ROM_BAUD = 115200;
    private static final int FLASH_WRITE_SIZE = 0x400;
    private static final int STUBLOADER_FLASH_WRITE_SIZE = 0x8000;
    private static final int FLASH_SECTOR_SIZE = 0x1000;
    private static final int FLASH_TIMEOUT_MS = 4000;
    private static final int COMMAND_TIMEOUT_MS = 100;
    private static final int CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000;
    private static final int ESP8266 = 0x8266;
    public static final int ESP32 = 0x32;
    private static final int ESP32S2 = 0x3252;
    private static final int ESP_FLASH_BEGIN = 0x02;
    private static final int ESP_FLASH_DATA = 0x03;
    private static final int ESP_FLASH_END = 0x04;
    private static final int ESP_MEM_BEGIN = 0x05;
    private static final int ESP_MEM_END = 0x06;
    private static final int ESP_MEM_DATA = 0x07;
    private static final int ESP_SYNC = 0x08;
    private static final int ESP_WRITE_REG = 0x09;
    private static final int ESP_READ_REG = 0x0A;
    private static final int ESP_SPI_SET_PARAMS = 0x0B;
    private static final int ESP_SPI_ATTACH = 0x0D;
    private static final int ESP_CHANGE_BAUDRATE = 0x0F;
    private static final int ESP_FLASH_DEFL_BEGIN = 0x10;
    private static final int ESP_FLASH_DEFL_DATA = 0x11;
    private static final int ESP_FLASH_DEFL_END = 0x12;
    private static final byte ESP_CHECKSUM_MAGIC = (byte) 0xEF;
    private static final int ERASE_REGION_TIMEOUT_PER_MB = 30000;
    private static final boolean IS_STUB = false;
    static final class CmdRet {
        int retCode;
        byte[] retValue = new byte[512];
    }
    private final SerialIO io;
    private final Progress cb;
    public EspFlasher(SerialIO io, Progress cb) {
        this.io = io;
        this.cb = (cb == null) ? new Progress() {} : cb;
    }
    byte[] slipEncode(byte[] buffer) {
        byte[] encoded = new byte[] {(byte) 0xC0};
        for (int x = 0; x < buffer.length; x++) {
            if (buffer[x] == (byte) 0xC0) {
                encoded = append(encoded, new byte[] {(byte) 0xDB, (byte) 0xDC});
            } else if (buffer[x] == (byte) 0xDB) {
                encoded = append(encoded, new byte[] {(byte) 0xDB, (byte) 0xDD});
            } else {
                encoded = append(encoded, new byte[] {buffer[x]});
            }
        }
        encoded = append(encoded, new byte[] {(byte) 0xC0});
        return encoded;
    }
    CmdRet sendCommand(byte opcode, byte[] buffer, int chk, int timeout) {
        CmdRet retVal = new CmdRet();
        byte[] data = new byte[8 + buffer.length];
        data[0] = 0x00;
        data[1] = opcode;
        data[2] = (byte) (buffer.length & 0xFF);
        data[3] = (byte) ((buffer.length >> 8) & 0xFF);
        data[4] = (byte) (chk & 0xFF);
        data[5] = (byte) ((chk >> 8) & 0xFF);
        data[6] = (byte) ((chk >> 16) & 0xFF);
        data[7] = (byte) ((chk >> 24) & 0xFF);
        System.arraycopy(buffer, 0, data, 8, buffer.length);
        retVal.retCode = 0;
        byte[] buf = slipEncode(data);
        io.write(buf, timeout);
        sleep(5);
        for (int i = 0; i < 10; i++) {
            int numRead = recv(retVal.retValue, retVal.retValue.length, timeout / 5);
            if (numRead <= 0) {
                retVal.retCode = -1;
                continue;
            }
            if (retVal.retValue[0] != (byte) 0xC0) {
                retVal.retCode = -1;
                continue;
            }
            cb.onInfo("Correct return value from ESP32");
            retVal.retCode = 1;
            break;
        }
        return retVal;
    }
    private int recv(byte[] buf, int length, long timeout) {
        int retval = 0;
        int totalRetval = 0;
        long startTime = System.currentTimeMillis();
        byte[] tmpbuf = new byte[length];
        while (true) {
            retval = io.read(tmpbuf, length, 1000);
            if (retval > 0) {
                int copy = Math.min(retval, buf.length - totalRetval);
                if (copy > 0) {
                    System.arraycopy(tmpbuf, 0, buf, totalRetval, copy);
                    totalRetval += copy;
                }
                startTime = System.currentTimeMillis();
            }
            if (totalRetval >= 8) break;
            if ((System.currentTimeMillis() - startTime) > timeout) break;
        }
        return totalRetval;
    }
    public boolean initChip() {
        if (io == null) return false;
        io.setBaud(ESP_ROM_BAUD);
        boolean syncSuccess = false;
        cb.onInfo("Syncing with ESP32");
        for (int i = 0; i < 3; i++) {
            cb.onInfo("Sync attempt " + (i + 1));
            if (sync() != 0) {
                syncSuccess = true;
                cb.onInfo("Sync success");
                sleep(1000);
                break;
            }
        }
        return syncSuccess;
    }
    int sync() {
        int response = 0;
        byte[] cmddata = new byte[36];
        cmddata[0] = 0x07;
        cmddata[1] = 0x07;
        cmddata[2] = 0x12;
        cmddata[3] = 0x20;
        for (int x = 4; x < 36; x++) cmddata[x] = 0x55;
        for (int x = 0; x < 7; x++) {
            CmdRet ret = sendCommand((byte) ESP_SYNC, cmddata, 0, COMMAND_TIMEOUT_MS);
            if (ret.retCode == 1) {
                response = 1;
                break;
            }
        }
        return response;
    }
    public int readRegister(int reg) {
        try {
            byte[] packet = intToBytes(reg);
            CmdRet ret = sendCommand((byte) ESP_READ_REG, packet, 0, 100);
            return (ret.retValue[5] & 0xFF)
                    | ((ret.retValue[6] & 0xFF) << 8)
                    | ((ret.retValue[7] & 0xFF) << 16)
                    | ((ret.retValue[8] & 0xFF) << 24);
        } catch (Exception e) {
            return 0;
        }
    }
    public int detectChip() {
        int magic = readRegister(CHIP_DETECT_MAGIC_REG_ADDR);
        if (magic == 0xfff0c101) return ESP8266;
        if (magic == 0x00f01d83 || magic == 0xf01d) return ESP32;
        if (magic == 0x000007c6) return ESP32S2;
        return 0;
    }
    public void init() {
        int flashSize = 4 * 1024 * 1024;
        if (!IS_STUB) {
            byte[] pkt = append(intToBytes(0), intToBytes(0));
            sendCommand((byte) ESP_SPI_ATTACH, pkt, 0, COMMAND_TIMEOUT_MS);
        }
        cb.onInfo("Configuring flash size...");
        byte[] pkt2 = append(intToBytes(0), intToBytes(flashSize));
        pkt2 = append(pkt2, intToBytes(0x10000));
        pkt2 = append(pkt2, intToBytes(4096));
        pkt2 = append(pkt2, intToBytes(256));
        pkt2 = append(pkt2, intToBytes(0xFFFF));
        sendCommand((byte) ESP_SPI_SET_PARAMS, pkt2, 0, COMMAND_TIMEOUT_MS);
    }
    private int flashDeflBegin(int size, int compsize, int offset) {
        int writeBlock = IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE;
        int numBlocks = (int) Math.floor((double) (compsize + writeBlock - 1) / (double) writeBlock);
        int eraseBlocks = (int) Math.floor((double) (size + writeBlock - 1) / (double) writeBlock);
        int writeSize, timeout;
        if (IS_STUB) {
            writeSize = size;
            timeout = 3000;
        } else {
            writeSize = eraseBlocks * writeBlock;
            timeout = timeoutPerMb(ERASE_REGION_TIMEOUT_PER_MB, writeSize);
        }
        cb.onInfo("Compressed " + size + " bytes to " + compsize + "...");
        byte[] pkt = append(intToBytes(writeSize), intToBytes(numBlocks));
        pkt = append(pkt, intToBytes(writeBlock));
        pkt = append(pkt, intToBytes(offset));
        sendCommand((byte) ESP_FLASH_DEFL_BEGIN, pkt, 0, timeout);
        return numBlocks;
    }
    private CmdRet flashDeflBlock(byte[] data, int seq, int timeout) {
        byte[] pkt = append(intToBytes(data.length), intToBytes(seq));
        pkt = append(pkt, intToBytes(0));
        pkt = append(pkt, intToBytes(0));
        pkt = append(pkt, data);
        return sendCommand((byte) ESP_FLASH_DEFL_DATA, pkt, checksum(data), timeout);
    }
    public void flashData(byte[] binaryData, int offset, int part) {
        int filesize = binaryData.length;
        cb.onInfo("Writing data with filesize: " + filesize);
        byte[] image = compressBytes(binaryData);
        int blocks = flashDeflBegin(filesize, image.length, offset);
        int seq = 0;
        int position = 0;
        int writeBlock = IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE;
        while (image.length - position > 0) {
            int percentage = (int) Math.floor((double) (100 * (seq + 1)) / (double) blocks);
            cb.onUploading(percentage);
            byte[] block;
            if (image.length - position >= writeBlock) {
                block = sub(image, position, writeBlock);
            } else {
                block = sub(image, position, image.length - position);
            }
            int attempts = 0;
            final int MAX_ATTEMPTS = 10;
            CmdRet retVal;
            do {
                attempts++;
                retVal = flashDeflBlock(block, seq, FLASH_TIMEOUT_MS);
                if (retVal.retCode == -1) {
                    cb.onInfo("Retry #" + attempts + " (ret code " + retVal.retCode + ")");
                }
            } while (attempts < MAX_ATTEMPTS && retVal.retCode == -1);
            seq += 1;
            position += writeBlock;
        }
    }
    public void reset() {
        try {
            io.setDTR(false);
            io.setRTS(true);
            sleep(100);
            io.setDTR(true);
            io.setRTS(false);
            sleep(50);
            io.setDTR(false);
        } catch (Exception e) {
        }
    }
    int checksum(byte[] data) {
        int chk = ESP_CHECKSUM_MAGIC & 0xFF;
        for (byte datum : data) chk ^= (datum & 0xFF);
        return chk;
    }
    byte[] compressBytes(byte[] uncompressed) {
        Deflater compressor = new Deflater();
        compressor.setLevel(Deflater.BEST_COMPRESSION);
        compressor.setInput(uncompressed);
        compressor.finish();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(uncompressed.length);
        byte[] buf = new byte[1024];
        while (!compressor.finished()) {
            int count = compressor.deflate(buf);
            bos.write(buf, 0, count);
        }
        compressor.end();
        return bos.toByteArray();
    }
    private int timeoutPerMb(int secondsPerMb, int sizeBytes) {
        int result = secondsPerMb * (sizeBytes / 1_000_000);
        return Math.max(result, 3000);
    }
    private static byte[] intToBytes(int i) {
        return new byte[] {
                (byte) (i & 0xff),
                (byte) ((i >> 8) & 0xff),
                (byte) ((i >> 16) & 0xff),
                (byte) ((i >> 24) & 0xff)
        };
    }
    private static byte[] append(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
    private static byte[] sub(byte[] a, int pos, int length) {
        byte[] c = new byte[length];
        System.arraycopy(a, pos, c, 0, length);
        return c;
    }
    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
