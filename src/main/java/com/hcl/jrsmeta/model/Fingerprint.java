package com.hcl.jrsmeta.model;

import java.util.Locale;

/** Connection identity used for stitching (spec §7.4). {@code raw} is the sanitized URL or opaque value. */
public record Fingerprint(String dialect, String host, Integer port, String database, String schema, String raw) {

    public static Fingerprint opaque(String scheme, String value) {
        return new Fingerprint(scheme, null, null, null, null, value == null ? "" : value);
    }

    public String key() {
        String d = lower(dialect == null ? "unknown" : dialect);
        if (host == null) return d + "://" + lower(raw);
        return d + "://" + lower(host) + (port == null ? "" : ":" + port) + "/" + lower(database);
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
