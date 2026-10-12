package com.hcl.jrsmeta.lineage;

import com.hcl.jrsmeta.model.*;
import java.util.*;

/** Spec §7.1: descriptor references -> asset-level lineage edges. Data flows producer(toUri) -> consumer(fromUri). */
public final class LineageBuilder {
    public record LineageResult(List<LineageEdge> edges, List<String> warnings) {}

    public LineageResult build(String serverId, List<Ref> refs, Set<String> knownUris) {
        Map<String, LineageEdge> edges = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Ref ref : refs) {
            if (ref.toUri() == null || ref.fromUri() == null) continue;
            String source = Asset.idOf(serverId, ref.toUri());
            String target = Asset.idOf(serverId, ref.fromUri());
            EdgeType type = ref.kind() == Ref.RefKind.INPUT_CONTROL ? EdgeType.PARAMETER : EdgeType.DATA_FLOW;
            Map<String, Object> attrs = new LinkedHashMap<>(ref.attributes());
            attrs.put("refKind", ref.kind().name());
            Confidence conf = Confidence.EXACT;
            if (!knownUris.contains(ref.toUri())) {
                attrs.put("unresolved", true);
                conf = Confidence.INFERRED;
                warnings.add(ref.fromUri() + " references " + ref.toUri() + " (" + ref.kind() + ") which is not in the crawled inventory");
            }
            String key = source + "->" + target + ":" + type;
            edges.putIfAbsent(key, new LineageEdge(source, target, type, conf, attrs));
        }
        return new LineageResult(new ArrayList<>(edges.values()), warnings);
    }
}
