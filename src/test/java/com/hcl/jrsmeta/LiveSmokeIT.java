package com.hcl.jrsmeta;

import com.hcl.jrsmeta.client.*;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.crawl.SyncState;
import com.hcl.jrsmeta.emit.JsonFileEmitter;
import com.hcl.jrsmeta.lineage.StitchMap;
import com.hcl.jrsmeta.model.ResourceLookup;
import com.hcl.jrsmeta.parse.ParserRegistry;
import com.hcl.jrsmeta.run.ConnectorRun;
import com.hcl.jrsmeta.run.RunReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs only when JRS_META_LIVE=1 and JRS_META_BASE_URL / JRS_META_USER / JRS_META_PASSWORD point at a reachable server.
 * Optional JRS_META_LIVE_FOLDER (default /public) limits the crawl. Read-only.
 *   mvn -q test -Dtest=LiveSmokeIT -Dsurefire.excludes=none
 */
@EnabledIfEnvironmentVariable(named = "JRS_META_LIVE", matches = "1")
class LiveSmokeIT {
    @TempDir Path tmp;

    private ConnectorConfig cfg(String folder) {
        return ConnectorConfig.builder().serverId("live").baseUrl(System.getenv("JRS_META_BASE_URL"))
                .username(System.getenv("JRS_META_USER")).password(System.getenv("JRS_META_PASSWORD").toCharArray())
                .includeFolders(List.of(folder)).rateLimitPerSecond(5).stateDir(tmp.resolve("state")).outputDir(tmp.resolve("out")).build();
    }

    @Test void serverInfoAndTopLevelListing() throws Exception {
        ConnectorConfig cfg = cfg("/");
        JrsRepository repo = new RestJrsRepository(new JrsClient(cfg, AuditLog.NOOP), cfg);
        ServerInfo info = repo.serverInfo();
        assertThat(info.version()).isNotBlank();
        List<ResourceLookup> top = repo.listResources("/", false);
        assertThat(top).isNotEmpty();
        System.out.println("live: " + info + " top-level=" + top.size());
    }

    @Test void fullCrawlOfOneFolderEmitsFiles() throws Exception {
        String folder = System.getenv().getOrDefault("JRS_META_LIVE_FOLDER", "/public");
        ConnectorConfig cfg = cfg(folder);
        JrsRepository repo = new RestJrsRepository(new JrsClient(cfg, AuditLog.NOOP), cfg);
        ConnectorRun run = new ConnectorRun(cfg, repo, ParserRegistry.defaults(), new JsonFileEmitter(cfg.outputDir()), SyncState.load(cfg.stateDir().resolve("sync-state.json")), StitchMap.empty());
        RunReport rep = run.execute(true, false);
        System.out.println(rep.summary());
        rep.warnings().forEach(w -> System.out.println("  " + w));
        assertThat(rep.processed()).isGreaterThan(0);
        assertThat(tmp.resolve("out").resolve(rep.runId()).resolve("assets.json")).exists();
        // Spec Phase 1 exit criterion: >= 95% of resources ingested.
        assertThat((double) rep.processed() / Math.max(1, rep.processed() + rep.failed())).isGreaterThanOrEqualTo(0.95);
    }
}
