package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;

/** Spec §9.2: Domain -> SEMANTIC_MODEL + LOGICAL_FIELD children carrying their physical column mapping. */
public final class DomainParser implements DescriptorParser {
    private final DomainSchemaParser schemaParser = new DomainSchemaParser();

    @Override public Set<String> resourceTypes() { return Set.of("semanticLayerDataSource"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri();
        JsonNode d = ctx.repo().descriptor(uri, "semanticLayerDataSource");
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", "semanticLayerDataSource");
        List<Ref> refs = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        Descriptors.refUri(d.path("dataSource")).ifPresent(ds -> { attrs.put("dataSourceUri", ds); refs.add(new Ref(uri, ds, Ref.RefKind.DATASOURCE)); });

        byte[] xml = null;
        JsonNode schema = d.path("schema");
        String inline = schema.path("schemaFile").path("content").asText(null);
        Optional<String> schemaRef = Descriptors.refUri(schema);
        if (inline != null && !inline.isEmpty()) { attrs.put("schemaSource", "inline"); xml = Base64.getMimeDecoder().decode(inline); }
        else if (schemaRef.isPresent()) {
            attrs.put("schemaSource", "reference"); attrs.put("schemaUri", schemaRef.get());
            try { xml = ctx.repo().file(schemaRef.get(), "application/xml"); }
            catch (IOException e) { warnings.add("schema.xml fetch failed for " + schemaRef.get() + ": " + e.getMessage()); }
        } else { attrs.put("schemaSource", "missing"); warnings.add("domain " + uri + " has no schema in its descriptor"); }

        DomainSchema ds = null;
        if (xml != null) {
            try { ds = schemaParser.parse(xml); }
            catch (IOException e) { warnings.add("schema.xml of " + uri + " could not be parsed: " + e.getMessage()); }
        }

        List<Asset> assets = new ArrayList<>();
        List<Asset> children = new ArrayList<>();
        if (ds != null) {
            attrs.put("tables", ds.tables().values().stream().map(t -> Attrs.of("id", t.id(), "schema", t.schemaAlias(), "table", t.tableName(), "datasourceAlias", t.datasourceId(), "joinTree", t.joinTree())).toList());
            attrs.put("queries", ds.queries().values().stream().map(q -> Attrs.of("id", q.id(), "sql", ctx.config().redactSqlLiterals() ? SqlRedactor.redact(q.sql()) : q.sql())).toList());
            attrs.put("itemCount", ds.items().size());
        }
        Asset model = Asset.of(ctx.serverId(), lookup, AssetType.SEMANTIC_MODEL, attrs);
        assets.add(model);
        if (ds != null) {
            List<String> unresolved = new ArrayList<>();
            for (DomainSchema.Item it : ds.items()) {
                Optional<DomainSchema.PhysicalColumn> pc = ds.resolve(it.resourceId());
                Map<String, Object> a = Attrs.of("resourceId", it.resourceId(), "groupPath", it.groupPath(), "description", it.description());
                if (pc.isPresent()) {
                    a.put("datasourceAlias", pc.get().datasourceId()); a.put("physicalSchema", pc.get().schema());
                    a.put("physicalTable", pc.get().table()); a.put("physicalColumn", pc.get().column());
                    a.put("mappingConfidence", pc.get().exact() ? Confidence.EXACT.name() : Confidence.DERIVED.name());
                } else {
                    a.put("mappingConfidence", Confidence.INFERRED.name());
                    unresolved.add(it.resourceId());
                    warnings.add("domain " + uri + " item " + it.id() + " has unresolvable resourceId " + it.resourceId());
                }
                children.add(Asset.child(model, "item", it.id(), AssetType.LOGICAL_FIELD, it.label() != null ? it.label() : it.id(), a));
            }
            if (!unresolved.isEmpty()) assets.set(0, model.withAttributes(Map.of("unresolvedItems", unresolved)));
        }
        assets.addAll(children);
        return new ParseResult(assets, refs, warnings);
    }
}
