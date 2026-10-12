package com.hcl.jrsmeta.client;

public record ServerInfo(String version, String edition, String editionName, String build) {
    /** Domains, dashboards and ad hoc views exist only on commercial editions (spec §10 "CE vs Pro"). */
    public boolean isPro() { return edition != null && edition.toUpperCase().startsWith("PRO"); }
}
