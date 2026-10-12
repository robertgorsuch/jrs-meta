package com.hcl.jrsmeta.crawl;

import com.hcl.jrsmeta.model.ResourceLookup;
import java.util.List;

public record ChangeSet(List<ResourceLookup> toProcess, List<String> deletedUris, int unchanged, boolean forcedFull) {}
