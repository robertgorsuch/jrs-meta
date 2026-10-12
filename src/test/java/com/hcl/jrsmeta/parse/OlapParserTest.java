package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OlapParserTest {
    ParseContext ctx(FakeRepository r) { return new ParseContext(r, "srv", TestConfig.base().build()); }

    @Test void schema_fileProducesCubeChildren() throws Exception {
        FakeRepository r = new FakeRepository().withFile("/analysis/fm_schema",
            "<Schema name=\"FM\"><Cube name=\"Sales\"><Table name=\"sales_fact\"/><Measure name=\"Units\" column=\"unit_sales\" aggregator=\"sum\"/></Cube></Schema>");
        ParseResult p = new OlapParser().parse(ctx(r), new ResourceLookup("/analysis/fm_schema", "FM", null, "olapMondrianSchema", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.OLAP_SCHEMA);
        assertThat(p.assets()).filteredOn(a -> a.type() == AssetType.OLAP_CUBE).hasSize(1);
        Asset cube = p.assets().get(1);
        assertThat(cube.id()).isEqualTo("srv|/analysis/fm_schema#cube:Sales");
        assertThat(cube.attributes()).containsEntry("factTable", "sales_fact");
        assertThat(cube.attributes().toString()).contains("unit_sales");
    }

    @Test void connection_refsDatasourceAndSchema() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/analysis/conn",
            "{\"uri\":\"/analysis/conn\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/fm\"}},\"schema\":{\"schemaReference\":{\"uri\":\"/analysis/fm_schema\"}}}");
        ParseResult p = new OlapParser().parse(ctx(r), new ResourceLookup("/analysis/conn", "c", null, "secureMondrianConnection", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.OLAP_CONNECTION);
        assertThat(p.refs()).containsExactlyInAnyOrder(new Ref("/analysis/conn", "/ds/fm", Ref.RefKind.DATASOURCE), new Ref("/analysis/conn", "/analysis/fm_schema", Ref.RefKind.OLAP_SCHEMA));
    }

    @Test void olapUnit_refsConnectionAndKeepsMdx() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/analysis/view",
            "{\"uri\":\"/analysis/view\",\"mdxQuery\":\"select {[Measures].[Units]} on columns from Sales\",\"olapConnection\":{\"olapConnectionReference\":{\"uri\":\"/analysis/conn\"}}}");
        ParseResult p = new OlapParser().parse(ctx(r), new ResourceLookup("/analysis/view", "v", null, "olapUnit", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.OLAP_VIEW);
        assertThat(p.assets().get(0).attributes()).containsEntry("queryLanguage", "mdx").containsEntry("mdxQuery", "select {[Measures].[Units]} on columns from Sales");
        assertThat(p.refs()).containsExactly(new Ref("/analysis/view", "/analysis/conn", Ref.RefKind.OLAP_CONNECTION));
    }
}
