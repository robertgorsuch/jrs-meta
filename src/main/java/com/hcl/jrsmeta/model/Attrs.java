package com.hcl.jrsmeta.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Null-tolerant ordered attribute map builder (Map.of rejects null values). */
public final class Attrs {
    private Attrs() {}
    public static Map<String, Object> of(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("key/value pairs required");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
