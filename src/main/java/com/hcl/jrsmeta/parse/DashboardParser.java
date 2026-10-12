package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcl.jrsmeta.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec §9.4: dashboard export archive -> BI_DASHBOARD + tiles + DASHLET refs; falls back to the bare descriptor.
 * Designer-made chart tiles reference "temp" ad hoc views that exist only inside the dashboard's export; those are
 * cataloged as embedded BI_VIEW children and their tiles are linked to the Domain/Topic the view queries.
 */
public final class DashboardParser implements DescriptorParser {
    private static final Pattern DIV = Pattern.compile("<div\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_ATTR = Pattern.compile("data-(\\w+)\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private final ObjectMapper mapper = new ObjectMapper();
    private final JrxmlParser jrxmlParser = new JrxmlParser();

    /** An ad hoc view embedded in the dashboard export (designer "tmpAdv_*" resource). */
    record EmbeddedView(String name, String dataSourceUri) {}

    @Override public Set<String> resourceTypes() { return Set.of("dashboard"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", "dashboard");
        List<Ref> refs = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        ExportArchive archive = null;

        if (!ctx.config().deepParseDashboards()) {
            warnings.add("dashboard " + uri + ": deepParseDashboards=false, tiles and dashlet lineage not extracted");
        } else {
            try { archive = ExportArchive.read(ctx.repo().exportArchive(List.of(uri))); }
            catch (IOException e) { warnings.add("dashboard " + uri + ": export failed, falling back to descriptor: " + e.getMessage()); }
        }

        if (archive == null) {
            try { ctx.repo().descriptor(uri, "dashboard"); } catch (IOException e) { warnings.add("dashboard " + uri + " descriptor unavailable: " + e.getMessage()); }
            attrs.put("parseMode", "descriptor");
            return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, AssetType.BI_DASHBOARD, attrs)), refs, warnings);
        }

        attrs.put("parseMode", "export");
        Optional<byte[]> components = archive.findBySuffix("_files/components.data");
        if (components.isEmpty()) warnings.add("dashboard " + uri + ": export archive has no components.data");
        Map<String, Map<String, Integer>> layout = archive.findBySuffix("_files/layout").map(b -> parseLayout(new String(b, StandardCharsets.UTF_8))).orElse(Map.of());
        archive.findBySuffix("_files/wiring.data").ifPresent(b -> {
            try { attrs.put("wiring", mapper.readValue(b, List.class)); } catch (IOException e) { warnings.add("dashboard " + uri + ": wiring.data unreadable: " + e.getMessage()); }
        });
        Map<String, EmbeddedView> embedded = embeddedViews(archive, uri, warnings);

