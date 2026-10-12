package com.hcl.jrsmeta.parse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import java.io.IOException;
import java.util.*;

public final class DomainSchemaParser {

    public DomainSchema parse(byte[] xml) throws IOException {
        Document doc = Xml.parse(xml);
        Map<String, DomainSchema.Table> tables = new LinkedHashMap<>();
        for (Element t : Xml.descendants(doc, "jdbcTable")) {
            Map<String, String> aliases = new LinkedHashMap<>();
            for (Element ji : Xml.children(t, "joinInfo")) aliases.put(Xml.attr(ji, "alias"), Xml.attr(ji, "referenceId"));
            for (Element trl : Xml.children(t, "tableRefList"))
                for (Element tr : Xml.children(trl, "tableRef")) aliases.put(Xml.attr(tr, "tableAlias"), Xml.attr(tr, "tableId"));
            boolean joinTree = !aliases.isEmpty() || !Xml.children(t, "joinList").isEmpty();
            String tableName = Xml.attr(t, "datasourceTableName") != null ? Xml.attr(t, "datasourceTableName") : Xml.attr(t, "tableName");
            tables.put(Xml.attr(t, "id"), new DomainSchema.Table(Xml.attr(t, "id"), Xml.attr(t, "datasourceId"), Xml.attr(t, "schemaAlias"),
                    tableName, fields(t), aliases, joinTree));
        }
        Map<String, DomainSchema.Query> queries = new LinkedHashMap<>();
        for (Element q : Xml.descendants(doc, "jdbcQuery"))
            queries.put(Xml.attr(q, "id"), new DomainSchema.Query(Xml.attr(q, "id"), Xml.attr(q, "datasourceId"), Xml.childText(q, "query"), fields(q)));

        List<DomainSchema.Item> items = new ArrayList<>();
        for (Element i : Xml.descendants(doc, "item"))
            items.add(new DomainSchema.Item(Xml.attr(i, "id"), Xml.attr(i, "label"), Xml.attr(i, "description"), Xml.attr(i, "resourceId"), groupPath(i)));
        return new DomainSchema(tables, queries, items);
    }

    private static Map<String, DomainSchema.Field> fields(Element owner) {
        Map<String, DomainSchema.Field> out = new LinkedHashMap<>();
        for (Element fl : Xml.children(owner, "fieldList"))
            for (Element f : Xml.children(fl, "field"))
                out.put(Xml.attr(f, "id"), new DomainSchema.Field(Xml.attr(f, "id"), Xml.attr(f, "fieldDBName"), Xml.attr(f, "type")));
        return out;
    }

    /** Ancestor itemGroup ids, outermost first, joined with '/'; empty for island-level items. */
    private static String groupPath(Element item) {
        Deque<String> path = new ArrayDeque<>();
        for (Node n = item.getParentNode(); n instanceof Element e; n = n.getParentNode())
            if ("itemGroup".equals(e.getLocalName() == null ? e.getTagName() : e.getLocalName()) && Xml.attr(e, "id") != null) path.addFirst(Xml.attr(e, "id"));
        return String.join("/", path);
    }
}
