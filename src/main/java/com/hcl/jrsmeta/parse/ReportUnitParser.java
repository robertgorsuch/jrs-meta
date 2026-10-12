package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spec §9.1: report descriptor + JRXML -> BI_REPORT, fields, parameters, and datasource/subreport/input-control refs. */
public final class ReportUnitParser implements DescriptorParser {
    private static final Pattern REPO_URI = Pattern.compile("repo:(/[^\"'\\s]+)");
    private static final Pattern FILE_NAME = Pattern.compile("\"(?:[^\"]*/)?([^\"/]+\\.(?:jrxml|jasper))\"");
    private final JrxmlParser jrxmlParser = new JrxmlParser();

    @Override public Set<String> resourceTypes() { return Set.of("reportUnit"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri();
        JsonNode d = ctx.repo().descriptor(uri, "reportUnit");
        List<Ref> refs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", "reportUnit");

        Descriptors.refUri(d.path("dataSource")).ifPresent(ds -> { attrs.put("dataSourceUri", ds); refs.add(new Ref(uri, ds, Ref.RefKind.DATASOURCE)); });
        Descriptors.refUri(d.path("query")).ifPresent(q -> { attrs.put("queryResourceUri", q); refs.add(new Ref(uri, q, Ref.RefKind.QUERY_RESOURCE)); });

        List<String> icUris = new ArrayList<>();
        for (JsonNode ic : d.path("inputControls")) Descriptors.refUri(ic).ifPresent(u -> { icUris.add(u); refs.add(new Ref(uri, u, Ref.RefKind.INPUT_CONTROL)); });
        attrs.put("inputControlUris", icUris);
        attrs.put("alwaysPromptControls", d.path("alwaysPromptControls").asBoolean(false));

        Map<String, String> resourceFiles = new LinkedHashMap<>();   // name -> uri of the report's companion files
        for (JsonNode r : d.path("resources")) {
            String name = Descriptors.text(r, "name");
            Descriptors.refUri(r.path("file")).ifPresent(u -> { if (name != null) resourceFiles.put(name, u); });
        }

        byte[] jrxml = loadJrxml(ctx, uri, d, attrs, warnings);
        JrxmlModel model = null;
        if (jrxml != null) {
            try { model = jrxmlParser.parse(jrxml); }
            catch (IOException e) { warnings.add("jrxml of " + uri + " could not be parsed: " + e.getMessage()); }
        }

        List<Asset> assets = new ArrayList<>();
        if (model != null) {
            attrs.put("queryLanguage", model.queryLanguage());
            if (model.queryText() != null) attrs.put("queryText", ctx.config().redactSqlLiterals() ? SqlRedactor.redact(model.queryText()) : model.queryText());
            List<Map<String, Object>> datasets = new ArrayList<>();
            for (JrxmlModel.Dataset ds : model.subDatasets())
                datasets.add(Attrs.of("name", ds.name(), "queryLanguage", ds.queryLanguage(),
                        "queryText", ctx.config().redactSqlLiterals() ? SqlRedactor.redact(ds.queryText()) : ds.queryText(),
                        "fields", ds.fields().stream().map(JrxmlModel.Field::name).toList()));
            attrs.put("subDatasets", datasets);
            attrs.put("variables", model.variables().stream().map(v -> Attrs.of("name", v.name(), "javaClass", v.javaClass(), "calculation", v.calculation(), "expression", v.expression())).toList());

            List<String> subUris = new ArrayList<>(); List<String> unresolved = new ArrayList<>();
            for (String expr : model.subreportExpressions()) {
                Optional<String> sub = resolveSubreport(expr, resourceFiles);
                if (sub.isPresent()) { subUris.add(sub.get()); refs.add(new Ref(uri, sub.get(), Ref.RefKind.SUBREPORT)); }
                else { unresolved.add(expr); warnings.add("unresolved subreport expression in " + uri + ": " + expr); }
            }
            attrs.put("subreportUris", subUris);
            attrs.put("unresolvedSubreports", unresolved);
        }

        Asset report = Asset.of(ctx.serverId(), lookup, AssetType.BI_REPORT, attrs);
        assets.add(report);
        if (model != null) {
            for (JrxmlModel.Field f : model.fields())
                assets.add(Asset.child(report, "field", f.name(), AssetType.REPORT_FIELD, f.name(), Attrs.of("javaClass", f.javaClass())));
            for (JrxmlModel.Parameter p : model.parameters())
                assets.add(Asset.child(report, "param", p.name(), AssetType.REPORT_PARAMETER, p.name(), Attrs.of("javaClass", p.javaClass(), "defaultValueExpression", p.defaultValueExpression())));
        }
        return new ParseResult(assets, refs, warnings);
    }

    private static byte[] loadJrxml(ParseContext ctx, String uri, JsonNode d, Map<String, Object> attrs, List<String> warnings) {
        JsonNode j = d.path("jrxml");
        String inline = j.path("jrxmlFile").path("content").asText(null);
        if (inline != null && !inline.isEmpty()) { attrs.put("jrxmlSource", "inline"); return Base64.getMimeDecoder().decode(inline); }
        Optional<String> ref = Descriptors.refUri(j);
        if (ref.isPresent()) {
            attrs.put("jrxmlSource", "reference"); attrs.put("jrxmlUri", ref.get());
            try { return ctx.repo().file(ref.get(), "application/xml"); }
            catch (IOException e) { warnings.add("jrxml fetch failed for " + ref.get() + ": " + e.getMessage()); return null; }
        }
        attrs.put("jrxmlSource", "missing");
        warnings.add("report " + uri + " has no jrxml in its descriptor");
        return null;
    }

    /** "repo:/abs/uri" -> that URI; "...child.jrxml" -> the matching companion resource; otherwise unresolved. */
    static Optional<String> resolveSubreport(String expr, Map<String, String> resourceFiles) {
        Matcher m = REPO_URI.matcher(expr);
        if (m.find()) return Optional.of(m.group(1));
        Matcher f = FILE_NAME.matcher(expr);
        if (f.find()) {
            String name = f.group(1);
            String base = name.replaceAll("\\.(jrxml|jasper)$", "");
            for (Map.Entry<String, String> e : resourceFiles.entrySet()) {
                String k = e.getKey();
                if (k.equals(name) || k.equals(base) || k.equals(base + ".jrxml") || k.equals(base + ".jasper")) return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }
}
