package com.hcl.jrsmeta.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class MainTest {
    @TempDir Path tmp;

    @Test void crawl_withoutCredentialsExitsOneWithMessage() throws Exception {
        Path cfg = tmp.resolve("c.json");
        Files.writeString(cfg, "{\"serverId\":\"s\",\"baseUrl\":\"http://localhost:1\"}");
        StringWriter err = new StringWriter();
        CommandLine cl = new CommandLine(new Main(java.util.Map.of()));
        cl.setErr(new PrintWriter(err));
        int code = cl.execute("crawl", "--config", cfg.toString());
        assertThat(code).isEqualTo(1);
        assertThat(err.toString()).contains("JRS_META_USER");
    }

    @Test void help_listsCommands() {
        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(new Main(java.util.Map.of()));
        cl.setOut(new PrintWriter(out));
        assertThat(cl.execute("--help")).isZero();
        assertThat(out.toString()).contains("crawl").contains("probe");
    }
}
