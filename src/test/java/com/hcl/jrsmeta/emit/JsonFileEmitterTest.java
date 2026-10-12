package com.hcl.jrsmeta.emit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcl.jrsmeta.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class JsonFileEmitterTest {
    @TempDir Path tmp;

    @Test void emit_writesThreeFilesAndLatestPointer() throws Exception {
        Asset a = new Asset("srv|/r", "srv", "/r", AssetType.BI_REPORT, "R", null, "/", null, "2024-01-01T00:00:00", null, 1,
                Map.of("fingerprintParts", new Fingerprint("postgresql", "h", 5432, "db", null, "jdbc:postgresql://h/db")));
        LineageEdge e = new LineageEdge("srv|/ds", "srv|/r", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("refKind", "DATASOURCE"));
        EmitBatch b = new EmitBatch("srv", "20261012T120000Z", "2026-10-12T12:00:00Z", "full", List.of(a), List.of(e), List.of("srv|/old"), List.of("w1"), Map.of("assets", 1));
        new JsonFileEmitter(tmp).emit(b);

        Path dir = tmp.resolve("20261012T120000Z");
        ObjectMapper m = new ObjectMapper();
        JsonNode assets = m.readTree(Files.readString(dir.resolve("assets.json")));
        assertThat(assets.get("assets")).hasSize(1);
        assertThat(assets.get("assets").get(0).get("type").asText()).isEqualTo("BI_REPORT");
        assertThat(assets.get("assets").get(0).get("attributes").get("fingerprintParts").get("host").asText()).isEqualTo("h");
        JsonNode lineage = m.readTree(Files.readString(dir.resolve("lineage.json")));
        assertThat(lineage.get("edges").get(0).get("confidence").asText()).isEqualTo("EXACT");
        JsonNode report = m.readTree(Files.readString(dir.resolve("run-report.json")));
        assertThat(report.get("deletedAssetIds").get(0).asText()).isEqualTo("srv|/old");
        assertThat(report.get("warnings").get(0).asText()).isEqualTo("w1");
        assertThat(report.get("stats").get("assets").asInt()).isEqualTo(1);
        assertThat(report.hasNonNull("finishedAt")).isTrue();
        assertThat(Files.readString(tmp.resolve("latest.txt")).trim()).isEqualTo("20261012T120000Z");
    }
}
