package com.hcl.jrsmeta.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A reference discovered while parsing: {@code fromUri} (consumer) reads {@code toUri}. */
public record Ref(String fromUri, String toUri, RefKind kind, Map<String, Object> attributes) {
    public enum RefKind { DATASOURCE, QUERY_RESOURCE, SUBREPORT, INPUT_CONTROL, DASHLET, OLAP_CONNECTION, OLAP_SCHEMA, SUB_DATASOURCE }

    public Ref {
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
    public Ref(String fromUri, String toUri, RefKind kind) { this(fromUri, toUri, kind, Map.of()); }
}
