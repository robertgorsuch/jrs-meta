package com.hcl.jrsmeta.model;

import java.util.List;

public record ParseResult(List<Asset> assets, List<Ref> refs, List<String> warnings) {
    public ParseResult {
        assets = List.copyOf(assets); refs = List.copyOf(refs); warnings = List.copyOf(warnings);
    }
    public static ParseResult of(Asset single) { return new ParseResult(List.of(single), List.of(), List.of()); }
}
