package com.hcl.jrsmeta.testutil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcl.jrsmeta.client.JrsClientException;
import com.hcl.jrsmeta.client.JrsRepository;
import com.hcl.jrsmeta.client.ServerInfo;
import com.hcl.jrsmeta.model.ResourceLookup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** In-memory JrsRepository for parser tests. */
public final class FakeRepository implements JrsRepository {
    private static final ObjectMapper M = new ObjectMapper();
    private final Map<String, String> descriptors = new HashMap<>();
    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, byte[]> exports = new HashMap<>();
    private final Map<String, List<String>> jobs = new HashMap<>();
    private final Map<Long, String> jobById = new HashMap<>();
    private final Map<String, List<String>> perms = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    public final List<ResourceLookup> listing = new ArrayList<>();
    public final List<String> requestedUris = new ArrayList<>();
    public ServerInfo info = new ServerInfo("10.0.0", "PRO", "Enterprise", "test");

    public FakeRepository withDescriptor(String uri, String json) { descriptors.put(uri, json); return this; }
    public FakeRepository withFile(String uri, String text) { files.put(uri, text.getBytes(StandardCharsets.UTF_8)); return this; }
    public FakeRepository withFile(String uri, byte[] bytes) { files.put(uri, bytes); return this; }
    public FakeRepository withExport(String uri, byte[] zip) { exports.put(uri, zip); return this; }
    public FakeRepository withJobs(String uri, String... summaries) { jobs.put(uri, List.of(summaries)); return this; }
    public FakeRepository withJob(long id, String json) { jobById.put(id, json); return this; }
    public FakeRepository withPermissions(String uri, String... entries) { perms.put(uri, List.of(entries)); return this; }
    public FakeRepository withMissingFile(String uri) { missing.add(uri); return this; }

    @Override public ServerInfo serverInfo() { return info; }
    @Override public List<ResourceLookup> listResources(String folderUri, boolean recursive) { return listing; }
    @Override public JsonNode descriptor(String uri, String type) throws IOException {
        requestedUris.add(uri);
        String j = descriptors.get(uri);
        if (j == null) throw new JrsClientException(404, "no descriptor: " + uri);
        return M.readTree(j);
    }
    @Override public byte[] file(String uri, String accept) throws IOException {
        requestedUris.add(uri);
        if (missing.contains(uri) || !files.containsKey(uri)) throw new JrsClientException(404, "no file: " + uri);
        return files.get(uri);
    }
    @Override public byte[] exportArchive(List<String> uris) throws IOException {
        byte[] z = exports.get(uris.get(0));
        if (z == null) throw new IOException("no export for " + uris);
        return z;
    }
    @Override public List<JsonNode> jobs(String reportUri) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String s : jobs.getOrDefault(reportUri, List.of())) out.add(M.readTree(s));
        return out;
    }
    @Override public JsonNode job(long id) throws IOException {
        String j = jobById.get(id);
        if (j == null) throw new JrsClientException(404, "no job " + id);
        return M.readTree(j);
    }
    @Override public List<JsonNode> permissions(String uri) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String s : perms.getOrDefault(uri, List.of())) out.add(M.readTree(s));
        return out;
    }
}
