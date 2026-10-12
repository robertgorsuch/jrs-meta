package com.hcl.jrsmeta.enrich;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.parse.ParseContext;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class OperationalMetadataEnricherTest {
    Asset report = new Asset("srv|/reports/x", "srv", "/reports/x", AssetType.BI_REPORT, "X", null, "/reports", null, null, null, 1, Map.of());
    Asset field = new Asset("srv|/reports/x#field:a", "srv", null, AssetType.REPORT_FIELD, "a", null, "/reports", "srv|/reports/x", null, null, 1, Map.of());
    Asset folder = new Asset("srv|/reports", "srv", "/reports", AssetType.FOLDER, "Reports", null, "/", null, null, null, 1, Map.of());

    @Test void enrich_addsSchedulesWithRecipientsAndPermissions() throws Exception {
        FakeRepository r = new FakeRepository()
            .withJobs("/reports/x", "{\"id\":7,\"label\":\"nightly\",\"owner\":\"jasperadmin\",\"reportUnitURI\":\"/reports/x\",\"state\":{\"nextFireTime\":\"2026-10-13T02:00:00\",\"previousFireTime\":\"2026-10-12T02:00:00\",\"value\":\"NORMAL\"}}")
            .withJob(7, "{\"id\":7,\"label\":\"nightly\",\"outputFormats\":{\"outputFormat\":[\"PDF\",\"XLSX\"]},\"mailNotification\":{\"toAddresses\":{\"address\":[\"ops@example.com\"]}},\"repositoryDestination\":{\"folderURI\":\"/out\"}}")
            .withPermissions("/reports/x", "{\"uri\":\"repo:/reports/x\",\"recipient\":\"role:/ROLE_USER\",\"mask\":2}")
            .withPermissions("/reports", "{\"uri\":\"repo:/reports\",\"recipient\":\"role:/ROLE_ADMINISTRATOR\",\"mask\":1}");
        List<String> warnings = new ArrayList<>();
        List<Asset> out = new OperationalMetadataEnricher().enrich(new ParseContext(r, "srv", TestConfig.base().build()), List.of(report, field, folder), warnings);

        Asset rep = out.get(0);
        List<?> schedules = (List<?>) rep.attributes().get("schedules");
        assertThat(schedules).hasSize(1);
        @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) schedules.get(0);
        assertThat(s).containsEntry("id", 7L).containsEntry("label", "nightly").containsEntry("owner", "jasperadmin").containsEntry("nextFireTime", "2026-10-13T02:00:00")
                .containsEntry("outputFormats", List.of("PDF", "XLSX")).containsEntry("mailTo", List.of("ops@example.com")).containsEntry("repositoryFolder", "/out");
        assertThat((List<?>) rep.attributes().get("permissions")).hasSize(1);
        assertThat(out.get(1).attributes()).doesNotContainKey("permissions");       // child: skipped
        assertThat((List<?>) out.get(2).attributes().get("permissions")).hasSize(1);  // folder: permissions only
        assertThat(out.get(2).attributes()).doesNotContainKey("schedules");
        assertThat(warnings).isEmpty();
    }

    @Test void enrich_failuresBecomeWarningsNotExceptions() throws Exception {
        FakeRepository r = new FakeRepository().withJobs("/reports/x", "{\"id\":9}");   // job(9) missing -> 404
        List<String> warnings = new ArrayList<>();
        List<Asset> out = new OperationalMetadataEnricher().enrich(new ParseContext(r, "srv", TestConfig.base().build()), List.of(report), warnings);
        assertThat(out).hasSize(1);
        List<?> schedules = (List<?>) out.get(0).attributes().get("schedules");
        assertThat(schedules).hasSize(1);                                             // summary kept even if detail fails
        assertThat(warnings).anyMatch(w -> w.contains("job 9"));
    }
}