        List<Asset> tiles = new ArrayList<>();
        List<Asset> views = new ArrayList<>();
        Asset dash = Asset.of(ctx.serverId(), lookup, AssetType.BI_DASHBOARD, attrs);
        if (components.isPresent()) {
            JsonNode arr;
            try { arr = mapper.readTree(components.get()); }
            catch (IOException e) { warnings.add("dashboard " + uri + ": components.data unreadable: " + e.getMessage()); arr = mapper.createArrayNode(); }
            Set<String> viewsEmitted = new HashSet<>();
            for (JsonNode c : arr) {
                String type = Descriptors.text(c, "type"); String id = Descriptors.text(c, "id");
                if (id == null || "dashboardProperties".equals(type) || "dashboardProperties".equals(id)) continue;
                String resource = Descriptors.text(c, "resource");
                Map<String, Object> ta = Attrs.of("tileType", type, "resourceUri", resource, "dataSourceUri", Descriptors.text(c, "dataSourceUri"));
                Map<String, Integer> pos = layout.get(id);
                if (pos != null) ta.putAll(pos);
                String label = Descriptors.text(c, "label") != null ? Descriptors.text(c, "label") : (Descriptors.text(c, "name") != null ? Descriptors.text(c, "name") : id);
                String tileType = type == null ? "" : type;

                EmbeddedView view = resource == null ? null : embedded.get(baseName(resource));
                String owner = resource == null ? null : embeddedOwnerOf(resource, embedded.keySet());
                if (view != null) {
                    // chart/table tile over a designer view that lives only inside this export: link to what the view queries
                    ta.put("embeddedView", view.name()); ta.put("dataSourceUri", view.dataSourceUri());
                    if (viewsEmitted.add(view.name())) views.add(embeddedViewAsset(dash, view, archive, uri));
                    if (view.dataSourceUri() != null)
                        refs.add(new Ref(uri, view.dataSourceUri(), Ref.RefKind.DASHLET, Map.of("tileId", id, "tileType", tileType, "embeddedView", view.name())));
                    else warnings.add("dashboard " + uri + ": embedded view " + view.name() + " declares no data source");
                } else if (owner != null) {
                    ta.put("embeddedControlOf", owner);           // input control belonging to an embedded view: no data lineage of its own
                } else if (resource != null && !resource.isBlank()) {
                    refs.add(new Ref(uri, resource, Ref.RefKind.DASHLET, Map.of("tileId", id, "tileType", tileType)));
                }
                tiles.add(Asset.child(dash, "tile", id, AssetType.DASHBOARD_TILE, label, ta));
            }
        }
        List<Asset> assets = new ArrayList<>();
        assets.add(dash.withAttributes(Map.of("tileCount", tiles.size(), "embeddedViewCount", views.size())));
        assets.addAll(tiles);
        assets.addAll(views);
        return new ParseResult(assets, refs, warnings);
    }

    /** name -> embedded ad hoc view, from the dashboard descriptor XML inside the export (localResource xsi:type="adhocDataView"). */
    private Map<String, EmbeddedView> embeddedViews(ExportArchive archive, String uri, List<String> warnings) {
        Map<String, EmbeddedView> out = new LinkedHashMap<>();
        Optional<byte[]> xml = archive.findBySuffix(uri + ".xml");
        if (xml.isEmpty()) return out;
        try {
            Document doc = Xml.parse(xml.get());
            for (Element lr : Xml.descendants(doc, "localResource")) {
                String xsiType = lr.getAttributeNS(XSI, "type");
                if (xsiType == null || xsiType.isEmpty()) xsiType = lr.getAttribute("xsi:type");
                if (!"adhocDataView".equals(xsiType)) continue;
                String name = Xml.childText(lr, "name");
                String ds = null;
                for (Element d : Xml.children(lr, "dataSource")) ds = Xml.childText(d, "uri");
                if (name != null) out.put(name, new EmbeddedView(name, ds));
            }
        } catch (IOException e) {
            warnings.add("dashboard " + uri + ": export descriptor unreadable, embedded views not resolved: " + e.getMessage());
        }
        return out;
    }

    private Asset embeddedViewAsset(Asset dash, EmbeddedView view, ExportArchive archive, String uri) {
        Map<String, Object> va = Attrs.of("embedded", true, "dataSourceUri", view.dataSourceUri(), "resourceType", "adhocDataView");
        archive.findBySuffix(view.name() + "_files/topicJRXML.data").ifPresent(b -> {
            try { JrxmlModel m = jrxmlParser.parse(b); va.put("queryLanguage", m.queryLanguage()); va.put("fieldCount", m.fields().size()); }
            catch (IOException ignored) { va.put("queryLanguage", null); }
        });
        return Asset.child(dash, "view", view.name(), AssetType.BI_VIEW, view.name(), va);
    }

    private static String baseName(String resourceUri) {
        int i = resourceUri.lastIndexOf('/');
        return i < 0 ? resourceUri : resourceUri.substring(i + 1);
    }

    /** If the resource lives under "<embeddedViewName>_files/", return that view's name. */
    private static String embeddedOwnerOf(String resourceUri, Set<String> embeddedNames) {
        for (String n : embeddedNames) if (resourceUri.contains("/" + n + "_files/")) return n;
        return null;
    }

    /** componentId -> {x,y,width,height} from the 40-column grid HTML. Attribute order varies between designer versions. */
    static Map<String, Map<String, Integer>> parseLayout(String html) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        Matcher div = DIV.matcher(html);
        while (div.find()) {
            Map<String, String> raw = new HashMap<>();
            Matcher a = DATA_ATTR.matcher(div.group(1));
            while (a.find()) raw.put(a.group(1).toLowerCase(Locale.ROOT), a.group(2));
            String id = raw.get("componentid");
            if (id == null) continue;
            Map<String, Integer> pos = new LinkedHashMap<>();
            for (String k : List.of("x", "y", "width", "height")) if (raw.containsKey(k)) { try { pos.put(k, Integer.parseInt(raw.get(k).trim())); } catch (NumberFormatException ignored) { } }
            out.put(id, pos);
        }
        return out;
    }
}
