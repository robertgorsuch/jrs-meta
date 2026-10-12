package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class DomainParserTest {
    static final String SCHEMA = """
        <schema xmlns="http://www.jaspersoft.com/2007/SL/XMLSchema" version="1.3">
          <items><item id="store_name" label="Store" resourceId="store.store_name"/><item id="lost" label="Lost" resourceId="x.y"/></items>
          <resources><jdbcTable id="store" datasourceId="dsFoodMart" schemaAlias="public" datasourceTableName="store">
            <fieldList><field id="store_name" fieldDBName="store_name" type="java.lang.String"/></fieldList></jdbcTable></resources>
        </schema>
        """;
    ResourceLookup lk = new ResourceLookup("/domains/fm", "FoodMart", null, "semanticLayerDataSource", null, "2024-01-01T00:00:00", 1);
    ParseContext ctx(FakeRepository r) { return new ParseContext(r, "srv", TestConfig.base().build()); }

    @Test void parse_referencedSchemaProducesModelAndLogicalFields() throws Exception {
        FakeRepository r = new FakeRepository()
            .withDescriptor("/domains/fm", "{\"uri\":\"/domains/fm\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/fm\"}},\"schema\":{\"schemaReference\":{\"uri\":\"/domains/fm_files/schema.xml\"}}}")
            .withFile("/domains/fm_files/schema.xml", SCHEMA);
        ParseResult p = new DomainParser().parse(ctx(r), lk);
        Asset model = p.assets().get(0);
        assertThat(model.type()).isEqualTo(AssetType.SEMANTIC_MODEL);
        assertThat(model.attributes()).containsEntry("dataSourceUri", "/ds/fm").containsEntry("schemaUri", "/domains/fm_files/schema.xml").containsEntry("itemCount", 2);
        assertThat(p.refs()).containsExactly(new Ref("/domains/fm", "/ds/fm", Ref.RefKind.DATASOURCE));
        assertThat(p.assets()).filteredOn(a -> a.type() == AssetType.LOGICAL_FIELD).hasSize(2);
        Asset f = p.assets().stream().filter(a -> "srv|/domains/fm#item:store_name".equals(a.id())).findFirst().orElseThrow();
        assertThat(f.attributes()).containsEntry("physicalTable", "store").containsEntry("physicalColumn", "store_name").containsEntry("physicalSchema", "public").containsEntry("mappingConfidence", "EXACT");
        Asset lost = p.assets().stream().filter(a -> a.id().endsWith("#item:lost")).findFirst().orElseThrow();
        assertThat(lost.attributes()).containsEntry("mappingConfidence", "INFERRED");
        assertThat(p.warnings()).anyMatch(w -> w.contains("x.y"));
    }

    @Test void parse_inlineSchemaFile() throws Exception {
        String d = "{\"uri\":\"/domains/fm\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/fm\"}},\"schema\":{\"schemaFile\":{\"type\":\"xml\",\"content\":\""
                + Base64.getEncoder().encodeToString(SCHEMA.getBytes()) + "\"}}}";
        ParseResult p = new DomainParser().parse(ctx(new FakeRepository().withDescriptor("/domains/fm", d)), lk);
        assertThat(p.assets().get(0).attributes()).containsEntry("schemaSource", "inline");
        assertThat(p.assets()).hasSize(3);
    }

    @Test void parse_missingSchemaStillEmitsModel() throws Exception {
        String d = "{\"uri\":\"/domains/fm\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/fm\"}},\"schema\":{\"schemaReference\":{\"uri\":\"/domains/fm_files/schema.xml\"}}}";
        ParseResult p = new DomainParser().parse(ctx(new FakeRepository().withDescriptor("/domains/fm", d)), lk);
        assertThat(p.assets()).hasSize(1);
        assertThat(p.warnings()).anyMatch(w -> w.contains("schema.xml"));
    }
}
