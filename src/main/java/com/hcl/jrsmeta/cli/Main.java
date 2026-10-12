package com.hcl.jrsmeta.cli;

import com.hcl.jrsmeta.client.*;
import com.hcl.jrsmeta.config.ConfigLoader;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.crawl.SyncState;
import com.hcl.jrsmeta.emit.JsonFileEmitter;
import com.hcl.jrsmeta.lineage.StitchMap;
import com.hcl.jrsmeta.model.ResourceLookup;
import com.hcl.jrsmeta.parse.ParserRegistry;
import com.hcl.jrsmeta.run.ConnectorRun;
import com.hcl.jrsmeta.run.RunReport;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "jrs-meta", mixinStandardHelpOptions = true, version = "jrs-meta-connector 0.1.0",
        description = "Read-only Jaspersoft metadata & lineage connector (REST v2).",
        subcommands = {Main.Crawl.class, Main.Probe.class})
public final class Main {
    final Map<String, String> env;

    public Main() { this(System.getenv()); }
    Main(Map<String, String> env) { this.env = env; }

    public static void main(String[] args) { System.exit(new CommandLine(new Main()).execute(args)); }

    ConnectorConfig load(Path config) throws Exception { return ConfigLoader.load(config, env); }

    @Command(name = "crawl", description = "Crawl the repository and emit assets + lineage.")
    static final class Crawl implements Callable<Integer> {
        @ParentCommand Main parent;
        @Spec CommandSpec spec;                       // picocli's out/err writers, so tests can capture output
        @Option(names = "--config", required = true, description = "connector JSON config") Path config;
        @Option(names = "--full", description = "ignore sync state and process everything") boolean full;
        @Option(names = "--dry-run", description = "plan only: list counts, fetch no descriptors, emit nothing") boolean dryRun;

        @Override public Integer call() throws Exception {
            PrintWriter out = spec.commandLine().getOut(), err = spec.commandLine().getErr();
            ConnectorConfig cfg;
            try { cfg = parent.load(config); }
            catch (IllegalArgumentException e) { err.println(e.getMessage()); err.flush(); return 1; }
            Path audit = cfg.stateDir().resolve("audit-" + System.currentTimeMillis() + ".jsonl");
            try (AuditLog log = new JsonlAuditLog(audit, cfg.username())) {
                JrsRepository repo = new RestJrsRepository(new JrsClient(cfg, log), cfg);
                SyncState state = SyncState.load(cfg.stateDir().resolve("sync-state.json"));
                ConnectorRun run = new ConnectorRun(cfg, repo, ParserRegistry.defaults(), new JsonFileEmitter(cfg.outputDir()), state, StitchMap.load(cfg.stitchMapFile()));
                RunReport rep = run.execute(full, dryRun);
                out.println(rep.summary());
                rep.warnings().stream().limit(50).forEach(w -> out.println("  warning: " + w));
                if (rep.warnings().size() > 50) out.println("  ... " + (rep.warnings().size() - 50) + " more warnings in run-report.json");
                out.flush();
                return rep.failed() > 0 ? 2 : 0;
            } catch (Exception e) {
                err.println("crawl failed: " + e.getMessage()); err.flush();
                return 1;
            }
        }
    }

    @Command(name = "probe", description = "Check connectivity, edition and top-level folders without crawling.")
    static final class Probe implements Callable<Integer> {
        @ParentCommand Main parent;
        @Spec CommandSpec spec;
        @Option(names = "--config", required = true) Path config;

        @Override public Integer call() throws Exception {
            PrintWriter out = spec.commandLine().getOut(), err = spec.commandLine().getErr();
            ConnectorConfig cfg;
            try { cfg = parent.load(config); }
            catch (IllegalArgumentException e) { err.println(e.getMessage()); err.flush(); return 1; }
            try {
                JrsRepository repo = new RestJrsRepository(new JrsClient(cfg, AuditLog.NOOP), cfg);
                ServerInfo info = repo.serverInfo();
                out.printf("server: %s  version: %s  edition: %s (%s)%n", cfg.baseUrl(), info.version(), info.edition(), info.editionName());
                out.println("capabilities: reports+datasources=yes, domains/dashboards/adhoc=" + (info.isPro() ? "yes" : "no (CE)"));
                List<ResourceLookup> top = repo.listResources("/", false);
                out.println("top-level resources: " + top.size());
                top.forEach(l -> out.printf("  %-24s %s%n", l.resourceType(), l.uri()));
                out.flush();
                return 0;
            } catch (Exception e) {
                err.println("probe failed: " + e.getMessage()); err.flush();
                return 1;
            }
        }
    }
}
