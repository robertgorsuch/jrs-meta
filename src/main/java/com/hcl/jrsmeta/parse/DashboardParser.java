package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spec §9.4: dashboard export archive -> BI_DASHBOARD + tiles + DASHLET refs; falls back to the bare descriptor. */
public final class DashboardParser implements DescriptorParser {
    private static final Pattern DIV = Pattern.compile("<div\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_ATTR = Pattern.compile("data-(\\w+)\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper = new ObjectMapper();

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

        List<Asset> tiles = new ArrayList<>();
        Asset dash = Asset.of(ctx.serverId(), lookup, AssetType.BI_DASHBOARD, attrs);
        if (components.isPresent()) {
            JsonNode arr;
            try { arr = mapper.readTree(components.get()); }
            catch (IOException e) { warnings.add("dashboard " + uri + ": components.data unreadable: " + e.getMessage()); arr = mapper.createArrayNode(); }
            for (JsonNode c : arr) {
                String type = Descriptors.text(c, "type"); String id = Descriptors.text(c, "id");
                if (id == null || "dashboardProperties".equals(type) || "dashboardProperties".equals(id)) continue;
                String resource = Descriptors.text(c, "resource");
                Map<String, Object> ta = Attrs.of("tileType", type, "resourceUri", resource, "dataSourceUri", Descriptors.text(c, "dataSourceUri"));
                Map<String, Integer> pos = layout.get(id);
                if (pos != null) ta.putAll(pos);
                String label = Descriptors.text(c, "label") != null ? Descriptors.text(c, "label") : (Descriptors.text(c, "name") != null ? Descriptors.text(c, "name") : id);
                tiles.add(Asset.child(dash, "tile", id, AssetType.DASHBOARD_TILE, label, ta));
                if (resource != null && !resource.isBlank())
                    refs.add(new Ref(uri, resource, Ref.RefKind.DASHLET, Map.of("tileId", id, "tileType", type == null ? "" : type)));
            }
        }
        List<Asset> assets = new ArrayList<>();
        assets.add(dash.withAttributes(Map.of("tileCount", tiles.size())));
        assets.addAll(tiles);
        return new ParseResult(assets, refs, warnings);
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
