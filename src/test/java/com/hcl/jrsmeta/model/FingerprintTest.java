package com.hcl.jrsmeta.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FingerprintTest {
    @Test void key_isLowercaseDialectHostPortDatabase() {
        var fp = new Fingerprint("PostgreSQL", "DB.Example.com", 5432, "SalesDB", null, "jdbc:...");
        assertThat(fp.key()).isEqualTo("postgresql://db.example.com:5432/salesdb");
    }
    @Test void key_omitsPortWhenUnknown() {
        assertThat(new Fingerprint("oracle", "h", null, "orcl", null, null).key()).isEqualTo("oracle://h/orcl");
    }
    @Test void opaque_usesRawValue() {
        assertThat(Fingerprint.opaque("jndi", "jdbc/FoodMart").key()).isEqualTo("jndi://jdbc/foodmart");
    }
}
