package com.hcl.jrsmeta.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable connector settings. Password is a char[] and is excluded from toString. */
public record ConnectorConfig(
        String serverId, String baseUrl, String username, char[] password,
        List<String> includeFolders, List<String> excludeFolders,
        int pageSize, double rateLimitPerSecond, int maxRetries, int requestTimeoutSeconds,
        int exportPollSeconds, int exportTimeoutSeconds, int checkpointEvery,
        Path stateDir, Path outputDir, Path stitchMapFile,
        boolean fetchOperationalMetadata, boolean deepParseDashboards, boolean redactSqlLiterals) {

    public ConnectorConfig {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        includeFolders = List.copyOf(includeFolders);
        excludeFolders = List.copyOf(excludeFolders);
    }

    public String restBase() { return baseUrl + "/rest_v2"; }

    @Override public String toString() {
        return "ConnectorConfig{serverId=" + serverId + ", baseUrl=" + baseUrl + ", username=" + username
                + ", password=***, includeFolders=" + includeFolders + ", excludeFolders=" + excludeFolders
                + ", pageSize=" + pageSize + ", rateLimitPerSecond=" + rateLimitPerSecond + ", maxRetries=" + maxRetries
                + ", deepParseDashboards=" + deepParseDashboards + ", fetchOperationalMetadata=" + fetchOperationalMetadata + "}";
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String serverId, baseUrl, username; private char[] password = new char[0];
        private List<String> includeFolders = List.of("/"); private List<String> excludeFolders = List.of();
        private int pageSize = 100; private double rateLimitPerSecond = 5.0; private int maxRetries = 4;
        private int requestTimeoutSeconds = 60; private int exportPollSeconds = 2; private int exportTimeoutSeconds = 300;
        private int checkpointEvery = 100;
        private Path stateDir = Path.of("state"), outputDir = Path.of("out"), stitchMapFile = Path.of("config", "stitch-map.json");
        private boolean fetchOperationalMetadata = true, deepParseDashboards = true, redactSqlLiterals = false;

        public Builder serverId(String v) { serverId = v; return this; }
        public Builder baseUrl(String v) { baseUrl = v == null ? null : v.replaceAll("/+$", ""); return this; }
        public Builder username(String v) { username = v; return this; }
        public Builder password(char[] v) { password = v; return this; }
        public Builder includeFolders(List<String> v) { includeFolders = v; return this; }
        public Builder excludeFolders(List<String> v) { excludeFolders = v; return this; }
        public Builder pageSize(int v) { pageSize = v; return this; }
        public Builder rateLimitPerSecond(double v) { rateLimitPerSecond = v; return this; }
        public Builder maxRetries(int v) { maxRetries = v; return this; }
        public Builder requestTimeoutSeconds(int v) { requestTimeoutSeconds = v; return this; }
        public Builder exportPollSeconds(int v) { exportPollSeconds = v; return this; }
        public Builder exportTimeoutSeconds(int v) { exportTimeoutSeconds = v; return this; }
        public Builder checkpointEvery(int v) { checkpointEvery = v; return this; }
        public Builder stateDir(Path v) { stateDir = v; return this; }
        public Builder outputDir(Path v) { outputDir = v; return this; }
        public Builder stitchMapFile(Path v) { stitchMapFile = v; return this; }
        public Builder fetchOperationalMetadata(boolean v) { fetchOperationalMetadata = v; return this; }
        public Builder deepParseDashboards(boolean v) { deepParseDashboards = v; return this; }
        public Builder redactSqlLiterals(boolean v) { redactSqlLiterals = v; return this; }

        public ConnectorConfig build() {
            return new ConnectorConfig(serverId, baseUrl, username, password, includeFolders, excludeFolders,
                    pageSize, rateLimitPerSecond, maxRetries, requestTimeoutSeconds, exportPollSeconds, exportTimeoutSeconds,
                    checkpointEvery, stateDir, outputDir, stitchMapFile, fetchOperationalMetadata, deepParseDashboards, redactSqlLiterals);
        }
    }
}
