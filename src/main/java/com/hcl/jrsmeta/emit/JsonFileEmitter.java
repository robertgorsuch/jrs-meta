package com.hcl.jrsmeta.emit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonFileEmitter implements Emitter {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path outputDir;

    public JsonFileEmitter(Path outputDir) { this.outputDir = outputDir; }

    @Override public void emit(EmitBatch b) throws IOException {
        Path dir = outputDir.resolve(b.runId());
        Files.createDirectories(dir);
        Map<String, Object> assets = header(b); assets.put("assets", b.assets());
        Map<String, Object> lineage = header(b); lineage.put("edges", b.edges());
        Map<String, Object> report = header(b);
        report.put("startedAt", b.startedAt()); report.put("finishedAt", Instant.now().toString());
        report.put("deletedAssetIds", b.deletedAssetIds()); report.put("stats", b.stats()); report.put("warnings", b.warnings());
        MAPPER.writeValue(dir.resolve("assets.json").toFile(), assets);
        MAPPER.writeValue(dir.resolve("lineage.json").toFile(), lineage);
        MAPPER.writeValue(dir.resolve("run-report.json").toFile(), report);
        Files.writeString(outputDir.resolve("latest.txt"), b.runId() + System.lineSeparator());
    }

    private static Map<String, Object> header(EmitBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverId", b.serverId()); m.put("runId", b.runId()); m.put("mode", b.mode());
        return m;
    }
}
