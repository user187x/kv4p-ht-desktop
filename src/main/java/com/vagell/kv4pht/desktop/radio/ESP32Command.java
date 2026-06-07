/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.desktop.radio;

/**
 * Commands understood by the kv4p HT ESP32 firmware. The byte values here are part of the wire
 * protocol and must match the microcontroller code exactly. Ported verbatim from the Android app's
 * RadioAudioService.ESP32Command.
 */
public enum ESP32Command {
  PTT_DOWN((byte) 1),
  PTT_UP((byte) 2),
  TUNE_TO((byte) 3), // params: txFreq + rxFreq + tone(2) + squelch(1) + bandwidth(W/N)
  FILTERS((byte) 4), // params: emphasis + highpass + lowpass (each '0'/'1')
  STOP((byte) 5),
  GET_FIRMWARE_VER((byte) 6);

  private final byte commandByte;

  ESP32Command(byte commandByte) {
    this.commandByte = commandByte;
  }

  public byte getByte() {
    return commandByte;
  }
}
