package com.hcl.jrsmeta.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Loads connector.json; credentials come only from the environment. */
public final class ConfigLoader {
    public static final String ENV_USER = "JRS_META_USER";
    public static final String ENV_PASSWORD = "JRS_META_PASSWORD";
    public static final String ENV_BASE_URL = "JRS_META_BASE_URL";

    private ConfigLoader() {}

    public static ConnectorConfig load(Path file, Map<String, String> env) throws IOException {
        JsonNode n = new ObjectMapper().readTree(Files.readString(file));
        List<String> problems = new ArrayList<>();
        if (n.has("password") || n.has("username")) problems.add("credentials must not be in the config file (remove 'username'/'password'; use " + ENV_USER + "/" + ENV_PASSWORD + ")");

        ConnectorConfig.Builder b = ConnectorConfig.builder();
        String serverId = text(n, "serverId"); if (serverId == null) problems.add("serverId is required");
        String baseUrl = env.getOrDefault(ENV_BASE_URL, text(n, "baseUrl")); if (baseUrl == null) problems.add("baseUrl is required (file or " + ENV_BASE_URL + ")");
        String user = env.get(ENV_USER); if (user == null || user.isBlank()) problems.add(ENV_USER + " environment variable is required");
        String pass = env.get(ENV_PASSWORD); if (pass == null) problems.add(ENV_PASSWORD + " environment variable is required");
        if (!problems.isEmpty()) throw new IllegalArgumentException("Invalid configuration: " + String.join("; ", problems));

        b.serverId(serverId).baseUrl(baseUrl).username(user).password(pass.toCharArray());
        if (n.has("includeFolders")) b.includeFolders(strings(n.get("includeFolders")));
        if (n.has("excludeFolders")) b.excludeFolders(strings(n.get("excludeFolders")));
        if (n.has("pageSize")) b.pageSize(n.get("pageSize").asInt());
        if (n.has("rateLimitPerSecond")) b.rateLimitPerSecond(n.get("rateLimitPerSecond").asDouble());
        if (n.has("maxRetries")) b.maxRetries(n.get("maxRetries").asInt());
        if (n.has("requestTimeoutSeconds")) b.requestTimeoutSeconds(n.get("requestTimeoutSeconds").asInt());
        if (n.has("exportPollSeconds")) b.exportPollSeconds(n.get("exportPollSeconds").asInt());
        if (n.has("exportTimeoutSeconds")) b.exportTimeoutSeconds(n.get("exportTimeoutSeconds").asInt());
        if (n.has("checkpointEvery")) b.checkpointEvery(n.get("checkpointEvery").asInt());
        if (n.has("stateDir")) b.stateDir(Path.of(n.get("stateDir").asText()));
        if (n.has("outputDir")) b.outputDir(Path.of(n.get("outputDir").asText()));
        if (n.has("stitchMapFile")) b.stitchMapFile(Path.of(n.get("stitchMapFile").asText()));
        if (n.has("fetchOperationalMetadata")) b.fetchOperationalMetadata(n.get("fetchOperationalMetadata").asBoolean());
        if (n.has("deepParseDashboards")) b.deepParseDashboards(n.get("deepParseDashboards").asBoolean());
        if (n.has("redactSqlLiterals")) b.redactSqlLiterals(n.get("redactSqlLiterals").asBoolean());
        return b.build();
    }

    private static String text(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asText() : null; }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(e -> out.add(e.asText()));
        return out;
    }
}
