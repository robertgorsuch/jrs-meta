package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DataSourceParserTest {
    ParseContext ctx(FakeRepository r) { return new ParseContext(r, "srv", TestConfig.base().build()); }
    ResourceLookup lk(String uri, String type) { return new ResourceLookup(uri, "L", null, type, null, null, 1); }

    @Test void jdbc_fingerprintAndNoPassword() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/ds/pg", """
            {"uri":"/ds/pg","label":"PG","driverClass":"org.postgresql.Driver","username":"svc","password":"S3CRET",
             "connectionUrl":"jdbc:postgresql://db.example.com:5432/salesdb?password=S3CRET","timezone":"UTC"}""");
        ParseResult p = new DataSourceParser().parse(ctx(r), lk("/ds/pg", "jdbcDataSource"));
        Asset a = p.assets().get(0);
        assertThat(a.type()).isEqualTo(AssetType.BI_DATASOURCE);
        assertThat(a.attributes()).containsEntry("fingerprint", "postgresql://db.example.com:5432/salesdb").containsEntry("dbUser", "svc").containsEntry("dsType", "jdbcDataSource");
        assertThat(a.attributes().toString()).doesNotContain("S3CRET");
        assertThat(a.attributes()).doesNotContainKey("password");
    }

    @Test void jndi_isOpaqueFingerprint() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/ds/j", "{\"uri\":\"/ds/j\",\"jndiName\":\"jdbc/FoodMart\"}");
        Asset a = new DataSourceParser().parse(ctx(r), lk("/ds/j", "jndiJdbcDataSource")).assets().get(0);
        assertThat(a.attributes()).containsEntry("fingerprint", "jndi://jdbc/foodmart").containsEntry("jndiName", "jdbc/FoodMart");
    }

    @Test void virtual_expandsSubDataSourcesAsRefs() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/ds/v", "{\"uri\":\"/ds/v\",\"subDataSources\":[{\"id\":\"a\",\"uri\":\"/ds/pg\"},{\"id\":\"b\",\"uri\":\"/ds/j\"}]}");
        ParseResult p = new DataSourceParser().parse(ctx(r), lk("/ds/v", "virtualDataSource"));
        assertThat(p.refs()).containsExactly(new Ref("/ds/v", "/ds/pg", Ref.RefKind.SUB_DATASOURCE), new Ref("/ds/v", "/ds/j", Ref.RefKind.SUB_DATASOURCE));
        assertThat(p.assets().get(0).attributes()).containsEntry("subDataSourceUris", List.of("/ds/pg", "/ds/j")).doesNotContainKey("fingerprint");
    }

    @Test void aws_withoutUrlUsesInstanceIdentifier() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/ds/aws", """
            {"uri":"/ds/aws","driverClass":"org.postgresql.Driver","accessKey":"AKIA","secretKey":"shh","region":"us-east-1",
             "dbName":"prod","dbInstanceIdentifier":"rds-prod-1","dbService":"rds","username":"u"}""");
        Asset a = new DataSourceParser().parse(ctx(r), lk("/ds/aws", "awsDataSource")).assets().get(0);
        assertThat(a.attributes()).containsEntry("fingerprint", "aws://rds/us-east-1/rds-prod-1").containsEntry("region", "us-east-1");
        assertThat(a.attributes().toString()).doesNotContain("shh").doesNotContain("AKIA");
    }

    @Test void custom_redactsSecretLookingProperties() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/ds/c", """
            {"uri":"/ds/c","serviceClass":"com.acme.MongoDS","properties":[{"key":"host","value":"m1"},{"key":"apiKey","value":"zzz"},{"key":"password","value":"ppp"}]}""");
        Asset a = new DataSourceParser().parse(ctx(r), lk("/ds/c", "customDataSource")).assets().get(0);
        assertThat(a.attributes()).containsEntry("fingerprint", "custom://com.acme.mongods");
        assertThat(a.attributes().toString()).contains("host").contains("m1").doesNotContain("zzz").doesNotContain("ppp");
    }
}
