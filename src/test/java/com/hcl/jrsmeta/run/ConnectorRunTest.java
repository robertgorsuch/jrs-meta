package com.hcl.jrsmeta.run;

import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.crawl.SyncState;
import com.hcl.jrsmeta.emit.EmitBatch;
import com.hcl.jrsmeta.emit.Emitter;
import com.hcl.jrsmeta.lineage.StitchMap;
import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.parse.ParserRegistry;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ConnectorRunTest {
    @TempDir Path tmp;

    static final class CapturingEmitter implements Emitter {
        final List<EmitBatch> batches = new ArrayList<>();
        public void emit(EmitBatch b) { batches.add(b); }
    }

    private FakeRepository repo() {
        FakeRepository r = new FakeRepository();
        r.listing.add(new ResourceLookup("/reports", "Reports", null, "folder", null, "2024-01-01T00:00:00", 1));
        r.listing.add(new ResourceLookup("/reports/rev", "Revenue", null, "reportUnit", null, "2024-01-01T00:00:00", 1));
        r.listing.add(new ResourceLookup("/ds/pg", "PG", null, "jdbcDataSource", null, "2024-01-01T00:00:00", 1));
        r.listing.add(new ResourceLookup("/reports/broken", "Broken", null, "reportUnit", null, "2024-01-01T00:00:00", 1));   // no descriptor -> fails
        r.listing.add(new ResourceLookup("/temp/x", "Temp", null, "reportUnit", null, "2024-01-01T00:00:00", 1));           // excluded
        r.withDescriptor("/reports/rev", "{\"uri\":\"/reports/rev\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/pg\"}},\"jrxml\":{\"jrxmlFileReference\":{\"uri\":\"/reports/rev_files/main_jrxml\"}}}")
         .withFile("/reports/rev_files/main_jrxml", "<jasperReport><queryString><![CDATA[SELECT 1 AS one]]></queryString><field name=\"one\" class=\"java.lang.Integer\"/></jasperReport>")
         .withDescriptor("/ds/pg", "{\"uri\":\"/ds/pg\",\"driverClass\":\"org.postgresql.Driver\",\"connectionUrl\":\"jdbc:postgresql://h/db\"}");
        return r;
    }

    private ConnectorConfig cfg() {
        return TestConfig.base().excludeFolders(List.of("/temp")).fetchOperationalMetadata(false).stateDir(tmp).outputDir(tmp.resolve("out")).checkpointEvery(1).build();
    }

    @Test void execute_fullRunEmitsAssetsEdgesAndStitching() throws Exception {
        CapturingEmitter em = new CapturingEmitter();
        SyncState state = SyncState.load(tmp.resolve("sync-state.json"));
        RunReport rep = new ConnectorRun(cfg(), repo(), ParserRegistry.defaults(), em, state, StitchMap.empty()).execute(true, false);

        assertThat(rep.inventory()).isEqualTo(4);          // /temp/x filtered out
        assertThat(rep.processed()).isEqualTo(3);
        assertThat(rep.failed()).isEqualTo(1);
        assertThat(rep.shadowed()).isEqualTo(1);
        assertThat(rep.warnings()).anyMatch(w -> w.contains("/reports/broken"));
        assertThat(em.batches).hasSize(1);
        EmitBatch b = em.batches.get(0);
        assertThat(b.mode()).isEqualTo("full");
        assertThat(b.assets()).extracting(Asset::type).contains(AssetType.FOLDER, AssetType.BI_REPORT, AssetType.REPORT_FIELD, AssetType.BI_DATASOURCE, AssetType.PHYSICAL_DATASOURCE_SHADOW);
        assertThat(b.edges()).anyMatch(e -> e.type() == EdgeType.DATA_FLOW && e.sourceId().equals("test|/ds/pg") && e.targetId().equals("test|/reports/rev"));
        assertThat(b.edges()).anyMatch(e -> e.type() == EdgeType.STITCH);
        assertThat(SyncState.load(tmp.resolve("sync-state.json")).lastRunOk()).isTrue();
        assertThat(SyncState.load(tmp.resolve("sync-state.json")).knownUris()).containsExactlyInAnyOrder("/reports", "/reports/rev", "/ds/pg");   // failed one not marked
    }

    @Test void execute_incrementalSkipsUnchangedAndReportsDeleted() throws Exception {
        CapturingEmitter em = new CapturingEmitter();
        SyncState state = SyncState.load(tmp.resolve("sync-state.json"));
        FakeRepository r = repo();
        ConnectorRun run = new ConnectorRun(cfg(), r, ParserRegistry.defaults(), em, state, StitchMap.empty());
        run.execute(true, false);
        r.listing.removeIf(l -> l.uri().equals("/ds/pg"));
        r.listing.replaceAll(l -> l.uri().equals("/reports/rev") ? new ResourceLookup("/reports/rev", "Revenue", null, "reportUnit", null, "2024-02-01T00:00:00", 2) : l);
        RunReport rep = run.execute(false, false);
        assertThat(rep.mode()).isEqualTo("incremental");
        assertThat(rep.processed()).isEqualTo(1);
        assertThat(rep.unchanged()).isEqualTo(1);
        assertThat(rep.deleted()).isEqualTo(1);
        assertThat(em.batches.get(1).deletedAssetIds()).containsExactly("test|/ds/pg");
        assertThat(em.batches.get(1).assets()).extracting(Asset::uri).contains("/reports/rev");
    }

    @Test void execute_dryRunNeitherFetchesNorEmitsNorSaves() throws Exception {
        CapturingEmitter em = new CapturingEmitter();
        FakeRepository r = repo();
        RunReport rep = new ConnectorRun(cfg(), r, ParserRegistry.defaults(), em, SyncState.load(tmp.resolve("s.json")), StitchMap.empty()).execute(false, true);
        assertThat(rep.inventory()).isEqualTo(4);
        assertThat(r.requestedUris).isEmpty();
        assertThat(em.batches).isEmpty();
        assertThat(tmp.resolve("s.json")).doesNotExist();
    }
}
