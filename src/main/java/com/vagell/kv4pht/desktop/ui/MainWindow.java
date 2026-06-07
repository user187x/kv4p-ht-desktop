/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)

Swing replacement for MainActivity. Provides the frequency display, push-to-talk
(press-and-hold, or sticky), S-meter, squelch, channel-memory list, scan toggle
and a serial-port picker. Talks to RadioAudioService and persists state through
AppDatabase.
*/
package com.vagell.kv4pht.desktop.ui;

import com.fazecast.jSerialComm.SerialPort;
import com.vagell.kv4pht.desktop.data.AppDatabase;
import com.vagell.kv4pht.desktop.data.ChannelMemory;
import com.vagell.kv4pht.desktop.radio.RadioAudioService;
import com.vagell.kv4pht.desktop.radio.RadioAudioServiceCallbacks;
import com.vagell.kv4pht.desktop.radio.RadioProtocol;
import com.vagell.kv4pht.desktop.serial.SerialRadio;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class MainWindow extends JFrame {

  private final AppDatabase db;
  private final RadioAudioService radio = new RadioAudioService();

  private final JComboBox<PortItem> portCombo = new JComboBox<>();
  private final JButton connectButton = new JButton("Connect");
  private final JLabel statusLabel = new JLabel("Not connected");
  private final JTextField freqField = new JTextField("144.0000", 8);
  private final JButton tuneButton = new JButton("Tune");
  private final JProgressBar sMeter = new JProgressBar(0, 9);
  private final JSlider squelchSlider = new JSlider(0, 8, 0);
  private final JToggleButton scanButton = new JToggleButton("Scan");
  private final JCheckBox stickyPttCheck = new JCheckBox("Sticky PTT");
  private final JButton pttButton = new JButton("PUSH TO TALK");
  private final DefaultListModel<ChannelMemory> memModel = new DefaultListModel<>();
  private final JList<ChannelMemory> memList = new JList<>(memModel);

  private boolean sticky = false;

  public MainWindow(AppDatabase db) {
    super("kv4p HT (Desktop)");
    this.db = db;

    setDefaultCloseOperation(EXIT_ON_CLOSE);
    setMinimumSize(new Dimension(760, 560));
    buildUi();
    wireRadio();
    loadSettings();
    refreshPorts();
    refreshMemories();

    addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            saveSettings();
            radio.shutdown();
            db.close();
          }
        });
  }

  private static int parseIntOr(String s, int def) {
    try {
      return s == null ? def : Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private void buildUi() {
    JPanel root = new JPanel(new BorderLayout(8, 8));
    root.setBorder(new EmptyBorder(10, 10, 10, 10));
    setContentPane(root);

    // ---- Top: connection + settings ----
    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    top.add(new JLabel("Port:"));
    top.add(portCombo);
    JButton refresh = new JButton("\u21bb");
    refresh.setToolTipText("Refresh ports");
    refresh.addActionListener(e -> refreshPorts());
    top.add(refresh);
    top.add(connectButton);
    JButton settingsButton = new JButton("Settings\u2026");
    settingsButton.addActionListener(e -> openSettings());
    top.add(settingsButton);
    JButton firmwareButton = new JButton("Flash Firmware\u2026");
    firmwareButton.setToolTipText("Install or repair the ESP32 firmware on the radio");
    firmwareButton.addActionListener(e -> openFirmware());
    top.add(firmwareButton);
    root.add(top, BorderLayout.NORTH);

    connectButton.addActionListener(e -> toggleConnection());

    // ---- Center: frequency, s-meter, squelch, PTT ----
    JPanel center = new JPanel();
    center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

    JPanel freqRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
    freqField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 48));
    freqField.setHorizontalAlignment(JTextField.CENTER);
    freqRow.add(freqField);
    freqRow.add(new JLabel("MHz"));
    freqRow.add(tuneButton);
    center.add(freqRow);
    tuneButton.addActionListener(e -> tuneToTyped());
    freqField.addActionListener(e -> tuneToTyped());

    JPanel meterRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
    meterRow.add(new JLabel("S-meter:"));
    sMeter.setStringPainted(true);
    sMeter.setPreferredSize(new Dimension(280, 22));
    meterRow.add(sMeter);
    center.add(meterRow);

    JPanel sqRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
    sqRow.add(new JLabel("Squelch:"));
    squelchSlider.setMajorTickSpacing(1);
    squelchSlider.setPaintTicks(true);
    squelchSlider.setPaintLabels(true);
    squelchSlider.setPreferredSize(new Dimension(280, 48));
    squelchSlider.addChangeListener(
        e -> {
          if (!squelchSlider.getValueIsAdjusting()) retuneCurrent();
        });
    sqRow.add(squelchSlider);
    center.add(sqRow);

    JPanel pttRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
    pttButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
    pttButton.setPreferredSize(new Dimension(320, 80));
    pttButton.setEnabled(false);
    pttRow.add(pttButton);
    center.add(pttRow);

    JPanel togglesRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
    togglesRow.add(scanButton);
    togglesRow.add(stickyPttCheck);
    center.add(togglesRow);

    root.add(center, BorderLayout.CENTER);

    stickyPttCheck.addActionListener(e -> sticky = stickyPttCheck.isSelected());
    scanButton.addActionListener(e -> radio.setScanning(scanButton.isSelected()));

    setupPttBehavior();

    // ---- Right: memories ----
    JPanel right = new JPanel(new BorderLayout(4, 4));
    right.setBorder(BorderFactory.createTitledBorder("Favorites"));
    right.setPreferredSize(new Dimension(240, 0));
    memList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    memList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) tuneToSelectedMemory();
        });
    right.add(new JScrollPane(memList), BorderLayout.CENTER);

    JPanel memButtons = new JPanel(new GridLayout(1, 3, 4, 0));
    JButton addBtn = new JButton("Add");
    JButton editBtn = new JButton("Edit");
    JButton delBtn = new JButton("Del");
    addBtn.addActionListener(e -> addMemory());
    editBtn.addActionListener(e -> editMemory());
    delBtn.addActionListener(e -> deleteMemory());
    memButtons.add(addBtn);
    memButtons.add(editBtn);
    memButtons.add(delBtn);
    right.add(memButtons, BorderLayout.SOUTH);
    root.add(right, BorderLayout.EAST);

    // ---- Bottom: status ----
    statusLabel.setBorder(new EmptyBorder(4, 4, 0, 4));
    root.add(statusLabel, BorderLayout.SOUTH);
  }

  private void setupPttBehavior() {
    pttButton.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            if (sticky) {
              if (radio.getMode() == RadioAudioService.MODE_TX) radio.endPtt();
              else radio.startPtt();
            } else {
              radio.startPtt();
            }
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            if (!sticky) radio.endPtt();
          }
        });
  }

  // ====================================================================
  private void wireRadio() {
    radio.setCallbacks(
        new RadioAudioServiceCallbacks() {
          @Override
          public void connected() {
            SwingUtilities.invokeLater(
                () -> {
                  statusLabel.setText("Connected \u2014 receiving");
                  connectButton.setText("Disconnect");
                  radio.setChannelMemories(currentMemories());
                });
          }

          @Override
          public void radioMissing() {
            SwingUtilities.invokeLater(
                () -> statusLabel.setText("No radio found. Pick a port and try again."));
          }

          @Override
          public void disconnected() {
            SwingUtilities.invokeLater(
                () -> {
                  statusLabel.setText("Disconnected");
                  connectButton.setText("Connect");
                  pttButton.setEnabled(false);
                  sMeter.setValue(0);
                });
          }

          @Override
          public void missingFirmware() {
            SwingUtilities.invokeLater(
                () -> {
                  statusLabel.setText("ESP32 firmware not responding.");
                  int choice =
                      JOptionPane.showConfirmDialog(
                          MainWindow.this,
                          "The radio's ESP32 didn't respond with a firmware version.\n"
                              + "This usually means the firmware is missing or corrupt.\n\n"
                              + "Flash the bundled firmware now?",
                          "Firmware not responding",
                          JOptionPane.YES_NO_OPTION,
                          JOptionPane.WARNING_MESSAGE);
                  if (choice == JOptionPane.YES_OPTION) {
                    openFirmware();
                  }
                });
          }

          @Override
          public void txAllowed(boolean allowed) {
            SwingUtilities.invokeLater(
                () -> {
                  pttButton.setEnabled(allowed && radio.isConnected());
                  pttButton.setToolTipText(allowed ? null : "TX not allowed on this frequency");
                });
          }

          @Override
          public void txStarted() {
            SwingUtilities.invokeLater(
                () -> {
                  pttButton.setBackground(new Color(0xD32F2F));
                  pttButton.setText("TRANSMITTING");
                  statusLabel.setText("Transmitting\u2026");
                });
          }

          @Override
          public void txEnded() {
            SwingUtilities.invokeLater(
                () -> {
                  pttButton.setBackground(null);
                  pttButton.setText("PUSH TO TALK");
                  statusLabel.setText("Connected \u2014 receiving");
                });
          }

          @Override
          public void sMeterUpdate(int v) {
            SwingUtilities.invokeLater(
                () -> {
                  sMeter.setValue(v);
                  sMeter.setString("S" + v);
                });
          }

          @Override
          public void scannedToMemory(int memoryId) {
            SwingUtilities.invokeLater(() -> selectMemoryById(memoryId));
          }

          @Override
          public void status(String message) {
            SwingUtilities.invokeLater(() -> statusLabel.setText(message));
          }
        });
  }

  // ====================================================================
  // Ports / connection
  private void refreshPorts() {
    portCombo.removeAllItems();
    List<SerialPort> ports = SerialRadio.listPorts();
    SerialPort guess = SerialRadio.guessRadioPort();
    for (SerialPort p : ports) {
      PortItem item = new PortItem(p);
      portCombo.addItem(item);
      if (guess != null && p.getSystemPortName().equals(guess.getSystemPortName())) {
        portCombo.setSelectedItem(item);
      }
    }
    if (ports.isEmpty()) statusLabel.setText("No serial ports detected.");
  }

  private void toggleConnection() {
    if (radio.isConnected()) {
      radio.disconnect();
    } else {
      PortItem item = (PortItem) portCombo.getSelectedItem();
      radio.connect(item == null ? null : item.port);
    }
  }

  // ====================================================================
  // Tuning
  private void tuneToTyped() {
    String safe = RadioProtocol.makeSafe2MFreq(freqField.getText());
    freqField.setText(safe);
    memList.clearSelection();
    radio.tuneToFreq(safe, squelchSlider.getValue(), true);
  }

  private void retuneCurrent() {
    if (!radio.isConnected()) return;
    if (radio.getActiveMemoryId() >= 0) {
      radio.tuneToMemory(radio.getActiveMemoryId(), squelchSlider.getValue(), true);
    } else {
      radio.tuneToFreq(freqField.getText(), squelchSlider.getValue(), true);
    }
  }

  private void tuneToSelectedMemory() {
    ChannelMemory m = memList.getSelectedValue();
    if (m == null) return;
    freqField.setText(RadioProtocol.makeSafe2MFreq(m.frequency));
    radio.tuneToMemory(m, squelchSlider.getValue(), true);
  }

  private void selectMemoryById(int id) {
    for (int i = 0; i < memModel.size(); i++) {
      if (memModel.get(i).memoryId == id) {
        memList.setSelectedIndex(i);
        freqField.setText(RadioProtocol.makeSafe2MFreq(memModel.get(i).frequency));
        return;
      }
    }
  }

  // ====================================================================
  // Memories
  private java.util.List<ChannelMemory> currentMemories() {
    java.util.List<ChannelMemory> list = new java.util.ArrayList<>();
    for (int i = 0; i < memModel.size(); i++) list.add(memModel.get(i));
    return list;
  }

  private void refreshMemories() {
    memModel.clear();
    for (ChannelMemory m : db.getAllMemories()) memModel.addElement(m);
    radio.setChannelMemories(currentMemories());
  }

  private void addMemory() {
    ChannelMemory m = MemoryDialog.show(this, null);
    if (m != null) {
      db.insertMemory(m);
      refreshMemories();
    }
  }

  private void editMemory() {
    ChannelMemory sel = memList.getSelectedValue();
    if (sel == null) return;
    ChannelMemory m = MemoryDialog.show(this, sel);
    if (m != null) {
      db.updateMemory(m);
      refreshMemories();
    }
  }

  private void deleteMemory() {
    ChannelMemory sel = memList.getSelectedValue();
    if (sel == null) return;
    if (JOptionPane.showConfirmDialog(
            this, "Delete \"" + sel + "\"?", "Confirm", JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION) {
      db.deleteMemory(sel.memoryId);
      refreshMemories();
    }
  }

  // ====================================================================
  // Settings persistence
  private void loadSettings() {
    squelchSlider.setValue(parseIntOr(db.getSetting("squelch"), 0));
    radio.setSquelch(squelchSlider.getValue());
    radio.setBandwidth(db.getSetting("bandwidth", "Wide"));
    radio.setMicGainBoost(db.getSetting("micGainBoost", "None"));
    radio.setMaxFreq(parseIntOr(db.getSetting("maxFreq"), 148));
    radio.setFilterSettings(
        "1".equals(db.getSetting("emphasis", "1")),
        "1".equals(db.getSetting("highpass", "1")),
        "1".equals(db.getSetting("lowpass", "1")));
    String lastFreq = db.getSetting("lastFreq", "144.0000");
    freqField.setText(RadioProtocol.makeSafe2MFreq(lastFreq));
  }

  private void saveSettings() {
    db.setSetting("squelch", Integer.toString(squelchSlider.getValue()));
    db.setSetting("lastFreq", RadioProtocol.makeSafe2MFreq(freqField.getText()));
  }

  private void openSettings() {
    SettingsDialog.show(this, db, radio);
  }

  private void openFirmware() {
    if (!radio.isConnectedForFlashing()) {
      JOptionPane.showMessageDialog(
          this,
          "Connect to the radio's serial port first, then flash firmware.",
          "Not connected",
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    FirmwareDialog.show(this, radio);
  }

  /** Wraps a SerialPort for nice display in the combo box. */
  private record PortItem(SerialPort port) {

    @Override
    public String toString() {
      return port.getSystemPortName() + "  (" + port.getDescriptivePortName() + ")";
    }
  }
}
