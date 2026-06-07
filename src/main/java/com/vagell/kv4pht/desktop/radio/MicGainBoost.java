/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)
*/
package com.vagell.kv4pht.desktop.radio;

/** Software microphone gain applied to outgoing (TX) audio. Ported from the Android app. */
public enum MicGainBoost {
  NONE,
  LOW,
  MED,
  HIGH;

  public static MicGainBoost parse(String str) {
    if (str == null) return NONE;
   return switch (str) {
    case "High" -> HIGH;
    case "Med" -> MED;
    case "Low" -> LOW;
    default -> NONE;
   };
  }

  public static float toFloat(MicGainBoost g) {
   return switch (g) {
    case LOW -> 1.5f;
    case MED -> 2.0f;
    case HIGH -> 2.5f;
    default -> 1.0f;
   };
  }
}
