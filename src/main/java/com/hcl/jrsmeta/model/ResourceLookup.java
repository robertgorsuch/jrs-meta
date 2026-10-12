package com.hcl.jrsmeta.model;

/** One row of GET /rest_v2/resources. Dates are kept as the server's ISO strings (used only for change detection). */
public record ResourceLookup(String uri, String label, String description, String resourceType,
                             String creationDate, String updateDate, Integer version) {}
