package com.hcl.jrsmeta.parse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Parsed Domain schema.xml: physical resources plus presentation items (spec §9.2). */
public record DomainSchema(Map<String, Table> tables, Map<String, Query> queries, List<Item> items) {
    public record Field(String id, String dbName, String type) {}
    public record Table(String id, String datasourceId, String schemaAlias, String tableName, Map<String, Field> fields,
                        Map<String, String> aliasToTableId, boolean joinTree) {}
    public record Query(String id, String datasourceId, String sql, Map<String, Field> fields) {}
    public record Item(String id, String label, String description, String resourceId, String groupPath) {}
    public record PhysicalColumn(String datasourceId, String schema, String table, String column, boolean exact) {}

    /** resourceId is "table.field" for a lone table, "joinTree.alias.field" for a join tree, "query.field" for a derived table. */
    public Optional<PhysicalColumn> resolve(String resourceId) {
        if (resourceId == null) return Optional.empty();
        int dot = resourceId.indexOf('.');
        if (dot < 0) return Optional.empty();
        String resId = resourceId.substring(0, dot);
        String rest = resourceId.substring(dot + 1);
        Table t = tables.get(resId);
        if (t == null) {
            Query q = queries.get(resId);
            return q == null ? Optional.empty() : Optional.of(new PhysicalColumn(q.datasourceId(), null, "(query:" + resId + ")", rest, false));
        }
        Field f = t.fields().get(rest);
        String column = f != null && f.dbName() != null ? f.dbName() : rest.substring(rest.lastIndexOf('.') + 1);
        String tableName = t.tableName(); String schema = t.schemaAlias();
        if (t.joinTree() && rest.contains(".")) {
            String alias = rest.substring(0, rest.indexOf('.'));
            Table base = tables.get(t.aliasToTableId().getOrDefault(alias, alias));
            if (base != null) { tableName = base.tableName(); schema = base.schemaAlias(); }
        }
        return Optional.of(new PhysicalColumn(t.datasourceId(), schema, tableName, column, true));
    }
}
