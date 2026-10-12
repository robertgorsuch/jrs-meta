package com.hcl.jrsmeta.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir Path tmp;

    private Path write(String json) throws Exception {
        Path p = tmp.resolve("c.json"); Files.writeString(p, json); return p;
    }

    @Test void load_readsFileAndEnvCredentials() throws Exception {
        Path p = write("""
            {"serverId":"prod","baseUrl":"https://jrs.example.com/jasperserver-pro/",
             "includeFolders":["/reports","/public"],"excludeFolders":["/public/Samples"],
             "pageSize":50,"rateLimitPerSecond":2.5,"deepParseDashboards":false}
            """);
        ConnectorConfig c = ConfigLoader.load(p, Map.of("JRS_META_USER", "svc", "JRS_META_PASSWORD", "s3cret"));
        assertThat(c.serverId()).isEqualTo("prod");
        assertThat(c.baseUrl()).isEqualTo("https://jrs.example.com/jasperserver-pro"); // trailing slash stripped
        assertThat(c.username()).isEqualTo("svc");
        assertThat(new String(c.password())).isEqualTo("s3cret");
        assertThat(c.includeFolders()).containsExactly("/reports", "/public");
        assertThat(c.excludeFolders()).containsExactly("/public/Samples");
        assertThat(c.pageSize()).isEqualTo(50);
        assertThat(c.rateLimitPerSecond()).isEqualTo(2.5);
        assertThat(c.deepParseDashboards()).isFalse();
        assertThat(c.fetchOperationalMetadata()).isTrue();      // default
        assertThat(c.maxRetries()).isEqualTo(4);                 // default
        assertThat(c.stateDir()).isEqualTo(Path.of("state"));    // default
    }

    @Test void load_envBaseUrlOverridesFile() throws Exception {
        Path p = write("{\"serverId\":\"x\",\"baseUrl\":\"http://file\"}");
        ConnectorConfig c = ConfigLoader.load(p, Map.of("JRS_META_USER", "u", "JRS_META_PASSWORD", "p", "JRS_META_BASE_URL", "http://env"));
        assertThat(c.baseUrl()).isEqualTo("http://env");
    }

    @Test void load_failsListingAllMissingFields() throws Exception {
        Path p = write("{}");
        assertThatThrownBy(() -> ConfigLoader.load(p, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("serverId").hasMessageContaining("baseUrl")
            .hasMessageContaining("JRS_META_USER").hasMessageContaining("JRS_META_PASSWORD");
    }

    @Test void load_rejectsPasswordInFile() throws Exception {
        Path p = write("{\"serverId\":\"x\",\"baseUrl\":\"http://h\",\"password\":\"nope\"}");
        assertThatThrownBy(() -> ConfigLoader.load(p, Map.of("JRS_META_USER", "u", "JRS_META_PASSWORD", "p")))
            .hasMessageContaining("password");
    }

    @Test void toString_neverContainsPassword() {
        ConnectorConfig c = ConnectorConfig.builder().serverId("s").baseUrl("http://h").username("u").password("hunter2".toCharArray()).build();
        assertThat(c.toString()).doesNotContain("hunter2");
    }

    @Test void builder_defaultIncludeIsRoot() {
        ConnectorConfig c = ConnectorConfig.builder().serverId("s").baseUrl("http://h").username("u").password("p".toCharArray()).build();
        assertThat(c.includeFolders()).isEqualTo(List.of("/"));
    }
}
