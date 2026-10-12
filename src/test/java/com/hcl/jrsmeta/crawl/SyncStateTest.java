package com.hcl.jrsmeta.crawl;

import com.hcl.jrsmeta.model.ResourceLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class SyncStateTest {
    @TempDir Path tmp;
    ResourceLookup a1 = new ResourceLookup("/a", "A", null, "reportUnit", "2024-01-01T00:00:00", "2024-01-02T00:00:00", 1);
    ResourceLookup a2 = new ResourceLookup("/a", "A", null, "reportUnit", "2024-01-01T00:00:00", "2024-01-03T00:00:00", 2);

    @Test void missingFileIsEmptyStateWithLastRunOk() throws Exception {
        SyncState s = SyncState.load(tmp.resolve("missing.json"));
        assertThat(s.knownUris()).isEmpty();
        assertThat(s.lastRunOk()).isTrue();
        assertThat(s.isChanged(a1)).isTrue();
    }
    @Test void roundTripPersistsEntriesAndRunFlags() throws Exception {
        Path f = tmp.resolve("state.json");
        SyncState s = SyncState.load(f);
        s.beginRun(); s.markProcessed(a1); s.save();
        SyncState crashed = SyncState.load(f);
        assertThat(crashed.lastRunOk()).isFalse();                 // beginRun without completeRun
        assertThat(crashed.isChanged(a1)).isFalse();
        assertThat(crashed.isChanged(a2)).isTrue();                // updateDate/version differ
        crashed.completeRun(); crashed.save();
        SyncState done = SyncState.load(f);
        assertThat(done.lastRunOk()).isTrue();
        assertThat(done.lastCompletedRun()).isNotNull();
        assertThat(Files.readString(f)).contains("\"/a\"");
    }
    @Test void removeForgetsUri() throws Exception {
        SyncState s = SyncState.load(tmp.resolve("s.json"));
        s.markProcessed(a1); s.remove("/a");
        assertThat(s.knownUris()).isEmpty();
    }
}
