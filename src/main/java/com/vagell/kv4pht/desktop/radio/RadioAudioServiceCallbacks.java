/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)
*/
package com.vagell.kv4pht.desktop.radio;

/** Callbacks from the radio service back to the UI. */
public interface RadioAudioServiceCallbacks {
    default void connected() {}
    default void radioMissing() {}
    default void disconnected() {}
    default void missingFirmware() {}
    default void outdatedFirmware(int verInt) {}
    default void txAllowed(boolean allowed) {}
    default void txStarted() {}
    default void txEnded() {}
    default void sMeterUpdate(int value0to9) {}
    default void scannedToMemory(int memoryId) {}
    default void modeChanged(int mode) {}
    default void status(String message) {}
}
