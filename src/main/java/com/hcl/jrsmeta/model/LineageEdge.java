package com.hcl.jrsmeta.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Data flows from sourceId to targetId. */
public record LineageEdge(String sourceId, String targetId, EdgeType type, Confidence confidence, Map<String, Object> attributes) {
    public LineageEdge {
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
