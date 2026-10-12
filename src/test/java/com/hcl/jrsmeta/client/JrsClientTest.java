package com.hcl.jrsmeta.client;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.*;

class JrsClientTest {
    @RegisterExtension static WireMockExtension wm = WireMockExtension.newInstance().build();

    record Entry(String method, String path, int status) {}
    static final class CapturingAudit implements AuditLog {
        final List<Entry> entries = new ArrayList<>();
        public void record(String m, String p, int s, long ms) { entries.add(new Entry(m, p, s)); }
        public void close() {}
    }

    private ConnectorConfig cfg(int retries) {
        return TestConfig.base().baseUrl(wm.baseUrl() + "/jasperserver-pro").username("svc").password("pw".toCharArray()).maxRetries(retries).build();
    }

    @Test void get_sendsBasicAuthAndAcceptUnderRestV2() throws Exception {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/serverInfo")).willReturn(okJson("{\"version\":\"10.0.0\"}")));
        JrsClient c = new JrsClient(cfg(0), AuditLog.NOOP);
        JrsClient.Response r = c.get("/serverInfo", "application/json");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text()).contains("10.0.0");
        String expected = "Basic " + Base64.getEncoder().encodeToString("svc:pw".getBytes(StandardCharsets.UTF_8));
        wm.verify(getRequestedFor(urlEqualTo("/jasperserver-pro/rest_v2/serverInfo"))
                .withHeader("Authorization", equalTo(expected)).withHeader("Accept", equalTo("application/json")));
    }

    @Test void get_retriesOn503ThenSucceeds() throws Exception {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).inScenario("r").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).inScenario("r").whenScenarioStateIs("ok").willReturn(ok("fine")));
        CapturingAudit audit = new CapturingAudit();
        JrsClient c = new JrsClient(cfg(2), audit, ms -> {});          // no real sleeping
        assertThat(c.get("/x", "*/*").text()).isEqualTo("fine");
        assertThat(audit.entries).extracting(Entry::status).containsExactly(503, 200);
    }

    @Test void get_401FailsFastWithoutRetry() {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).willReturn(aResponse().withStatus(401)));
        JrsClient c = new JrsClient(cfg(3), AuditLog.NOOP, ms -> {});
        assertThatThrownBy(() -> c.get("/x", "*/*")).isInstanceOf(JrsClientException.class)
                .hasMessageContaining("JRS_META_USER").extracting("status").isEqualTo(401);
        wm.verify(1, getRequestedFor(urlEqualTo("/jasperserver-pro/rest_v2/x")));
    }

    @Test void get_404ReturnsResponseNotException() throws Exception {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).willReturn(aResponse().withStatus(404)));
        assertThat(new JrsClient(cfg(0), AuditLog.NOOP).get("/x", "*/*").status()).isEqualTo(404);
    }

    @Test void get_givesUpAfterMaxRetries() {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).willReturn(aResponse().withStatus(503)));
        JrsClient c = new JrsClient(cfg(2), AuditLog.NOOP, ms -> {});
        assertThatThrownBy(() -> c.get("/x", "*/*")).isInstanceOf(JrsClientException.class).hasMessageContaining("503");
        wm.verify(3, getRequestedFor(urlEqualTo("/jasperserver-pro/rest_v2/x")));
    }

    @Test void audit_neverRecordsAuthorization() throws Exception {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).willReturn(ok("")));
        CapturingAudit audit = new CapturingAudit();
        new JrsClient(cfg(0), audit).get("/x", "*/*");
        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).toString()).doesNotContain("Basic").doesNotContain("pw");
    }

    @Test void encodePath_keepsSlashesEncodesSpaces() {
        assertThat(JrsClient.encodePath("/public/Samples/1._Supermart Dashboard/a&b"))
                .isEqualTo("/public/Samples/1._Supermart%20Dashboard/a%26b");
    }

    @Test void post_sendsBodyWithContentType() throws Exception {
        wm.stubFor(post(urlEqualTo("/jasperserver-pro/rest_v2/export")).willReturn(okJson("{\"id\":\"e1\"}")));
        JrsClient.Response r = new JrsClient(cfg(0), AuditLog.NOOP).post("/export", "application/json", "{\"uris\":[]}".getBytes(), "application/json");
        assertThat(r.text()).contains("e1");
        wm.verify(postRequestedFor(urlEqualTo("/jasperserver-pro/rest_v2/export")).withHeader("Content-Type", equalTo("application/json")).withRequestBody(equalTo("{\"uris\":[]}")));
    }
}
