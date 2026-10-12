package com.hcl.jrsmeta.lineage;

import com.hcl.jrsmeta.model.*;
import java.util.*;

public final class Stitcher {
    public record StitchResult(List<Asset> shadowAssets, List<LineageEdge> edges, int matched, int shadowed) {}

    private final StitchMap map;

    public Stitcher(StitchMap map) { this.map = map; }

    public StitchResult stitch(String serverId, List<Asset> assets) {
        Map<String, Asset> shadows = new LinkedHashMap<>();
        List<LineageEdge> edges = new ArrayList<>();
        int matched = 0, shadowed = 0;
        for (Asset a : assets) {
            if (a.type() != AssetType.BI_DATASOURCE) continue;
            Object key = a.attributes().get("fingerprint");
            if (!(key instanceof String fp) || fp.isBlank()) continue;      // virtual datasources stitch via their sub-datasources
            Optional<String> physical = map.physicalAssetId(fp);
            if (physical.isPresent()) {
                matched++;
                edges.add(new LineageEdge(physical.get(), a.id(), EdgeType.STITCH, Confidence.EXACT, Map.of("fingerprint", fp)));
            } else {
                shadowed++;
                String shadowId = "shadow|" + fp;
                shadows.computeIfAbsent(shadowId, id -> new Asset(id, serverId, null, AssetType.PHYSICAL_DATASOURCE_SHADOW, fp,
                        "Unmapped external datasource. Add its fingerprint to the stitch map to connect BI lineage to the physical asset.",
                        null, null, null, null, null, Attrs.of("fingerprint", fp, "fingerprintParts", a.attributes().get("fingerprintParts"))));
                edges.add(new LineageEdge(shadowId, a.id(), EdgeType.STITCH, Confidence.INFERRED, Map.of("fingerprint", fp, "needsMapping", true)));
            }
        }
        return new StitchResult(new ArrayList<>(shadows.values()), edges, matched, shadowed);
    }
}
