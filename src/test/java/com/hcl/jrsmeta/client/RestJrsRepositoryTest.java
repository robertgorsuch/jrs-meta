package com.hcl.jrsmeta.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.model.ResourceLookup;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.*;

class RestJrsRepositoryTest {
    @RegisterExtension static WireMockExtension wm = WireMockExtension.newInstance().build();
    static final String R = "/jasperserver-pro/rest_v2";

    private RestJrsRepository repo(int pageSize) {
        ConnectorConfig cfg = TestConfig.base().baseUrl(wm.baseUrl() + "/jasperserver-pro").pageSize(pageSize).build();
        return new RestJrsRepository(new JrsClient(cfg, AuditLog.NOOP), cfg);
    }

    private static String lookup(String uri, String type) {
        return "{\"uri\":\"" + uri + "\",\"label\":\"L\",\"description\":\"D\",\"resourceType\":\"" + type
                + "\",\"creationDate\":\"2024-01-01T00:00:00\",\"updateDate\":\"2024-02-01T00:00:00\",\"version\":2,\"permissionMask\":1}";
    }

    @Test void listResources_paginatesUntilShortPage() throws Exception {
        wm.stubFor(get(urlPathEqualTo(R + "/resources")).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson("{\"resourceLookup\":[" + lookup("/a", "reportUnit") + "," + lookup("/b", "folder") + "]}")
                        .withHeader("Result-Count", "2").withHeader("Next-Offset", "2")));
        wm.stubFor(get(urlPathEqualTo(R + "/resources")).withQueryParam("offset", equalTo("2"))
                .willReturn(okJson("{\"resourceLookup\":[" + lookup("/c", "jdbcDataSource") + "]}").withHeader("Result-Count", "1")));
        List<ResourceLookup> out = repo(2).listResources("/", true);
        assertThat(out).extracting(ResourceLookup::uri).containsExactly("/a", "/b", "/c");
        assertThat(out.get(0)).isEqualTo(new ResourceLookup("/a", "L", "D", "reportUnit", "2024-01-01T00:00:00", "2024-02-01T00:00:00", 2));
        wm.verify(getRequestedFor(urlPathEqualTo(R + "/resources")).withQueryParam("folderUri", equalTo("/"))
                .withQueryParam("recursive", equalTo("true")).withQueryParam("limit", equalTo("2")));
    }

    @Test void listResources_emptyFolderReturns204() throws Exception {
        wm.stubFor(get(urlPathEqualTo(R + "/resources")).willReturn(aResponse().withStatus(204)));
        assertThat(repo(100).listResources("/empty", true)).isEmpty();
    }

    @Test void listResources_fullLastPageStopsOn204() throws Exception {
        wm.stubFor(get(urlPathEqualTo(R + "/resources")).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson("{\"resourceLookup\":[" + lookup("/a", "reportUnit") + "]}")));
        wm.stubFor(get(urlPathEqualTo(R + "/resources")).withQueryParam("offset", equalTo("1")).willReturn(aResponse().withStatus(204)));
        assertThat(repo(1).listResources("/", true)).hasSize(1);
    }

    @Test void descriptor_usesTypedAcceptAndEncodedPath() throws Exception {
        wm.stubFor(get(urlEqualTo(R + "/resources/reports/My%20Report")).withHeader("Accept", equalTo("application/repository.reportUnit+json"))
                .willReturn(okJson("{\"uri\":\"/reports/My Report\",\"label\":\"x\"}")));
        JsonNode d = repo(100).descriptor("/reports/My Report", "reportUnit");
        assertThat(d.get("label").asText()).isEqualTo("x");
    }

    @Test void descriptor_404Throws() {
        wm.stubFor(get(urlEqualTo(R + "/resources/gone")).willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> repo(100).descriptor("/gone", "reportUnit")).isInstanceOf(JrsClientException.class)
                .extracting("status").isEqualTo(404);
    }

    @Test void file_returnsRawBytes() throws Exception {
        wm.stubFor(get(urlEqualTo(R + "/resources/reports/x_files/main_jrxml")).withHeader("Accept", equalTo("application/xml"))
                .willReturn(ok("<jasperReport/>")));
        assertThat(new String(repo(100).file("/reports/x_files/main_jrxml", "application/xml"))).isEqualTo("<jasperReport/>");
    }

    @Test void exportArchive_postsPollsThenDownloads() throws Exception {
        wm.stubFor(post(urlEqualTo(R + "/export")).withRequestBody(equalToJson("{\"uris\":[\"/dash/a\"],\"parameters\":[]}"))
                .willReturn(okJson("{\"id\":\"e1\",\"phase\":\"inprogress\"}")));
        wm.stubFor(get(urlEqualTo(R + "/export/e1/state")).inScenario("s").whenScenarioStateIs(STARTED)
                .willReturn(okJson("{\"phase\":\"inprogress\"}")).willSetStateTo("done"));
        wm.stubFor(get(urlEqualTo(R + "/export/e1/state")).inScenario("s").whenScenarioStateIs("done")
                .willReturn(okJson("{\"phase\":\"finished\"}")));
        wm.stubFor(get(urlEqualTo(R + "/export/e1/exportFile")).willReturn(aResponse().withStatus(200).withBody(new byte[]{'P', 'K', 3, 4})));
        assertThat(repo(100).exportArchive(List.of("/dash/a"))).startsWith((byte) 'P', (byte) 'K');
    }

    @Test void exportArchive_failedPhaseThrows() {
        wm.stubFor(post(urlEqualTo(R + "/export")).willReturn(okJson("{\"id\":\"e2\"}")));
        wm.stubFor(get(urlEqualTo(R + "/export/e2/state")).willReturn(okJson("{\"phase\":\"failed\",\"message\":\"boom\"}")));
        assertThatThrownBy(() -> repo(100).exportArchive(List.of("/x"))).hasMessageContaining("boom");
    }

    @Test void exportArchive_timesOutWhenNeverFinished() {
        wm.stubFor(post(urlEqualTo(R + "/export")).willReturn(okJson("{\"id\":\"e3\"}")));
        wm.stubFor(get(urlEqualTo(R + "/export/e3/state")).willReturn(okJson("{\"phase\":\"inprogress\"}")));
        assertThatThrownBy(() -> repo(100).exportArchive(List.of("/x"))).hasMessageContaining("timed out");  // exportTimeoutSeconds=1 in TestConfig
    }

    @Test void jobs_204IsEmptyAndSummariesAreReturned() throws Exception {
        wm.stubFor(get(urlPathEqualTo(R + "/jobs")).withQueryParam("reportUnitURI", equalTo("/r/none")).willReturn(aResponse().withStatus(204)));
        wm.stubFor(get(urlPathEqualTo(R + "/jobs")).withQueryParam("reportUnitURI", equalTo("/r/one"))
                .willReturn(okJson("{\"jobsummary\":[{\"id\":7,\"label\":\"nightly\"}]}")));
        assertThat(repo(100).jobs("/r/none")).isEmpty();
        assertThat(repo(100).jobs("/r/one")).hasSize(1).first().matches(j -> j.get("id").asLong() == 7);
    }

    @Test void job_fetchesFullDefinition() throws Exception {
        wm.stubFor(get(urlEqualTo(R + "/jobs/7")).willReturn(okJson("{\"id\":7,\"outputFormats\":{\"outputFormat\":[\"PDF\"]}}")));
        assertThat(repo(100).job(7).path("outputFormats").path("outputFormat").get(0).asText()).isEqualTo("PDF");
    }

    @Test void permissions_effectiveListOr204() throws Exception {
        wm.stubFor(get(urlEqualTo(R + "/permissions/reports/x?effectivePermissions=true"))
                .willReturn(okJson("{\"permission\":[{\"uri\":\"repo:/reports/x\",\"recipient\":\"role:/ROLE_USER\",\"mask\":2}]}")));
        wm.stubFor(get(urlEqualTo(R + "/permissions/reports/none?effectivePermissions=true")).willReturn(aResponse().withStatus(204)));
        assertThat(repo(100).permissions("/reports/x")).hasSize(1);
        assertThat(repo(100).permissions("/reports/none")).isEmpty();
    }

    @Test void serverInfo_mapsEdition() throws Exception {
        wm.stubFor(get(urlEqualTo(R + "/serverInfo")).willReturn(okJson("{\"version\":\"10.0.0\",\"edition\":\"PRO\",\"editionName\":\"Enterprise\",\"build\":\"20240101\"}")));
        ServerInfo s = repo(100).serverInfo();
        assertThat(s.version()).isEqualTo("10.0.0");
        assertThat(s.isPro()).isTrue();
    }
}
