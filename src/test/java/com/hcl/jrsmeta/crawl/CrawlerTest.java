package com.hcl.jrsmeta.crawl;

import com.hcl.jrsmeta.model.ResourceLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CrawlerTest {
    @TempDir Path tmp;
    ResourceLookup a = new ResourceLookup("/a", "A", null, "reportUnit", null, "2024-01-02T00:00:00", 1);
    ResourceLookup aNew = new ResourceLookup("/a", "A", null, "reportUnit", null, "2024-01-05T00:00:00", 2);
    ResourceLookup b = new ResourceLookup("/b", "B", null, "folder", null, "2024-01-02T00:00:00", 1);
    ResourceLookup c = new ResourceLookup("/c", "C", null, "jdbcDataSource", null, "2024-01-02T00:00:00", 1);

    @Test void incremental_selectsChangedAndNewDetectsDeleted() throws Exception {
        SyncState s = SyncState.load(tmp.resolve("s.json"));
        s.markProcessed(a); s.markProcessed(b); s.completeRun();
        ChangeSet cs = new Crawler().plan(List.of(aNew, b, c), s, false);
        assertThat(cs.toProcess()).extracting(ResourceLookup::uri).containsExactly("/a", "/c");
        assertThat(cs.unchanged()).isEqualTo(1);
        assertThat(cs.deletedUris()).isEmpty();
        assertThat(cs.forcedFull()).isFalse();
        ChangeSet cs2 = new Crawler().plan(List.of(b), s, false);
        assertThat(cs2.deletedUris()).containsExactly("/a");
    }
    @Test void full_processesEverything() throws Exception {
        SyncState s = SyncState.load(tmp.resolve("s.json"));
        s.markProcessed(a); s.completeRun();
        assertThat(new Crawler().plan(List.of(a, b), s, true).toProcess()).hasSize(2);
    }
    @Test void plan_forcesFullWhenLastRunNotOk() throws Exception {
        SyncState s = SyncState.load(tmp.resolve("s.json"));
        s.beginRun(); s.markProcessed(a);                            // checkpointed, never completed
        ChangeSet cs = new Crawler().plan(List.of(a), s, false);
        assertThat(cs.forcedFull()).isTrue();
        assertThat(cs.toProcess()).hasSize(1);
    }
}
