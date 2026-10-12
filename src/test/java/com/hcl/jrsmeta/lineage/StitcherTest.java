package com.hcl.jrsmeta.lineage;

import com.hcl.jrsmeta.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class StitcherTest {
    @TempDir Path tmp;

    Asset ds(String uri, String fp) {
        return new Asset("srv|" + uri, "srv", uri, AssetType.BI_DATASOURCE, uri, null, "/ds", null, null, null, 1, fp == null ? Map.of() : Map.of("fingerprint", fp));
    }

    @Test void stitch_matchedUnmatchedAndVirtual() throws Exception {
        Path f = tmp.resolve("stitch.json");
        Files.writeString(f, "{\"stitches\":[{\"fingerprint\":\"POSTGRESQL://db.example.com:5432/salesdb\",\"physicalAssetId\":\"cat:ds:1\"}]}");
        Stitcher s = new Stitcher(StitchMap.load(f));
        List<Asset> in = List.of(ds("/ds/pg", "postgresql://db.example.com:5432/salesdb"), ds("/ds/ora", "oracle://ora:1521/orcl"), ds("/ds/ora2", "oracle://ora:1521/orcl"), ds("/ds/virtual", null),
                new Asset("srv|/reports/x", "srv", "/reports/x", AssetType.BI_REPORT, "x", null, "/", null, null, null, 1, Map.of("fingerprint", "ignored")));
        Stitcher.StitchResult r = s.stitch("srv", in);

        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.shadowed()).isEqualTo(2);
        assertThat(r.shadowAssets()).hasSize(1);                                   // same fingerprint -> one shadow node
        Asset shadow = r.shadowAssets().get(0);
        assertThat(shadow.id()).isEqualTo("shadow|oracle://ora:1521/orcl");
        assertThat(shadow.type()).isEqualTo(AssetType.PHYSICAL_DATASOURCE_SHADOW);
        assertThat(r.edges()).containsExactlyInAnyOrder(
                new LineageEdge("cat:ds:1", "srv|/ds/pg", EdgeType.STITCH, Confidence.EXACT, Map.of("fingerprint", "postgresql://db.example.com:5432/salesdb")),
                new LineageEdge("shadow|oracle://ora:1521/orcl", "srv|/ds/ora", EdgeType.STITCH, Confidence.INFERRED, Map.of("fingerprint", "oracle://ora:1521/orcl", "needsMapping", true)),
                new LineageEdge("shadow|oracle://ora:1521/orcl", "srv|/ds/ora2", EdgeType.STITCH, Confidence.INFERRED, Map.of("fingerprint", "oracle://ora:1521/orcl", "needsMapping", true)));
    }

    @Test void stitchMap_missingFileIsEmpty() throws Exception {
        StitchMap m = StitchMap.load(tmp.resolve("nope.json"));
        assertThat(m.physicalAssetId("x")).isEmpty();
        assertThat(m.size()).isZero();
    }
}
