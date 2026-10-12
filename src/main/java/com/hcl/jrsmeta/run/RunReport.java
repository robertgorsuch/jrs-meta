package com.hcl.jrsmeta.run;

import java.util.List;

public record RunReport(String runId, String mode, String edition, int inventory, int processed, int failed, int unchanged, int deleted,
                        int assets, int edges, int stitched, int shadowed, List<String> warnings) {
    public String summary() {
        return String.format("run %s (%s, edition %s): inventory=%d processed=%d failed=%d unchanged=%d deleted=%d assets=%d edges=%d stitched=%d shadowed=%d warnings=%d",
                runId, mode, edition, inventory, processed, failed, unchanged, deleted, assets, edges, stitched, shadowed, warnings.size());
    }
}
