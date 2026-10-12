package com.hcl.jrsmeta.testutil;

import com.hcl.jrsmeta.config.ConnectorConfig;

public final class TestConfig {
    private TestConfig() {}
    /** Builder with no rate limit and no retries so unit tests stay fast and deterministic. */
    public static ConnectorConfig.Builder base() {
        return ConnectorConfig.builder().serverId("test").baseUrl("http://localhost:0")
                .username("u").password("p".toCharArray()).rateLimitPerSecond(0).maxRetries(0)
                .exportPollSeconds(0).exportTimeoutSeconds(1);
    }
}
