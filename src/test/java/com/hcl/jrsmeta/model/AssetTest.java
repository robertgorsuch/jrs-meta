package com.hcl.jrsmeta.model;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AssetTest {
    @Test void idOf_isServerIdPipeUri() {
        assertThat(Asset.idOf("prod", "/reports/x")).isEqualTo("prod|/reports/x");
    }
    @Test void childIdOf_appendsKindAndName() {
        assertThat(Asset.childIdOf("prod|/reports/x", "field", "region")).isEqualTo("prod|/reports/x#field:region");
    }
    @Test void folderOf_handlesRootAndNested() {
        assertThat(Asset.folderOf("/reports/sales/x")).isEqualTo("/reports/sales");
        assertThat(Asset.folderOf("/x")).isEqualTo("/");
        assertThat(Asset.folderOf("/")).isEqualTo("/");
    }
    @Test void of_copiesLookupMetadata() {
        var l = new ResourceLookup("/reports/x", "X", "desc", "reportUnit", "2024-01-01T00:00:00", "2024-02-01T00:00:00", 3);
        Asset a = Asset.of("prod", l, AssetType.BI_REPORT, Attrs.of("k", null));
        assertThat(a.id()).isEqualTo("prod|/reports/x");
        assertThat(a.folder()).isEqualTo("/reports");
        assertThat(a.version()).isEqualTo(3);
        assertThat(a.attributes()).containsKey("k");
    }
    @Test void child_inheritsParentIdentity() {
        var l = new ResourceLookup("/reports/x", "X", null, "reportUnit", null, null, 1);
        Asset r = Asset.of("prod", l, AssetType.BI_REPORT, Map.of());
        Asset f = Asset.child(r, "field", "region", AssetType.REPORT_FIELD, "region", Attrs.of("javaClass", "java.lang.String"));
        assertThat(f.id()).isEqualTo("prod|/reports/x#field:region");
        assertThat(f.parentId()).isEqualTo(r.id());
        assertThat(f.uri()).isNull();
    }
    @Test void withAttributes_isImmutableMerge() {
        Asset a = new Asset("i", "s", "/u", AssetType.OTHER, "l", null, "/", null, null, null, null, Map.of("a", 1));
        Asset b = a.withAttributes(Map.of("b", 2));
        assertThat(a.attributes()).doesNotContainKey("b");
        assertThat(b.attributes()).containsEntry("a", 1).containsEntry("b", 2);
        assertThatThrownBy(() -> b.attributes().put("c", 3)).isInstanceOf(UnsupportedOperationException.class);
    }
}
