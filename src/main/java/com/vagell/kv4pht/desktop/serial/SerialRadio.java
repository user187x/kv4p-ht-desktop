/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)

Replaces the Android usb-serial-for-android stack with jSerialComm, which works
on Windows / macOS / Linux and bundles its own native libraries. Opens the port
at the same 230400 8N1 settings the ESP32 firmware expects, asserts RTS+DTR
(needed by the CP210x bridge on the kv4p board), and streams incoming bytes to a
listener on a dedicated read thread.
*/
package com.vagell.kv4pht.desktop.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.vagell.kv4pht.desktop.firmware.EspFlasher;
import com.vagell.kv4pht.desktop.radio.RadioProtocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SerialRadio {

    public interface DataListener {
        void onData(byte[] data);
        void onDisconnected();
    }

    private SerialPort port;
    private volatile boolean running = false;
    private volatile boolean flashing = false;
    private Thread readThread;
    private DataListener listener;

    /** Returns the currently available serial ports. */
    public static List<SerialPort> listPorts() {
        return new ArrayList<>(Arrays.asList(SerialPort.getCommPorts()));
    }

    /**
     * Best guess at which port is the kv4p HT, based on the CP210x USB-UART
     * bridge description. Returns null if nothing looks like a match.
     */
    public static SerialPort guessRadioPort() {
        for (SerialPort p : SerialPort.getCommPorts()) {
            String desc = (p.getDescriptivePortName() + " " + p.getPortDescription()).toLowerCase();
            if (desc.contains("cp210") || desc.contains("silicon labs")
                    || desc.contains("uart") || desc.contains("usb serial")) {
                return p;
            }
        }
        // Fall back to the first available port if there's exactly one.
        SerialPort[] all = SerialPort.getCommPorts();
        return all.length == 1 ? all[0] : null;
    }

    public boolean isOpen() {
        return port != null && port.isOpen();
    }

    public String portName() {
        return port == null ? "(none)" : port.getSystemPortName();
    }

    /** Opens the given port with the radio's serial parameters. */
    public synchronized void open(SerialPort p, DataListener listener) throws Exception {
        close();
        this.listener = listener;
        this.port = p;

        port.setComPortParameters(RadioProtocol.SERIAL_BAUD, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

        if (!port.openPort()) {
            this.port = null;
            throw new Exception("Could not open serial port " + p.getSystemPortName());
        }

        // The CP210x on the kv4p board behaves best with RTS+DTR asserted.
        try { port.setRTS(); port.setDTR(); } catch (Throwable ignored) {}

        running = true;
        readThread = new Thread(this::readLoop, "kv4p-serial-read");
        readThread.setDaemon(true);
        readThread.start();
    }

    private void readLoop() {
        byte[] buf = new byte[4096];
        while (running && port != null && port.isOpen()) {
            try {
                int n = port.readBytes(buf, buf.length);
                if (n > 0) {
                    byte[] chunk = Arrays.copyOf(buf, n);
                    if (listener != null) listener.onData(chunk);
                } else if (n < 0) {
                    break; // error / port closed
                }
            } catch (Exception e) {
                break;
            }
        }
        running = false;
        if (listener != null && !flashing) listener.onDisconnected();
    }

    /** Writes bytes to the radio in <=128 byte pieces, like the original app. */
    public synchronized void write(byte[] data) {
        if (!isOpen()) return;
        final int MAX = 128;
        int written = 0;
        while (written < data.length) {
            int len = Math.min(MAX, data.length - written);
            byte[] part = Arrays.copyOfRange(data, written, written + len);
            int w = port.writeBytes(part, part.length);
            if (w < 0) break;
            written += len;
        }
    }

    public synchronized void close() {
        running = false;
        flashing = false;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        if (port != null && port.isOpen()) {
            try { port.closePort(); } catch (Throwable ignored) {}
        }
        port = null;
    }

    // ====================================================================
    // Firmware flashing support
    // ====================================================================
    /**
     * Suspends the normal read loop and hands back a serial channel the firmware
     * flasher can use directly. The port stays open. Call {@link #endFlashing()}
     * (or {@link #close()}) when done.
     */
    public synchronized EspFlasher.SerialIO beginFlashing() {
        flashing = true;
        running = false;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        // The ROM bootloader replies relatively slowly; give reads room to block.
        if (port != null) {
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 2000);
        }
        return new PortFlasherIO(port);
    }

    public synchronized void endFlashing() {
        flashing = false;
    }

    /** Adapts a jSerialComm port to the flasher's small SerialIO contract. */
    private static final class PortFlasherIO implements EspFlasher.SerialIO {
        private final SerialPort p;
        PortFlasherIO(SerialPort p) { this.p = p; }

        @Override public int read(byte[] buf, int len, int timeoutMs) {
            if (p == null || !p.isOpen()) return -1;
            int n = p.readBytes(buf, Math.min(len, buf.length));
            return Math.max(n, 0);
        }
        @Override public void write(byte[] data, int timeoutMs) {
            if (p == null || !p.isOpen()) return;
            p.writeBytes(data, data.length);
        }
        @Override public void setBaud(int baud) {
            if (p != null) {
                p.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            }
        }
        @Override public void setRTS(boolean on) {
            if (p == null) return;
            if (on) p.setRTS(); else p.clearRTS();
        }
        @Override public void setDTR(boolean on) {
            if (p == null) return;
            if (on) p.setDTR(); else p.clearDTR();
        }
    }
}
