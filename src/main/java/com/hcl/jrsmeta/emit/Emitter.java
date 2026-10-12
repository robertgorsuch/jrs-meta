package com.hcl.jrsmeta.emit;

import java.io.IOException;

/** Spec §8.1 "Emitter": maps to the catalog's ingestion format. Phase 1 ships the JSON file implementation. */
public interface Emitter {
    void emit(EmitBatch batch) throws IOException;
}
