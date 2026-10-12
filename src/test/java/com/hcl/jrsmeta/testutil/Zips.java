package com.hcl.jrsmeta.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Zips {
    private Zips() {}
    public static byte[] of(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); ZipOutputStream z = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); }
            z.finish();
            return bos.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
