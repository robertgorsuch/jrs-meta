package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Spec §9.5: every datasource flavour -> BI_DATASOURCE with a stitching fingerprint; secrets never copied. */
public final class DataSourceParser implements DescriptorParser {
    static final Set<String> TYPES = Set.of("jdbcDataSource", "jndiJdbcDataSource", "virtualDataSource", "awsDataSource",
            "beanDataSource", "customDataSource", "azureSqlDataSource");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)pass|secret|token|key|credential");

    @Override public Set<String> resourceTypes() { return TYPES; }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri(); String type = lookup.resourceType();
        JsonNode d = ctx.repo().descriptor(uri, type);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", type); attrs.put("dsType", type);
        List<Ref> refs = new ArrayList<>();
        Fingerprint fp = null;

        switch (type) {
            case "jdbcDataSource", "azureSqlDataSource" -> {
                String url = Descriptors.text(d, "connectionUrl"); String drv = Descriptors.text(d, "driverClass");
                attrs.put("driverClass", drv); attrs.put("connectionUrl", JdbcUrlParser.sanitize(url));
                attrs.put("dbUser", Descriptors.text(d, "username")); attrs.put("timezone", Descriptors.text(d, "timezone"));
                fp = JdbcUrlParser.parse(url, drv);
            }
            case "jndiJdbcDataSource" -> {
                String j = Descriptors.text(d, "jndiName");
                attrs.put("jndiName", j); attrs.put("timezone", Descriptors.text(d, "timezone"));
                fp = Fingerprint.opaque("jndi", j);
            }
            case "virtualDataSource" -> {
                List<String> subs = new ArrayList<>();
                for (JsonNode s : d.path("subDataSources")) {
                    String u = Descriptors.text(s, "uri");
                    if (u != null) { subs.add(u); refs.add(new Ref(uri, u, Ref.RefKind.SUB_DATASOURCE)); }
                }
                attrs.put("subDataSourceUris", subs);          // stitched through its sub-datasources, no own fingerprint
            }
            case "awsDataSource" -> {
                String url = Descriptors.text(d, "connectionUrl"); String drv = Descriptors.text(d, "driverClass");
                attrs.put("driverClass", drv); attrs.put("connectionUrl", JdbcUrlParser.sanitize(url)); attrs.put("dbUser", Descriptors.text(d, "username"));
                for (String k : List.of("region", "dbName", "dbInstanceIdentifier", "dbService", "roleArn")) attrs.put(k, Descriptors.text(d, k));
                fp = url != null && !url.isBlank() ? JdbcUrlParser.parse(url, drv)
                        : Fingerprint.opaque("aws", nz(Descriptors.text(d, "dbService"), "rds") + "/" + nz(Descriptors.text(d, "region"), "?") + "/" + nz(Descriptors.text(d, "dbInstanceIdentifier"), "?"));
            }
            case "beanDataSource" -> {
                attrs.put("beanName", Descriptors.text(d, "beanName")); attrs.put("beanMethod", Descriptors.text(d, "beanMethod"));
                fp = Fingerprint.opaque("bean", Descriptors.text(d, "beanName"));
            }
            case "customDataSource" -> {
                String svc = Descriptors.text(d, "serviceClass");
                attrs.put("serviceClass", svc);
                Map<String, String> props = new LinkedHashMap<>();
                for (JsonNode p : d.path("properties")) {
                    String k = Descriptors.text(p, "key");
                    if (k != null) props.put(k, SECRET_KEY.matcher(k).find() ? "***" : Descriptors.text(p, "value"));
                }
                attrs.put("properties", props);
                fp = Fingerprint.opaque("custom", svc);
            }
            default -> throw new IllegalStateException("unsupported datasource type " + type);
        }
        if (fp != null) { attrs.put("fingerprint", fp.key()); attrs.put("fingerprintParts", fp); }
        return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, AssetType.BI_DATASOURCE, attrs)), refs, List.of());
    }

    private static String nz(String v, String dflt) { return v == null || v.isBlank() ? dflt : v; }
}
