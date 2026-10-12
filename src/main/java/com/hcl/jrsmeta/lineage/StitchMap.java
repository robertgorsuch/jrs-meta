package com.hcl.jrsmeta.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Admin-maintained fingerprint -> physical catalog asset id mapping (spec §7.4 "admin mapping"). */
public final class StitchMap {
    private final Map<String, String> byKey;

    private StitchMap(Map<String, String> byKey) { this.byKey = byKey; }

    public static StitchMap load(Path file) throws IOException {
        Map<String, String> m = new HashMap<>();
        if (file != null && Files.exists(file)) {
            JsonNode root = new ObjectMapper().readTree(Files.readString(file));
            for (JsonNode s : root.path("stitches")) {
                String fp = s.path("fingerprint").asText(null); String id = s.path("physicalAssetId").asText(null);
                if (fp != null && id != null) m.put(fp.toLowerCase(Locale.ROOT), id);
            }
        }
        return new StitchMap(m);
    }

    public static StitchMap empty() { return new StitchMap(Map.of()); }

    public Optional<String> physicalAssetId(String fingerprintKey) {
        return fingerprintKey == null ? Optional.empty() : Optional.ofNullable(byKey.get(fingerprintKey.toLowerCase(Locale.ROOT)));
    }

    public int size() { return byKey.size(); }
}
