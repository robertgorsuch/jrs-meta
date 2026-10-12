package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;

/** Folders, files, input controls, shared queries and any type without a dedicated parser. */
public final class GenericResourceParser implements DescriptorParser {
    @Override public Set<String> resourceTypes() { return Set.of("folder", "file", "inputControl", "query"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri(); String type = lookup.resourceType() == null ? "unknown" : lookup.resourceType();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", type);
        List<Ref> refs = new ArrayList<>(); List<String> warnings = new ArrayList<>();

        if ("folder".equals(type)) return ParseResult.of(Asset.of(ctx.serverId(), lookup, AssetType.FOLDER, attrs));

        JsonNode d;
        try { d = ctx.repo().descriptor(uri, type); }
        catch (IOException e) { warnings.add("descriptor of " + uri + " (" + type + ") unavailable: " + e.getMessage()); d = null; }

        AssetType at = switch (type) {
            case "file" -> AssetType.FILE;
            case "inputControl" -> AssetType.INPUT_CONTROL;
            case "query" -> AssetType.QUERY;
            default -> AssetType.OTHER;
        };
        if (d != null) {
            switch (at) {
                case FILE -> attrs.put("fileType", Descriptors.text(d, "type"));
                case QUERY -> {
                    attrs.put("queryLanguage", Descriptors.text(d, "language"));
                    String v = Descriptors.text(d, "value");
                    attrs.put("queryText", ctx.config().redactSqlLiterals() ? SqlRedactor.redact(v) : v);
                    Descriptors.refUri(d.path("dataSource")).ifPresent(u -> { attrs.put("dataSourceUri", u); refs.add(new Ref(uri, u, Ref.RefKind.DATASOURCE)); });
                }
                case INPUT_CONTROL -> {
                    attrs.put("controlType", d.hasNonNull("type") ? d.get("type").asInt() : null);
                    attrs.put("mandatory", d.path("mandatory").asBoolean(false));
                    JsonNode q = d.path("query");
                    Descriptors.refUri(q).ifPresent(u -> attrs.put("queryUri", u));            // shared query resource
                    JsonNode embedded = q.path("query");                                         // embedded query
                    if (!embedded.isMissingNode()) {
                        String v = Descriptors.text(embedded, "value");
                        attrs.put("queryText", ctx.config().redactSqlLiterals() ? SqlRedactor.redact(v) : v);
                        Descriptors.refUri(embedded.path("dataSource")).ifPresent(u -> { attrs.put("dataSourceUri", u); refs.add(new Ref(uri, u, Ref.RefKind.DATASOURCE)); });
                    }
                    Descriptors.refUri(d.path("dataType")).ifPresent(u -> attrs.put("dataTypeUri", u));
                    Descriptors.refUri(d.path("listOfValues")).ifPresent(u -> attrs.put("listOfValuesUri", u));
                }
                default -> { }
            }
        }
        return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, at, attrs)), refs, warnings);
    }
}
