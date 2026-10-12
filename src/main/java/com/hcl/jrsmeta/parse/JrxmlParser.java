package com.hcl.jrsmeta.parse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Extracts query, fields, parameters, variables, sub-datasets and subreport expressions from JR6/JR7 JRXML. */
public final class JrxmlParser {

    public JrxmlModel parse(byte[] xml) throws IOException {
        Document doc = Xml.parse(xml);
        Element root = doc.getDocumentElement();
        String[] lang = new String[1]; String[] text = new String[1];
        query(root, lang, text);

        List<JrxmlModel.Parameter> params = new ArrayList<>();
        for (Element p : Xml.children(root, "parameter"))
            params.add(new JrxmlModel.Parameter(Xml.attr(p, "name"), Xml.attr(p, "class"), Xml.childText(p, "defaultValueExpression", "defaultValue", "expression")));

        List<JrxmlModel.Variable> vars = new ArrayList<>();
        for (Element v : Xml.children(root, "variable"))
            vars.add(new JrxmlModel.Variable(Xml.attr(v, "name"), Xml.attr(v, "class"), Xml.attr(v, "calculation"), Xml.childText(v, "variableExpression", "expression")));

        List<JrxmlModel.Dataset> datasets = new ArrayList<>();
        for (Element ds : Xml.children(root, "subDataset")) {
            String[] l = new String[1]; String[] t = new String[1];
            query(ds, l, t);
            datasets.add(new JrxmlModel.Dataset(Xml.attr(ds, "name"), l[0], t[0], fields(ds)));
        }

        List<String> subreports = new ArrayList<>();
        for (Element s : Xml.descendants(root, "subreport")) addSubreportExpr(s, subreports);           // JR6 <subreport>
        for (Element e : Xml.descendants(root, "element"))                                             // JR7 <element kind="subreport">
            if ("subreport".equals(Xml.attr(e, "kind"))) addSubreportExpr(e, subreports);

        return new JrxmlModel(lang[0], text[0], fields(root), params, vars, datasets, subreports);
    }

    private static void addSubreportExpr(Element s, List<String> out) {
        String expr = Xml.childText(s, "subreportExpression", "expression");
        if (expr != null && !expr.isBlank()) out.add(expr);
    }

    private static List<JrxmlModel.Field> fields(Element scope) {
        List<JrxmlModel.Field> out = new ArrayList<>();
        for (Element f : Xml.children(scope, "field")) out.add(new JrxmlModel.Field(Xml.attr(f, "name"), Xml.attr(f, "class")));
        return out;
    }

    private static void query(Element scope, String[] lang, String[] text) {
        for (String name : new String[]{"query", "queryString"}) {
            for (Element q : Xml.children(scope, name)) {
                String l = Xml.attr(q, "language");
                lang[0] = (l == null || l.isBlank() ? "sql" : l).toLowerCase(Locale.ROOT);
                text[0] = q.getTextContent().trim();
                return;
            }
        }
    }
}
