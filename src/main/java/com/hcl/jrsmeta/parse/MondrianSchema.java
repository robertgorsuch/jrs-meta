package com.hcl.jrsmeta.parse;

import java.util.List;

public record MondrianSchema(String name, List<Cube> cubes) {
    public record Cube(String name, String factTable, List<Measure> measures, List<Dimension> dimensions) {}
    public record Measure(String name, String column, String aggregator) {}
    public record Dimension(String name, List<Level> levels) {}
    public record Level(String name, String table, String column) {}
}
