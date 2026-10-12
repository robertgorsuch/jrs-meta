package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.Fingerprint;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JDBC URL -> connection fingerprint (spec §7.4 / §9.5). Credentials are stripped before anything is stored. */
public final class JdbcUrlParser {
    private static final int CI = Pattern.CASE_INSENSITIVE;
    // db2 databases end at the first ':' because DB2 URLs carry properties as "/DB:key=value;key=value;"
    private static final Pattern STD = Pattern.compile("^jdbc:(postgresql|mysql|mariadb|redshift|db2|vertica)://(?:[^@/]+@)?([^/:;?,]+)(?::(\\d+))?(?:,[^/]*)?/([^?;:]*)", CI);
    private static final Pattern MSSQL = Pattern.compile("^jdbc:sqlserver://([^;:\\\\]+)(?:\\\\[^;:]+)?(?::(\\d+))?(.*)$", CI);
    private static final Pattern ORACLE = Pattern.compile("^jdbc:oracle:(?:thin|oci):(?:[^@]*)@(?://)?([^:/]+)(?::(\\d+))?[:/]([^?;]+)", CI);
    private static final Pattern SNOW = Pattern.compile("^jdbc:snowflake://([^/?:]+)(?::(\\d+))?/?(?:\\?(.*))?$", CI);
    private static final Pattern GENERIC = Pattern.compile("^jdbc:([a-z0-9]+):", CI);
    /** Any property whose key *contains* a secret-looking word (sslpassword, accessToken, trustStorePassword, ...KeyStorePassword) or names the user. */
    private static final Pattern CRED_PARAM = Pattern.compile("(?i)([?&;:](?:[a-z0-9_.-]*(?:pass|pwd|secret|token|key|credential)[a-z0-9_.-]*|user|username|uid)=)[^&;]*");
    private static final Pattern CRED_USERINFO = Pattern.compile("//[^/@]+@");
    /** Oracle thin/oci "user/password@" form has no '//' before the userinfo. */
    private static final Pattern ORACLE_USERINFO = Pattern.compile("(?i)^(jdbc:oracle:(?:thin|oci):)[^@/]*/[^@]*@");
    static final Map<String, Integer> DEFAULT_PORTS = Map.of("postgresql", 5432, "mysql", 3306, "mariadb", 3306, "redshift", 5439,
            "sqlserver", 1433, "oracle", 1521, "snowflake", 443, "db2", 50000, "vertica", 5433);

    private JdbcUrlParser() {}

    public static String sanitize(String url) {
        if (url == null) return null;
        String s = ORACLE_USERINFO.matcher(url).replaceAll("$1***@");
        s = CRED_USERINFO.matcher(s).replaceAll("//***@");
        return CRED_PARAM.matcher(s).replaceAll("$1***");
    }

    public static Fingerprint parse(String url, String driverClass) {
        if (url == null || url.isBlank()) return Fingerprint.opaque(dialectFromDriver(driverClass), "");
        String u = url.trim();
        Matcher m;
        if ((m = STD.matcher(u)).find()) return fp(m.group(1), m.group(2), m.group(3), m.group(4), null, u);
        if ((m = MSSQL.matcher(u)).find()) return fp("sqlserver", m.group(1), m.group(2), prop(m.group(3), "databaseName", "database"), null, u);
        if ((m = ORACLE.matcher(u)).find()) return fp("oracle", m.group(1), m.group(2), m.group(3), null, u);
        if ((m = SNOW.matcher(u)).find()) return fp("snowflake", m.group(1), m.group(2), prop(m.group(3), "db"), prop(m.group(3), "schema"), u);
        if ((m = GENERIC.matcher(u)).find()) return Fingerprint.opaque(m.group(1).toLowerCase(Locale.ROOT), sanitize(u));
        return Fingerprint.opaque(dialectFromDriver(driverClass), sanitize(u));
    }

    private static Fingerprint fp(String dialect, String host, String port, String db, String schema, String raw) {
        String d = dialect.toLowerCase(Locale.ROOT);
        Integer p = port != null ? Integer.valueOf(port) : DEFAULT_PORTS.get(d);
        return new Fingerprint(d, host, p, db == null || db.isEmpty() ? null : db, schema, sanitize(raw));
    }

    private static String prop(String props, String... names) {
        if (props == null) return null;
        for (String kv : props.split("[;&]")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq).trim();
            for (String n : names) if (n.equalsIgnoreCase(k)) return kv.substring(eq + 1).trim();
        }
        return null;
    }

    private static String dialectFromDriver(String driver) {
        if (driver == null) return "unknown";
        String d = driver.toLowerCase(Locale.ROOT);
        for (String k : DEFAULT_PORTS.keySet()) if (d.contains(k)) return k;
        if (d.contains("postgres")) return "postgresql";
        if (d.contains("microsoft")) return "sqlserver";
        return "unknown";
    }
}
