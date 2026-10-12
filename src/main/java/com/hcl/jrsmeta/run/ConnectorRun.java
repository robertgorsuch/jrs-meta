package com.hcl.jrsmeta.run;

import com.hcl.jrsmeta.client.JrsRepository;
import com.hcl.jrsmeta.client.ServerInfo;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.crawl.*;
import com.hcl.jrsmeta.emit.EmitBatch;
import com.hcl.jrsmeta.emit.Emitter;
import com.hcl.jrsmeta.enrich.OperationalMetadataEnricher;
import com.hcl.jrsmeta.lineage.*;
import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.parse.ParseContext;
import com.hcl.jrsmeta.parse.ParserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Spec §8: crawl -> parse -> enrich -> lineage -> stitch -> emit, resumable and read-only. */
public final class ConnectorRun {
    private static final Logger log = LoggerFactory.getLogger(ConnectorRun.class);
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ConnectorConfig cfg;
    private final JrsRepository repo;
    private final ParserRegistry parsers;
    private final Emitter emitter;
    private final SyncState state;
    private final Stitcher stitcher;

    public ConnectorRun(ConnectorConfig cfg, JrsRepository repo, ParserRegistry parsers, Emitter emitter, SyncState state, StitchMap stitchMap) {
        this.cfg = cfg; this.repo = repo; this.parsers = parsers; this.emitter = emitter; this.state = state; this.stitcher = new Stitcher(stitchMap);
    }

    public RunReport execute(boolean full, boolean dryRun) throws IOException {
        Instant started = Instant.now();
        String runId = RUN_ID.format(started);
        List<String> warnings = new ArrayList<>();

        ServerInfo info = repo.serverInfo();
        log.info("JRS {} edition={} ({})", info.version(), info.edition(), info.editionName());
        if (!info.isPro()) warnings.add("Community Edition detected: Domains, dashboards and ad hoc views are not available on this server");

        FolderFilter filter = new FolderFilter(cfg.includeFolders(), cfg.excludeFolders());
        List<ResourceLookup> inventory = new ArrayList<>();
        for (String folder : cfg.includeFolders()) inventory.addAll(repo.listResources(folder, true));
        inventory = inventory.stream().filter(l -> filter.accepts(l.uri())).toList();
        Set<String> knownUris = inventory.stream().map(ResourceLookup::uri).collect(Collectors.toSet());

        ChangeSet plan = new Crawler().plan(inventory, state, full);
        String mode = full || plan.forcedFull() ? "full" : "incremental";
        if (plan.forcedFull()) warnings.add("previous run did not complete and no run ever completed; forcing a full crawl");
        if (plan.resumed()) warnings.add("previous run did not complete; resuming by re-emitting resources checkpointed since the last completed run");
        log.info("{} crawl: {} to process, {} unchanged, {} deleted", mode, plan.toProcess().size(), plan.unchanged(), plan.deletedUris().size());
        if (dryRun) return new RunReport(runId, mode + " (dry-run)", info.edition(), inventory.size(), plan.toProcess().size(), 0, plan.unchanged(), plan.deletedUris().size(), 0, 0, 0, 0, warnings);

        state.beginRun(); state.save();
        ParseContext ctx = new ParseContext(repo, cfg.serverId(), cfg);
        List<Asset> assets = new ArrayList<>(); List<Ref> refs = new ArrayList<>();
        List<String> deletedIds = new ArrayList<>();
        for (String u : plan.deletedUris()) {                       // a deleted parent takes its children with it
            deletedIds.add(Asset.idOf(cfg.serverId(), u));
            deletedIds.addAll(state.childIds(u));
        }
        int processed = 0, failed = 0, sinceCheckpoint = 0;
        for (ResourceLookup l : plan.toProcess()) {
            try {
                ParseResult r = parsers.forType(l.resourceType()).parse(ctx, l);
                assets.addAll(r.assets()); refs.addAll(r.refs()); warnings.addAll(r.warnings());
                List<String> childIds = r.assets().stream().filter(a -> a.parentId() != null).map(Asset::id).toList();
                for (String old : state.childIds(l.uri())) if (!childIds.contains(old)) deletedIds.add(old);   // dropped field/tile/item
                state.markProcessed(l, childIds); processed++;
            } catch (Exception e) {
                failed++;
                warnings.add("FAILED " + l.uri() + " (" + l.resourceType() + "): " + e.getMessage());
                log.warn("failed to process {} ({}): {}", l.uri(), l.resourceType(), e.toString());
            }
            if (++sinceCheckpoint >= cfg.checkpointEvery()) { state.save(); sinceCheckpoint = 0; }
        }
        for (String u : plan.deletedUris()) state.remove(u);

        if (cfg.fetchOperationalMetadata()) assets = new OperationalMetadataEnricher().enrich(ctx, assets, warnings);

        LineageBuilder.LineageResult lineage = new LineageBuilder().build(cfg.serverId(), refs, knownUris);
        warnings.addAll(lineage.warnings());
        Stitcher.StitchResult stitch = stitcher.stitch(cfg.serverId(), assets);
        List<Asset> allAssets = new ArrayList<>(assets); allAssets.addAll(stitch.shadowAssets());
        List<LineageEdge> allEdges = new ArrayList<>(lineage.edges()); allEdges.addAll(stitch.edges());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("inventory", inventory.size()); stats.put("processed", processed); stats.put("failed", failed); stats.put("unchanged", plan.unchanged());
        stats.put("deleted", deletedIds.size()); stats.put("assets", allAssets.size()); stats.put("edges", allEdges.size());
        stats.put("stitched", stitch.matched()); stats.put("shadowed", stitch.shadowed()); stats.put("serverVersion", info.version()); stats.put("edition", info.edition());
        emitter.emit(new EmitBatch(cfg.serverId(), runId, started.toString(), mode, allAssets, allEdges, deletedIds, warnings, stats));

        state.completeRun(); state.save();
        return new RunReport(runId, mode, info.edition(), inventory.size(), processed, failed, plan.unchanged(), deletedIds.size(),
                allAssets.size(), allEdges.size(), stitch.matched(), stitch.shadowed(), warnings);
    }
}
