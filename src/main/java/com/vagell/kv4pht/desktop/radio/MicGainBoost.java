/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)
*/
package com.vagell.kv4pht.desktop.radio;

/** Software microphone gain applied to outgoing (TX) audio. Ported from the Android app. */
public enum MicGainBoost {
    NONE, LOW, MED, HIGH;

    public static MicGainBoost parse(String str) {
        if (str == null) return NONE;
        switch (str) {
            case "High": return HIGH;
            case "Med":  return MED;
            case "Low":  return LOW;
            default:     return NONE;
        }
    }

    public static float toFloat(MicGainBoost g) {
        switch (g) {
            case LOW:  return 1.5f;
            case MED:  return 2.0f;
            case HIGH: return 2.5f;
            default:   return 1.0f;
        }
    }
}
