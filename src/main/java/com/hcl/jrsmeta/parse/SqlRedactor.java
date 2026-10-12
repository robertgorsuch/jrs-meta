package com.hcl.jrsmeta.parse;

import java.util.regex.Pattern;

/** Spec §13: optional redaction of literals before SQL text is stored as metadata. */
public final class SqlRedactor {
    private static final Pattern STRING = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d+(?:\\.\\d+)?(?![\\w.])");
    private SqlRedactor() {}

    public static String redact(String sql) {
        if (sql == null) return null;
        return NUMBER.matcher(STRING.matcher(sql).replaceAll("?")).replaceAll("?");
    }
}
