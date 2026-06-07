/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)

Swing replacement for FirmwareActivity. Shows the same "put the board in
bootloader mode" instructions as the Android app (hold BOOT, tap RESET, release
BOOT), then flashes the bundled firmware while showing progress. Flashing runs
on a background worker; the dialog can't be closed mid-flash.
*/
package com.vagell.kv4pht.desktop.ui;

import com.vagell.kv4pht.desktop.firmware.EspFlasher;
import com.vagell.kv4pht.desktop.firmware.FirmwareFlasher;
import com.vagell.kv4pht.desktop.radio.RadioAudioService;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class FirmwareDialog extends JDialog {

  private final RadioAudioService radio;
  private final JButton flashButton = new JButton("Start flashing");
  private final JButton closeButton = new JButton("Close");
  private final JProgressBar progress = new JProgressBar(0, 100);
  private final JLabel status =
      new JLabel("Ready to flash firmware v" + FirmwareFlasher.PACKAGED_FIRMWARE_VER + ".");
  private boolean busy = false;

  private FirmwareDialog(Frame owner, RadioAudioService radio) {
    super(owner, "Firmware update", true);
    this.radio = radio;
    buildUi();
    setSize(520, 420);
    setLocationRelativeTo(owner);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            if (!busy) dispose();
          }
        });
  }

  public static void show(Frame owner, RadioAudioService radio) {
    new FirmwareDialog(owner, radio).setVisible(true);
  }

  private void buildUi() {
    JPanel root = new JPanel(new BorderLayout(10, 10));
    root.setBorder(new EmptyBorder(14, 16, 14, 16));
    setContentPane(root);

    JLabel title = new JLabel("Install / repair kv4p HT firmware");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
    root.add(title, BorderLayout.NORTH);

    JEditorPane steps =
        new JEditorPane(
            "text/html",
            "<html><body style='font-family:sans-serif;font-size:12px;'>"
                + "Put the board into bootloader mode, then press <b>Start flashing</b>:"
                + "<ol>"
                + "<li>Hold the <b>BOOT</b> button</li>"
                + "<li>Press and release <b>RESET</b></li>"
                + "<li>Release <b>BOOT</b></li>"
                + "<li>Within a few seconds, click <b>Start flashing</b></li>"
                + "</ol>"
                + "Keep the radio plugged in the whole time. If it keeps failing, "
                + "the web flasher at kv4p.com is an alternative."
                + "</body></html>");
    steps.setEditable(false);
    steps.setOpaque(false);
    steps.setBorder(null);
    root.add(steps, BorderLayout.CENTER);

    JPanel south = new JPanel(new BorderLayout(8, 8));
    progress.setStringPainted(true);
    south.add(progress, BorderLayout.NORTH);
    status.setBorder(new EmptyBorder(4, 0, 4, 0));
    south.add(status, BorderLayout.CENTER);

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    flashButton.addActionListener(e -> startFlashing());
    closeButton.addActionListener(
        e -> {
          if (!busy) dispose();
        });
    buttons.add(closeButton);
    buttons.add(flashButton);
    south.add(buttons, BorderLayout.SOUTH);

    root.add(south, BorderLayout.SOUTH);
  }

  private void startFlashing() {
    if (busy) return;
    if (!radio.isConnectedForFlashing()) {
      status.setText("Not connected to a serial port. Connect first, then retry.");
      return;
    }
    busy = true;
    flashButton.setEnabled(false);
    closeButton.setEnabled(false);
    progress.setValue(0);
    status.setText("Connecting to bootloader...");

    final EspFlasher.SerialIO io = radio.beginFirmwareFlash();
    final FirmwareFlasher flasher = new FirmwareFlasher();

    new SwingWorker<Boolean, Object[]>() {
      private volatile boolean success = false;

      @Override
      protected Boolean doInBackground() {
        flasher.flashFirmware(
            io,
            new FirmwareFlasher.Callback() {
              @Override
              public void connectedToBootloader() {
                publish(new Object[] {"status", "Connected to bootloader. Flashing..."});
              }

              @Override
              public void reportProgress(int percent) {
                publish(new Object[] {"progress", percent});
              }

              @Override
              public void info(String message) {
                publish(new Object[] {"status", message});
              }

              @Override
              public void doneFlashing(boolean ok) {
                success = ok;
              }
            });
        return success;
      }

      @Override
      protected void process(java.util.List<Object[]> chunks) {
        for (Object[] c : chunks) {
          if ("progress".equals(c[0])) {
            progress.setValue((Integer) c[1]);
            progress.setString(c[1] + "%");
          } else if ("status".equals(c[0])) {
            status.setText(String.valueOf(c[1]));
          }
        }
      }

      @Override
      protected void done() {
        boolean ok;
        try {
          ok = get();
        } catch (Exception e) {
          ok = false;
        }
        radio.finishFirmwareFlash(ok);
        progress.setValue(ok ? 100 : progress.getValue());
        status.setText(
            ok
                ? "Firmware flashed successfully. Reconnecting to the radio..."
                : "Flashing failed. Make sure the board is in bootloader mode and try again.");
        busy = false;
        flashButton.setEnabled(true);
        flashButton.setText("Flash again");
        closeButton.setEnabled(true);
      }
    }.execute();
  }
}
