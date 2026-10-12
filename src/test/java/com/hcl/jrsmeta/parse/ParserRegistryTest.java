package com.hcl.jrsmeta.parse;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ParserRegistryTest {
    @Test void defaults_routeKnownTypesAndFallBack() {
        ParserRegistry reg = ParserRegistry.defaults();
        assertThat(reg.forType("reportUnit")).isInstanceOf(ReportUnitParser.class);
        assertThat(reg.forType("jdbcDataSource")).isInstanceOf(DataSourceParser.class);
        assertThat(reg.forType("semanticLayerDataSource")).isInstanceOf(DomainParser.class);
        assertThat(reg.forType("adhocDataView")).isInstanceOf(AdhocViewParser.class);
        assertThat(reg.forType("folder")).isInstanceOf(GenericResourceParser.class);
        assertThat(reg.forType("whatever")).isInstanceOf(GenericResourceParser.class);
        assertThat(reg.forType(null)).isInstanceOf(GenericResourceParser.class);
    }
}
