package com.hcl.jrsmeta.lineage;

import com.hcl.jrsmeta.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class LineageBuilderTest {
    @Test void build_directionTypesAndUnresolved() {
        List<Ref> refs = List.of(
                new Ref("/reports/r", "/ds/pg", Ref.RefKind.DATASOURCE),
                new Ref("/reports/r", "/reports/sub", Ref.RefKind.SUBREPORT),
                new Ref("/reports/r", "/reports/r_files/Region", Ref.RefKind.INPUT_CONTROL),
                new Ref("/dash/d", "/reports/r", Ref.RefKind.DASHLET, Map.of("tileId", "T1")),
                new Ref("/dash/d", "/reports/gone", Ref.RefKind.DASHLET, Map.of("tileId", "T2")),
                new Ref("/ds/virtual", "/ds/pg", Ref.RefKind.SUB_DATASOURCE),
                new Ref("/reports/r", "/ds/pg", Ref.RefKind.DATASOURCE));                   // duplicate
        Set<String> known = Set.of("/reports/r", "/ds/pg", "/reports/sub", "/reports/r_files/Region", "/dash/d", "/ds/virtual");
        LineageBuilder.LineageResult r = new LineageBuilder().build("srv", refs, known);

        assertThat(r.edges()).hasSize(6);
        assertThat(r.edges()).contains(
                new LineageEdge("srv|/ds/pg", "srv|/reports/r", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("refKind", "DATASOURCE")),
                new LineageEdge("srv|/reports/sub", "srv|/reports/r", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("refKind", "SUBREPORT")),
                new LineageEdge("srv|/reports/r_files/Region", "srv|/reports/r", EdgeType.PARAMETER, Confidence.EXACT, Map.of("refKind", "INPUT_CONTROL")),
                new LineageEdge("srv|/reports/r", "srv|/dash/d", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("tileId", "T1", "refKind", "DASHLET")),
                new LineageEdge("srv|/ds/pg", "srv|/ds/virtual", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("refKind", "SUB_DATASOURCE")));
        LineageEdge gone = r.edges().stream().filter(e -> e.sourceId().equals("srv|/reports/gone")).findFirst().orElseThrow();
        assertThat(gone.attributes()).containsEntry("unresolved", true).containsEntry("tileId", "T2");
        assertThat(gone.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(r.warnings()).hasSize(1).first().asString().contains("/reports/gone");
    }
}
