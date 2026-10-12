package com.hcl.jrsmeta.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonlAuditLog implements AuditLog {
    private final BufferedWriter out;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String serviceAccount;

    public JsonlAuditLog(Path file, String serviceAccount) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.serviceAccount = serviceAccount;
    }

    @Override public synchronized void record(String method, String path, int status, long millis) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", Instant.now().toString()); e.put("account", serviceAccount);
        e.put("method", method); e.put("path", path); e.put("status", status); e.put("ms", millis);
        try { out.write(mapper.writeValueAsString(e)); out.newLine(); out.flush(); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    @Override public synchronized void close() throws IOException { out.close(); }
}
