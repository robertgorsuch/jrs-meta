package com.hcl.jrsmeta.model;

public enum AssetType {
    FOLDER, BI_REPORT, REPORT_FIELD, REPORT_PARAMETER, BI_DASHBOARD, DASHBOARD_TILE, BI_VIEW,
    SEMANTIC_MODEL, LOGICAL_FIELD, BI_DATASOURCE, OLAP_VIEW, OLAP_CONNECTION, OLAP_SCHEMA, OLAP_CUBE,
    INPUT_CONTROL, QUERY, FILE, OTHER,
    /** Placeholder for a physical database the stitch map could not resolve (spec §7.4). */
    PHYSICAL_DATASOURCE_SHADOW
}
