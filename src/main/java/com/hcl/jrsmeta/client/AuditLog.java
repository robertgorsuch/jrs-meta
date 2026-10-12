package com.hcl.jrsmeta.client;

import java.io.Closeable;
import java.io.IOException;

/** Spec §13: audit trail of every read. Implementations must never receive credentials. */
public interface AuditLog extends Closeable {
    void record(String method, String path, int status, long millis);
    @Override default void close() throws IOException {}
    AuditLog NOOP = (m, p, s, ms) -> {};
}
