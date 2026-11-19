package com.gameneko.aigame.engine;

/** Version carried by persisted and exchanged protocol envelopes. */
public record ProtocolVersion(int major, int minor) {
    public ProtocolVersion {
        if (major < 0 || minor < 0) throw new IllegalArgumentException("Protocol version must not be negative");
    }

    public static ProtocolVersion current() { return new ProtocolVersion(1, 0); }
}
