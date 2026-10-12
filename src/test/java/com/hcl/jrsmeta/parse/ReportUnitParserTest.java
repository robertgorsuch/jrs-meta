package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ReportUnitParserTest {
    static final String JRXML = """
        <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports" name="rev">
          <parameter name="Region" class="java.lang.String"/>
          <query language="sql"><![CDATA[SELECT r.region_name AS region, SUM(s.amount) AS revenue FROM sales_fact s JOIN region r ON r.region_id = s.region_id WHERE r.region_name = 'WEST' GROUP BY r.region_name]]></query>
          <field name="region" class="java.lang.String"/>
          <field name="revenue" class="java.math.BigDecimal"/>
          <detail><band height="10">
            <subreport><subreportExpression><![CDATA["repo:/reports/sub/detail"]]></subreportExpression></subreport>
            <subreport><subreportExpression><![CDATA[$P{SUBREPORT_DIR} + "child.jrxml"]]></subreportExpression></subreport>
            <subreport><subreportExpression><![CDATA[$P{SUBREPORT_DIR} + "unknown.jasper"]]></subreportExpression></subreport>
          </band></detail>
        </jasperReport>
        """;

    static final String DESCRIPTOR_REF = """
        {"uri":"/reports/rev","label":"Revenue","description":"d","version":3,
         "alwaysPromptControls":true,
         "dataSource":{"dataSourceReference":{"uri":"/datasources/sales"}},
         "jrxml":{"jrxmlFileReference":{"uri":"/reports/rev_files/main_jrxml"}},
         "inputControls":[{"inputControlReference":{"uri":"/reports/rev_files/Region"}}],
         "resources":[{"name":"child.jrxml","file":{"fileReference":{"uri":"/reports/rev_files/child.jrxml"}}}]}
        """;

    ResourceLookup lookup = new ResourceLookup("/reports/rev", "Revenue", "d", "reportUnit", "2024-01-01T00:00:00", "2024-02-01T00:00:00", 3);

    private ParseContext ctx(FakeRepository repo, boolean redact) {
        return new ParseContext(repo, "srv", TestConfig.base().redactSqlLiterals(redact).build());
    }

    @Test void parse_referencedJrxmlProducesReportChildrenAndRefs() throws Exception {
        FakeRepository repo = new FakeRepository().withDescriptor("/reports/rev", DESCRIPTOR_REF).withFile("/reports/rev_files/main_jrxml", JRXML);
        ParseResult r = new ReportUnitParser().parse(ctx(repo, false), lookup);

        Asset report = r.assets().get(0);
        assertThat(report.type()).isEqualTo(AssetType.BI_REPORT);
        assertThat(report.id()).isEqualTo("srv|/reports/rev");
        assertThat(report.attributes()).containsEntry("dataSourceUri", "/datasources/sales").containsEntry("queryLanguage", "sql")
                .containsEntry("jrxmlSource", "reference").containsEntry("alwaysPromptControls", true);
        assertThat((String) report.attributes().get("queryText")).contains("'WEST'");
        assertThat(report.attributes().get("subreportUris")).isEqualTo(List.of("/reports/sub/detail", "/reports/rev_files/child.jrxml"));
        assertThat(report.attributes().get("unresolvedSubreports")).isEqualTo(List.of("$P{SUBREPORT_DIR} + \"unknown.jasper\""));

        assertThat(r.assets()).filteredOn(a -> a.type() == AssetType.REPORT_FIELD).extracting(Asset::label).containsExactly("region", "revenue");
        assertThat(r.assets()).filteredOn(a -> a.type() == AssetType.REPORT_FIELD).extracting(Asset::id).contains("srv|/reports/rev#field:region");
        assertThat(r.assets()).filteredOn(a -> a.type() == AssetType.REPORT_PARAMETER).extracting(Asset::label).containsExactly("Region");

        assertThat(r.refs()).containsExactlyInAnyOrder(
                new Ref("/reports/rev", "/datasources/sales", Ref.RefKind.DATASOURCE),
                new Ref("/reports/rev", "/reports/rev_files/Region", Ref.RefKind.INPUT_CONTROL),
                new Ref("/reports/rev", "/reports/sub/detail", Ref.RefKind.SUBREPORT),
                new Ref("/reports/rev", "/reports/rev_files/child.jrxml", Ref.RefKind.SUBREPORT));
        assertThat(r.warnings()).anyMatch(w -> w.contains("unknown.jasper"));
    }

    @Test void resolveSubreport_relativeRepoNameResolvesAgainstCompanionFiles() {
        // JRS sample reports use "repo:Name.jrxml" (no leading slash): relative to the report's own _files resources
        Map<String, String> files = Map.of("Top5CustomersReport.jrxml", "/public/Samples/Reports/Geo_files/Top5CustomersReport.jrxml");
        assertThat(ReportUnitParser.resolveSubreport("\"repo:Top5CustomersReport.jrxml\"", files)).contains("/public/Samples/Reports/Geo_files/Top5CustomersReport.jrxml");
        assertThat(ReportUnitParser.resolveSubreport("\"repo:Top5CustomersReport\"", files)).contains("/public/Samples/Reports/Geo_files/Top5CustomersReport.jrxml");
        assertThat(ReportUnitParser.resolveSubreport("\"repo:Missing.jrxml\"", files)).isEmpty();
        assertThat(ReportUnitParser.resolveSubreport("\"repo:/abs/path\"", files)).contains("/abs/path");
    }

    @Test void parse_resourcesWrappedInResourceObjectAsLiveJrsReturnsThem() throws Exception {
        // JRS 10 returns "resources":{"resource":[...]} (wrapper object), not a bare array
        String d = """
            {"uri":"/reports/geo","label":"Geo",
             "jrxml":{"jrxmlFileReference":{"uri":"/reports/geo_files/main"}},
             "resources":{"resource":[{"name":"undo.png","file":{"fileReference":{"uri":"/reports/geo_files/undo.png"}}},
                                      {"name":"Top5CustomersReport.jrxml","file":{"fileReference":{"uri":"/reports/geo_files/Top5CustomersReport.jrxml"}}}]}}
            """;
        String jrxml = "<jasperReport><queryString><![CDATA[SELECT 1]]></queryString><detail><band height=\"1\"><subreport><subreportExpression><![CDATA[\"repo:Top5CustomersReport.jrxml\"]]></subreportExpression></subreport></band></detail></jasperReport>";
        FakeRepository repo = new FakeRepository().withDescriptor("/reports/geo", d).withFile("/reports/geo_files/main", jrxml);
        ParseResult r = new ReportUnitParser().parse(ctx(repo, false), new ResourceLookup("/reports/geo", "Geo", null, "reportUnit", null, null, 1));
        assertThat(r.refs()).contains(new Ref("/reports/geo", "/reports/geo_files/Top5CustomersReport.jrxml", Ref.RefKind.SUBREPORT));
        assertThat(r.assets().get(0).attributes().get("unresolvedSubreports")).isEqualTo(List.of());
    }

    @Test void parse_inlineBase64Jrxml() throws Exception {
        String inline = "{\"uri\":\"/reports/i\",\"label\":\"I\",\"jrxml\":{\"jrxmlFile\":{\"label\":\"main\",\"type\":\"jrxml\",\"content\":\""
                + Base64.getEncoder().encodeToString(JRXML.getBytes()) + "\"}}}";
        FakeRepository repo = new FakeRepository().withDescriptor("/reports/i", inline);
        ParseResult r = new ReportUnitParser().parse(ctx(repo, false), new ResourceLookup("/reports/i", "I", null, "reportUnit", null, null, 1));
        assertThat(r.assets().get(0).attributes()).containsEntry("jrxmlSource", "inline");
        assertThat(r.assets()).filteredOn(a -> a.type() == AssetType.REPORT_FIELD).hasSize(2);
        assertThat(repo.requestedUris).containsExactly("/reports/i");            // no file fetch
    }

    @Test void parse_missingJrxmlStillEmitsReportWithWarning() throws Exception {
        FakeRepository repo = new FakeRepository().withDescriptor("/reports/rev", DESCRIPTOR_REF).withMissingFile("/reports/rev_files/main_jrxml");
        ParseResult r = new ReportUnitParser().parse(ctx(repo, false), lookup);
        assertThat(r.assets()).hasSize(1);
        assertThat(r.assets().get(0).attributes()).containsEntry("jrxmlSource", "reference").doesNotContainKey("queryText");
        assertThat(r.refs()).contains(new Ref("/reports/rev", "/datasources/sales", Ref.RefKind.DATASOURCE));
        assertThat(r.warnings()).anyMatch(w -> w.contains("main_jrxml"));
    }

    @Test void parse_queryResourceReferenceBecomesRef() throws Exception {
        String d = "{\"uri\":\"/reports/q\",\"label\":\"Q\",\"query\":{\"queryReference\":{\"uri\":\"/queries/shared\"}}}";
        FakeRepository repo = new FakeRepository().withDescriptor("/reports/q", d);
        ParseResult r = new ReportUnitParser().parse(ctx(repo, false), new ResourceLookup("/reports/q", "Q", null, "reportUnit", null, null, 1));
        assertThat(r.refs()).contains(new Ref("/reports/q", "/queries/shared", Ref.RefKind.QUERY_RESOURCE));
        assertThat(r.assets().get(0).attributes()).containsEntry("queryResourceUri", "/queries/shared");
    }

    @Test void parse_redactsSqlLiteralsWhenConfigured() throws Exception {
        FakeRepository repo = new FakeRepository().withDescriptor("/reports/rev", DESCRIPTOR_REF).withFile("/reports/rev_files/main_jrxml", JRXML);
        ParseResult r = new ReportUnitParser().parse(ctx(repo, true), lookup);
        assertThat((String) r.assets().get(0).attributes().get("queryText")).doesNotContain("WEST").contains("= ?");
    }

    @Test void descriptors_refUriFindsAnyReferenceKey() throws Exception {
        var m = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(Descriptors.refUri(m.readTree("{\"dataSourceReference\":{\"uri\":\"/a\"}}"))).contains("/a");
        assertThat(Descriptors.refUri(m.readTree("{\"semanticLayerDataSourceReference\":{\"uri\":\"/b\"}}"))).contains("/b");
        assertThat(Descriptors.refUri(m.readTree("{\"jdbcDataSource\":{\"connectionUrl\":\"x\"}}"))).isEmpty();   // embedded, not a reference
        assertThat(Descriptors.refUri(m.missingNode())).isEmpty();
    }
}
