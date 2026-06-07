/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)
*/
package com.vagell.kv4pht.desktop.ui;

import com.vagell.kv4pht.desktop.data.ChannelMemory;
import com.vagell.kv4pht.desktop.radio.RadioProtocol;
import java.awt.*;
import javax.swing.*;

/** Modal editor for a single ChannelMemory. Returns the edited memory or null on cancel. */
final class MemoryDialog {

  private MemoryDialog() {}

  static ChannelMemory show(Component parent, ChannelMemory existing) {
    JTextField name = new JTextField(existing != null ? nz(existing.name) : "", 16);
    JTextField freq = new JTextField(existing != null ? nz(existing.frequency) : "146.5200", 10);
    JComboBox<String> offset = new JComboBox<>(new String[] {"None", "Down (-)", "Up (+)"});
    JComboBox<String> tone = new JComboBox<>(RadioProtocol.toneOptions());
    JTextField group = new JTextField(existing != null ? nz(existing.group) : "", 12);

    if (existing != null) {
      offset.setSelectedIndex(existing.offset);
      tone.setSelectedItem(existing.tone == null ? "None" : existing.tone);
    }

    JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
    p.add(new JLabel("Name:"));
    p.add(name);
    p.add(new JLabel("Frequency:"));
    p.add(freq);
    p.add(new JLabel("Offset:"));
    p.add(offset);
    p.add(new JLabel("Tone:"));
    p.add(tone);
    p.add(new JLabel("Group:"));
    p.add(group);

    int res =
        JOptionPane.showConfirmDialog(
            parent,
            p,
            existing == null ? "Add memory" : "Edit memory",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    if (res != JOptionPane.OK_OPTION) return null;

    ChannelMemory m = existing != null ? existing : new ChannelMemory();
    m.name = name.getText().trim();
    m.frequency = RadioProtocol.makeSafe2MFreq(freq.getText().trim());
    m.offset = offset.getSelectedIndex(); // 0/1/2 matches OFFSET_NONE/DOWN/UP
    m.tone = (String) tone.getSelectedItem();
    m.group = group.getText().trim();
    return m;
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
