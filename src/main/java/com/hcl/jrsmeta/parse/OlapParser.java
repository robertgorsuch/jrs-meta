package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;

public final class OlapParser implements DescriptorParser {
    private final MondrianSchemaParser schemaParser = new MondrianSchemaParser();

    @Override public Set<String> resourceTypes() { return Set.of("olapMondrianSchema", "secureMondrianConnection", "mondrianConnection", "olapUnit"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri(); String type = lookup.resourceType();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", type);
        List<Ref> refs = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        switch (type) {
            case "olapMondrianSchema" -> {
                List<Asset> assets = new ArrayList<>();
                MondrianSchema ms = null;
                try { ms = schemaParser.parse(ctx.repo().file(uri, "application/xml")); }
                catch (IOException e) { warnings.add("mondrian schema " + uri + " unavailable or unparseable: " + e.getMessage()); }
                if (ms != null) { attrs.put("schemaName", ms.name()); attrs.put("cubeCount", ms.cubes().size()); }
                Asset schema = Asset.of(ctx.serverId(), lookup, AssetType.OLAP_SCHEMA, attrs);
                assets.add(schema);
                if (ms != null) for (MondrianSchema.Cube c : ms.cubes()) {
                    Map<String, Object> ca = Attrs.of("factTable", c.factTable(),
                            "measures", c.measures().stream().map(m -> Attrs.of("name", m.name(), "column", m.column(), "aggregator", m.aggregator())).toList(),
                            "dimensions", c.dimensions().stream().map(d -> Attrs.of("name", d.name(),
                                    "levels", d.levels().stream().map(l -> Attrs.of("name", l.name(), "table", l.table(), "column", l.column())).toList())).toList());
                    assets.add(Asset.child(schema, "cube", c.name(), AssetType.OLAP_CUBE, c.name(), ca));
                }
                return new ParseResult(assets, refs, warnings);
            }
            case "secureMondrianConnection", "mondrianConnection" -> {
                JsonNode d = ctx.repo().descriptor(uri, type);
                Descriptors.refUri(d.path("dataSource")).ifPresent(u -> { attrs.put("dataSourceUri", u); refs.add(new Ref(uri, u, Ref.RefKind.DATASOURCE)); });
                Descriptors.refUri(d.path("schema")).ifPresent(u -> { attrs.put("schemaUri", u); refs.add(new Ref(uri, u, Ref.RefKind.OLAP_SCHEMA)); });
                return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, AssetType.OLAP_CONNECTION, attrs)), refs, warnings);
            }
            case "olapUnit" -> {
                JsonNode d = ctx.repo().descriptor(uri, type);
                attrs.put("queryLanguage", "mdx"); attrs.put("mdxQuery", Descriptors.text(d, "mdxQuery"));
                Descriptors.refUri(d.path("olapConnection")).ifPresent(u -> { attrs.put("olapConnectionUri", u); refs.add(new Ref(uri, u, Ref.RefKind.OLAP_CONNECTION)); });
                return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, AssetType.OLAP_VIEW, attrs)), refs, warnings);
            }
            default -> throw new IllegalStateException("unsupported OLAP type " + type);
        }
    }
}
