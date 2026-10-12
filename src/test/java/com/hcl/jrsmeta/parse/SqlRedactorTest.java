package com.hcl.jrsmeta.parse;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SqlRedactorTest {
    @Test void redact_replacesStringAndNumericLiteralsOnly() {
        String sql = "SELECT t1.col_2, 'O''Brien' AS n FROM sales_fact t1 WHERE amount > 100.5 AND region = 'WEST'";
        assertThat(SqlRedactor.redact(sql)).isEqualTo("SELECT t1.col_2, ? AS n FROM sales_fact t1 WHERE amount > ? AND region = ?");
    }
    @Test void redact_nullSafe() { assertThat(SqlRedactor.redact(null)).isNull(); }
}
