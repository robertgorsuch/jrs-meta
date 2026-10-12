package com.hcl.jrsmeta.parse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ParserRegistry {
    private final Map<String, DescriptorParser> byType = new HashMap<>();
    private final DescriptorParser fallback;

    public ParserRegistry(DescriptorParser fallback, List<DescriptorParser> parsers) {
        this.fallback = fallback;
        for (DescriptorParser p : parsers) for (String t : p.resourceTypes()) byType.put(t, p);
    }

    /** Tasks 12 and 13 append DashboardParser and OlapParser here. */
    public static ParserRegistry defaults() {
        return new ParserRegistry(new GenericResourceParser(), List.of(
                new ReportUnitParser(), new DataSourceParser(), new DomainParser(), new AdhocViewParser(),
                new DashboardParser(), new OlapParser(), new GenericResourceParser()));
    }

    public DescriptorParser forType(String resourceType) {
        return resourceType == null ? fallback : byType.getOrDefault(resourceType, fallback);
    }
}
