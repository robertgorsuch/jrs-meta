package com.hcl.jrsmeta.crawl;

import java.util.List;

/**
 * Include/exclude by folder prefix (segment-aligned). Hidden "<resource>_files" children are not filtered here:
 * the listing omits them unless showHiddenItems=true, and a name-based rule would drop user folders such as "/Finance/Audit_files".
 */
public final class FolderFilter {
    private final List<String> include;
    private final List<String> exclude;

    public FolderFilter(List<String> include, List<String> exclude) {
        this.include = include.isEmpty() ? List.of("/") : include.stream().map(FolderFilter::norm).toList();
        this.exclude = exclude.stream().map(FolderFilter::norm).toList();
    }

    public boolean accepts(String uri) {
        if (uri == null) return false;
        if (exclude.stream().anyMatch(p -> under(uri, p))) return false;
        return include.stream().anyMatch(p -> under(uri, p));
    }

    static boolean under(String uri, String prefix) {
        if (prefix.equals("/")) return true;
        return uri.equals(prefix) || uri.startsWith(prefix + "/");
    }

    private static String norm(String p) {
        String s = p.trim();
        if (!s.startsWith("/")) s = "/" + s;
        return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
