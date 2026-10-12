package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;

/**
 * Spec §9.3. Phase 1 needs only the backing Topic/Domain/datasource reference, which the live descriptor carries;
 * the opaque query/component state (export-archive path) is left for Phase 2 column lineage.
 */
public final class AdhocViewParser implements DescriptorParser {
    @Override public Set<String> resourceTypes() { return Set.of("adhocDataView"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri();
        JsonNode d = ctx.repo().descriptor(uri, "adhocDataView");
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", "adhocDataView");
        attrs.put("queryPresent", d.has("query") && !d.get("query").isNull());
        List<Ref> refs = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        Optional<String> ds = Descriptors.refUri(d.path("dataSource"));
        if (ds.isPresent()) { attrs.put("dataSourceUri", ds.get()); refs.add(new Ref(uri, ds.get(), Ref.RefKind.DATASOURCE)); }
        else warnings.add("ad hoc view " + uri + " exposes no data source reference in its live descriptor");
        return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, AssetType.BI_VIEW, attrs)), refs, warnings);
    }
}
