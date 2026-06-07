/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)
*/
package com.vagell.kv4pht.desktop;

import com.vagell.kv4pht.desktop.data.AppDatabase;
import com.vagell.kv4pht.desktop.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class App {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        final AppDatabase db = AppDatabase.open();
        SwingUtilities.invokeLater(() -> {
            MainWindow w = new MainWindow(db);
            w.setLocationByPlatform(true);
            w.setVisible(true);
        });
    }
}
