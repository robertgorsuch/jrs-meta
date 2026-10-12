package com.hcl.jrsmeta.crawl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hcl.jrsmeta.model.ResourceLookup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-server sync state (spec §8.2): what was processed, with the descriptor's updateDate/version for change
 * detection, plus run bookkeeping so a run that checkpointed but never completed forces a full re-crawl.
 */
public final class SyncState {
    public record Entry(String resourceType, String updateDate, Integer version, String processedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class FileModel {
        public int formatVersion = 1;
        public boolean lastRunOk = true;
        public String lastRunStarted;
        public String lastCompletedRun;
        public Map<String, Entry> resources = new LinkedHashMap<>();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path file;
    private final FileModel m;

    private SyncState(Path file, FileModel m) { this.file = file; this.m = m; }

    public static SyncState load(Path file) throws IOException {
        if (!Files.exists(file)) return new SyncState(file, new FileModel());
        return new SyncState(file, MAPPER.readValue(Files.readString(file), FileModel.class));
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, MAPPER.writeValueAsString(m));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);   // atomic-ish replace
    }

    public synchronized boolean isChanged(ResourceLookup l) {
        Entry e = m.resources.get(l.uri());
        return e == null || !Objects.equals(e.updateDate(), l.updateDate()) || !Objects.equals(e.version(), l.version());
    }

    public synchronized void markProcessed(ResourceLookup l) {
        m.resources.put(l.uri(), new Entry(l.resourceType(), l.updateDate(), l.version(), Instant.now().toString()));
    }

    public synchronized void remove(String uri) { m.resources.remove(uri); }

    public synchronized Set<String> knownUris() { return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(m.resources.keySet())); }

    public synchronized boolean lastRunOk() { return m.lastRunOk; }
    public synchronized void beginRun() { m.lastRunOk = false; m.lastRunStarted = Instant.now().toString(); }
    public synchronized void completeRun() { m.lastRunOk = true; m.lastCompletedRun = Instant.now().toString(); }
    public synchronized String lastCompletedRun() { return m.lastCompletedRun; }
}
