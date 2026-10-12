package com.hcl.jrsmeta;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest {
    @Test
    void toolchainRunsJava17Code() {
        String s = """
            text block""";          // Java 15+ feature guards the release level
        assertThat(s).isEqualTo("text block");
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(17);
    }
}
