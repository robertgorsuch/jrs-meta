package com.hcl.jrsmeta.parse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.io.IOException;
import java.util.*;

/** Spec §9.6: cube/dimension/level/measure -> physical table.column references. */
public final class MondrianSchemaParser {

    public MondrianSchema parse(byte[] xml) throws IOException {
        Document doc = Xml.parse(xml);
        Element root = doc.getDocumentElement();
        Map<String, MondrianSchema.Dimension> shared = new LinkedHashMap<>();
        for (Element d : Xml.children(root, "Dimension")) shared.put(Xml.attr(d, "name"), dimension(d));

        List<MondrianSchema.Cube> cubes = new ArrayList<>();
        for (Element c : Xml.children(root, "Cube")) {
            String fact = Xml.children(c, "Table").stream().findFirst().map(t -> Xml.attr(t, "name")).orElse(null);
            List<MondrianSchema.Measure> measures = new ArrayList<>();
            for (Element m : Xml.children(c, "Measure")) measures.add(new MondrianSchema.Measure(Xml.attr(m, "name"), Xml.attr(m, "column"), Xml.attr(m, "aggregator")));
            List<MondrianSchema.Dimension> dims = new ArrayList<>();
            for (Element child : Xml.children(c, "DimensionUsage")) {
                MondrianSchema.Dimension src = shared.get(Xml.attr(child, "source"));
                if (src != null) dims.add(new MondrianSchema.Dimension(Xml.attr(child, "name") != null ? Xml.attr(child, "name") : src.name(), src.levels()));
            }
            for (Element child : Xml.children(c, "Dimension")) dims.add(dimension(child));
            cubes.add(new MondrianSchema.Cube(Xml.attr(c, "name"), fact, measures, dims));
        }
        return new MondrianSchema(Xml.attr(root, "name"), cubes);
    }

    private static MondrianSchema.Dimension dimension(Element d) {
        List<MondrianSchema.Level> levels = new ArrayList<>();
        for (Element h : Xml.children(d, "Hierarchy")) {
            List<Element> tables = Xml.descendants(h, "Table");
            String single = tables.size() == 1 ? Xml.attr(tables.get(0), "name") : null;   // levels omit table= when the hierarchy has one table
            for (Element l : Xml.children(h, "Level"))
                levels.add(new MondrianSchema.Level(Xml.attr(l, "name"), Xml.attr(l, "table") != null ? Xml.attr(l, "table") : single, Xml.attr(l, "column")));
        }
        return new MondrianSchema.Dimension(Xml.attr(d, "name"), levels);
    }
}
