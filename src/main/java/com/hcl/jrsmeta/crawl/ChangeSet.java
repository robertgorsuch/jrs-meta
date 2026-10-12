package com.hcl.jrsmeta.crawl;

import com.hcl.jrsmeta.model.ResourceLookup;
import java.util.List;

/**
 * forcedFull: previous run never completed and no earlier run ever completed, so everything is reprocessed.
 * resumed: previous run never completed; only resources checkpointed since the last completed run (plus changes) are reprocessed.
 */
public record ChangeSet(List<ResourceLookup> toProcess, List<String> deletedUris, int unchanged, boolean forcedFull, boolean resumed) {}
