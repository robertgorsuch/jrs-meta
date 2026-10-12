package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import com.hcl.jrsmeta.testutil.Zips;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DashboardParserTest {
    static final String COMPONENTS = """
        [{"type":"dashboardProperties","id":"dashboardProperties","canvasBackgroundColor":"#fff"},
         {"type":"reportUnit","id":"Sales_Report","name":"Sales Report","label":"Sales Report","resource":"/reports/sales","dataSourceUri":"/ds/pg","showTitleBar":true},
         {"type":"adhocDataView","id":"Trend","name":"Trend","resource":"/adhoc/trend"},
         {"type":"text","id":"Title","label":"Text","text":"Executive view"},
         {"type":"image","id":"Logo","label":"Image","url":"repo:/img/logo"}]
        """;
    static final String LAYOUT = """
        <div data-componentId='Sales_Report' data-x='0' data-y='0' data-width='20' data-height='10'></div>
        <div data-width='20' data-height='10' data-componentId="Trend" data-x="20" data-y="0"></div>
        <div data-componentId='Title' data-x='0' data-y='10' data-width='40' data-height='2'></div>
        """;
    static final String WIRING = """
        [{"producer":"dashboardProperties:@init","consumers":["Sales_Report:@refresh","Trend:@refresh"]},
         {"producer":"Sales_Report:Region","consumers":["Trend:Region"]}]
        """;
    ResourceLookup lk = new ResourceLookup("/dashboards/exec", "Exec", null, "dashboard", null, "2024-01-01T00:00:00", 2);

    private byte[] zip() {
        return Zips.of(Map.of("index.xml", "<index/>".getBytes(), "resources/dashboards/exec.xml", "<dashboardModelResource/>".getBytes(),
                "resources/dashboards/exec_files/components.data", COMPONENTS.getBytes(),
                "resources/dashboards/exec_files/layout", LAYOUT.getBytes(),
                "resources/dashboards/exec_files/wiring.data", WIRING.getBytes()));
    }

    @Test void parse_exportModeProducesTilesAndDashletRefs() throws Exception {
        FakeRepository r = new FakeRepository().withExport("/dashboards/exec", zip());
        ParseResult p = new DashboardParser().parse(new ParseContext(r, "srv", TestConfig.base().build()), lk);
        Asset dash = p.assets().get(0);
        assertThat(dash.type()).isEqualTo(AssetType.BI_DASHBOARD);
        assertThat(dash.attributes()).containsEntry("parseMode", "export").containsEntry("tileCount", 4);
        assertThat(p.assets()).filteredOn(a -> a.type() == AssetType.DASHBOARD_TILE).extracting(Asset::id)
                .containsExactly("srv|/dashboards/exec#tile:Sales_Report", "srv|/dashboards/exec#tile:Trend", "srv|/dashboards/exec#tile:Title", "srv|/dashboards/exec#tile:Logo");
        Asset sales = p.assets().get(1);
        assertThat(sales.attributes()).containsEntry("tileType", "reportUnit").containsEntry("resourceUri", "/reports/sales").containsEntry("dataSourceUri", "/ds/pg")
                .containsEntry("x", 0).containsEntry("y", 0).containsEntry("width", 20).containsEntry("height", 10);
        Asset trend = p.assets().get(2);
        assertThat(trend.attributes()).containsEntry("x", 20).containsEntry("width", 20);     // attribute order independent
        assertThat(p.refs()).containsExactly(
                new Ref("/dashboards/exec", "/reports/sales", Ref.RefKind.DASHLET, Map.of("tileId", "Sales_Report", "tileType", "reportUnit")),
                new Ref("/dashboards/exec", "/adhoc/trend", Ref.RefKind.DASHLET, Map.of("tileId", "Trend", "tileType", "adhocDataView")));
        assertThat((java.util.List<?>) dash.attributes().get("wiring")).hasSize(2);
    }

    @Test void parse_descriptorModeWhenDeepParseDisabled() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/dashboards/exec", "{\"uri\":\"/dashboards/exec\",\"label\":\"Exec\"}");
        ParseResult p = new DashboardParser().parse(new ParseContext(r, "srv", TestConfig.base().deepParseDashboards(false).build()), lk);
        assertThat(p.assets()).hasSize(1);
        assertThat(p.assets().get(0).attributes()).containsEntry("parseMode", "descriptor");
        assertThat(p.refs()).isEmpty();
        assertThat(p.warnings()).anyMatch(w -> w.contains("deepParseDashboards"));
    }

    @Test void parse_exportFailureDegradesToDescriptorWithWarning() throws Exception {
        FakeRepository r = new FakeRepository().withDescriptor("/dashboards/exec", "{\"uri\":\"/dashboards/exec\"}");   // no export registered -> IOException
        ParseResult p = new DashboardParser().parse(new ParseContext(r, "srv", TestConfig.base().build()), lk);
        assertThat(p.assets()).hasSize(1);
        assertThat(p.assets().get(0).attributes()).containsEntry("parseMode", "descriptor");
        assertThat(p.warnings()).anyMatch(w -> w.contains("export"));
    }

    @Test void parse_archiveWithoutComponentsWarns() throws Exception {
        FakeRepository r = new FakeRepository().withExport("/dashboards/exec", Zips.of(Map.of("index.xml", "<i/>".getBytes())));
        ParseResult p = new DashboardParser().parse(new ParseContext(r, "srv", TestConfig.base().build()), lk);
        assertThat(p.assets()).hasSize(1);
        assertThat(p.warnings()).anyMatch(w -> w.contains("components.data"));
    }
}
