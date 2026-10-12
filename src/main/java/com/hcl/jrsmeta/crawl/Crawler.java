package com.hcl.jrsmeta.crawl;

import com.hcl.jrsmeta.model.ResourceLookup;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Turns a live inventory + previous state into the set of resources to (re)process. */
public final class Crawler {
    public ChangeSet plan(List<ResourceLookup> inventory, SyncState state, boolean full) {
        boolean interrupted = !full && !state.lastRunOk();          // previous run died between checkpoint and emit
        String lastCompleted = state.lastCompletedRun();
        boolean forced = interrupted && lastCompleted == null;      // nothing was ever emitted: restart from scratch
        boolean resumed = interrupted && lastCompleted != null;     // re-emit only what was checkpointed after the last good emit
        boolean effectiveFull = full || forced;
        Set<String> seen = new HashSet<>();
        List<ResourceLookup> todo = new ArrayList<>();
        int unchanged = 0;
        for (ResourceLookup l : inventory) {
            if (!seen.add(l.uri())) continue;            // listing of overlapping include folders
            boolean process = effectiveFull || state.isChanged(l) || (resumed && state.processedAfter(l.uri(), lastCompleted));
            if (process) todo.add(l); else unchanged++;
        }
        List<String> deleted = state.knownUris().stream().filter(u -> !seen.contains(u)).toList();
        return new ChangeSet(todo, deleted, unchanged, forced, resumed);
    }
}
