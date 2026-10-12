package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.Fingerprint;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcUrlParserTest {
    @Test void postgres_withPortAndParams() {
        Fingerprint f = JdbcUrlParser.parse("jdbc:postgresql://DB.example.com:5433/SalesDB?ssl=true", "org.postgresql.Driver");
        assertThat(f.dialect()).isEqualTo("postgresql"); assertThat(f.host()).isEqualTo("DB.example.com");
        assertThat(f.port()).isEqualTo(5433); assertThat(f.database()).isEqualTo("SalesDB");
        assertThat(f.key()).isEqualTo("postgresql://db.example.com:5433/salesdb");
    }
    @Test void mysql_defaultPort() {
        assertThat(JdbcUrlParser.parse("jdbc:mysql://h/foodmart", null).key()).isEqualTo("mysql://h:3306/foodmart");
    }
    @Test void sqlserver_databaseNameProperty() {
        Fingerprint f = JdbcUrlParser.parse("jdbc:sqlserver://sql01\\inst:1433;databaseName=Reporting;encrypt=true", null);
        assertThat(f.key()).isEqualTo("sqlserver://sql01:1433/reporting");
    }
    @Test void oracle_thinServiceAndSid() {
        assertThat(JdbcUrlParser.parse("jdbc:oracle:thin:@//ora.example.com:1521/ORCLPDB", null).key()).isEqualTo("oracle://ora.example.com:1521/orclpdb");
        assertThat(JdbcUrlParser.parse("jdbc:oracle:thin:@ora:1521:ORCL", null).key()).isEqualTo("oracle://ora:1521/orcl");
    }
    @Test void snowflake_dbAndSchemaParams() {
        Fingerprint f = JdbcUrlParser.parse("jdbc:snowflake://acct.snowflakecomputing.com/?db=ANALYTICS&schema=PUBLIC&warehouse=WH", null);
        assertThat(f.key()).isEqualTo("snowflake://acct.snowflakecomputing.com:443/analytics");
        assertThat(f.schema()).isEqualTo("PUBLIC");
    }
    @Test void redshift() {
        assertThat(JdbcUrlParser.parse("jdbc:redshift://c.abc.us-east-1.redshift.amazonaws.com:5439/dev", null).key())
                .isEqualTo("redshift://c.abc.us-east-1.redshift.amazonaws.com:5439/dev");
    }
    @Test void unknownSubprotocol_isOpaqueButKeyed() {
        Fingerprint f = JdbcUrlParser.parse("jdbc:weirddb:thing=1", "com.weird.Driver");
        assertThat(f.dialect()).isEqualTo("weirddb"); assertThat(f.host()).isNull();
        assertThat(f.key()).startsWith("weirddb://");
    }
    @Test void missingUrl_fallsBackToDriver() {
        assertThat(JdbcUrlParser.parse(null, "org.postgresql.Driver").dialect()).isEqualTo("postgresql");
    }
    @Test void sanitize_stripsCredentials() {
        assertThat(JdbcUrlParser.sanitize("jdbc:postgresql://h/db?user=bob&password=hunter2&ssl=true")).isEqualTo("jdbc:postgresql://h/db?user=***&password=***&ssl=true");
        assertThat(JdbcUrlParser.sanitize("jdbc:mysql://bob:hunter2@h/db")).isEqualTo("jdbc:mysql://***@h/db");
        assertThat(JdbcUrlParser.sanitize("jdbc:sqlserver://h;user=bob;password=x;databaseName=d")).doesNotContain("hunter2").doesNotContain("password=x");
        Fingerprint f = JdbcUrlParser.parse("jdbc:postgresql://h/db?user=bob&password=hunter2", null);
        assertThat(f.raw()).doesNotContain("hunter2");
        assertThat(f.key()).isEqualTo("postgresql://h:5432/db");
    }
}
