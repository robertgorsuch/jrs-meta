package com.hcl.jrsmeta.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** In-memory view of a JRS export zip (spec §5.2 export mode). */
public final class ExportArchive {
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private final Map<String, byte[]> entries;

    private ExportArchive(Map<String, byte[]> entries) { this.entries = entries; }

    public static ExportArchive read(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] data = z.readAllBytes();
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new IOException("export archive exceeds " + MAX_TOTAL_BYTES + " bytes uncompressed");
                out.put(e.getName().replace('\\', '/'), data);
            }
        }
        if (out.isEmpty()) throw new IOException("not a zip archive or archive is empty");
        return new ExportArchive(out);
    }

    public Set<String> entryNames() { return Collections.unmodifiableSet(entries.keySet()); }

    public Optional<byte[]> findBySuffix(String suffix) {
        return entries.entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).map(Map.Entry::getValue).findFirst();
    }
}
