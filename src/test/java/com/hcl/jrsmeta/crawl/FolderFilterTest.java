package com.hcl.jrsmeta.crawl;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FolderFilterTest {
    @Test void rootIncludeAcceptsEverythingExceptExcluded() {
        FolderFilter f = new FolderFilter(List.of("/"), List.of("/public/Samples", "/temp"));
        assertThat(f.accepts("/reports/x")).isTrue();
        assertThat(f.accepts("/public/Samples/Reports/a")).isFalse();
        assertThat(f.accepts("/public/SamplesX/a")).isTrue();      // prefix must end at a segment boundary
        assertThat(f.accepts("/temp")).isFalse();
    }
    @Test void includeMustMatchASegmentPrefix() {
        FolderFilter f = new FolderFilter(List.of("/reports/sales"), List.of());
        assertThat(f.accepts("/reports/sales")).isTrue();
        assertThat(f.accepts("/reports/sales/q1")).isTrue();
        assertThat(f.accepts("/reports/salesman/x")).isFalse();
        assertThat(f.accepts("/reports")).isFalse();
    }
    @Test void hiddenFilesChildrenAreAlwaysSkipped() {
        FolderFilter f = new FolderFilter(List.of("/"), List.of());
        assertThat(f.accepts("/reports/x_files/main_jrxml")).isFalse();
    }
}
