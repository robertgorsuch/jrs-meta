package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.testutil.Zips;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ExportArchiveTest {
    @Test void read_indexesEntriesAndFindsBySuffix() throws Exception {
        byte[] zip = Zips.of(Map.of("index.xml", "<i/>".getBytes(), "resources/dashboards/exec_files/components.data", "[]".getBytes(), "resources/dashboards/exec.xml", "<d/>".getBytes()));
        ExportArchive a = ExportArchive.read(zip);
        assertThat(a.entryNames()).hasSize(3);
        assertThat(a.findBySuffix("_files/components.data")).map(String::new).contains("[]");
        assertThat(a.findBySuffix("_files/layout")).isEmpty();
    }
    @Test void read_rejectsGarbage() {
        assertThatThrownBy(() -> ExportArchive.read("not a zip".getBytes())).isInstanceOf(java.io.IOException.class);
    }
}
