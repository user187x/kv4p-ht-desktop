/*
kv4p HT desktop port - GPLv3 (see http://kv4p.com)
*/
package com.vagell.kv4pht.desktop.data;

/**
 * A stored channel/repeater memory. Plain POJO replacing the Room @Entity from
 * the Android app. Field names and semantics are preserved.
 */
public class ChannelMemory {
    public static final int OFFSET_NONE = 0;
    public static final int OFFSET_DOWN = 1;
    public static final int OFFSET_UP = 2;

    public int memoryId;       // primary key (auto-increment)
    public String name;
    public String frequency;   // "xxx.xxxx"
    public int offset;         // 0=none, 1=down, 2=up
    public String tone;        // CTCSS tone as string, "None"/"0" = none
    public String group;       // optional grouping label

    private boolean highlighted = false;

    public ChannelMemory() {}

    public ChannelMemory(String name, String frequency, int offset, String tone, String group) {
        this.name = name;
        this.frequency = frequency;
        this.offset = offset;
        this.tone = tone;
        this.group = group;
    }

    public void setHighlighted(boolean highlighted) { this.highlighted = highlighted; }
    public boolean isHighlighted() { return highlighted; }

    public String offsetLabel() {
        switch (offset) {
            case OFFSET_UP:   return "+";
            case OFFSET_DOWN: return "-";
            default:          return "";
        }
    }

    @Override
    public String toString() {
        String n = (name == null || name.isEmpty()) ? "(unnamed)" : name;
        return n + "  " + frequency + offsetLabel();
    }
}
