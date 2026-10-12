package com.hcl.jrsmeta.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.model.ResourceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RestJrsRepository implements JrsRepository {
    private static final Logger log = LoggerFactory.getLogger(RestJrsRepository.class);
    private static final String JSON = "application/json";
    private final JrsClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int pageSize;
    private final long pollMillis;
    private final long exportTimeoutMillis;

    public RestJrsRepository(JrsClient client, ConnectorConfig cfg) {
        this.client = client;
        this.pageSize = cfg.pageSize();
        this.pollMillis = cfg.exportPollSeconds() * 1000L;
        this.exportTimeoutMillis = cfg.exportTimeoutSeconds() * 1000L;
    }

    @Override public ServerInfo serverInfo() throws IOException {
        JsonNode n = json(client.get("/serverInfo", JSON));
        return new ServerInfo(text(n, "version"), text(n, "edition"), text(n, "editionName"), text(n, "build"));
    }

    @Override public List<ResourceLookup> listResources(String folderUri, boolean recursive) throws IOException {
        List<ResourceLookup> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            String q = "/resources?folderUri=" + enc(folderUri) + "&recursive=" + recursive + "&limit=" + pageSize + "&offset=" + offset;
            JrsClient.Response r = client.get(q, JSON);
            // A missing include folder must not look like an empty one: that would turn into mass deletion downstream.
            if (r.status() == 404 && offset == 0) throw new JrsClientException(404, "include folder not found or not readable: " + folderUri);
            if (r.status() == 204 || r.status() == 404 || r.body().length == 0) break;        // 204 = no (more) resources
            int n = 0;
            for (JsonNode e : mapper.readTree(r.body()).path("resourceLookup")) { out.add(toLookup(e)); n++; }
            if (n == 0) break;
            // Permission filtering can shorten a page while Next-Offset says there is more; trust the header when present.
            Optional<Integer> nextHeader = r.header("Next-Offset").map(String::trim).filter(v -> !v.isEmpty()).map(Integer::valueOf);
            if (nextHeader.isPresent()) { if (nextHeader.get() <= offset) break; offset = nextHeader.get(); continue; }
            if (n < pageSize) break;
            offset += n;
        }
        log.debug("listed {} resources under {}", out.size(), folderUri);
        return out;
    }

    @Override public JsonNode descriptor(String uri, String resourceType) throws IOException {
        JrsClient.Response r = client.get("/resources" + JrsClient.encodePath(uri), "application/repository." + resourceType + "+json");
        if (r.status() == 404) throw new JrsClientException(404, "resource not found: " + uri);
        return mapper.readTree(r.body());
    }

    @Override public byte[] file(String uri, String accept) throws IOException {
        JrsClient.Response r = client.get("/resources" + JrsClient.encodePath(uri), accept);
        if (r.status() == 404) throw new JrsClientException(404, "file not found: " + uri);
        return r.body();
    }

    @Override public byte[] exportArchive(List<String> uris) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("uris"); uris.forEach(arr::add);
        body.putArray("parameters");
        JsonNode started = json(client.post("/export", JSON, mapper.writeValueAsBytes(body), JSON));
        String id = text(started, "id");
        if (id == null) throw new IOException("export did not return an id: " + started);
        long deadline = System.currentTimeMillis() + exportTimeoutMillis;
        while (true) {
            JsonNode state = json(client.get("/export/" + id + "/state", JSON));
            String phase = text(state, "phase");
            if ("finished".equalsIgnoreCase(phase)) break;
            if ("failed".equalsIgnoreCase(phase)) throw new IOException("export " + id + " failed: " + text(state, "message"));
            if (System.currentTimeMillis() > deadline) throw new IOException("export " + id + " timed out after " + exportTimeoutMillis + " ms (phase=" + phase + ")");
            try { Thread.sleep(pollMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted", e); }
        }
        return client.get("/export/" + id + "/exportFile", "application/zip").body();   // note: bare GET /export/{id} is 405
    }

    @Override public List<JsonNode> jobs(String reportUri) throws IOException {
        JrsClient.Response r = client.get("/jobs?reportUnitURI=" + enc(reportUri), JSON);
        return r.status() == 204 || r.body().length == 0 ? List.of() : toList(mapper.readTree(r.body()).path("jobsummary"));
    }

    @Override public JsonNode job(long id) throws IOException {
        return json(client.get("/jobs/" + id, "application/job+json"));
    }

    @Override public List<JsonNode> permissions(String uri) throws IOException {
        JrsClient.Response r = client.get("/permissions" + JrsClient.encodePath(uri) + "?effectivePermissions=true", JSON);
        return r.status() == 204 || r.status() == 404 || r.body().length == 0 ? List.of() : toList(mapper.readTree(r.body()).path("permission"));
    }

    private JsonNode json(JrsClient.Response r) throws IOException {
        if (r.status() == 404) throw new JrsClientException(404, "not found");
        return r.body().length == 0 ? mapper.createObjectNode() : mapper.readTree(r.body());
    }

    private static List<JsonNode> toList(JsonNode arr) { List<JsonNode> l = new ArrayList<>(); arr.forEach(l::add); return l; }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private static String text(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asText() : null; }

    static ResourceLookup toLookup(JsonNode e) {
        return new ResourceLookup(text(e, "uri"), text(e, "label"), text(e, "description"), text(e, "resourceType"),
                text(e, "creationDate"), text(e, "updateDate"), e.hasNonNull("version") ? e.get("version").asInt() : null);
    }
}
