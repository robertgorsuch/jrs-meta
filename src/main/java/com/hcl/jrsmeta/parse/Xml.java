package com.hcl.jrsmeta.parse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Namespace-agnostic DOM helpers over a hardened parser (no external DTD/entity fetches). */
public final class Xml {
    private Xml() {}

    public static Document parse(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Legacy JRXML carries a <!DOCTYPE>; allow it but never resolve anything external.
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            if (hasEntityReference(d.getDocumentElement())) throw new IOException("XML contains entity references; refusing to parse");
            return d;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("XML parse failed: " + e.getMessage(), e);
        }
    }

    private static boolean hasEntityReference(Node n) {
        if (n == null) return false;
        if (n.getNodeType() == Node.ENTITY_REFERENCE_NODE) return true;
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) if (hasEntityReference(c)) return true;
        return false;
    }

    public static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling())
            if (c instanceof Element e && localName.equals(e.getLocalName() == null ? e.getTagName() : e.getLocalName())) out.add(e);
        return out;
    }

    public static List<Element> descendants(Node root, String localName) {
        Element start = root instanceof Document d ? d.getDocumentElement() : (Element) root;
        NodeList nl = start.getElementsByTagNameNS("*", localName);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        if (out.isEmpty()) {                                   // no-namespace documents (legacy DOCTYPE jrxml)
            NodeList plain = start.getElementsByTagName(localName);
            for (int i = 0; i < plain.getLength(); i++) out.add((Element) plain.item(i));
        }
        return out;
    }

    public static String attr(Element e, String name) { return e.hasAttribute(name) ? e.getAttribute(name) : null; }

    /** Text of the first direct child matching any of the names, trimmed; null if none. */
    public static String childText(Element parent, String... localNames) {
        for (String n : localNames) for (Element c : children(parent, n)) return c.getTextContent().trim();
        return null;
    }
}
