package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/** Helpers for JRS JSON descriptors, whose references look like {"<something>Reference":{"uri":"/x"}}. */
public final class Descriptors {
    private Descriptors() {}

    public static Optional<String> refUri(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) return Optional.empty();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().endsWith("Reference") && e.getValue().hasNonNull("uri")) return Optional.of(e.getValue().get("uri").asText());
        }
        return Optional.empty();
    }

    public static String text(JsonNode n, String field) { return n != null && n.hasNonNull(field) ? n.get(field).asText() : null; }
}
