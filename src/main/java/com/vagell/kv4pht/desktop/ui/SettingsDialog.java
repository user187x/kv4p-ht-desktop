/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)
*/
package com.vagell.kv4pht.desktop.ui;

import com.vagell.kv4pht.desktop.data.AppDatabase;
import com.vagell.kv4pht.desktop.radio.RadioAudioService;
import java.awt.*;
import javax.swing.*;

/** Settings editor mirroring the Android SettingsActivity options that apply to desktop. */
final class SettingsDialog {

  private SettingsDialog() {}

  static void show(Component parent, AppDatabase db, RadioAudioService radio) {
    JComboBox<String> bandwidth = new JComboBox<>(new String[] {"Wide", "Narrow"});
    bandwidth.setSelectedItem(db.getSetting("bandwidth", "Wide"));

    JComboBox<String> maxFreq = new JComboBox<>(new String[] {"148", "146"});
    maxFreq.setSelectedItem(db.getSetting("maxFreq", "148"));

    JComboBox<String> micGain = new JComboBox<>(new String[] {"None", "Low", "Med", "High"});
    micGain.setSelectedItem(db.getSetting("micGainBoost", "None"));

    JCheckBox emphasis =
        new JCheckBox("Pre- & De-emphasis", "1".equals(db.getSetting("emphasis", "1")));
    JCheckBox highpass = new JCheckBox("Highpass", "1".equals(db.getSetting("highpass", "1")));
    JCheckBox lowpass = new JCheckBox("Lowpass", "1".equals(db.getSetting("lowpass", "1")));

    JTextField callsign = new JTextField(db.getSetting("callsign", ""), 10);

    JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
    p.add(new JLabel("Bandwidth:"));
    p.add(bandwidth);
    p.add(new JLabel("Max TX frequency:"));
    p.add(maxFreq);
    p.add(new JLabel("Mic gain boost:"));
    p.add(micGain);
    p.add(new JLabel("Callsign:"));
    p.add(callsign);
    p.add(new JLabel("Filters:"));
    p.add(emphasis);
    p.add(new JLabel(""));
    p.add(highpass);
    p.add(new JLabel(""));
    p.add(lowpass);

    int res =
        JOptionPane.showConfirmDialog(
            parent, p, "Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (res != JOptionPane.OK_OPTION) return;

    db.setSetting("bandwidth", (String) bandwidth.getSelectedItem());
    db.setSetting("maxFreq", (String) maxFreq.getSelectedItem());
    db.setSetting("micGainBoost", (String) micGain.getSelectedItem());
    db.setSetting("callsign", callsign.getText().trim());
    db.setSetting("emphasis", emphasis.isSelected() ? "1" : "0");
    db.setSetting("highpass", highpass.isSelected() ? "1" : "0");
    db.setSetting("lowpass", lowpass.isSelected() ? "1" : "0");

    // Apply live.
    radio.setBandwidth((String) bandwidth.getSelectedItem());
    radio.setMaxFreq(Integer.parseInt((String) maxFreq.getSelectedItem()));
    radio.setMicGainBoost((String) micGain.getSelectedItem());
    radio.setFilterSettings(emphasis.isSelected(), highpass.isSelected(), lowpass.isSelected());
  }
}
