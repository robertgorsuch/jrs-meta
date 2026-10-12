package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GenericResourceParserTest {
    ParseContext ctx(FakeRepository r) { return new ParseContext(r, "srv", TestConfig.base().build()); }

    @Test void folder_needsNoDescriptorFetch() throws Exception {
        FakeRepository r = new FakeRepository();
        ParseResult p = new GenericResourceParser().parse(ctx(r), new ResourceLookup("/reports", "Reports", null, "folder", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.FOLDER);
        assertThat(r.requestedUris).isEmpty();
    }

    @Test void file_capturesType() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/img/logo", "{\"uri\":\"/img/logo\",\"type\":\"img\"}");
        ParseResult p = new GenericResourceParser().parse(ctx(r), new ResourceLookup("/img/logo", "logo", null, "file", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.FILE);
        assertThat(p.assets().get(0).attributes()).containsEntry("fileType", "img");
    }

    @Test void query_emitsDatasourceRefAndSql() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/q/shared", "{\"uri\":\"/q/shared\",\"value\":\"SELECT 1\",\"language\":\"sql\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/pg\"}}}");
        ParseResult p = new GenericResourceParser().parse(ctx(r), new ResourceLookup("/q/shared", "q", null, "query", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.QUERY);
        assertThat(p.assets().get(0).attributes()).containsEntry("queryText", "SELECT 1").containsEntry("queryLanguage", "sql");
        assertThat(p.refs()).containsExactly(new Ref("/q/shared", "/ds/pg", Ref.RefKind.DATASOURCE));
    }

    @Test void inputControl_withEmbeddedQueryRefsDatasource() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/ic/region", "{\"uri\":\"/ic/region\",\"type\":4,\"query\":{\"query\":{\"value\":\"SELECT r FROM region\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/pg\"}}}}}");
        ParseResult p = new GenericResourceParser().parse(ctx(r), new ResourceLookup("/ic/region", "Region", null, "inputControl", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.INPUT_CONTROL);
        assertThat(p.assets().get(0).attributes()).containsEntry("controlType", 4);
        assertThat(p.refs()).containsExactly(new Ref("/ic/region", "/ds/pg", Ref.RefKind.DATASOURCE));
    }

    @Test void unknownType_becomesOtherWithoutFailing() throws Exception {
        FakeRepository r = new FakeRepository();   // descriptor 404s
        ParseResult p = new GenericResourceParser().parse(ctx(r), new ResourceLookup("/x/odd", "odd", null, "mysteryType", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.OTHER);
        assertThat(p.assets().get(0).attributes()).containsEntry("resourceType", "mysteryType");
    }
}
