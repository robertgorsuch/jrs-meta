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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-server sync state (spec §8.2): what was processed, with the descriptor's updateDate/version for change
 * detection, the child asset ids each resource produced (so dropped children can be deleted downstream),
 * plus run bookkeeping so an interrupted run resumes instead of silently skipping checkpointed resources.
 */
public final class SyncState {
    public record Entry(String resourceType, String updateDate, Integer version, String processedAt, List<String> childIds) {
        public Entry { childIds = childIds == null ? List.of() : List.copyOf(childIds); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class FileModel {
        public int formatVersion = 2;
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

    public synchronized void markProcessed(ResourceLookup l) { markProcessed(l, List.of()); }

    public synchronized void markProcessed(ResourceLookup l, List<String> childIds) {
        m.resources.put(l.uri(), new Entry(l.resourceType(), l.updateDate(), l.version(), Instant.now().toString(), childIds));
    }

    /** Child asset ids emitted the last time this URI was processed; empty if unknown. */
    public synchronized List<String> childIds(String uri) {
        Entry e = m.resources.get(uri);
        return e == null ? List.of() : e.childIds();
    }

    /** True when the URI was checkpointed after the given ISO instant (i.e. by a run that never completed). */
    public synchronized boolean processedAfter(String uri, String isoInstant) {
        Entry e = m.resources.get(uri);
        if (e == null || e.processedAt() == null || isoInstant == null) return false;
        return Instant.parse(e.processedAt()).isAfter(Instant.parse(isoInstant));
    }

    public synchronized void remove(String uri) { m.resources.remove(uri); }

    public synchronized Set<String> knownUris() { return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(m.resources.keySet())); }

    public synchronized boolean lastRunOk() { return m.lastRunOk; }
    public synchronized void beginRun() { m.lastRunOk = false; m.lastRunStarted = Instant.now().toString(); }
    public synchronized void completeRun() { m.lastRunOk = true; m.lastCompletedRun = Instant.now().toString(); }
    public synchronized String lastCompletedRun() { return m.lastCompletedRun; }
}
