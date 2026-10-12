package com.hcl.jrsmeta.lineage;

import com.hcl.jrsmeta.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class LineageBuilderTest {
    @Test void build_localFilesChildrenAreNotUnresolved() {
        // input controls and companion files live under <report>_files/, which the listing hides; they are local, not missing
        List<Ref> refs = List.of(
                new Ref("/reports/r", "/reports/r_files/Country", Ref.RefKind.INPUT_CONTROL),
                new Ref("/reports/r", "/reports/r_files/sub.jrxml", Ref.RefKind.SUBREPORT),
                new Ref("/reports/r", "/reports/other_files/x", Ref.RefKind.SUBREPORT));      // someone else's _files: still unresolved
        LineageBuilder.LineageResult r = new LineageBuilder().build("srv", refs, Set.of("/reports/r"));
        assertThat(r.edges()).hasSize(3);
        assertThat(r.edges().get(0).attributes()).containsEntry("local", true).doesNotContainKey("unresolved");
        assertThat(r.edges().get(0).confidence()).isEqualTo(Confidence.EXACT);
        assertThat(r.edges().get(1).attributes()).containsEntry("local", true);
        assertThat(r.edges().get(2).attributes()).containsEntry("unresolved", true);
        assertThat(r.warnings()).hasSize(1).first().asString().contains("/reports/other_files/x");
    }

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
                new LineageEdge("srv|/reports/r_files/Region", "srv|/reports/r", EdgeType.PARAMETER, Confidence.EXACT, Map.of("local", true, "refKind", "INPUT_CONTROL")),
                new LineageEdge("srv|/reports/r", "srv|/dash/d", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("tileId", "T1", "refKind", "DASHLET")),
                new LineageEdge("srv|/ds/pg", "srv|/ds/virtual", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("refKind", "SUB_DATASOURCE")));
        LineageEdge gone = r.edges().stream().filter(e -> e.sourceId().equals("srv|/reports/gone")).findFirst().orElseThrow();
        assertThat(gone.attributes()).containsEntry("unresolved", true).containsEntry("tileId", "T2");
        assertThat(gone.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(r.warnings()).hasSize(1).first().asString().contains("/reports/gone");
    }
}
