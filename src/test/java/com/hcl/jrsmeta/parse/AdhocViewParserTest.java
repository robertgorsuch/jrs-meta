package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdhocViewParserTest {
    ParseContext ctx(FakeRepository r) { return new ParseContext(r, "srv", TestConfig.base().build()); }
    ResourceLookup lk = new ResourceLookup("/adhoc/sales", "Sales view", null, "adhocDataView", null, null, 1);

    @Test void parse_backingDomainBecomesDatasourceRef() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/adhoc/sales",
            "{\"uri\":\"/adhoc/sales\",\"dataSource\":{\"semanticLayerDataSourceReference\":{\"uri\":\"/domains/fm\"}},\"query\":{\"multiAxis\":{}}}");
        ParseResult p = new AdhocViewParser().parse(ctx(r), lk);
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.BI_VIEW);
        assertThat(p.assets().get(0).attributes()).containsEntry("dataSourceUri", "/domains/fm").containsEntry("queryPresent", true);
        assertThat(p.refs()).containsExactly(new Ref("/adhoc/sales", "/domains/fm", Ref.RefKind.DATASOURCE));
    }

    @Test void parse_noDataSourceWarns() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/adhoc/sales", "{\"uri\":\"/adhoc/sales\"}");
        ParseResult p = new AdhocViewParser().parse(ctx(r), lk);
        assertThat(p.refs()).isEmpty();
        assertThat(p.warnings()).anyMatch(w -> w.contains("no data source"));
    }
}
