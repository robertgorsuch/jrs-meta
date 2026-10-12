# Jaspersoft Metadata & Lineage Connector — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A read-only Java CLI that crawls a JasperReports Server (JRS) over REST v2, catalogs every repository artifact as a typed asset, derives asset-level lineage (data source → Domain/Topic → ad hoc view → report → dashboard), stitches BI data sources to physical databases by connection fingerprint, syncs incrementally, and emits normalized JSON for a catalog to ingest.

**Architecture:** Single Maven module, package `com.hcl.jrsmeta`. A thin HTTP client (`client`) feeds a per-resource-type parser registry (`parse`) that turns JRS descriptors, JRXML, Domain `schema.xml`, Mondrian XML and dashboard export archives into `Asset`s plus typed `Ref`s. `lineage` turns refs into confidence-scored edges and stitches data sources; `crawl` owns change detection and resumable state; `emit` writes JSON files; `run` orchestrates and `cli` exposes `crawl`/`probe` commands. Parsers depend on a `JrsRepository` interface so every parser is unit-tested with an in-memory fake; the REST implementation is tested with WireMock.

**Tech Stack:** Java 17 (language level), built with JDK 21 + Maven 3.9.9; `java.net.http.HttpClient`; Jackson 2.17 (JSON); JDK DOM (XML, hardened); picocli 4.7 (CLI); SLF4J + Logback; JUnit 5, AssertJ, WireMock 3 (tests). No Spring.

**Spec:** `C:\Users\rgorsuch\Downloads\Jaspersoft Metadata & Lineage Connector Design.md` (same content as the jasper-deploy skill's `references/catalog-connector-pdd.md`). Supporting shape references used while planning: that skill's `references/jrs-rest-api.md`, `dashboard-model.md`, `domains-deep.md`, `olap.md`, `jr7-schema.md`.

**Scope of this plan:** Spec §11 **Phase 1 only** (inventory + metadata, asset-level lineage, stitching, incremental read-only sync). Phase 2 (SQL column lineage), Phase 3 (tag propagation, certification), Phase 4 (usage, scale) get their own plans. Phase 1 still *captures* Domain item→column and Mondrian measure/level→column mappings as attributes (they are free while parsing), so Phase 2 only has to emit edges from them.

**Assumptions made (spec is silent):**
- The catalog ingestion API is not specified, so the emitter writes JSON files (`assets.json`, `lineage.json`, `run-report.json`) behind an `Emitter` interface. A catalog-API emitter is a later task.
- Stitching (spec §7.4) needs the catalog's physical-asset IDs. Phase 1 computes the connection fingerprint and resolves it through an admin-maintained `stitch-map.json`; unmatched fingerprints become shadow assets, exactly as §7.4 describes.
- Credentials come only from environment variables (`JRS_META_USER`, `JRS_META_PASSWORD`), never from the config file.
- JRS descriptors do not expose a resource "owner"; effective permissions are captured instead and `owner` is left null (documented limitation).

## Global Constraints

- Java language level **17** (`<maven.compiler.release>17</maven.compiler.release>`); build with JDK 21. On this machine the PATH `mvn` is broken under JDK 11, so every `mvn` below means, in PowerShell:
  ```powershell
  $env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"; & "C:\apache-maven-3.9.9\bin\mvn.cmd" <args>
  ```
- **Read-only**: the connector issues only `GET` and the `POST /rest_v2/export` family. Never `PUT`/`DELETE`/`POST /resources`.
- **REST v2 only** (spec NG4): every request path starts with `{baseUrl}/rest_v2/`.
- **Never log or persist secrets**: `Authorization` header, passwords, JDBC URL credentials, AWS keys, custom-datasource secret properties are redacted before they reach any attribute, log line or audit record.
- **Server protection** (spec G6, §8.2): bounded request rate (`rateLimitPerSecond`), retries with exponential backoff on 429/5xx, session cookie reuse so Basic auth does not create a server session per request.
- **Stable identity** (spec §6.3): asset id = `serverId + "|" + repositoryUri`; child id = `parentId + "#" + kind + ":" + name`.
- **Graceful degradation** (spec §10): one resource failing to parse never aborts the run; it is counted and reported with a warning.
- Project root: `C:\Users\rgorsuch\jrs-meta`. Package root: `com.hcl.jrsmeta`. Commit after every task.

## Review Focus

Spec-implied inputs no task's happy path exercises; each has a pinned test in the owning task.

1. **Empty JRS responses are `204 No Content`, not `[]`** (listings, jobs, permissions). A naive JSON parse of an empty body crashes the crawl. → Task 5 test `listResources_emptyFolderReturns204`.
2. **Repository URIs contain spaces and punctuation** (`/public/Samples/1._Supermart_Dashboard`, labels with `&`). Paths must be percent-encoded per segment while keeping `/`. → Task 4 test `encodePath_keepsSlashesEncodesSpaces`.
3. **A report whose JRXML file is missing or forbidden (404/403)** must still be cataloged with its descriptor metadata and a warning, not dropped. → Task 8 test `parse_missingJrxmlStillEmitsReportWithWarning`.
4. **JDBC URLs can carry credentials** (`?user=x&password=y`, `//user:pw@host`). They must never reach `connectionUrl`, the fingerprint, or logs. → Task 9 test `sanitize_stripsCredentials`.
5. **Legacy JRXML with a `<!DOCTYPE>`** (JasperReports ≤ 3.x reports still live in old repositories) must parse offline without fetching the DTD and without XXE exposure. → Task 7 test `parse_doctypeJrxmlParsesWithoutNetwork`.
6. **An export job that never reaches `finished`** must time out instead of polling forever. → Task 5 test `exportArchive_timesOutWhenNeverFinished`.
7. **A previous run that crashed after checkpointing but before emitting** must not silently skip those resources on the next incremental run. → Task 6 test `plan_forcesFullWhenLastRunNotOk`.

---

## File Structure

```
jrs-meta/
  pom.xml
  .gitignore
  README.md
  config/connector.example.json          # sample config (no secrets)
  config/stitch-map.example.json         # sample fingerprint -> physical asset map
  src/main/resources/logback.xml
  src/main/java/com/hcl/jrsmeta/
    cli/Main.java                        # picocli: crawl, probe
    config/ConnectorConfig.java          # immutable config + Builder
    config/ConfigLoader.java             # JSON file + env overrides + validation
    model/AssetType.java  Confidence.java  EdgeType.java
    model/Asset.java  LineageEdge.java  ResourceLookup.java  Ref.java  ParseResult.java  Fingerprint.java  Attrs.java
    client/AuditLog.java  JsonlAuditLog.java  RateLimiter.java  JrsClientException.java  JrsClient.java
    client/ServerInfo.java  JrsRepository.java  RestJrsRepository.java
    crawl/FolderFilter.java  SyncState.java  ChangeSet.java  Crawler.java
    parse/Xml.java                       # hardened DOM helpers
    parse/Descriptors.java               # JSON descriptor helpers (refUri etc.)
    parse/DescriptorParser.java  ParseContext.java  ParserRegistry.java
    parse/JrxmlModel.java  JrxmlParser.java  SqlRedactor.java  ReportUnitParser.java
    parse/JdbcUrlParser.java  DataSourceParser.java
    parse/DomainSchema.java  DomainSchemaParser.java  DomainParser.java
    parse/AdhocViewParser.java  GenericResourceParser.java
    parse/ExportArchive.java  DashboardParser.java
    parse/MondrianSchema.java  MondrianSchemaParser.java  OlapParser.java
    enrich/OperationalMetadataEnricher.java
    lineage/LineageBuilder.java  StitchMap.java  Stitcher.java
    emit/Emitter.java  EmitBatch.java  JsonFileEmitter.java
    run/RunReport.java  ConnectorRun.java
  src/test/java/com/hcl/jrsmeta/
    testutil/TestConfig.java  testutil/FakeRepository.java
    ...one test class per production class (named <Class>Test)
    LiveSmokeIT.java                     # env-gated, real server
```

Responsibilities: `client` knows HTTP and nothing about artifacts; `parse` knows artifacts and nothing about HTTP (it sees `JrsRepository`); `lineage` knows only `Asset`/`Ref`; `run` is the only place that wires them.

---

### Task 1: Project scaffold

**Files:**
- Create: `pom.xml`, `.gitignore`, `src/main/resources/logback.xml`, `src/test/java/com/hcl/jrsmeta/SmokeTest.java`

**Interfaces:**
- Produces: Maven coordinates `com.hcl.jrsmeta:jrs-meta-connector:0.1.0-SNAPSHOT`, shaded jar `target/jrs-meta-connector.jar`, main class `com.hcl.jrsmeta.cli.Main` (created in Task 18).

- [ ] **Step 1: Initialise git and write `.gitignore`**

```bash
cd /c/Users/rgorsuch/jrs-meta && git init -b main
```

`.gitignore`:
```
target/
state/
out/
*.log
config/connector.json
config/stitch-map.json
.idea/
*.iml
.vscode/
```

- [ ] **Step 2: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.hcl.jrsmeta</groupId>
  <artifactId>jrs-meta-connector</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>
  <name>Jaspersoft Metadata &amp; Lineage Connector</name>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <jackson.version>2.17.2</jackson.version>
    <junit.version>5.10.3</junit.version>
  </properties>

  <dependencies>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>${jackson.version}</version></dependency>
    <dependency><groupId>info.picocli</groupId><artifactId>picocli</artifactId><version>4.7.6</version></dependency>
    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.13</version></dependency>
    <dependency><groupId>ch.qos.logback</groupId><artifactId>logback-classic</artifactId><version>1.5.6</version></dependency>

    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>${junit.version}</version><scope>test</scope></dependency>
    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><version>3.26.3</version><scope>test</scope></dependency>
    <dependency><groupId>org.wiremock</groupId><artifactId>wiremock</artifactId><version>3.9.1</version><scope>test</scope></dependency>
  </dependencies>

  <build>
    <finalName>jrs-meta-connector</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version>
        <configuration>
          <excludes><exclude>**/*IT.java</exclude></excludes>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.0</version>
        <executions><execution><phase>package</phase><goals><goal>shade</goal></goals>
          <configuration>
            <transformers>
              <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                <mainClass>com.hcl.jrsmeta.cli.Main</mainClass>
              </transformer>
              <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
            </transformers>
            <filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.DSA</exclude><exclude>META-INF/*.RSA</exclude></excludes></filter></filters>
          </configuration>
        </execution></executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Write `src/main/resources/logback.xml`**

```xml
<configuration>
  <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{30} - %msg%n</pattern></encoder>
  </appender>
  <logger name="com.hcl.jrsmeta" level="INFO"/>
  <root level="WARN"><appender-ref ref="STDERR"/></root>
</configuration>
```

- [ ] **Step 4: Write the smoke test**

`src/test/java/com/hcl/jrsmeta/SmokeTest.java`:
```java
package com.hcl.jrsmeta;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest {
    @Test
    void toolchainRunsJava17Code() {
        String s = """
            text block""";          // Java 15+ feature guards the release level
        assertThat(s).isEqualTo("text block");
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(17);
    }
}
```

- [ ] **Step 5: Run the build**

Run: `mvn -q test`
Expected: `BUILD SUCCESS` (quiet mode prints nothing on success), 1 test run.

- [ ] **Step 6: Commit**

```bash
git add pom.xml .gitignore src && git commit -m "chore: scaffold jrs-meta-connector Maven project (Java 17)"
```

---

### Task 2: Domain model

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/model/{AssetType,Confidence,EdgeType,Attrs,Asset,LineageEdge,ResourceLookup,Ref,ParseResult,Fingerprint}.java`
- Test: `src/test/java/com/hcl/jrsmeta/model/AssetTest.java`, `FingerprintTest.java`

**Interfaces:**
- Produces (used by every later task):
  - `enum AssetType { FOLDER, BI_REPORT, REPORT_FIELD, REPORT_PARAMETER, BI_DASHBOARD, DASHBOARD_TILE, BI_VIEW, SEMANTIC_MODEL, LOGICAL_FIELD, BI_DATASOURCE, OLAP_VIEW, OLAP_CONNECTION, OLAP_SCHEMA, OLAP_CUBE, INPUT_CONTROL, QUERY, FILE, OTHER, PHYSICAL_DATASOURCE_SHADOW }`
  - `enum Confidence { EXACT, PARSED, DERIVED, INFERRED }`
  - `enum EdgeType { DATA_FLOW, STITCH, PARAMETER }`
  - `record Asset(String id, String serverId, String uri, AssetType type, String label, String description, String folder, String parentId, String createdAt, String updatedAt, Integer version, Map<String,Object> attributes)` with `idOf`, `childIdOf`, `folderOf`, `of(serverId, lookup, type, attrs)`, `child(parent, kind, name, type, label, attrs)`, `withAttributes(map)`.
  - `record LineageEdge(String sourceId, String targetId, EdgeType type, Confidence confidence, Map<String,Object> attributes)` — data flows **source → target**.
  - `record ResourceLookup(String uri, String label, String description, String resourceType, String creationDate, String updateDate, Integer version)`
  - `record Ref(String fromUri, String toUri, RefKind kind, Map<String,Object> attributes)`; `enum RefKind { DATASOURCE, QUERY_RESOURCE, SUBREPORT, INPUT_CONTROL, DASHLET, OLAP_CONNECTION, OLAP_SCHEMA, SUB_DATASOURCE }`. `fromUri` is the consumer, `toUri` what it reads.
  - `record ParseResult(List<Asset> assets, List<Ref> refs, List<String> warnings)`
  - `record Fingerprint(String dialect, String host, Integer port, String database, String schema, String raw)` with `key()` and `opaque(scheme, value)`.
  - `Attrs.of(Object... kv)` → `LinkedHashMap` that tolerates null values.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/hcl/jrsmeta/model/AssetTest.java`:
```java
package com.hcl.jrsmeta.model;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AssetTest {
    @Test void idOf_isServerIdPipeUri() {
        assertThat(Asset.idOf("prod", "/reports/x")).isEqualTo("prod|/reports/x");
    }
    @Test void childIdOf_appendsKindAndName() {
        assertThat(Asset.childIdOf("prod|/reports/x", "field", "region")).isEqualTo("prod|/reports/x#field:region");
    }
    @Test void folderOf_handlesRootAndNested() {
        assertThat(Asset.folderOf("/reports/sales/x")).isEqualTo("/reports/sales");
        assertThat(Asset.folderOf("/x")).isEqualTo("/");
        assertThat(Asset.folderOf("/")).isEqualTo("/");
    }
    @Test void of_copiesLookupMetadata() {
        var l = new ResourceLookup("/reports/x", "X", "desc", "reportUnit", "2024-01-01T00:00:00", "2024-02-01T00:00:00", 3);
        Asset a = Asset.of("prod", l, AssetType.BI_REPORT, Attrs.of("k", null));
        assertThat(a.id()).isEqualTo("prod|/reports/x");
        assertThat(a.folder()).isEqualTo("/reports");
        assertThat(a.version()).isEqualTo(3);
        assertThat(a.attributes()).containsKey("k");
    }
    @Test void child_inheritsParentIdentity() {
        var l = new ResourceLookup("/reports/x", "X", null, "reportUnit", null, null, 1);
        Asset r = Asset.of("prod", l, AssetType.BI_REPORT, Map.of());
        Asset f = Asset.child(r, "field", "region", AssetType.REPORT_FIELD, "region", Attrs.of("javaClass", "java.lang.String"));
        assertThat(f.id()).isEqualTo("prod|/reports/x#field:region");
        assertThat(f.parentId()).isEqualTo(r.id());
        assertThat(f.uri()).isNull();
    }
    @Test void withAttributes_isImmutableMerge() {
        Asset a = new Asset("i", "s", "/u", AssetType.OTHER, "l", null, "/", null, null, null, null, Map.of("a", 1));
        Asset b = a.withAttributes(Map.of("b", 2));
        assertThat(a.attributes()).doesNotContainKey("b");
        assertThat(b.attributes()).containsEntry("a", 1).containsEntry("b", 2);
        assertThatThrownBy(() -> b.attributes().put("c", 3)).isInstanceOf(UnsupportedOperationException.class);
    }
}
```

`src/test/java/com/hcl/jrsmeta/model/FingerprintTest.java`:
```java
package com.hcl.jrsmeta.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FingerprintTest {
    @Test void key_isLowercaseDialectHostPortDatabase() {
        var fp = new Fingerprint("PostgreSQL", "DB.Example.com", 5432, "SalesDB", null, "jdbc:...");
        assertThat(fp.key()).isEqualTo("postgresql://db.example.com:5432/salesdb");
    }
    @Test void key_omitsPortWhenUnknown() {
        assertThat(new Fingerprint("oracle", "h", null, "orcl", null, null).key()).isEqualTo("oracle://h/orcl");
    }
    @Test void opaque_usesRawValue() {
        assertThat(Fingerprint.opaque("jndi", "jdbc/FoodMart").key()).isEqualTo("jndi://jdbc/foodmart");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=AssetTest,FingerprintTest`
Expected: compilation errors (`cannot find symbol: class Asset`).

- [ ] **Step 3: Write the model**

`AssetType.java`:
```java
package com.hcl.jrsmeta.model;

public enum AssetType {
    FOLDER, BI_REPORT, REPORT_FIELD, REPORT_PARAMETER, BI_DASHBOARD, DASHBOARD_TILE, BI_VIEW,
    SEMANTIC_MODEL, LOGICAL_FIELD, BI_DATASOURCE, OLAP_VIEW, OLAP_CONNECTION, OLAP_SCHEMA, OLAP_CUBE,
    INPUT_CONTROL, QUERY, FILE, OTHER,
    /** Placeholder for a physical database the stitch map could not resolve (spec §7.4). */
    PHYSICAL_DATASOURCE_SHADOW
}
```

`Confidence.java`:
```java
package com.hcl.jrsmeta.model;

/** Spec §7.3. */
public enum Confidence { EXACT, PARSED, DERIVED, INFERRED }
```

`EdgeType.java`:
```java
package com.hcl.jrsmeta.model;

public enum EdgeType { DATA_FLOW, STITCH, PARAMETER }
```

`Attrs.java`:
```java
package com.hcl.jrsmeta.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Null-tolerant ordered attribute map builder (Map.of rejects null values). */
public final class Attrs {
    private Attrs() {}
    public static Map<String, Object> of(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("key/value pairs required");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
```

`Asset.java`:
```java
package com.hcl.jrsmeta.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record Asset(String id, String serverId, String uri, AssetType type, String label, String description,
                    String folder, String parentId, String createdAt, String updatedAt, Integer version,
                    Map<String, Object> attributes) {

    public Asset {
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static String idOf(String serverId, String uri) { return serverId + "|" + uri; }

    public static String childIdOf(String parentId, String kind, String name) { return parentId + "#" + kind + ":" + name; }

    public static String folderOf(String uri) {
        if (uri == null) return null;
        int i = uri.lastIndexOf('/');
        return i <= 0 ? "/" : uri.substring(0, i);
    }

    public static Asset of(String serverId, ResourceLookup l, AssetType type, Map<String, Object> attrs) {
        return new Asset(idOf(serverId, l.uri()), serverId, l.uri(), type, l.label(), l.description(),
                folderOf(l.uri()), null, l.creationDate(), l.updateDate(), l.version(), attrs);
    }

    public static Asset child(Asset parent, String kind, String name, AssetType type, String label, Map<String, Object> attrs) {
        return new Asset(childIdOf(parent.id(), kind, name), parent.serverId(), null, type, label, null,
                parent.folder(), parent.id(), parent.createdAt(), parent.updatedAt(), parent.version(), attrs);
    }

    public Asset withAttributes(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>(attributes);
        m.putAll(extra);
        return new Asset(id, serverId, uri, type, label, description, folder, parentId, createdAt, updatedAt, version, m);
    }
}
```

`LineageEdge.java`:
```java
package com.hcl.jrsmeta.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Data flows from sourceId to targetId. */
public record LineageEdge(String sourceId, String targetId, EdgeType type, Confidence confidence, Map<String, Object> attributes) {
    public LineageEdge {
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
```

`ResourceLookup.java`:
```java
package com.hcl.jrsmeta.model;

/** One row of GET /rest_v2/resources. Dates are kept as the server's ISO strings (used only for change detection). */
public record ResourceLookup(String uri, String label, String description, String resourceType,
                             String creationDate, String updateDate, Integer version) {}
```

`Ref.java`:
```java
package com.hcl.jrsmeta.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A reference discovered while parsing: {@code fromUri} (consumer) reads {@code toUri}. */
public record Ref(String fromUri, String toUri, RefKind kind, Map<String, Object> attributes) {
    public enum RefKind { DATASOURCE, QUERY_RESOURCE, SUBREPORT, INPUT_CONTROL, DASHLET, OLAP_CONNECTION, OLAP_SCHEMA, SUB_DATASOURCE }

    public Ref {
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
    public Ref(String fromUri, String toUri, RefKind kind) { this(fromUri, toUri, kind, Map.of()); }
}
```

`ParseResult.java`:
```java
package com.hcl.jrsmeta.model;

import java.util.List;

public record ParseResult(List<Asset> assets, List<Ref> refs, List<String> warnings) {
    public ParseResult {
        assets = List.copyOf(assets); refs = List.copyOf(refs); warnings = List.copyOf(warnings);
    }
    public static ParseResult of(Asset single) { return new ParseResult(List.of(single), List.of(), List.of()); }
}
```

`Fingerprint.java`:
```java
package com.hcl.jrsmeta.model;

import java.util.Locale;

/** Connection identity used for stitching (spec §7.4). {@code raw} is the sanitized URL or opaque value. */
public record Fingerprint(String dialect, String host, Integer port, String database, String schema, String raw) {

    public static Fingerprint opaque(String scheme, String value) {
        return new Fingerprint(scheme, null, null, null, null, value == null ? "" : value);
    }

    public String key() {
        String d = lower(dialect == null ? "unknown" : dialect);
        if (host == null) return d + "://" + lower(raw);
        return d + "://" + lower(host) + (port == null ? "" : ":" + port) + "/" + lower(database);
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=AssetTest,FingerprintTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(model): asset, edge, ref, lookup and fingerprint records"
```

---

### Task 3: Configuration

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/config/ConnectorConfig.java`, `ConfigLoader.java`, `config/connector.example.json`, `src/test/java/com/hcl/jrsmeta/testutil/TestConfig.java`
- Test: `src/test/java/com/hcl/jrsmeta/config/ConfigLoaderTest.java`

**Interfaces:**
- Produces: `ConnectorConfig` record (fields below) + `ConnectorConfig.builder()`; `ConfigLoader.load(Path file, Map<String,String> env)`; `TestConfig.base()` → a Builder pre-filled for tests.
- Env variables: `JRS_META_USER`, `JRS_META_PASSWORD` (required), `JRS_META_BASE_URL` (optional override).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/hcl/jrsmeta/config/ConfigLoaderTest.java`:
```java
package com.hcl.jrsmeta.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir Path tmp;

    private Path write(String json) throws Exception {
        Path p = tmp.resolve("c.json"); Files.writeString(p, json); return p;
    }

    @Test void load_readsFileAndEnvCredentials() throws Exception {
        Path p = write("""
            {"serverId":"prod","baseUrl":"https://jrs.example.com/jasperserver-pro/",
             "includeFolders":["/reports","/public"],"excludeFolders":["/public/Samples"],
             "pageSize":50,"rateLimitPerSecond":2.5,"deepParseDashboards":false}
            """);
        ConnectorConfig c = ConfigLoader.load(p, Map.of("JRS_META_USER", "svc", "JRS_META_PASSWORD", "s3cret"));
        assertThat(c.serverId()).isEqualTo("prod");
        assertThat(c.baseUrl()).isEqualTo("https://jrs.example.com/jasperserver-pro"); // trailing slash stripped
        assertThat(c.username()).isEqualTo("svc");
        assertThat(new String(c.password())).isEqualTo("s3cret");
        assertThat(c.includeFolders()).containsExactly("/reports", "/public");
        assertThat(c.excludeFolders()).containsExactly("/public/Samples");
        assertThat(c.pageSize()).isEqualTo(50);
        assertThat(c.rateLimitPerSecond()).isEqualTo(2.5);
        assertThat(c.deepParseDashboards()).isFalse();
        assertThat(c.fetchOperationalMetadata()).isTrue();      // default
        assertThat(c.maxRetries()).isEqualTo(4);                 // default
        assertThat(c.stateDir()).isEqualTo(Path.of("state"));    // default
    }

    @Test void load_envBaseUrlOverridesFile() throws Exception {
        Path p = write("{\"serverId\":\"x\",\"baseUrl\":\"http://file\"}");
        ConnectorConfig c = ConfigLoader.load(p, Map.of("JRS_META_USER", "u", "JRS_META_PASSWORD", "p", "JRS_META_BASE_URL", "http://env"));
        assertThat(c.baseUrl()).isEqualTo("http://env");
    }

    @Test void load_failsListingAllMissingFields() throws Exception {
        Path p = write("{}");
        assertThatThrownBy(() -> ConfigLoader.load(p, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("serverId").hasMessageContaining("baseUrl")
            .hasMessageContaining("JRS_META_USER").hasMessageContaining("JRS_META_PASSWORD");
    }

    @Test void load_rejectsPasswordInFile() throws Exception {
        Path p = write("{\"serverId\":\"x\",\"baseUrl\":\"http://h\",\"password\":\"nope\"}");
        assertThatThrownBy(() -> ConfigLoader.load(p, Map.of("JRS_META_USER", "u", "JRS_META_PASSWORD", "p")))
            .hasMessageContaining("password");
    }

    @Test void toString_neverContainsPassword() {
        ConnectorConfig c = ConnectorConfig.builder().serverId("s").baseUrl("http://h").username("u").password("hunter2".toCharArray()).build();
        assertThat(c.toString()).doesNotContain("hunter2");
    }

    @Test void builder_defaultIncludeIsRoot() {
        ConnectorConfig c = ConnectorConfig.builder().serverId("s").baseUrl("http://h").username("u").password("p".toCharArray()).build();
        assertThat(c.includeFolders()).isEqualTo(List.of("/"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ConfigLoaderTest`
Expected: compilation error (`ConnectorConfig` not found).

- [ ] **Step 3: Write the config classes**

`ConnectorConfig.java`:
```java
package com.hcl.jrsmeta.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable connector settings. Password is a char[] and is excluded from toString. */
public record ConnectorConfig(
        String serverId, String baseUrl, String username, char[] password,
        List<String> includeFolders, List<String> excludeFolders,
        int pageSize, double rateLimitPerSecond, int maxRetries, int requestTimeoutSeconds,
        int exportPollSeconds, int exportTimeoutSeconds, int checkpointEvery,
        Path stateDir, Path outputDir, Path stitchMapFile,
        boolean fetchOperationalMetadata, boolean deepParseDashboards, boolean redactSqlLiterals) {

    public ConnectorConfig {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        includeFolders = List.copyOf(includeFolders);
        excludeFolders = List.copyOf(excludeFolders);
    }

    public String restBase() { return baseUrl + "/rest_v2"; }

    @Override public String toString() {
        return "ConnectorConfig{serverId=" + serverId + ", baseUrl=" + baseUrl + ", username=" + username
                + ", password=***, includeFolders=" + includeFolders + ", excludeFolders=" + excludeFolders
                + ", pageSize=" + pageSize + ", rateLimitPerSecond=" + rateLimitPerSecond + ", maxRetries=" + maxRetries
                + ", deepParseDashboards=" + deepParseDashboards + ", fetchOperationalMetadata=" + fetchOperationalMetadata + "}";
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String serverId, baseUrl, username; private char[] password = new char[0];
        private List<String> includeFolders = List.of("/"); private List<String> excludeFolders = List.of();
        private int pageSize = 100; private double rateLimitPerSecond = 5.0; private int maxRetries = 4;
        private int requestTimeoutSeconds = 60; private int exportPollSeconds = 2; private int exportTimeoutSeconds = 300;
        private int checkpointEvery = 100;
        private Path stateDir = Path.of("state"), outputDir = Path.of("out"), stitchMapFile = Path.of("config", "stitch-map.json");
        private boolean fetchOperationalMetadata = true, deepParseDashboards = true, redactSqlLiterals = false;

        public Builder serverId(String v) { serverId = v; return this; }
        public Builder baseUrl(String v) { baseUrl = v == null ? null : v.replaceAll("/+$", ""); return this; }
        public Builder username(String v) { username = v; return this; }
        public Builder password(char[] v) { password = v; return this; }
        public Builder includeFolders(List<String> v) { includeFolders = v; return this; }
        public Builder excludeFolders(List<String> v) { excludeFolders = v; return this; }
        public Builder pageSize(int v) { pageSize = v; return this; }
        public Builder rateLimitPerSecond(double v) { rateLimitPerSecond = v; return this; }
        public Builder maxRetries(int v) { maxRetries = v; return this; }
        public Builder requestTimeoutSeconds(int v) { requestTimeoutSeconds = v; return this; }
        public Builder exportPollSeconds(int v) { exportPollSeconds = v; return this; }
        public Builder exportTimeoutSeconds(int v) { exportTimeoutSeconds = v; return this; }
        public Builder checkpointEvery(int v) { checkpointEvery = v; return this; }
        public Builder stateDir(Path v) { stateDir = v; return this; }
        public Builder outputDir(Path v) { outputDir = v; return this; }
        public Builder stitchMapFile(Path v) { stitchMapFile = v; return this; }
        public Builder fetchOperationalMetadata(boolean v) { fetchOperationalMetadata = v; return this; }
        public Builder deepParseDashboards(boolean v) { deepParseDashboards = v; return this; }
        public Builder redactSqlLiterals(boolean v) { redactSqlLiterals = v; return this; }

        public ConnectorConfig build() {
            return new ConnectorConfig(serverId, baseUrl, username, password, includeFolders, excludeFolders,
                    pageSize, rateLimitPerSecond, maxRetries, requestTimeoutSeconds, exportPollSeconds, exportTimeoutSeconds,
                    checkpointEvery, stateDir, outputDir, stitchMapFile, fetchOperationalMetadata, deepParseDashboards, redactSqlLiterals);
        }
    }
}
```

`ConfigLoader.java`:
```java
package com.hcl.jrsmeta.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Loads connector.json; credentials come only from the environment. */
public final class ConfigLoader {
    public static final String ENV_USER = "JRS_META_USER";
    public static final String ENV_PASSWORD = "JRS_META_PASSWORD";
    public static final String ENV_BASE_URL = "JRS_META_BASE_URL";

    private ConfigLoader() {}

    public static ConnectorConfig load(Path file, Map<String, String> env) throws IOException {
        JsonNode n = new ObjectMapper().readTree(Files.readString(file));
        List<String> problems = new ArrayList<>();
        if (n.has("password") || n.has("username")) problems.add("credentials must not be in the config file (remove 'username'/'password'; use " + ENV_USER + "/" + ENV_PASSWORD + ")");

        ConnectorConfig.Builder b = ConnectorConfig.builder();
        String serverId = text(n, "serverId"); if (serverId == null) problems.add("serverId is required");
        String baseUrl = env.getOrDefault(ENV_BASE_URL, text(n, "baseUrl")); if (baseUrl == null) problems.add("baseUrl is required (file or " + ENV_BASE_URL + ")");
        String user = env.get(ENV_USER); if (user == null || user.isBlank()) problems.add(ENV_USER + " environment variable is required");
        String pass = env.get(ENV_PASSWORD); if (pass == null) problems.add(ENV_PASSWORD + " environment variable is required");
        if (!problems.isEmpty()) throw new IllegalArgumentException("Invalid configuration: " + String.join("; ", problems));

        b.serverId(serverId).baseUrl(baseUrl).username(user).password(pass.toCharArray());
        if (n.has("includeFolders")) b.includeFolders(strings(n.get("includeFolders")));
        if (n.has("excludeFolders")) b.excludeFolders(strings(n.get("excludeFolders")));
        if (n.has("pageSize")) b.pageSize(n.get("pageSize").asInt());
        if (n.has("rateLimitPerSecond")) b.rateLimitPerSecond(n.get("rateLimitPerSecond").asDouble());
        if (n.has("maxRetries")) b.maxRetries(n.get("maxRetries").asInt());
        if (n.has("requestTimeoutSeconds")) b.requestTimeoutSeconds(n.get("requestTimeoutSeconds").asInt());
        if (n.has("exportPollSeconds")) b.exportPollSeconds(n.get("exportPollSeconds").asInt());
        if (n.has("exportTimeoutSeconds")) b.exportTimeoutSeconds(n.get("exportTimeoutSeconds").asInt());
        if (n.has("checkpointEvery")) b.checkpointEvery(n.get("checkpointEvery").asInt());
        if (n.has("stateDir")) b.stateDir(Path.of(n.get("stateDir").asText()));
        if (n.has("outputDir")) b.outputDir(Path.of(n.get("outputDir").asText()));
        if (n.has("stitchMapFile")) b.stitchMapFile(Path.of(n.get("stitchMapFile").asText()));
        if (n.has("fetchOperationalMetadata")) b.fetchOperationalMetadata(n.get("fetchOperationalMetadata").asBoolean());
        if (n.has("deepParseDashboards")) b.deepParseDashboards(n.get("deepParseDashboards").asBoolean());
        if (n.has("redactSqlLiterals")) b.redactSqlLiterals(n.get("redactSqlLiterals").asBoolean());
        return b.build();
    }

    private static String text(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asText() : null; }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(e -> out.add(e.asText()));
        return out;
    }
}
```

`config/connector.example.json`:
```json
{
  "serverId": "jrs-prod",
  "baseUrl": "https://jrs.example.com/jasperserver-pro",
  "includeFolders": ["/"],
  "excludeFolders": ["/temp", "/public/Samples"],
  "pageSize": 100,
  "rateLimitPerSecond": 5,
  "maxRetries": 4,
  "requestTimeoutSeconds": 60,
  "exportPollSeconds": 2,
  "exportTimeoutSeconds": 300,
  "checkpointEvery": 100,
  "stateDir": "state",
  "outputDir": "out",
  "stitchMapFile": "config/stitch-map.json",
  "fetchOperationalMetadata": true,
  "deepParseDashboards": true,
  "redactSqlLiterals": false
}
```

`src/test/java/com/hcl/jrsmeta/testutil/TestConfig.java`:
```java
package com.hcl.jrsmeta.testutil;

import com.hcl.jrsmeta.config.ConnectorConfig;

public final class TestConfig {
    private TestConfig() {}
    /** Builder with no rate limit and no retries so unit tests stay fast and deterministic. */
    public static ConnectorConfig.Builder base() {
        return ConnectorConfig.builder().serverId("test").baseUrl("http://localhost:0")
                .username("u").password("p".toCharArray()).rateLimitPerSecond(0).maxRetries(0)
                .exportPollSeconds(0).exportTimeoutSeconds(1);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=ConfigLoaderTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src config && git commit -m "feat(config): ConnectorConfig builder, JSON loader with env-only credentials"
```

---

### Task 4: HTTP client with auth, retry, rate limit and audit log

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/client/{AuditLog,JsonlAuditLog,RateLimiter,JrsClientException,JrsClient}.java`
- Test: `src/test/java/com/hcl/jrsmeta/client/JrsClientTest.java`, `RateLimiterTest.java`

**Interfaces:**
- Consumes: `ConnectorConfig` (Task 3).
- Produces:
  - `interface AuditLog extends Closeable { void record(String method, String path, int status, long millis); AuditLog NOOP; }`
  - `JsonlAuditLog(Path file, String serviceAccount)` appends one JSON object per request.
  - `JrsClient(ConnectorConfig cfg, AuditLog audit)`; `Response get(String pathAndQuery, String accept)`, `Response post(String path, String contentType, byte[] body, String accept)`; `record Response(int status, HttpHeaders headers, byte[] body)` with `header(name)` and `text()`; `static String encodePath(String uri)`.
  - `get`/`post` return normally for 2xx and 404 (caller decides); throw `JrsClientException(status, message)` for 401/403 immediately and for other 4xx; retry 429/502/503/504 and `IOException` up to `maxRetries`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/hcl/jrsmeta/client/RateLimiterTest.java`:
```java
package com.hcl.jrsmeta.client;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {
    @Test void acquire_spacesCallsByInterval() throws Exception {
        RateLimiter l = new RateLimiter(50);           // 20 ms apart
        long t0 = System.nanoTime();
        for (int i = 0; i < 4; i++) l.acquire();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isGreaterThanOrEqualTo(55);     // 3 gaps * 20ms, tolerant
    }
    @Test void zeroRate_meansUnlimited() throws Exception {
        RateLimiter l = new RateLimiter(0);
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) l.acquire();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(50);
    }
}
```

`src/test/java/com/hcl/jrsmeta/client/JrsClientTest.java`:
```java
package com.hcl.jrsmeta.client;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.*;

class JrsClientTest {
    @RegisterExtension static WireMockExtension wm = WireMockExtension.newInstance().build();

    record Entry(String method, String path, int status) {}
    static final class CapturingAudit implements AuditLog {
        final List<Entry> entries = new ArrayList<>();
        public void record(String m, String p, int s, long ms) { entries.add(new Entry(m, p, s)); }
        public void close() {}
    }

    private ConnectorConfig cfg(int retries) {
        return TestConfig.base().baseUrl(wm.baseUrl() + "/jasperserver-pro").username("svc").password("pw".toCharArray()).maxRetries(retries).build();
    }

    @Test void get_sendsBasicAuthAndAcceptUnderRestV2() throws Exception {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/serverInfo")).willReturn(okJson("{\"version\":\"10.0.0\"}")));
        JrsClient c = new JrsClient(cfg(0), AuditLog.NOOP);
        JrsClient.Response r = c.get("/serverInfo", "application/json");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text()).contains("10.0.0");
        String expected = "Basic " + Base64.getEncoder().encodeToString("svc:pw".getBytes(StandardCharsets.UTF_8));
        wm.verify(getRequestedFor(urlEqualTo("/jasperserver-pro/rest_v2/serverInfo"))
                .withHeader("Authorization", equalTo(expected)).withHeader("Accept", equalTo("application/json")));
    }

    @Test void get_retriesOn503ThenSucceeds() throws Exception {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).inScenario("r").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).inScenario("r").whenScenarioStateIs("ok").willReturn(ok("fine")));
        CapturingAudit audit = new CapturingAudit();
        JrsClient c = new JrsClient(cfg(2), audit, ms -> {});          // no real sleeping
        assertThat(c.get("/x", "*/*").text()).isEqualTo("fine");
        assertThat(audit.entries).extracting(Entry::status).containsExactly(503, 200);
    }

    @Test void get_401FailsFastWithoutRetry() {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).willReturn(aResponse().withStatus(401)));
        JrsClient c = new JrsClient(cfg(3), AuditLog.NOOP, ms -> {});
        assertThatThrownBy(() -> c.get("/x", "*/*")).isInstanceOf(JrsClientException.class)
                .hasMessageContaining("JRS_META_USER").extracting("status").isEqualTo(401);
        wm.verify(1, getRequestedFor(urlEqualTo("/jasperserver-pro/rest_v2/x")));
    }

    @Test void get_404ReturnsResponseNotException() throws Exception {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).willReturn(aResponse().withStatus(404)));
        assertThat(new JrsClient(cfg(0), AuditLog.NOOP).get("/x", "*/*").status()).isEqualTo(404);
    }

    @Test void get_givesUpAfterMaxRetries() {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).willReturn(aResponse().withStatus(503)));
        JrsClient c = new JrsClient(cfg(2), AuditLog.NOOP, ms -> {});
        assertThatThrownBy(() -> c.get("/x", "*/*")).isInstanceOf(JrsClientException.class).hasMessageContaining("503");
        wm.verify(3, getRequestedFor(urlEqualTo("/jasperserver-pro/rest_v2/x")));
    }

    @Test void audit_neverRecordsAuthorization() throws Exception {
        wm.stubFor(get(urlEqualTo("/jasperserver-pro/rest_v2/x")).willReturn(ok("")));
        CapturingAudit audit = new CapturingAudit();
        new JrsClient(cfg(0), audit).get("/x", "*/*");
        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).toString()).doesNotContain("Basic").doesNotContain("pw");
    }

    @Test void encodePath_keepsSlashesEncodesSpaces() {
        assertThat(JrsClient.encodePath("/public/Samples/1._Supermart Dashboard/a&b"))
                .isEqualTo("/public/Samples/1._Supermart%20Dashboard/a%26b");
    }

    @Test void post_sendsBodyWithContentType() throws Exception {
        wm.stubFor(post(urlEqualTo("/jasperserver-pro/rest_v2/export")).willReturn(okJson("{\"id\":\"e1\"}")));
        JrsClient.Response r = new JrsClient(cfg(0), AuditLog.NOOP).post("/export", "application/json", "{\"uris\":[]}".getBytes(), "application/json");
        assertThat(r.text()).contains("e1");
        wm.verify(postRequestedFor(urlEqualTo("/jasperserver-pro/rest_v2/export")).withHeader("Content-Type", equalTo("application/json")).withRequestBody(equalTo("{\"uris\":[]}")));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=JrsClientTest,RateLimiterTest`
Expected: compilation errors (classes missing).

- [ ] **Step 3: Write the client classes**

`AuditLog.java`:
```java
package com.hcl.jrsmeta.client;

import java.io.Closeable;

/** Spec §13: audit trail of every read. Implementations must never receive credentials. */
public interface AuditLog extends Closeable {
    void record(String method, String path, int status, long millis);
    @Override default void close() {}
    AuditLog NOOP = (m, p, s, ms) -> {};
}
```

`JsonlAuditLog.java`:
```java
package com.hcl.jrsmeta.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonlAuditLog implements AuditLog {
    private final BufferedWriter out;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String serviceAccount;

    public JsonlAuditLog(Path file, String serviceAccount) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.serviceAccount = serviceAccount;
    }

    @Override public synchronized void record(String method, String path, int status, long millis) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", Instant.now().toString()); e.put("account", serviceAccount);
        e.put("method", method); e.put("path", path); e.put("status", status); e.put("ms", millis);
        try { out.write(mapper.writeValueAsString(e)); out.newLine(); out.flush(); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    @Override public synchronized void close() throws IOException { out.close(); }
}
```

`RateLimiter.java`:
```java
package com.hcl.jrsmeta.client;

import java.util.concurrent.TimeUnit;

/** Minimal fixed-interval limiter; 0 permits/second disables limiting. */
public final class RateLimiter {
    private final long intervalNanos;
    private long nextAllowed;

    public RateLimiter(double permitsPerSecond) {
        intervalNanos = permitsPerSecond <= 0 ? 0 : (long) (1_000_000_000L / permitsPerSecond);
    }

    public synchronized void acquire() throws InterruptedException {
        if (intervalNanos == 0) return;
        long now = System.nanoTime();
        if (now < nextAllowed) { TimeUnit.NANOSECONDS.sleep(nextAllowed - now); now = nextAllowed; }
        nextAllowed = now + intervalNanos;
    }
}
```

`JrsClientException.java`:
```java
package com.hcl.jrsmeta.client;

import java.io.IOException;

public class JrsClientException extends IOException {
    private final int status;
    public JrsClientException(int status, String message) { super(message); this.status = status; }
    public JrsClientException(int status, String message, Throwable cause) { super(message, cause); this.status = status; }
    public int getStatus() { return status; }
}
```

`JrsClient.java`:
```java
package com.hcl.jrsmeta.client;

import com.hcl.jrsmeta.config.ConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** Read-only HTTP transport for JRS REST v2. Knows nothing about repository artifacts. */
public final class JrsClient {
    private static final Logger log = LoggerFactory.getLogger(JrsClient.class);
    private static final Set<Integer> RETRY_STATUS = Set.of(429, 502, 503, 504);

    public record Response(int status, HttpHeaders headers, byte[] body) {
        public Optional<String> header(String name) { return headers.firstValue(name); }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private final HttpClient http;
    private final String restBase;
    private final String authHeader;
    private final RateLimiter limiter;
    private final AuditLog audit;
    private final int maxRetries;
    private final Duration timeout;
    private final Sleeper sleeper;

    public JrsClient(ConnectorConfig cfg, AuditLog audit) { this(cfg, audit, Thread::sleep); }

    JrsClient(ConnectorConfig cfg, AuditLog audit, Sleeper sleeper) {
        this.restBase = cfg.restBase();
        this.audit = audit;
        this.sleeper = sleeper;
        this.maxRetries = cfg.maxRetries();
        this.timeout = Duration.ofSeconds(cfg.requestTimeoutSeconds());
        this.limiter = new RateLimiter(cfg.rateLimitPerSecond());
        String creds = cfg.username() + ":" + new String(cfg.password());
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
        // Cookie manager keeps the JSESSIONID so Basic auth does not open a new server session per request (spec G6).
        this.http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Percent-encode each path segment, keeping '/'. Repository URIs may contain spaces and '&'. */
    public static String encodePath(String uri) {
        return Arrays.stream(uri.split("/", -1))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    public Response get(String pathAndQuery, String accept) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(restBase + pathAndQuery)).header("Accept", accept).GET();
        return send(b, "GET", pathAndQuery);
    }

    public Response post(String path, String contentType, byte[] body, String accept) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(restBase + path)).header("Accept", accept)
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return send(b, "POST", path);
    }

    private Response send(HttpRequest.Builder b, String method, String path) throws IOException {
        HttpRequest req = b.header("Authorization", authHeader).timeout(timeout).build();
        IOException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            acquirePermit();
            long t0 = System.nanoTime();
            try {
                HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                audit.record(method, path, r.statusCode(), (System.nanoTime() - t0) / 1_000_000);
                int s = r.statusCode();
                if (s == 401 || s == 403) throw new JrsClientException(s, method + " " + path + " -> HTTP " + s
                        + ": authentication/authorization failed (check JRS_META_USER / JRS_META_PASSWORD and repository read permission)");
                if (RETRY_STATUS.contains(s)) {
                    last = new JrsClientException(s, method + " " + path + " -> HTTP " + s);
                    if (attempt < maxRetries) { backoff(attempt, s); continue; }
                    throw last;
                }
                if (s >= 400 && s != 404) throw new JrsClientException(s, method + " " + path + " -> HTTP " + s + " " + truncate(r.body()));
                return new Response(s, r.headers(), r.body());
            } catch (JrsClientException e) {
                throw e;
            } catch (IOException e) {
                audit.record(method, path, -1, (System.nanoTime() - t0) / 1_000_000);
                last = e;
                if (attempt < maxRetries) backoff(attempt, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while calling " + path, e);
            }
        }
        throw new JrsClientException(-1, "giving up after " + (maxRetries + 1) + " attempts: " + method + " " + path, last);
    }

    private void acquirePermit() throws IOException {
        try { limiter.acquire(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted", e); }
    }

    private void backoff(int attempt, int status) throws IOException {
        long ms = (long) (500 * Math.pow(2, attempt)) + ThreadLocalRandom.current().nextInt(250);
        log.warn("retrying after {} ms (attempt {} of {}, status {})", ms, attempt + 1, maxRetries, status);
        try { sleeper.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted during backoff", e); }
    }

    private static String truncate(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8);
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=JrsClientTest,RateLimiterTest`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(client): REST v2 transport with basic auth, retry/backoff, rate limit, audit log"
```

---

### Task 5: Repository facade (listing, descriptors, files, export, jobs, permissions)

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/client/{ServerInfo,JrsRepository,RestJrsRepository}.java`
- Test: `src/test/java/com/hcl/jrsmeta/client/RestJrsRepositoryTest.java`

**Interfaces:**
- Consumes: `JrsClient` (Task 4), `ResourceLookup` (Task 2).
- Produces:
  ```java
  public record ServerInfo(String version, String edition, String editionName, String build) { boolean isPro() }
  public interface JrsRepository {
      ServerInfo serverInfo() throws IOException;
      List<ResourceLookup> listResources(String folderUri, boolean recursive) throws IOException;  // all pages
      JsonNode descriptor(String uri, String resourceType) throws IOException;                     // 404 -> JrsClientException(404)
      byte[] file(String uri, String accept) throws IOException;                                  // 404 -> JrsClientException(404)
      byte[] exportArchive(List<String> uris) throws IOException;                                 // zip bytes
      List<JsonNode> jobs(String reportUri) throws IOException;                                   // summaries; 204 -> empty
      JsonNode job(long id) throws IOException;                                                   // full job
      List<JsonNode> permissions(String uri) throws IOException;                                  // effective; 204 -> empty
  }
  ```
  `RestJrsRepository(JrsClient client, ConnectorConfig cfg)` implements it.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/hcl/jrsmeta/client/RestJrsRepositoryTest.java`:
```java
package com.hcl.jrsmeta.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.model.ResourceLookup;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.*;

class RestJrsRepositoryTest {
    @RegisterExtension static WireMockExtension wm = WireMockExtension.newInstance().build();
    static final String R = "/jasperserver-pro/rest_v2";

    private RestJrsRepository repo(int pageSize) {
        ConnectorConfig cfg = TestConfig.base().baseUrl(wm.baseUrl() + "/jasperserver-pro").pageSize(pageSize).build();
        return new RestJrsRepository(new JrsClient(cfg, AuditLog.NOOP), cfg);
    }

    private static String lookup(String uri, String type) {
        return "{\"uri\":\"" + uri + "\",\"label\":\"L\",\"description\":\"D\",\"resourceType\":\"" + type
                + "\",\"creationDate\":\"2024-01-01T00:00:00\",\"updateDate\":\"2024-02-01T00:00:00\",\"version\":2,\"permissionMask\":1}";
    }

    @Test void listResources_paginatesUntilShortPage() throws Exception {
        wm.stubFor(get(urlPathEqualTo(R + "/resources")).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson("{\"resourceLookup\":[" + lookup("/a", "reportUnit") + "," + lookup("/b", "folder") + "]}")
                        .withHeader("Result-Count", "2").withHeader("Next-Offset", "2")));
        wm.stubFor(get(urlPathEqualTo(R + "/resources")).withQueryParam("offset", equalTo("2"))
                .willReturn(okJson("{\"resourceLookup\":[" + lookup("/c", "jdbcDataSource") + "]}").withHeader("Result-Count", "1")));
        List<ResourceLookup> out = repo(2).listResources("/", true);
        assertThat(out).extracting(ResourceLookup::uri).containsExactly("/a", "/b", "/c");
        assertThat(out.get(0)).isEqualTo(new ResourceLookup("/a", "L", "D", "reportUnit", "2024-01-01T00:00:00", "2024-02-01T00:00:00", 2));
        wm.verify(getRequestedFor(urlPathEqualTo(R + "/resources")).withQueryParam("folderUri", equalTo("/"))
                .withQueryParam("recursive", equalTo("true")).withQueryParam("limit", equalTo("2")));
    }

    @Test void listResources_emptyFolderReturns204() throws Exception {
        wm.stubFor(get(urlPathEqualTo(R + "/resources")).willReturn(aResponse().withStatus(204)));
        assertThat(repo(100).listResources("/empty", true)).isEmpty();
    }

    @Test void listResources_fullLastPageStopsOn204() throws Exception {
        wm.stubFor(get(urlPathEqualTo(R + "/resources")).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson("{\"resourceLookup\":[" + lookup("/a", "reportUnit") + "]}")));
        wm.stubFor(get(urlPathEqualTo(R + "/resources")).withQueryParam("offset", equalTo("1")).willReturn(aResponse().withStatus(204)));
        assertThat(repo(1).listResources("/", true)).hasSize(1);
    }

    @Test void descriptor_usesTypedAcceptAndEncodedPath() throws Exception {
        wm.stubFor(get(urlEqualTo(R + "/resources/reports/My%20Report")).withHeader("Accept", equalTo("application/repository.reportUnit+json"))
                .willReturn(okJson("{\"uri\":\"/reports/My Report\",\"label\":\"x\"}")));
        JsonNode d = repo(100).descriptor("/reports/My Report", "reportUnit");
        assertThat(d.get("label").asText()).isEqualTo("x");
    }

    @Test void descriptor_404Throws() {
        wm.stubFor(get(urlEqualTo(R + "/resources/gone")).willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> repo(100).descriptor("/gone", "reportUnit")).isInstanceOf(JrsClientException.class)
                .extracting("status").isEqualTo(404);
    }

    @Test void file_returnsRawBytes() throws Exception {
        wm.stubFor(get(urlEqualTo(R + "/resources/reports/x_files/main_jrxml")).withHeader("Accept", equalTo("application/xml"))
                .willReturn(ok("<jasperReport/>")));
        assertThat(new String(repo(100).file("/reports/x_files/main_jrxml", "application/xml"))).isEqualTo("<jasperReport/>");
    }

    @Test void exportArchive_postsPollsThenDownloads() throws Exception {
        wm.stubFor(post(urlEqualTo(R + "/export")).withRequestBody(equalToJson("{\"uris\":[\"/dash/a\"],\"parameters\":[]}"))
                .willReturn(okJson("{\"id\":\"e1\",\"phase\":\"inprogress\"}")));
        wm.stubFor(get(urlEqualTo(R + "/export/e1/state")).inScenario("s").whenScenarioStateIs(STARTED)
                .willReturn(okJson("{\"phase\":\"inprogress\"}")).willSetStateTo("done"));
        wm.stubFor(get(urlEqualTo(R + "/export/e1/state")).inScenario("s").whenScenarioStateIs("done")
                .willReturn(okJson("{\"phase\":\"finished\"}")));
        wm.stubFor(get(urlEqualTo(R + "/export/e1/exportFile")).willReturn(aResponse().withStatus(200).withBody(new byte[]{'P', 'K', 3, 4})));
        assertThat(repo(100).exportArchive(List.of("/dash/a"))).startsWith((byte) 'P', (byte) 'K');
    }

    @Test void exportArchive_failedPhaseThrows() {
        wm.stubFor(post(urlEqualTo(R + "/export")).willReturn(okJson("{\"id\":\"e2\"}")));
        wm.stubFor(get(urlEqualTo(R + "/export/e2/state")).willReturn(okJson("{\"phase\":\"failed\",\"message\":\"boom\"}")));
        assertThatThrownBy(() -> repo(100).exportArchive(List.of("/x"))).hasMessageContaining("boom");
    }

    @Test void exportArchive_timesOutWhenNeverFinished() {
        wm.stubFor(post(urlEqualTo(R + "/export")).willReturn(okJson("{\"id\":\"e3\"}")));
        wm.stubFor(get(urlEqualTo(R + "/export/e3/state")).willReturn(okJson("{\"phase\":\"inprogress\"}")));
        assertThatThrownBy(() -> repo(100).exportArchive(List.of("/x"))).hasMessageContaining("timed out");  // exportTimeoutSeconds=1 in TestConfig
    }

    @Test void jobs_204IsEmptyAndSummariesAreReturned() throws Exception {
        wm.stubFor(get(urlPathEqualTo(R + "/jobs")).withQueryParam("reportUnitURI", equalTo("/r/none")).willReturn(aResponse().withStatus(204)));
        wm.stubFor(get(urlPathEqualTo(R + "/jobs")).withQueryParam("reportUnitURI", equalTo("/r/one"))
                .willReturn(okJson("{\"jobsummary\":[{\"id\":7,\"label\":\"nightly\"}]}")));
        assertThat(repo(100).jobs("/r/none")).isEmpty();
        assertThat(repo(100).jobs("/r/one")).hasSize(1).first().matches(j -> j.get("id").asLong() == 7);
    }

    @Test void job_fetchesFullDefinition() throws Exception {
        wm.stubFor(get(urlEqualTo(R + "/jobs/7")).willReturn(okJson("{\"id\":7,\"outputFormats\":{\"outputFormat\":[\"PDF\"]}}")));
        assertThat(repo(100).job(7).path("outputFormats").path("outputFormat").get(0).asText()).isEqualTo("PDF");
    }

    @Test void permissions_effectiveListOr204() throws Exception {
        wm.stubFor(get(urlEqualTo(R + "/permissions/reports/x?effectivePermissions=true"))
                .willReturn(okJson("{\"permission\":[{\"uri\":\"repo:/reports/x\",\"recipient\":\"role:/ROLE_USER\",\"mask\":2}]}")));
        wm.stubFor(get(urlEqualTo(R + "/permissions/reports/none?effectivePermissions=true")).willReturn(aResponse().withStatus(204)));
        assertThat(repo(100).permissions("/reports/x")).hasSize(1);
        assertThat(repo(100).permissions("/reports/none")).isEmpty();
    }

    @Test void serverInfo_mapsEdition() throws Exception {
        wm.stubFor(get(urlEqualTo(R + "/serverInfo")).willReturn(okJson("{\"version\":\"10.0.0\",\"edition\":\"PRO\",\"editionName\":\"Enterprise\",\"build\":\"20240101\"}")));
        ServerInfo s = repo(100).serverInfo();
        assertThat(s.version()).isEqualTo("10.0.0");
        assertThat(s.isPro()).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=RestJrsRepositoryTest`
Expected: compilation errors.

- [ ] **Step 3: Write the repository classes**

`ServerInfo.java`:
```java
package com.hcl.jrsmeta.client;

public record ServerInfo(String version, String edition, String editionName, String build) {
    /** Domains, dashboards and ad hoc views exist only on commercial editions (spec §10 "CE vs Pro"). */
    public boolean isPro() { return edition != null && edition.toUpperCase().startsWith("PRO"); }
}
```

`JrsRepository.java`:
```java
package com.hcl.jrsmeta.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.ResourceLookup;
import java.io.IOException;
import java.util.List;

/** Everything the parsers need from JRS. Implemented over REST; faked in tests. */
public interface JrsRepository {
    ServerInfo serverInfo() throws IOException;
    List<ResourceLookup> listResources(String folderUri, boolean recursive) throws IOException;
    JsonNode descriptor(String uri, String resourceType) throws IOException;
    byte[] file(String uri, String accept) throws IOException;
    byte[] exportArchive(List<String> uris) throws IOException;
    List<JsonNode> jobs(String reportUri) throws IOException;
    JsonNode job(long id) throws IOException;
    List<JsonNode> permissions(String uri) throws IOException;
}
```

`RestJrsRepository.java`:
```java
package com.hcl.jrsmeta.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.model.ResourceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RestJrsRepository implements JrsRepository {
    private static final Logger log = LoggerFactory.getLogger(RestJrsRepository.class);
    private static final String JSON = "application/json";
    private final JrsClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int pageSize;
    private final long pollMillis;
    private final long exportTimeoutMillis;

    public RestJrsRepository(JrsClient client, ConnectorConfig cfg) {
        this.client = client;
        this.pageSize = cfg.pageSize();
        this.pollMillis = cfg.exportPollSeconds() * 1000L;
        this.exportTimeoutMillis = cfg.exportTimeoutSeconds() * 1000L;
    }

    @Override public ServerInfo serverInfo() throws IOException {
        JsonNode n = json(client.get("/serverInfo", JSON));
        return new ServerInfo(text(n, "version"), text(n, "edition"), text(n, "editionName"), text(n, "build"));
    }

    @Override public List<ResourceLookup> listResources(String folderUri, boolean recursive) throws IOException {
        List<ResourceLookup> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            String q = "/resources?folderUri=" + enc(folderUri) + "&recursive=" + recursive + "&limit=" + pageSize + "&offset=" + offset;
            JrsClient.Response r = client.get(q, JSON);
            if (r.status() == 204 || r.status() == 404 || r.body().length == 0) break;        // 204 = no (more) resources
            int n = 0;
            for (JsonNode e : mapper.readTree(r.body()).path("resourceLookup")) { out.add(toLookup(e)); n++; }
            if (n == 0) break;
            int next = r.header("Next-Offset").map(Integer::parseInt).orElse(offset + n);
            if (n < pageSize || next <= offset) break;
            offset = next;
        }
        log.debug("listed {} resources under {}", out.size(), folderUri);
        return out;
    }

    @Override public JsonNode descriptor(String uri, String resourceType) throws IOException {
        JrsClient.Response r = client.get("/resources" + JrsClient.encodePath(uri), "application/repository." + resourceType + "+json");
        if (r.status() == 404) throw new JrsClientException(404, "resource not found: " + uri);
        return mapper.readTree(r.body());
    }

    @Override public byte[] file(String uri, String accept) throws IOException {
        JrsClient.Response r = client.get("/resources" + JrsClient.encodePath(uri), accept);
        if (r.status() == 404) throw new JrsClientException(404, "file not found: " + uri);
        return r.body();
    }

    @Override public byte[] exportArchive(List<String> uris) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("uris"); uris.forEach(arr::add);
        body.putArray("parameters");
        JsonNode started = json(client.post("/export", JSON, mapper.writeValueAsBytes(body), JSON));
        String id = text(started, "id");
        if (id == null) throw new IOException("export did not return an id: " + started);
        long deadline = System.currentTimeMillis() + exportTimeoutMillis;
        while (true) {
            JsonNode state = json(client.get("/export/" + id + "/state", JSON));
            String phase = text(state, "phase");
            if ("finished".equalsIgnoreCase(phase)) break;
            if ("failed".equalsIgnoreCase(phase)) throw new IOException("export " + id + " failed: " + text(state, "message"));
            if (System.currentTimeMillis() > deadline) throw new IOException("export " + id + " timed out after " + exportTimeoutMillis + " ms (phase=" + phase + ")");
            try { Thread.sleep(pollMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted", e); }
        }
        return client.get("/export/" + id + "/exportFile", "application/zip").body();   // note: bare GET /export/{id} is 405
    }

    @Override public List<JsonNode> jobs(String reportUri) throws IOException {
        JrsClient.Response r = client.get("/jobs?reportUnitURI=" + enc(reportUri), JSON);
        return r.status() == 204 || r.body().length == 0 ? List.of() : toList(mapper.readTree(r.body()).path("jobsummary"));
    }

    @Override public JsonNode job(long id) throws IOException {
        return json(client.get("/jobs/" + id, "application/job+json"));
    }

    @Override public List<JsonNode> permissions(String uri) throws IOException {
        JrsClient.Response r = client.get("/permissions" + JrsClient.encodePath(uri) + "?effectivePermissions=true", JSON);
        return r.status() == 204 || r.status() == 404 || r.body().length == 0 ? List.of() : toList(mapper.readTree(r.body()).path("permission"));
    }

    private JsonNode json(JrsClient.Response r) throws IOException {
        if (r.status() == 404) throw new JrsClientException(404, "not found");
        return r.body().length == 0 ? mapper.createObjectNode() : mapper.readTree(r.body());
    }

    private static List<JsonNode> toList(JsonNode arr) { List<JsonNode> l = new ArrayList<>(); arr.forEach(l::add); return l; }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private static String text(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asText() : null; }

    static ResourceLookup toLookup(JsonNode e) {
        return new ResourceLookup(text(e, "uri"), text(e, "label"), text(e, "description"), text(e, "resourceType"),
                text(e, "creationDate"), text(e, "updateDate"), e.hasNonNull("version") ? e.get("version").asInt() : null);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=RestJrsRepositoryTest`
Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(client): JrsRepository facade with pagination, export polling, jobs and permissions"
```

---

### Task 6: Folder filter, sync state and change detection

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/crawl/{FolderFilter,SyncState,ChangeSet,Crawler}.java`
- Test: `src/test/java/com/hcl/jrsmeta/crawl/FolderFilterTest.java`, `SyncStateTest.java`, `CrawlerTest.java`

**Interfaces:**
- Consumes: `ResourceLookup` (Task 2).
- Produces:
  - `FolderFilter(List<String> include, List<String> exclude)`; `boolean accepts(String uri)`.
  - `SyncState` — `static SyncState load(Path file)`, `void save()`, `boolean isChanged(ResourceLookup)`, `void markProcessed(ResourceLookup)`, `void remove(String uri)`, `Set<String> knownUris()`, `boolean lastRunOk()`, `void beginRun()`, `void completeRun()`, `String lastCompletedRun()`.
  - `record ChangeSet(List<ResourceLookup> toProcess, List<String> deletedUris, int unchanged, boolean forcedFull)`.
  - `Crawler.plan(List<ResourceLookup> inventory, SyncState state, boolean full)` → `ChangeSet`.

- [ ] **Step 1: Write the failing tests**

`FolderFilterTest.java`:
```java
package com.hcl.jrsmeta.crawl;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FolderFilterTest {
    @Test void rootIncludeAcceptsEverythingExceptExcluded() {
        FolderFilter f = new FolderFilter(List.of("/"), List.of("/public/Samples", "/temp"));
        assertThat(f.accepts("/reports/x")).isTrue();
        assertThat(f.accepts("/public/Samples/Reports/a")).isFalse();
        assertThat(f.accepts("/public/SamplesX/a")).isTrue();      // prefix must end at a segment boundary
        assertThat(f.accepts("/temp")).isFalse();
    }
    @Test void includeMustMatchASegmentPrefix() {
        FolderFilter f = new FolderFilter(List.of("/reports/sales"), List.of());
        assertThat(f.accepts("/reports/sales")).isTrue();
        assertThat(f.accepts("/reports/sales/q1")).isTrue();
        assertThat(f.accepts("/reports/salesman/x")).isFalse();
        assertThat(f.accepts("/reports")).isFalse();
    }
    @Test void hiddenFilesChildrenAreAlwaysSkipped() {
        FolderFilter f = new FolderFilter(List.of("/"), List.of());
        assertThat(f.accepts("/reports/x_files/main_jrxml")).isFalse();
    }
}
```

`SyncStateTest.java`:
```java
package com.hcl.jrsmeta.crawl;

import com.hcl.jrsmeta.model.ResourceLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class SyncStateTest {
    @TempDir Path tmp;
    ResourceLookup a1 = new ResourceLookup("/a", "A", null, "reportUnit", "2024-01-01T00:00:00", "2024-01-02T00:00:00", 1);
    ResourceLookup a2 = new ResourceLookup("/a", "A", null, "reportUnit", "2024-01-01T00:00:00", "2024-01-03T00:00:00", 2);

    @Test void missingFileIsEmptyStateWithLastRunOk() throws Exception {
        SyncState s = SyncState.load(tmp.resolve("missing.json"));
        assertThat(s.knownUris()).isEmpty();
        assertThat(s.lastRunOk()).isTrue();
        assertThat(s.isChanged(a1)).isTrue();
    }
    @Test void roundTripPersistsEntriesAndRunFlags() throws Exception {
        Path f = tmp.resolve("state.json");
        SyncState s = SyncState.load(f);
        s.beginRun(); s.markProcessed(a1); s.save();
        SyncState crashed = SyncState.load(f);
        assertThat(crashed.lastRunOk()).isFalse();                 // beginRun without completeRun
        assertThat(crashed.isChanged(a1)).isFalse();
        assertThat(crashed.isChanged(a2)).isTrue();                // updateDate/version differ
        crashed.completeRun(); crashed.save();
        SyncState done = SyncState.load(f);
        assertThat(done.lastRunOk()).isTrue();
        assertThat(done.lastCompletedRun()).isNotNull();
        assertThat(Files.readString(f)).contains("\"/a\"");
    }
    @Test void removeForgetsUri() throws Exception {
        SyncState s = SyncState.load(tmp.resolve("s.json"));
        s.markProcessed(a1); s.remove("/a");
        assertThat(s.knownUris()).isEmpty();
    }
}
```

`CrawlerTest.java`:
```java
package com.hcl.jrsmeta.crawl;

import com.hcl.jrsmeta.model.ResourceLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CrawlerTest {
    @TempDir Path tmp;
    ResourceLookup a = new ResourceLookup("/a", "A", null, "reportUnit", null, "2024-01-02T00:00:00", 1);
    ResourceLookup aNew = new ResourceLookup("/a", "A", null, "reportUnit", null, "2024-01-05T00:00:00", 2);
    ResourceLookup b = new ResourceLookup("/b", "B", null, "folder", null, "2024-01-02T00:00:00", 1);
    ResourceLookup c = new ResourceLookup("/c", "C", null, "jdbcDataSource", null, "2024-01-02T00:00:00", 1);

    @Test void incremental_selectsChangedAndNewDetectsDeleted() throws Exception {
        SyncState s = SyncState.load(tmp.resolve("s.json"));
        s.markProcessed(a); s.markProcessed(b); s.completeRun();
        ChangeSet cs = new Crawler().plan(List.of(aNew, b, c), s, false);
        assertThat(cs.toProcess()).extracting(ResourceLookup::uri).containsExactly("/a", "/c");
        assertThat(cs.unchanged()).isEqualTo(1);
        assertThat(cs.deletedUris()).isEmpty();
        assertThat(cs.forcedFull()).isFalse();
        ChangeSet cs2 = new Crawler().plan(List.of(b), s, false);
        assertThat(cs2.deletedUris()).containsExactly("/a");
    }
    @Test void full_processesEverything() throws Exception {
        SyncState s = SyncState.load(tmp.resolve("s.json"));
        s.markProcessed(a); s.completeRun();
        assertThat(new Crawler().plan(List.of(a, b), s, true).toProcess()).hasSize(2);
    }
    @Test void plan_forcesFullWhenLastRunNotOk() throws Exception {
        SyncState s = SyncState.load(tmp.resolve("s.json"));
        s.beginRun(); s.markProcessed(a);                            // checkpointed, never completed
        ChangeSet cs = new Crawler().plan(List.of(a), s, false);
        assertThat(cs.forcedFull()).isTrue();
        assertThat(cs.toProcess()).hasSize(1);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=FolderFilterTest,SyncStateTest,CrawlerTest`
Expected: compilation errors.

- [ ] **Step 3: Write the crawl classes**

`FolderFilter.java`:
```java
package com.hcl.jrsmeta.crawl;

import java.util.List;

/** Include/exclude by folder prefix (segment-aligned). "_files" children are internal and always skipped. */
public final class FolderFilter {
    private final List<String> include;
    private final List<String> exclude;

    public FolderFilter(List<String> include, List<String> exclude) {
        this.include = include.isEmpty() ? List.of("/") : include.stream().map(FolderFilter::norm).toList();
        this.exclude = exclude.stream().map(FolderFilter::norm).toList();
    }

    public boolean accepts(String uri) {
        if (uri == null || uri.contains("_files/")) return false;
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
```

`SyncState.java`:
```java
package com.hcl.jrsmeta.crawl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hcl.jrsmeta.model.ResourceLookup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-server sync state (spec §8.2): what was processed, with the descriptor's updateDate/version for change
 * detection, plus run bookkeeping so a run that checkpointed but never completed forces a full re-crawl.
 */
public final class SyncState {
    public record Entry(String resourceType, String updateDate, Integer version, String processedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class FileModel {
        public int formatVersion = 1;
        public boolean lastRunOk = true;
        public String lastRunStarted;
        public String lastCompletedRun;
        public Map<String, Entry> resources = new LinkedHashMap<>();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path file;
    private final FileModel m;

    private SyncState(Path file, FileModel m) { this.file = file; this.m = m; }

    public static SyncState load(Path file) throws IOException {
        if (!Files.exists(file)) return new SyncState(file, new FileModel());
        return new SyncState(file, MAPPER.readValue(Files.readString(file), FileModel.class));
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, MAPPER.writeValueAsString(m));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);   // atomic-ish replace
    }

    public synchronized boolean isChanged(ResourceLookup l) {
        Entry e = m.resources.get(l.uri());
        return e == null || !Objects.equals(e.updateDate(), l.updateDate()) || !Objects.equals(e.version(), l.version());
    }

    public synchronized void markProcessed(ResourceLookup l) {
        m.resources.put(l.uri(), new Entry(l.resourceType(), l.updateDate(), l.version(), Instant.now().toString()));
    }

    public synchronized void remove(String uri) { m.resources.remove(uri); }

    public synchronized Set<String> knownUris() { return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(m.resources.keySet())); }

    public synchronized boolean lastRunOk() { return m.lastRunOk; }
    public synchronized void beginRun() { m.lastRunOk = false; m.lastRunStarted = Instant.now().toString(); }
    public synchronized void completeRun() { m.lastRunOk = true; m.lastCompletedRun = Instant.now().toString(); }
    public synchronized String lastCompletedRun() { return m.lastCompletedRun; }
}
```

`ChangeSet.java`:
```java
package com.hcl.jrsmeta.crawl;

import com.hcl.jrsmeta.model.ResourceLookup;
import java.util.List;

public record ChangeSet(List<ResourceLookup> toProcess, List<String> deletedUris, int unchanged, boolean forcedFull) {}
```

`Crawler.java`:
```java
package com.hcl.jrsmeta.crawl;

import com.hcl.jrsmeta.model.ResourceLookup;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Turns a live inventory + previous state into the set of resources to (re)process. */
public final class Crawler {
    public ChangeSet plan(List<ResourceLookup> inventory, SyncState state, boolean full) {
        boolean forced = !full && !state.lastRunOk();   // previous run died between checkpoint and emit
        boolean effectiveFull = full || forced;
        Set<String> seen = new HashSet<>();
        List<ResourceLookup> todo = new ArrayList<>();
        int unchanged = 0;
        for (ResourceLookup l : inventory) {
            if (!seen.add(l.uri())) continue;            // listing of overlapping include folders
            if (effectiveFull || state.isChanged(l)) todo.add(l); else unchanged++;
        }
        List<String> deleted = state.knownUris().stream().filter(u -> !seen.contains(u)).toList();
        return new ChangeSet(todo, deleted, unchanged, forced);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=FolderFilterTest,SyncStateTest,CrawlerTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(crawl): folder filter, persisted sync state, incremental change planning"
```

---

### Task 7: JRXML parser (JR6 and JR7 formats)

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/parse/Xml.java`, `JrxmlModel.java`, `JrxmlParser.java`
- Test: `src/test/java/com/hcl/jrsmeta/parse/JrxmlParserTest.java`

**Interfaces:**
- Produces:
  - `Xml.parse(byte[])` → hardened `org.w3c.dom.Document`; `Xml.children(Element, localName)`, `Xml.descendants(Node, localName)`, `Xml.attr(Element, name)`, `Xml.childText(Element, String... localNames)`.
  - `record JrxmlModel(String queryLanguage, String queryText, List<Field> fields, List<Parameter> parameters, List<Variable> variables, List<Dataset> subDatasets, List<String> subreportExpressions)` with nested records `Field(name, javaClass)`, `Parameter(name, javaClass, defaultValueExpression)`, `Variable(name, javaClass, calculation, expression)`, `Dataset(name, queryLanguage, queryText, List<Field> fields)`.
  - `JrxmlParser.parse(byte[] xml)` → `JrxmlModel`. Accepts JR6 (`queryString`, `subreportExpression`, `variableExpression`) and JR7 (`query`, `expression`) element names.

- [ ] **Step 1: Write the failing test**

```java
package com.hcl.jrsmeta.parse;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class JrxmlParserTest {
    static final String JR7 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports" name="rev" language="java">
          <parameter name="Region" class="java.lang.String"><defaultValueExpression><![CDATA["WEST"]]></defaultValueExpression></parameter>
          <query language="SQL"><![CDATA[SELECT r.region_name AS region, SUM(s.amount) AS revenue FROM sales_fact s JOIN region r ON r.region_id = s.region_id WHERE r.region_name = $P{Region} GROUP BY r.region_name]]></query>
          <field name="region" class="java.lang.String"/>
          <field name="revenue" class="java.math.BigDecimal"/>
          <variable name="total" class="java.math.BigDecimal" calculation="Sum"><expression><![CDATA[$F{revenue}]]></expression></variable>
          <subDataset name="chartData"><query language="sql"><![CDATA[SELECT 1 AS one]]></query><field name="one" class="java.lang.Integer"/></subDataset>
          <detail><band height="20">
            <element kind="subreport" x="0" y="0" width="100" height="20"><expression><![CDATA["repo:/reports/sub/detail"]]></expression></element>
          </band></detail>
        </jasperReport>
        """;

    static final String JR6 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports" name="old">
          <queryString language="sql"><![CDATA[SELECT a FROM t]]></queryString>
          <field name="a" class="java.lang.Long"/>
          <variable name="v" class="java.lang.Long" calculation="Count"><variableExpression><![CDATA[$F{a}]]></variableExpression></variable>
          <detail><band height="10"><subreport><subreportExpression><![CDATA[$P{SUBREPORT_DIR} + "child.jasper"]]></subreportExpression></subreport></band></detail>
        </jasperReport>
        """;

    static final String DOCTYPE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE jasperReport PUBLIC "//JasperReports//DTD Report Design//EN" "http://jasperreports.sourceforge.net/dtds/jasperreport.dtd">
        <jasperReport name="legacy">
          <queryString><![CDATA[SELECT 1]]></queryString>
          <field name="x" class="java.lang.Integer"/>
        </jasperReport>
        """;

    @Test void parse_jr7Format() throws Exception {
        JrxmlModel m = new JrxmlParser().parse(JR7.getBytes(StandardCharsets.UTF_8));
        assertThat(m.queryLanguage()).isEqualTo("sql");
        assertThat(m.queryText()).startsWith("SELECT r.region_name");
        assertThat(m.fields()).containsExactly(new JrxmlModel.Field("region", "java.lang.String"), new JrxmlModel.Field("revenue", "java.math.BigDecimal"));
        assertThat(m.parameters()).containsExactly(new JrxmlModel.Parameter("Region", "java.lang.String", "\"WEST\""));
        assertThat(m.variables()).containsExactly(new JrxmlModel.Variable("total", "java.math.BigDecimal", "Sum", "$F{revenue}"));
        assertThat(m.subDatasets()).hasSize(1);
        assertThat(m.subDatasets().get(0).name()).isEqualTo("chartData");
        assertThat(m.subDatasets().get(0).fields()).containsExactly(new JrxmlModel.Field("one", "java.lang.Integer"));
        assertThat(m.subreportExpressions()).containsExactly("\"repo:/reports/sub/detail\"");
    }

    @Test void parse_jr6Format() throws Exception {
        JrxmlModel m = new JrxmlParser().parse(JR6.getBytes(StandardCharsets.UTF_8));
        assertThat(m.queryText()).isEqualTo("SELECT a FROM t");
        assertThat(m.variables().get(0).expression()).isEqualTo("$F{a}");
        assertThat(m.subreportExpressions()).containsExactly("$P{SUBREPORT_DIR} + \"child.jasper\"");
    }

    @Test void parse_doctypeJrxmlParsesWithoutNetwork() throws Exception {
        JrxmlModel m = new JrxmlParser().parse(DOCTYPE.getBytes(StandardCharsets.UTF_8));   // must not fetch the DTD
        assertThat(m.queryLanguage()).isEqualTo("sql");                                      // default when absent
        assertThat(m.fields()).hasSize(1);
    }

    @Test void parse_rejectsExternalEntities() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><jasperReport><queryString>&e;</queryString></jasperReport>";
        assertThatThrownBy(() -> new JrxmlParser().parse(xxe.getBytes(StandardCharsets.UTF_8))).isInstanceOf(java.io.IOException.class);
    }

    @Test void parse_topLevelFieldsExcludeSubDatasetFields() throws Exception {
        JrxmlModel m = new JrxmlParser().parse(JR7.getBytes(StandardCharsets.UTF_8));
        assertThat(m.fields()).extracting(JrxmlModel.Field::name).doesNotContain("one");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=JrxmlParserTest`
Expected: compilation errors.

- [ ] **Step 3: Write the XML helper, model and parser**

`Xml.java`:
```java
package com.hcl.jrsmeta.parse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Namespace-agnostic DOM helpers over a hardened parser (no external DTD/entity fetches). */
public final class Xml {
    private Xml() {}

    public static Document parse(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Legacy JRXML carries a <!DOCTYPE>; allow it but never resolve anything external.
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            if (hasEntityReference(d.getDocumentElement())) throw new IOException("XML contains entity references; refusing to parse");
            return d;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("XML parse failed: " + e.getMessage(), e);
        }
    }

    private static boolean hasEntityReference(Node n) {
        if (n == null) return false;
        if (n.getNodeType() == Node.ENTITY_REFERENCE_NODE) return true;
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) if (hasEntityReference(c)) return true;
        return false;
    }

    public static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling())
            if (c instanceof Element e && localName.equals(e.getLocalName() == null ? e.getTagName() : e.getLocalName())) out.add(e);
        return out;
    }

    public static List<Element> descendants(Node root, String localName) {
        Element start = root instanceof Document d ? d.getDocumentElement() : (Element) root;
        NodeList nl = start.getElementsByTagNameNS("*", localName);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        if (out.isEmpty()) {                                   // no-namespace documents (legacy DOCTYPE jrxml)
            NodeList plain = start.getElementsByTagName(localName);
            for (int i = 0; i < plain.getLength(); i++) out.add((Element) plain.item(i));
        }
        return out;
    }

    public static String attr(Element e, String name) { return e.hasAttribute(name) ? e.getAttribute(name) : null; }

    /** Text of the first direct child matching any of the names, trimmed; null if none. */
    public static String childText(Element parent, String... localNames) {
        for (String n : localNames) for (Element c : children(parent, n)) return c.getTextContent().trim();
        return null;
    }
}
```

`JrxmlModel.java`:
```java
package com.hcl.jrsmeta.parse;

import java.util.List;

public record JrxmlModel(String queryLanguage, String queryText, List<Field> fields, List<Parameter> parameters,
                         List<Variable> variables, List<Dataset> subDatasets, List<String> subreportExpressions) {
    public record Field(String name, String javaClass) {}
    public record Parameter(String name, String javaClass, String defaultValueExpression) {}
    public record Variable(String name, String javaClass, String calculation, String expression) {}
    public record Dataset(String name, String queryLanguage, String queryText, List<Field> fields) {}
}
```

`JrxmlParser.java`:
```java
package com.hcl.jrsmeta.parse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Extracts query, fields, parameters, variables, sub-datasets and subreport expressions from JR6/JR7 JRXML. */
public final class JrxmlParser {

    public JrxmlModel parse(byte[] xml) throws IOException {
        Document doc = Xml.parse(xml);
        Element root = doc.getDocumentElement();
        String[] lang = new String[1]; String[] text = new String[1];
        query(root, lang, text);

        List<JrxmlModel.Parameter> params = new ArrayList<>();
        for (Element p : Xml.children(root, "parameter"))
            params.add(new JrxmlModel.Parameter(Xml.attr(p, "name"), Xml.attr(p, "class"), Xml.childText(p, "defaultValueExpression", "defaultValue", "expression")));

        List<JrxmlModel.Variable> vars = new ArrayList<>();
        for (Element v : Xml.children(root, "variable"))
            vars.add(new JrxmlModel.Variable(Xml.attr(v, "name"), Xml.attr(v, "class"), Xml.attr(v, "calculation"), Xml.childText(v, "variableExpression", "expression")));

        List<JrxmlModel.Dataset> datasets = new ArrayList<>();
        for (Element ds : Xml.children(root, "subDataset")) {
            String[] l = new String[1]; String[] t = new String[1];
            query(ds, l, t);
            datasets.add(new JrxmlModel.Dataset(Xml.attr(ds, "name"), l[0], t[0], fields(ds)));
        }

        List<String> subreports = new ArrayList<>();
        for (Element s : Xml.descendants(root, "subreport")) addSubreportExpr(s, subreports);           // JR6 <subreport>
        for (Element e : Xml.descendants(root, "element"))                                             // JR7 <element kind="subreport">
            if ("subreport".equals(Xml.attr(e, "kind"))) addSubreportExpr(e, subreports);

        return new JrxmlModel(lang[0], text[0], fields(root), params, vars, datasets, subreports);
    }

    private static void addSubreportExpr(Element s, List<String> out) {
        String expr = Xml.childText(s, "subreportExpression", "expression");
        if (expr != null && !expr.isBlank()) out.add(expr);
    }

    private static List<JrxmlModel.Field> fields(Element scope) {
        List<JrxmlModel.Field> out = new ArrayList<>();
        for (Element f : Xml.children(scope, "field")) out.add(new JrxmlModel.Field(Xml.attr(f, "name"), Xml.attr(f, "class")));
        return out;
    }

    private static void query(Element scope, String[] lang, String[] text) {
        for (String name : new String[]{"query", "queryString"}) {
            for (Element q : Xml.children(scope, name)) {
                String l = Xml.attr(q, "language");
                lang[0] = (l == null || l.isBlank() ? "sql" : l).toLowerCase(Locale.ROOT);
                text[0] = q.getTextContent().trim();
                return;
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=JrxmlParserTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(parse): hardened XML helper and JR6/JR7 JRXML parser"
```

---

### Task 8: Parser infrastructure and report-unit parser

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/parse/{Descriptors,DescriptorParser,ParseContext,SqlRedactor,ReportUnitParser}.java`, `src/test/java/com/hcl/jrsmeta/testutil/FakeRepository.java`
- Test: `src/test/java/com/hcl/jrsmeta/parse/ReportUnitParserTest.java`, `SqlRedactorTest.java`

**Interfaces:**
- Consumes: `JrsRepository` (Task 5), `JrxmlParser` (Task 7), model (Task 2), `ConnectorConfig` (Task 3).
- Produces:
  - `record ParseContext(JrsRepository repo, String serverId, ConnectorConfig config)`
  - `interface DescriptorParser { Set<String> resourceTypes(); ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException; }`
  - `Descriptors.refUri(JsonNode)` → `Optional<String>` (finds any `*Reference.uri` child); `Descriptors.text(JsonNode, String)`.
  - `SqlRedactor.redact(String sql)` — replaces string and numeric literals with `?`.
  - `ReportUnitParser` for type `reportUnit`: emits `BI_REPORT` + `REPORT_FIELD` + `REPORT_PARAMETER` children; refs `DATASOURCE`, `QUERY_RESOURCE`, `INPUT_CONTROL`, `SUBREPORT`.
  - `FakeRepository` (test util): in-memory `JrsRepository` with `descriptor(uri, json)`, `file(uri, bytes)`, `export(uri, zip)`, `jobs(uri, json...)`, `job(id, json)`, `permissions(uri, json...)`, `missingFile(uri)` to simulate 404.

Report attributes written: `resourceType`, `dataSourceUri`, `queryResourceUri`, `inputControlUris`, `subreportUris`, `unresolvedSubreports`, `alwaysPromptControls`, `jrxmlSource` (`inline`|`reference`|`missing`), `jrxmlUri`, `queryLanguage`, `queryText`, `subDatasets` (list of `{name, queryLanguage, queryText, fields}`), `variables` (list of `{name, javaClass, calculation, expression}`).

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/hcl/jrsmeta/testutil/FakeRepository.java`:
```java
package com.hcl.jrsmeta.testutil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcl.jrsmeta.client.JrsClientException;
import com.hcl.jrsmeta.client.JrsRepository;
import com.hcl.jrsmeta.client.ServerInfo;
import com.hcl.jrsmeta.model.ResourceLookup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** In-memory JrsRepository for parser tests. */
public final class FakeRepository implements JrsRepository {
    private static final ObjectMapper M = new ObjectMapper();
    private final Map<String, String> descriptors = new HashMap<>();
    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, byte[]> exports = new HashMap<>();
    private final Map<String, List<String>> jobs = new HashMap<>();
    private final Map<Long, String> jobById = new HashMap<>();
    private final Map<String, List<String>> perms = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    public final List<ResourceLookup> listing = new ArrayList<>();
    public final List<String> requestedUris = new ArrayList<>();
    public ServerInfo info = new ServerInfo("10.0.0", "PRO", "Enterprise", "test");

    public FakeRepository descriptor(String uri, String json) { descriptors.put(uri, json); return this; }
    public FakeRepository file(String uri, String text) { files.put(uri, text.getBytes(StandardCharsets.UTF_8)); return this; }
    public FakeRepository file(String uri, byte[] bytes) { files.put(uri, bytes); return this; }
    public FakeRepository export(String uri, byte[] zip) { exports.put(uri, zip); return this; }
    public FakeRepository jobs(String uri, String... summaries) { jobs.put(uri, List.of(summaries)); return this; }
    public FakeRepository job(long id, String json) { jobById.put(id, json); return this; }
    public FakeRepository permissions(String uri, String... entries) { perms.put(uri, List.of(entries)); return this; }
    public FakeRepository missingFile(String uri) { missing.add(uri); return this; }

    @Override public ServerInfo serverInfo() { return info; }
    @Override public List<ResourceLookup> listResources(String folderUri, boolean recursive) { return listing; }
    @Override public JsonNode descriptor(String uri, String type) throws IOException {
        requestedUris.add(uri);
        String j = descriptors.get(uri);
        if (j == null) throw new JrsClientException(404, "no descriptor: " + uri);
        return M.readTree(j);
    }
    @Override public byte[] file(String uri, String accept) throws IOException {
        requestedUris.add(uri);
        if (missing.contains(uri) || !files.containsKey(uri)) throw new JrsClientException(404, "no file: " + uri);
        return files.get(uri);
    }
    @Override public byte[] exportArchive(List<String> uris) throws IOException {
        byte[] z = exports.get(uris.get(0));
        if (z == null) throw new IOException("no export for " + uris);
        return z;
    }
    @Override public List<JsonNode> jobs(String reportUri) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String s : jobs.getOrDefault(reportUri, List.of())) out.add(M.readTree(s));
        return out;
    }
    @Override public JsonNode job(long id) throws IOException {
        String j = jobById.get(id);
        if (j == null) throw new JrsClientException(404, "no job " + id);
        return M.readTree(j);
    }
    @Override public List<JsonNode> permissions(String uri) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String s : perms.getOrDefault(uri, List.of())) out.add(M.readTree(s));
        return out;
    }
}
```

`SqlRedactorTest.java`:
```java
package com.hcl.jrsmeta.parse;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SqlRedactorTest {
    @Test void redact_replacesStringAndNumericLiteralsOnly() {
        String sql = "SELECT t1.col_2, 'O''Brien' AS n FROM sales_fact t1 WHERE amount > 100.5 AND region = 'WEST'";
        assertThat(SqlRedactor.redact(sql)).isEqualTo("SELECT t1.col_2, ? AS n FROM sales_fact t1 WHERE amount > ? AND region = ?");
    }
    @Test void redact_nullSafe() { assertThat(SqlRedactor.redact(null)).isNull(); }
}
```

`ReportUnitParserTest.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ReportUnitParserTest {
    static final String JRXML = """
        <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports" name="rev">
          <parameter name="Region" class="java.lang.String"/>
          <query language="sql"><![CDATA[SELECT r.region_name AS region, SUM(s.amount) AS revenue FROM sales_fact s JOIN region r ON r.region_id = s.region_id WHERE r.region_name = 'WEST' GROUP BY r.region_name]]></query>
          <field name="region" class="java.lang.String"/>
          <field name="revenue" class="java.math.BigDecimal"/>
          <detail><band height="10">
            <subreport><subreportExpression><![CDATA["repo:/reports/sub/detail"]]></subreportExpression></subreport>
            <subreport><subreportExpression><![CDATA[$P{SUBREPORT_DIR} + "child.jrxml"]]></subreportExpression></subreport>
            <subreport><subreportExpression><![CDATA[$P{SUBREPORT_DIR} + "unknown.jasper"]]></subreportExpression></subreport>
          </band></detail>
        </jasperReport>
        """;

    static final String DESCRIPTOR_REF = """
        {"uri":"/reports/rev","label":"Revenue","description":"d","version":3,
         "alwaysPromptControls":true,
         "dataSource":{"dataSourceReference":{"uri":"/datasources/sales"}},
         "jrxml":{"jrxmlFileReference":{"uri":"/reports/rev_files/main_jrxml"}},
         "inputControls":[{"inputControlReference":{"uri":"/reports/rev_files/Region"}}],
         "resources":[{"name":"child.jrxml","file":{"fileReference":{"uri":"/reports/rev_files/child.jrxml"}}}]}
        """;

    ResourceLookup lookup = new ResourceLookup("/reports/rev", "Revenue", "d", "reportUnit", "2024-01-01T00:00:00", "2024-02-01T00:00:00", 3);

    private ParseContext ctx(FakeRepository repo, boolean redact) {
        return new ParseContext(repo, "srv", TestConfig.base().redactSqlLiterals(redact).build());
    }

    @Test void parse_referencedJrxmlProducesReportChildrenAndRefs() throws Exception {
        FakeRepository repo = new FakeRepository().descriptor("/reports/rev", DESCRIPTOR_REF).file("/reports/rev_files/main_jrxml", JRXML);
        ParseResult r = new ReportUnitParser().parse(ctx(repo, false), lookup);

        Asset report = r.assets().get(0);
        assertThat(report.type()).isEqualTo(AssetType.BI_REPORT);
        assertThat(report.id()).isEqualTo("srv|/reports/rev");
        assertThat(report.attributes()).containsEntry("dataSourceUri", "/datasources/sales").containsEntry("queryLanguage", "sql")
                .containsEntry("jrxmlSource", "reference").containsEntry("alwaysPromptControls", true);
        assertThat((String) report.attributes().get("queryText")).contains("'WEST'");
        assertThat(report.attributes().get("subreportUris")).isEqualTo(List.of("/reports/sub/detail", "/reports/rev_files/child.jrxml"));
        assertThat(report.attributes().get("unresolvedSubreports")).isEqualTo(List.of("$P{SUBREPORT_DIR} + \"unknown.jasper\""));

        assertThat(r.assets()).filteredOn(a -> a.type() == AssetType.REPORT_FIELD).extracting(Asset::label).containsExactly("region", "revenue");
        assertThat(r.assets()).filteredOn(a -> a.type() == AssetType.REPORT_FIELD).extracting(Asset::id).contains("srv|/reports/rev#field:region");
        assertThat(r.assets()).filteredOn(a -> a.type() == AssetType.REPORT_PARAMETER).extracting(Asset::label).containsExactly("Region");

        assertThat(r.refs()).containsExactlyInAnyOrder(
                new Ref("/reports/rev", "/datasources/sales", Ref.RefKind.DATASOURCE),
                new Ref("/reports/rev", "/reports/rev_files/Region", Ref.RefKind.INPUT_CONTROL),
                new Ref("/reports/rev", "/reports/sub/detail", Ref.RefKind.SUBREPORT),
                new Ref("/reports/rev", "/reports/rev_files/child.jrxml", Ref.RefKind.SUBREPORT));
        assertThat(r.warnings()).anyMatch(w -> w.contains("unknown.jasper"));
    }

    @Test void parse_inlineBase64Jrxml() throws Exception {
        String inline = "{\"uri\":\"/reports/i\",\"label\":\"I\",\"jrxml\":{\"jrxmlFile\":{\"label\":\"main\",\"type\":\"jrxml\",\"content\":\""
                + Base64.getEncoder().encodeToString(JRXML.getBytes()) + "\"}}}";
        FakeRepository repo = new FakeRepository().descriptor("/reports/i", inline);
        ParseResult r = new ReportUnitParser().parse(ctx(repo, false), new ResourceLookup("/reports/i", "I", null, "reportUnit", null, null, 1));
        assertThat(r.assets().get(0).attributes()).containsEntry("jrxmlSource", "inline");
        assertThat(r.assets()).filteredOn(a -> a.type() == AssetType.REPORT_FIELD).hasSize(2);
        assertThat(repo.requestedUris).containsExactly("/reports/i");            // no file fetch
    }

    @Test void parse_missingJrxmlStillEmitsReportWithWarning() throws Exception {
        FakeRepository repo = new FakeRepository().descriptor("/reports/rev", DESCRIPTOR_REF).missingFile("/reports/rev_files/main_jrxml");
        ParseResult r = new ReportUnitParser().parse(ctx(repo, false), lookup);
        assertThat(r.assets()).hasSize(1);
        assertThat(r.assets().get(0).attributes()).containsEntry("jrxmlSource", "reference").doesNotContainKey("queryText");
        assertThat(r.refs()).contains(new Ref("/reports/rev", "/datasources/sales", Ref.RefKind.DATASOURCE));
        assertThat(r.warnings()).anyMatch(w -> w.contains("main_jrxml"));
    }

    @Test void parse_queryResourceReferenceBecomesRef() throws Exception {
        String d = "{\"uri\":\"/reports/q\",\"label\":\"Q\",\"query\":{\"queryReference\":{\"uri\":\"/queries/shared\"}}}";
        FakeRepository repo = new FakeRepository().descriptor("/reports/q", d);
        ParseResult r = new ReportUnitParser().parse(ctx(repo, false), new ResourceLookup("/reports/q", "Q", null, "reportUnit", null, null, 1));
        assertThat(r.refs()).contains(new Ref("/reports/q", "/queries/shared", Ref.RefKind.QUERY_RESOURCE));
        assertThat(r.assets().get(0).attributes()).containsEntry("queryResourceUri", "/queries/shared");
    }

    @Test void parse_redactsSqlLiteralsWhenConfigured() throws Exception {
        FakeRepository repo = new FakeRepository().descriptor("/reports/rev", DESCRIPTOR_REF).file("/reports/rev_files/main_jrxml", JRXML);
        ParseResult r = new ReportUnitParser().parse(ctx(repo, true), lookup);
        assertThat((String) r.assets().get(0).attributes().get("queryText")).doesNotContain("WEST").contains("= ?");
    }

    @Test void descriptors_refUriFindsAnyReferenceKey() throws Exception {
        var m = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(Descriptors.refUri(m.readTree("{\"dataSourceReference\":{\"uri\":\"/a\"}}"))).contains("/a");
        assertThat(Descriptors.refUri(m.readTree("{\"semanticLayerDataSourceReference\":{\"uri\":\"/b\"}}"))).contains("/b");
        assertThat(Descriptors.refUri(m.readTree("{\"jdbcDataSource\":{\"connectionUrl\":\"x\"}}"))).isEmpty();   // embedded, not a reference
        assertThat(Descriptors.refUri(m.missingNode())).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=ReportUnitParserTest,SqlRedactorTest`
Expected: compilation errors.

- [ ] **Step 3: Write the parser infrastructure**

`ParseContext.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.client.JrsRepository;
import com.hcl.jrsmeta.config.ConnectorConfig;

public record ParseContext(JrsRepository repo, String serverId, ConnectorConfig config) {}
```

`DescriptorParser.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.ParseResult;
import com.hcl.jrsmeta.model.ResourceLookup;
import java.io.IOException;
import java.util.Set;

/** One parser per JRS resource type family. Must never throw for a malformed payload it can partially read; use warnings. */
public interface DescriptorParser {
    Set<String> resourceTypes();
    ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException;
}
```

`Descriptors.java`:
```java
package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/** Helpers for JRS JSON descriptors, whose references look like {"<something>Reference":{"uri":"/x"}}. */
public final class Descriptors {
    private Descriptors() {}

    public static Optional<String> refUri(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) return Optional.empty();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().endsWith("Reference") && e.getValue().hasNonNull("uri")) return Optional.of(e.getValue().get("uri").asText());
        }
        return Optional.empty();
    }

    public static String text(JsonNode n, String field) { return n != null && n.hasNonNull(field) ? n.get(field).asText() : null; }
}
```

`SqlRedactor.java`:
```java
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
```

`ReportUnitParser.java`:
```java
package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spec §9.1: report descriptor + JRXML -> BI_REPORT, fields, parameters, and datasource/subreport/input-control refs. */
public final class ReportUnitParser implements DescriptorParser {
    private static final Pattern REPO_URI = Pattern.compile("repo:(/[^\"'\\s]+)");
    private static final Pattern FILE_NAME = Pattern.compile("\"(?:[^\"]*/)?([^\"/]+\\.(?:jrxml|jasper))\"");
    private final JrxmlParser jrxmlParser = new JrxmlParser();

    @Override public Set<String> resourceTypes() { return Set.of("reportUnit"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri();
        JsonNode d = ctx.repo().descriptor(uri, "reportUnit");
        List<Ref> refs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", "reportUnit");

        Descriptors.refUri(d.path("dataSource")).ifPresent(ds -> { attrs.put("dataSourceUri", ds); refs.add(new Ref(uri, ds, Ref.RefKind.DATASOURCE)); });
        Descriptors.refUri(d.path("query")).ifPresent(q -> { attrs.put("queryResourceUri", q); refs.add(new Ref(uri, q, Ref.RefKind.QUERY_RESOURCE)); });

        List<String> icUris = new ArrayList<>();
        for (JsonNode ic : d.path("inputControls")) Descriptors.refUri(ic).ifPresent(u -> { icUris.add(u); refs.add(new Ref(uri, u, Ref.RefKind.INPUT_CONTROL)); });
        attrs.put("inputControlUris", icUris);
        attrs.put("alwaysPromptControls", d.path("alwaysPromptControls").asBoolean(false));

        Map<String, String> resourceFiles = new LinkedHashMap<>();   // name -> uri of the report's companion files
        for (JsonNode r : d.path("resources")) {
            String name = Descriptors.text(r, "name");
            Descriptors.refUri(r.path("file")).ifPresent(u -> { if (name != null) resourceFiles.put(name, u); });
        }

        byte[] jrxml = loadJrxml(ctx, uri, d, attrs, warnings);
        JrxmlModel model = null;
        if (jrxml != null) {
            try { model = jrxmlParser.parse(jrxml); }
            catch (IOException e) { warnings.add("jrxml of " + uri + " could not be parsed: " + e.getMessage()); }
        }

        List<Asset> assets = new ArrayList<>();
        if (model != null) {
            attrs.put("queryLanguage", model.queryLanguage());
            if (model.queryText() != null) attrs.put("queryText", ctx.config().redactSqlLiterals() ? SqlRedactor.redact(model.queryText()) : model.queryText());
            List<Map<String, Object>> datasets = new ArrayList<>();
            for (JrxmlModel.Dataset ds : model.subDatasets())
                datasets.add(Attrs.of("name", ds.name(), "queryLanguage", ds.queryLanguage(),
                        "queryText", ctx.config().redactSqlLiterals() ? SqlRedactor.redact(ds.queryText()) : ds.queryText(),
                        "fields", ds.fields().stream().map(JrxmlModel.Field::name).toList()));
            attrs.put("subDatasets", datasets);
            attrs.put("variables", model.variables().stream().map(v -> Attrs.of("name", v.name(), "javaClass", v.javaClass(), "calculation", v.calculation(), "expression", v.expression())).toList());

            List<String> subUris = new ArrayList<>(); List<String> unresolved = new ArrayList<>();
            for (String expr : model.subreportExpressions()) {
                Optional<String> sub = resolveSubreport(expr, resourceFiles);
                if (sub.isPresent()) { subUris.add(sub.get()); refs.add(new Ref(uri, sub.get(), Ref.RefKind.SUBREPORT)); }
                else { unresolved.add(expr); warnings.add("unresolved subreport expression in " + uri + ": " + expr); }
            }
            attrs.put("subreportUris", subUris);
            attrs.put("unresolvedSubreports", unresolved);
        }

        Asset report = Asset.of(ctx.serverId(), lookup, AssetType.BI_REPORT, attrs);
        assets.add(report);
        if (model != null) {
            for (JrxmlModel.Field f : model.fields())
                assets.add(Asset.child(report, "field", f.name(), AssetType.REPORT_FIELD, f.name(), Attrs.of("javaClass", f.javaClass())));
            for (JrxmlModel.Parameter p : model.parameters())
                assets.add(Asset.child(report, "param", p.name(), AssetType.REPORT_PARAMETER, p.name(), Attrs.of("javaClass", p.javaClass(), "defaultValueExpression", p.defaultValueExpression())));
        }
        return new ParseResult(assets, refs, warnings);
    }

    private static byte[] loadJrxml(ParseContext ctx, String uri, JsonNode d, Map<String, Object> attrs, List<String> warnings) {
        JsonNode j = d.path("jrxml");
        String inline = j.path("jrxmlFile").path("content").asText(null);
        if (inline != null && !inline.isEmpty()) { attrs.put("jrxmlSource", "inline"); return Base64.getMimeDecoder().decode(inline); }
        Optional<String> ref = Descriptors.refUri(j);
        if (ref.isPresent()) {
            attrs.put("jrxmlSource", "reference"); attrs.put("jrxmlUri", ref.get());
            try { return ctx.repo().file(ref.get(), "application/xml"); }
            catch (IOException e) { warnings.add("jrxml fetch failed for " + ref.get() + ": " + e.getMessage()); return null; }
        }
        attrs.put("jrxmlSource", "missing");
        warnings.add("report " + uri + " has no jrxml in its descriptor");
        return null;
    }

    /** "repo:/abs/uri" -> that URI; "...child.jrxml" -> the matching companion resource; otherwise unresolved. */
    static Optional<String> resolveSubreport(String expr, Map<String, String> resourceFiles) {
        Matcher m = REPO_URI.matcher(expr);
        if (m.find()) return Optional.of(m.group(1));
        Matcher f = FILE_NAME.matcher(expr);
        if (f.find()) {
            String name = f.group(1);
            String base = name.replaceAll("\\.(jrxml|jasper)$", "");
            for (Map.Entry<String, String> e : resourceFiles.entrySet()) {
                String k = e.getKey();
                if (k.equals(name) || k.equals(base) || k.equals(base + ".jrxml") || k.equals(base + ".jasper")) return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=ReportUnitParserTest,SqlRedactorTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(parse): parser infrastructure, SQL redactor, reportUnit parser with fields/params/subreports"
```

---

### Task 9: Data sources, JDBC URL fingerprinting, secret redaction

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/parse/JdbcUrlParser.java`, `DataSourceParser.java`
- Test: `src/test/java/com/hcl/jrsmeta/parse/JdbcUrlParserTest.java`, `DataSourceParserTest.java`

**Interfaces:**
- Consumes: `Fingerprint`, `DescriptorParser`, `ParseContext` (Tasks 2, 8).
- Produces:
  - `JdbcUrlParser.parse(String url, String driverClass)` → `Fingerprint`; `JdbcUrlParser.sanitize(String url)` strips credentials.
  - `DataSourceParser` for `jdbcDataSource, jndiJdbcDataSource, virtualDataSource, awsDataSource, beanDataSource, customDataSource, azureSqlDataSource` → one `BI_DATASOURCE` asset; refs `SUB_DATASOURCE` for virtual. Attributes: `resourceType`, `dsType`, `fingerprint` (key string), `fingerprintParts` (`Fingerprint`), plus type-specific (`driverClass`, `connectionUrl` sanitized, `dbUser`, `timezone`, `jndiName`, `subDataSourceUris`, `region`, `dbInstanceIdentifier`, `dbService`, `dbName`, `beanName`, `beanMethod`, `serviceClass`, `properties`). Never `password`, `secretKey`, `accessKey`.

- [ ] **Step 1: Write the failing tests**

`JdbcUrlParserTest.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.Fingerprint;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcUrlParserTest {
    @Test void postgres_withPortAndParams() {
        Fingerprint f = JdbcUrlParser.parse("jdbc:postgresql://DB.example.com:5433/SalesDB?ssl=true", "org.postgresql.Driver");
        assertThat(f.dialect()).isEqualTo("postgresql"); assertThat(f.host()).isEqualTo("DB.example.com");
        assertThat(f.port()).isEqualTo(5433); assertThat(f.database()).isEqualTo("SalesDB");
        assertThat(f.key()).isEqualTo("postgresql://db.example.com:5433/salesdb");
    }
    @Test void mysql_defaultPort() {
        assertThat(JdbcUrlParser.parse("jdbc:mysql://h/foodmart", null).key()).isEqualTo("mysql://h:3306/foodmart");
    }
    @Test void sqlserver_databaseNameProperty() {
        Fingerprint f = JdbcUrlParser.parse("jdbc:sqlserver://sql01\\\\inst:1433;databaseName=Reporting;encrypt=true", null);
        assertThat(f.key()).isEqualTo("sqlserver://sql01:1433/reporting");
    }
    @Test void oracle_thinServiceAndSid() {
        assertThat(JdbcUrlParser.parse("jdbc:oracle:thin:@//ora.example.com:1521/ORCLPDB", null).key()).isEqualTo("oracle://ora.example.com:1521/orclpdb");
        assertThat(JdbcUrlParser.parse("jdbc:oracle:thin:@ora:1521:ORCL", null).key()).isEqualTo("oracle://ora:1521/orcl");
    }
    @Test void snowflake_dbAndSchemaParams() {
        Fingerprint f = JdbcUrlParser.parse("jdbc:snowflake://acct.snowflakecomputing.com/?db=ANALYTICS&schema=PUBLIC&warehouse=WH", null);
        assertThat(f.key()).isEqualTo("snowflake://acct.snowflakecomputing.com:443/analytics");
        assertThat(f.schema()).isEqualTo("PUBLIC");
    }
    @Test void redshift() {
        assertThat(JdbcUrlParser.parse("jdbc:redshift://c.abc.us-east-1.redshift.amazonaws.com:5439/dev", null).key())
                .isEqualTo("redshift://c.abc.us-east-1.redshift.amazonaws.com:5439/dev");
    }
    @Test void unknownSubprotocol_isOpaqueButKeyed() {
        Fingerprint f = JdbcUrlParser.parse("jdbc:weirddb:thing=1", "com.weird.Driver");
        assertThat(f.dialect()).isEqualTo("weirddb"); assertThat(f.host()).isNull();
        assertThat(f.key()).startsWith("weirddb://");
    }
    @Test void missingUrl_fallsBackToDriver() {
        assertThat(JdbcUrlParser.parse(null, "org.postgresql.Driver").dialect()).isEqualTo("postgresql");
    }
    @Test void sanitize_stripsCredentials() {
        assertThat(JdbcUrlParser.sanitize("jdbc:postgresql://h/db?user=bob&password=hunter2&ssl=true")).isEqualTo("jdbc:postgresql://h/db?user=***&password=***&ssl=true");
        assertThat(JdbcUrlParser.sanitize("jdbc:mysql://bob:hunter2@h/db")).isEqualTo("jdbc:mysql://***@h/db");
        assertThat(JdbcUrlParser.sanitize("jdbc:sqlserver://h;user=bob;password=x;databaseName=d")).doesNotContain("hunter2").doesNotContain("password=x");
        Fingerprint f = JdbcUrlParser.parse("jdbc:postgresql://h/db?user=bob&password=hunter2", null);
        assertThat(f.raw()).doesNotContain("hunter2");
        assertThat(f.key()).isEqualTo("postgresql://h:5432/db");
    }
}
```

`DataSourceParserTest.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DataSourceParserTest {
    ParseContext ctx(FakeRepository r) { return new ParseContext(r, "srv", TestConfig.base().build()); }
    ResourceLookup lk(String uri, String type) { return new ResourceLookup(uri, "L", null, type, null, null, 1); }

    @Test void jdbc_fingerprintAndNoPassword() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/ds/pg", """
            {"uri":"/ds/pg","label":"PG","driverClass":"org.postgresql.Driver","username":"svc","password":"S3CRET",
             "connectionUrl":"jdbc:postgresql://db.example.com:5432/salesdb?password=S3CRET","timezone":"UTC"}""");
        ParseResult p = new DataSourceParser().parse(ctx(r), lk("/ds/pg", "jdbcDataSource"));
        Asset a = p.assets().get(0);
        assertThat(a.type()).isEqualTo(AssetType.BI_DATASOURCE);
        assertThat(a.attributes()).containsEntry("fingerprint", "postgresql://db.example.com:5432/salesdb").containsEntry("dbUser", "svc").containsEntry("dsType", "jdbcDataSource");
        assertThat(a.attributes().toString()).doesNotContain("S3CRET");
        assertThat(a.attributes()).doesNotContainKey("password");
    }

    @Test void jndi_isOpaqueFingerprint() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/ds/j", "{\"uri\":\"/ds/j\",\"jndiName\":\"jdbc/FoodMart\"}");
        Asset a = new DataSourceParser().parse(ctx(r), lk("/ds/j", "jndiJdbcDataSource")).assets().get(0);
        assertThat(a.attributes()).containsEntry("fingerprint", "jndi://jdbc/foodmart").containsEntry("jndiName", "jdbc/FoodMart");
    }

    @Test void virtual_expandsSubDataSourcesAsRefs() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/ds/v", "{\"uri\":\"/ds/v\",\"subDataSources\":[{\"id\":\"a\",\"uri\":\"/ds/pg\"},{\"id\":\"b\",\"uri\":\"/ds/j\"}]}");
        ParseResult p = new DataSourceParser().parse(ctx(r), lk("/ds/v", "virtualDataSource"));
        assertThat(p.refs()).containsExactly(new Ref("/ds/v", "/ds/pg", Ref.RefKind.SUB_DATASOURCE), new Ref("/ds/v", "/ds/j", Ref.RefKind.SUB_DATASOURCE));
        assertThat(p.assets().get(0).attributes()).containsEntry("subDataSourceUris", List.of("/ds/pg", "/ds/j")).doesNotContainKey("fingerprint");
    }

    @Test void aws_withoutUrlUsesInstanceIdentifier() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/ds/aws", """
            {"uri":"/ds/aws","driverClass":"org.postgresql.Driver","accessKey":"AKIA","secretKey":"shh","region":"us-east-1",
             "dbName":"prod","dbInstanceIdentifier":"rds-prod-1","dbService":"rds","username":"u"}""");
        Asset a = new DataSourceParser().parse(ctx(r), lk("/ds/aws", "awsDataSource")).assets().get(0);
        assertThat(a.attributes()).containsEntry("fingerprint", "aws://rds/us-east-1/rds-prod-1").containsEntry("region", "us-east-1");
        assertThat(a.attributes().toString()).doesNotContain("shh").doesNotContain("AKIA");
    }

    @Test void custom_redactsSecretLookingProperties() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/ds/c", """
            {"uri":"/ds/c","serviceClass":"com.acme.MongoDS","properties":[{"key":"host","value":"m1"},{"key":"apiKey","value":"zzz"},{"key":"password","value":"ppp"}]}""");
        Asset a = new DataSourceParser().parse(ctx(r), lk("/ds/c", "customDataSource")).assets().get(0);
        assertThat(a.attributes()).containsEntry("fingerprint", "custom://com.acme.mongods");
        assertThat(a.attributes().toString()).contains("host").contains("m1").doesNotContain("zzz").doesNotContain("ppp");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=JdbcUrlParserTest,DataSourceParserTest`
Expected: compilation errors.

- [ ] **Step 3: Write the parsers**

`JdbcUrlParser.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.Fingerprint;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JDBC URL -> connection fingerprint (spec §7.4 / §9.5). Credentials are stripped before anything is stored. */
public final class JdbcUrlParser {
    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final Pattern STD = Pattern.compile("^jdbc:(postgresql|mysql|mariadb|redshift|db2|vertica)://(?:[^@/]+@)?([^/:;?,]+)(?::(\\d+))?(?:,[^/]*)?/([^?;]*)", CI);
    private static final Pattern MSSQL = Pattern.compile("^jdbc:sqlserver://([^;:\\\\]+)(?:\\\\[^;:]+)?(?::(\\d+))?(.*)$", CI);
    private static final Pattern ORACLE = Pattern.compile("^jdbc:oracle:(?:thin|oci):(?:[^@]*)@(?://)?([^:/]+)(?::(\\d+))?[:/]([^?;]+)", CI);
    private static final Pattern SNOW = Pattern.compile("^jdbc:snowflake://([^/?:]+)(?::(\\d+))?/?(?:\\?(.*))?$", CI);
    private static final Pattern GENERIC = Pattern.compile("^jdbc:([a-z0-9]+):", CI);
    private static final Pattern CRED_PARAM = Pattern.compile("(?i)([?&;](?:password|pwd|user|username|secret|token)=)[^&;]*");
    private static final Pattern CRED_USERINFO = Pattern.compile("//[^/@]+@");
    static final Map<String, Integer> DEFAULT_PORTS = Map.of("postgresql", 5432, "mysql", 3306, "mariadb", 3306, "redshift", 5439,
            "sqlserver", 1433, "oracle", 1521, "snowflake", 443, "db2", 50000, "vertica", 5433);

    private JdbcUrlParser() {}

    public static String sanitize(String url) {
        if (url == null) return null;
        return CRED_PARAM.matcher(CRED_USERINFO.matcher(url).replaceAll("//***@")).replaceAll("$1***");
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
```

`DataSourceParser.java`:
```java
package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Spec §9.5: every datasource flavour -> BI_DATASOURCE with a stitching fingerprint; secrets never copied. */
public final class DataSourceParser implements DescriptorParser {
    static final Set<String> TYPES = Set.of("jdbcDataSource", "jndiJdbcDataSource", "virtualDataSource", "awsDataSource",
            "beanDataSource", "customDataSource", "azureSqlDataSource");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)pass|secret|token|key|credential");

    @Override public Set<String> resourceTypes() { return TYPES; }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri(); String type = lookup.resourceType();
        JsonNode d = ctx.repo().descriptor(uri, type);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", type); attrs.put("dsType", type);
        List<Ref> refs = new ArrayList<>();
        Fingerprint fp = null;

        switch (type) {
            case "jdbcDataSource", "azureSqlDataSource" -> {
                String url = Descriptors.text(d, "connectionUrl"); String drv = Descriptors.text(d, "driverClass");
                attrs.put("driverClass", drv); attrs.put("connectionUrl", JdbcUrlParser.sanitize(url));
                attrs.put("dbUser", Descriptors.text(d, "username")); attrs.put("timezone", Descriptors.text(d, "timezone"));
                fp = JdbcUrlParser.parse(url, drv);
            }
            case "jndiJdbcDataSource" -> {
                String j = Descriptors.text(d, "jndiName");
                attrs.put("jndiName", j); attrs.put("timezone", Descriptors.text(d, "timezone"));
                fp = Fingerprint.opaque("jndi", j);
            }
            case "virtualDataSource" -> {
                List<String> subs = new ArrayList<>();
                for (JsonNode s : d.path("subDataSources")) {
                    String u = Descriptors.text(s, "uri");
                    if (u != null) { subs.add(u); refs.add(new Ref(uri, u, Ref.RefKind.SUB_DATASOURCE)); }
                }
                attrs.put("subDataSourceUris", subs);          // stitched through its sub-datasources, no own fingerprint
            }
            case "awsDataSource" -> {
                String url = Descriptors.text(d, "connectionUrl"); String drv = Descriptors.text(d, "driverClass");
                attrs.put("driverClass", drv); attrs.put("connectionUrl", JdbcUrlParser.sanitize(url)); attrs.put("dbUser", Descriptors.text(d, "username"));
                for (String k : List.of("region", "dbName", "dbInstanceIdentifier", "dbService", "roleArn")) attrs.put(k, Descriptors.text(d, k));
                fp = url != null && !url.isBlank() ? JdbcUrlParser.parse(url, drv)
                        : Fingerprint.opaque("aws", nz(Descriptors.text(d, "dbService"), "rds") + "/" + nz(Descriptors.text(d, "region"), "?") + "/" + nz(Descriptors.text(d, "dbInstanceIdentifier"), "?"));
            }
            case "beanDataSource" -> {
                attrs.put("beanName", Descriptors.text(d, "beanName")); attrs.put("beanMethod", Descriptors.text(d, "beanMethod"));
                fp = Fingerprint.opaque("bean", Descriptors.text(d, "beanName"));
            }
            case "customDataSource" -> {
                String svc = Descriptors.text(d, "serviceClass");
                attrs.put("serviceClass", svc);
                Map<String, String> props = new LinkedHashMap<>();
                for (JsonNode p : d.path("properties")) {
                    String k = Descriptors.text(p, "key");
                    if (k != null) props.put(k, SECRET_KEY.matcher(k).find() ? "***" : Descriptors.text(p, "value"));
                }
                attrs.put("properties", props);
                fp = Fingerprint.opaque("custom", svc);
            }
            default -> throw new IllegalStateException("unsupported datasource type " + type);
        }
        if (fp != null) { attrs.put("fingerprint", fp.key()); attrs.put("fingerprintParts", fp); }
        return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, AssetType.BI_DATASOURCE, attrs)), refs, List.of());
    }

    private static String nz(String v, String dflt) { return v == null || v.isBlank() ? dflt : v; }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=JdbcUrlParserTest,DataSourceParserTest`
Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(parse): datasource parser with JDBC fingerprinting and secret redaction"
```

---

### Task 10: Domains (semantic layer) — schema.xml and descriptor

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/parse/DomainSchema.java`, `DomainSchemaParser.java`, `DomainParser.java`
- Test: `src/test/java/com/hcl/jrsmeta/parse/DomainSchemaParserTest.java`, `DomainParserTest.java`

**Interfaces:**
- Consumes: `Xml` (Task 7), `DescriptorParser`/`Descriptors`/`ParseContext` (Task 8).
- Produces:
  - `record DomainSchema(Map<String,Table> tables, Map<String,Query> queries, List<Item> items)` with nested `Field(id, dbName, type)`, `Table(id, datasourceId, schemaAlias, tableName, Map<String,Field> fields, Map<String,String> aliasToTableId, boolean joinTree)`, `Query(id, datasourceId, sql, Map<String,Field> fields)`, `Item(id, label, description, resourceId, groupPath)`, `PhysicalColumn(datasourceId, schema, table, column, boolean exact)`; `Optional<PhysicalColumn> resolve(String itemResourceId)`.
  - `DomainSchemaParser.parse(byte[] schemaXml)` → `DomainSchema`.
  - `DomainParser` for `semanticLayerDataSource` → `SEMANTIC_MODEL` + `LOGICAL_FIELD` children; ref `DATASOURCE`. Logical field attributes: `resourceId`, `groupPath`, `physicalSchema`, `physicalTable`, `physicalColumn`, `datasourceAlias`, `mappingConfidence` (`EXACT` for table columns, `DERIVED` for jdbcQuery columns, `INFERRED` when unresolved). Model attributes: `schemaUri`/`schemaSource`, `tables` (list of `{id, schema, table, datasourceAlias, joinTree}`), `queries` (list of `{id, sql}`), `itemCount`, `unresolvedItems`.

- [ ] **Step 1: Write the failing tests**

`DomainSchemaParserTest.java`:
```java
package com.hcl.jrsmeta.parse;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class DomainSchemaParserTest {
    static final String SCHEMA = """
        <schema xmlns="http://www.jaspersoft.com/2007/SL/XMLSchema" version="1.3">
          <dataSources><jdbcDataSource id="dsFoodMart"><schemaMap><schema name="public"/></schemaMap></jdbcDataSource></dataSources>
          <itemGroups>
            <itemGroup id="AccountsSet" label="Accounts" resourceId="JoinTree_1">
              <itemGroups>
                <itemGroup id="Address" label="Address" resourceId="JoinTree_1">
                  <items><item id="city" label="City" resourceId="JoinTree_1.customer.city"/></items>
                </itemGroup>
              </itemGroups>
              <items><item id="account_num" label="Account" resourceId="JoinTree_1.customer.account_num"/></items>
            </itemGroup>
          </itemGroups>
          <items>
            <item id="store_name" label="Store" resourceId="store.store_name"/>
            <item id="usd" label="USD" resourceId="expenses_usd.as_dollars"/>
            <item id="ghost" label="Ghost" resourceId="nowhere.x"/>
          </items>
          <resources>
            <jdbcTable id="store" datasourceId="dsFoodMart" schemaAlias="public" datasourceTableName="store">
              <fieldList><field id="store_name" fieldDBName="store_name" type="java.lang.String"/></fieldList>
            </jdbcTable>
            <jdbcTable id="customer" datasourceId="dsFoodMart" schemaAlias="public" datasourceTableName="customer">
              <fieldList><field id="account_num" fieldDBName="account_num" type="java.lang.Long"/><field id="city" fieldDBName="city" type="java.lang.String"/></fieldList>
            </jdbcTable>
            <jdbcQuery id="expenses_usd" datasourceId="dsFoodMart">
              <fieldList><field id="as_dollars" type="java.math.BigDecimal"/></fieldList>
              <query>SELECT amount * 1.1 AS as_dollars FROM expenses</query>
            </jdbcQuery>
            <jdbcTable id="JoinTree_1" datasourceId="dsFoodMart" schemaAlias="public" datasourceTableName="customer">
              <fieldList>
                <field id="customer.account_num" fieldDBName="account_num" type="java.lang.Long"/>
                <field id="customer.city" fieldDBName="city" type="java.lang.String"/>
                <field id="store.store_name" fieldDBName="store_name" type="java.lang.String"/>
              </fieldList>
              <joinInfo alias="customer" referenceId="customer"/>
              <tableRefList><tableRef tableId="customer" tableAlias="customer"/><tableRef tableId="store" tableAlias="store"/></tableRefList>
              <joinList><join left="customer" right="store" type="inner" expr="customer.store_id == store.store_id"/></joinList>
            </jdbcTable>
          </resources>
        </schema>
        """;

    @Test void parse_tablesQueriesAndItems() throws Exception {
        DomainSchema s = new DomainSchemaParser().parse(SCHEMA.getBytes(StandardCharsets.UTF_8));
        assertThat(s.tables()).containsKeys("store", "customer", "JoinTree_1");
        assertThat(s.tables().get("JoinTree_1").joinTree()).isTrue();
        assertThat(s.tables().get("JoinTree_1").aliasToTableId()).containsEntry("store", "store").containsEntry("customer", "customer");
        assertThat(s.tables().get("store").joinTree()).isFalse();
        assertThat(s.queries().get("expenses_usd").sql()).startsWith("SELECT amount");
        assertThat(s.items()).extracting(DomainSchema.Item::id).containsExactly("city", "account_num", "store_name", "usd", "ghost");
        assertThat(s.items().get(0).groupPath()).isEqualTo("AccountsSet/Address");
        assertThat(s.items().get(2).groupPath()).isEmpty();
    }

    @Test void resolve_plainTableJoinTreeQueryAndUnknown() throws Exception {
        DomainSchema s = new DomainSchemaParser().parse(SCHEMA.getBytes(StandardCharsets.UTF_8));
        assertThat(s.resolve("store.store_name")).contains(new DomainSchema.PhysicalColumn("dsFoodMart", "public", "store", "store_name", true));
        assertThat(s.resolve("JoinTree_1.store.store_name")).contains(new DomainSchema.PhysicalColumn("dsFoodMart", "public", "store", "store_name", true));
        assertThat(s.resolve("JoinTree_1.customer.city")).contains(new DomainSchema.PhysicalColumn("dsFoodMart", "public", "customer", "city", true));
        assertThat(s.resolve("expenses_usd.as_dollars")).contains(new DomainSchema.PhysicalColumn("dsFoodMart", null, "(query:expenses_usd)", "as_dollars", false));
        assertThat(s.resolve("nowhere.x")).isEmpty();
        assertThat(s.resolve("noDot")).isEmpty();
    }
}
```

`DomainParserTest.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class DomainParserTest {
    static final String SCHEMA = """
        <schema xmlns="http://www.jaspersoft.com/2007/SL/XMLSchema" version="1.3">
          <items><item id="store_name" label="Store" resourceId="store.store_name"/><item id="lost" label="Lost" resourceId="x.y"/></items>
          <resources><jdbcTable id="store" datasourceId="dsFoodMart" schemaAlias="public" datasourceTableName="store">
            <fieldList><field id="store_name" fieldDBName="store_name" type="java.lang.String"/></fieldList></jdbcTable></resources>
        </schema>
        """;
    ResourceLookup lk = new ResourceLookup("/domains/fm", "FoodMart", null, "semanticLayerDataSource", null, "2024-01-01T00:00:00", 1);
    ParseContext ctx(FakeRepository r) { return new ParseContext(r, "srv", TestConfig.base().build()); }

    @Test void parse_referencedSchemaProducesModelAndLogicalFields() throws Exception {
        FakeRepository r = new FakeRepository()
            .descriptor("/domains/fm", "{\"uri\":\"/domains/fm\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/fm\"}},\"schema\":{\"schemaReference\":{\"uri\":\"/domains/fm_files/schema.xml\"}}}")
            .file("/domains/fm_files/schema.xml", SCHEMA);
        ParseResult p = new DomainParser().parse(ctx(r), lk);
        Asset model = p.assets().get(0);
        assertThat(model.type()).isEqualTo(AssetType.SEMANTIC_MODEL);
        assertThat(model.attributes()).containsEntry("dataSourceUri", "/ds/fm").containsEntry("schemaUri", "/domains/fm_files/schema.xml").containsEntry("itemCount", 2);
        assertThat(p.refs()).containsExactly(new Ref("/domains/fm", "/ds/fm", Ref.RefKind.DATASOURCE));
        assertThat(p.assets()).filteredOn(a -> a.type() == AssetType.LOGICAL_FIELD).hasSize(2);
        Asset f = p.assets().stream().filter(a -> "srv|/domains/fm#item:store_name".equals(a.id())).findFirst().orElseThrow();
        assertThat(f.attributes()).containsEntry("physicalTable", "store").containsEntry("physicalColumn", "store_name").containsEntry("physicalSchema", "public").containsEntry("mappingConfidence", "EXACT");
        Asset lost = p.assets().stream().filter(a -> a.id().endsWith("#item:lost")).findFirst().orElseThrow();
        assertThat(lost.attributes()).containsEntry("mappingConfidence", "INFERRED");
        assertThat(p.warnings()).anyMatch(w -> w.contains("x.y"));
    }

    @Test void parse_inlineSchemaFile() throws Exception {
        String d = "{\"uri\":\"/domains/fm\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/fm\"}},\"schema\":{\"schemaFile\":{\"type\":\"xml\",\"content\":\""
                + Base64.getEncoder().encodeToString(SCHEMA.getBytes()) + "\"}}}";
        ParseResult p = new DomainParser().parse(ctx(new FakeRepository().descriptor("/domains/fm", d)), lk);
        assertThat(p.assets().get(0).attributes()).containsEntry("schemaSource", "inline");
        assertThat(p.assets()).hasSize(3);
    }

    @Test void parse_missingSchemaStillEmitsModel() throws Exception {
        String d = "{\"uri\":\"/domains/fm\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/fm\"}},\"schema\":{\"schemaReference\":{\"uri\":\"/domains/fm_files/schema.xml\"}}}";
        ParseResult p = new DomainParser().parse(ctx(new FakeRepository().descriptor("/domains/fm", d)), lk);
        assertThat(p.assets()).hasSize(1);
        assertThat(p.warnings()).anyMatch(w -> w.contains("schema.xml"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=DomainSchemaParserTest,DomainParserTest`
Expected: compilation errors.

- [ ] **Step 3: Write the Domain classes**

`DomainSchema.java`:
```java
package com.hcl.jrsmeta.parse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Parsed Domain schema.xml: physical resources plus presentation items (spec §9.2). */
public record DomainSchema(Map<String, Table> tables, Map<String, Query> queries, List<Item> items) {
    public record Field(String id, String dbName, String type) {}
    public record Table(String id, String datasourceId, String schemaAlias, String tableName, Map<String, Field> fields,
                        Map<String, String> aliasToTableId, boolean joinTree) {}
    public record Query(String id, String datasourceId, String sql, Map<String, Field> fields) {}
    public record Item(String id, String label, String description, String resourceId, String groupPath) {}
    public record PhysicalColumn(String datasourceId, String schema, String table, String column, boolean exact) {}

    /** resourceId is "table.field" for a lone table, "joinTree.alias.field" for a join tree, "query.field" for a derived table. */
    public Optional<PhysicalColumn> resolve(String resourceId) {
        if (resourceId == null) return Optional.empty();
        int dot = resourceId.indexOf('.');
        if (dot < 0) return Optional.empty();
        String resId = resourceId.substring(0, dot);
        String rest = resourceId.substring(dot + 1);
        Table t = tables.get(resId);
        if (t == null) {
            Query q = queries.get(resId);
            return q == null ? Optional.empty() : Optional.of(new PhysicalColumn(q.datasourceId(), null, "(query:" + resId + ")", rest, false));
        }
        Field f = t.fields().get(rest);
        String column = f != null && f.dbName() != null ? f.dbName() : rest.substring(rest.lastIndexOf('.') + 1);
        String tableName = t.tableName(); String schema = t.schemaAlias();
        if (t.joinTree() && rest.contains(".")) {
            String alias = rest.substring(0, rest.indexOf('.'));
            Table base = tables.get(t.aliasToTableId().getOrDefault(alias, alias));
            if (base != null) { tableName = base.tableName(); schema = base.schemaAlias(); }
        }
        return Optional.of(new PhysicalColumn(t.datasourceId(), schema, tableName, column, true));
    }
}
```

`DomainSchemaParser.java`:
```java
package com.hcl.jrsmeta.parse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import java.io.IOException;
import java.util.*;

public final class DomainSchemaParser {

    public DomainSchema parse(byte[] xml) throws IOException {
        Document doc = Xml.parse(xml);
        Map<String, DomainSchema.Table> tables = new LinkedHashMap<>();
        for (Element t : Xml.descendants(doc, "jdbcTable")) {
            Map<String, String> aliases = new LinkedHashMap<>();
            for (Element ji : Xml.children(t, "joinInfo")) aliases.put(Xml.attr(ji, "alias"), Xml.attr(ji, "referenceId"));
            for (Element trl : Xml.children(t, "tableRefList"))
                for (Element tr : Xml.children(trl, "tableRef")) aliases.put(Xml.attr(tr, "tableAlias"), Xml.attr(tr, "tableId"));
            boolean joinTree = !aliases.isEmpty() || !Xml.children(t, "joinList").isEmpty();
            String tableName = Xml.attr(t, "datasourceTableName") != null ? Xml.attr(t, "datasourceTableName") : Xml.attr(t, "tableName");
            tables.put(Xml.attr(t, "id"), new DomainSchema.Table(Xml.attr(t, "id"), Xml.attr(t, "datasourceId"), Xml.attr(t, "schemaAlias"),
                    tableName, fields(t), aliases, joinTree));
        }
        Map<String, DomainSchema.Query> queries = new LinkedHashMap<>();
        for (Element q : Xml.descendants(doc, "jdbcQuery"))
            queries.put(Xml.attr(q, "id"), new DomainSchema.Query(Xml.attr(q, "id"), Xml.attr(q, "datasourceId"), Xml.childText(q, "query"), fields(q)));

        List<DomainSchema.Item> items = new ArrayList<>();
        for (Element i : Xml.descendants(doc, "item"))
            items.add(new DomainSchema.Item(Xml.attr(i, "id"), Xml.attr(i, "label"), Xml.attr(i, "description"), Xml.attr(i, "resourceId"), groupPath(i)));
        return new DomainSchema(tables, queries, items);
    }

    private static Map<String, DomainSchema.Field> fields(Element owner) {
        Map<String, DomainSchema.Field> out = new LinkedHashMap<>();
        for (Element fl : Xml.children(owner, "fieldList"))
            for (Element f : Xml.children(fl, "field"))
                out.put(Xml.attr(f, "id"), new DomainSchema.Field(Xml.attr(f, "id"), Xml.attr(f, "fieldDBName"), Xml.attr(f, "type")));
        return out;
    }

    /** Ancestor itemGroup ids, outermost first, joined with '/'; empty for island-level items. */
    private static String groupPath(Element item) {
        Deque<String> path = new ArrayDeque<>();
        for (Node n = item.getParentNode(); n instanceof Element e; n = n.getParentNode())
            if ("itemGroup".equals(e.getLocalName() == null ? e.getTagName() : e.getLocalName()) && Xml.attr(e, "id") != null) path.addFirst(Xml.attr(e, "id"));
        return String.join("/", path);
    }
}
```

`DomainParser.java`:
```java
package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;

/** Spec §9.2: Domain -> SEMANTIC_MODEL + LOGICAL_FIELD children carrying their physical column mapping. */
public final class DomainParser implements DescriptorParser {
    private final DomainSchemaParser schemaParser = new DomainSchemaParser();

    @Override public Set<String> resourceTypes() { return Set.of("semanticLayerDataSource"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri();
        JsonNode d = ctx.repo().descriptor(uri, "semanticLayerDataSource");
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", "semanticLayerDataSource");
        List<Ref> refs = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        Descriptors.refUri(d.path("dataSource")).ifPresent(ds -> { attrs.put("dataSourceUri", ds); refs.add(new Ref(uri, ds, Ref.RefKind.DATASOURCE)); });

        byte[] xml = null;
        JsonNode schema = d.path("schema");
        String inline = schema.path("schemaFile").path("content").asText(null);
        Optional<String> schemaRef = Descriptors.refUri(schema);
        if (inline != null && !inline.isEmpty()) { attrs.put("schemaSource", "inline"); xml = Base64.getMimeDecoder().decode(inline); }
        else if (schemaRef.isPresent()) {
            attrs.put("schemaSource", "reference"); attrs.put("schemaUri", schemaRef.get());
            try { xml = ctx.repo().file(schemaRef.get(), "application/xml"); }
            catch (IOException e) { warnings.add("schema.xml fetch failed for " + schemaRef.get() + ": " + e.getMessage()); }
        } else { attrs.put("schemaSource", "missing"); warnings.add("domain " + uri + " has no schema in its descriptor"); }

        DomainSchema ds = null;
        if (xml != null) {
            try { ds = schemaParser.parse(xml); }
            catch (IOException e) { warnings.add("schema.xml of " + uri + " could not be parsed: " + e.getMessage()); }
        }

        List<Asset> assets = new ArrayList<>();
        List<Asset> children = new ArrayList<>();
        if (ds != null) {
            attrs.put("tables", ds.tables().values().stream().map(t -> Attrs.of("id", t.id(), "schema", t.schemaAlias(), "table", t.tableName(), "datasourceAlias", t.datasourceId(), "joinTree", t.joinTree())).toList());
            attrs.put("queries", ds.queries().values().stream().map(q -> Attrs.of("id", q.id(), "sql", ctx.config().redactSqlLiterals() ? SqlRedactor.redact(q.sql()) : q.sql())).toList());
            attrs.put("itemCount", ds.items().size());
        }
        Asset model = Asset.of(ctx.serverId(), lookup, AssetType.SEMANTIC_MODEL, attrs);
        assets.add(model);
        if (ds != null) {
            List<String> unresolved = new ArrayList<>();
            for (DomainSchema.Item it : ds.items()) {
                Optional<DomainSchema.PhysicalColumn> pc = ds.resolve(it.resourceId());
                Map<String, Object> a = Attrs.of("resourceId", it.resourceId(), "groupPath", it.groupPath(), "description", it.description());
                if (pc.isPresent()) {
                    a.put("datasourceAlias", pc.get().datasourceId()); a.put("physicalSchema", pc.get().schema());
                    a.put("physicalTable", pc.get().table()); a.put("physicalColumn", pc.get().column());
                    a.put("mappingConfidence", pc.get().exact() ? Confidence.EXACT.name() : Confidence.DERIVED.name());
                } else {
                    a.put("mappingConfidence", Confidence.INFERRED.name());
                    unresolved.add(it.resourceId());
                    warnings.add("domain " + uri + " item " + it.id() + " has unresolvable resourceId " + it.resourceId());
                }
                children.add(Asset.child(model, "item", it.id(), AssetType.LOGICAL_FIELD, it.label() != null ? it.label() : it.id(), a));
            }
            if (!unresolved.isEmpty()) assets.set(0, model.withAttributes(Map.of("unresolvedItems", unresolved)));
        }
        assets.addAll(children);
        return new ParseResult(assets, refs, warnings);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=DomainSchemaParserTest,DomainParserTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(parse): Domain schema.xml parser and semantic model parser with item->column mapping"
```

---

### Task 11: Ad hoc views, generic resources, parser registry

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/parse/AdhocViewParser.java`, `GenericResourceParser.java`, `ParserRegistry.java`
- Test: `src/test/java/com/hcl/jrsmeta/parse/AdhocViewParserTest.java`, `GenericResourceParserTest.java`, `ParserRegistryTest.java`

**Interfaces:**
- Produces:
  - `AdhocViewParser` for `adhocDataView` → `BI_VIEW`; ref `DATASOURCE` to the backing Topic/Domain/datasource (any `*Reference.uri` under `dataSource`); attributes `dataSourceUri`, `queryPresent`.
  - `GenericResourceParser` for `folder`→`FOLDER`, `file`→`FILE` (`fileType`), `inputControl`→`INPUT_CONTROL` (`controlType`, `queryUri`/embedded query datasource ref → `DATASOURCE` ref), `query`→`QUERY` (`queryLanguage`, `queryText`, `DATASOURCE` ref), anything else → `OTHER`. It is the registry fallback.
  - `ParserRegistry.defaults()`; `DescriptorParser forType(String resourceType)` (never null). `ParserRegistry.defaults()` registers Report (8), DataSource (9), Domain (10), Adhoc (11), Dashboard (12), Olap (13) parsers — Tasks 12/13 add their lines to `defaults()`.

- [ ] **Step 1: Write the failing tests**

`AdhocViewParserTest.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdhocViewParserTest {
    ParseContext ctx(FakeRepository r) { return new ParseContext(r, "srv", TestConfig.base().build()); }
    ResourceLookup lk = new ResourceLookup("/adhoc/sales", "Sales view", null, "adhocDataView", null, null, 1);

    @Test void parse_backingDomainBecomesDatasourceRef() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/adhoc/sales",
            "{\"uri\":\"/adhoc/sales\",\"dataSource\":{\"semanticLayerDataSourceReference\":{\"uri\":\"/domains/fm\"}},\"query\":{\"multiAxis\":{}}}");
        ParseResult p = new AdhocViewParser().parse(ctx(r), lk);
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.BI_VIEW);
        assertThat(p.assets().get(0).attributes()).containsEntry("dataSourceUri", "/domains/fm").containsEntry("queryPresent", true);
        assertThat(p.refs()).containsExactly(new Ref("/adhoc/sales", "/domains/fm", Ref.RefKind.DATASOURCE));
    }

    @Test void parse_noDataSourceWarns() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/adhoc/sales", "{\"uri\":\"/adhoc/sales\"}");
        ParseResult p = new AdhocViewParser().parse(ctx(r), lk);
        assertThat(p.refs()).isEmpty();
        assertThat(p.warnings()).anyMatch(w -> w.contains("no data source"));
    }
}
```

`GenericResourceParserTest.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GenericResourceParserTest {
    ParseContext ctx(FakeRepository r) { return new ParseContext(r, "srv", TestConfig.base().build()); }

    @Test void folder_needsNoDescriptorFetch() throws Exception {
        FakeRepository r = new FakeRepository();
        ParseResult p = new GenericResourceParser().parse(ctx(r), new ResourceLookup("/reports", "Reports", null, "folder", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.FOLDER);
        assertThat(r.requestedUris).isEmpty();
    }

    @Test void file_capturesType() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/img/logo", "{\"uri\":\"/img/logo\",\"type\":\"img\"}");
        ParseResult p = new GenericResourceParser().parse(ctx(r), new ResourceLookup("/img/logo", "logo", null, "file", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.FILE);
        assertThat(p.assets().get(0).attributes()).containsEntry("fileType", "img");
    }

    @Test void query_emitsDatasourceRefAndSql() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/q/shared", "{\"uri\":\"/q/shared\",\"value\":\"SELECT 1\",\"language\":\"sql\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/pg\"}}}");
        ParseResult p = new GenericResourceParser().parse(ctx(r), new ResourceLookup("/q/shared", "q", null, "query", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.QUERY);
        assertThat(p.assets().get(0).attributes()).containsEntry("queryText", "SELECT 1").containsEntry("queryLanguage", "sql");
        assertThat(p.refs()).containsExactly(new Ref("/q/shared", "/ds/pg", Ref.RefKind.DATASOURCE));
    }

    @Test void inputControl_withEmbeddedQueryRefsDatasource() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/ic/region", "{\"uri\":\"/ic/region\",\"type\":4,\"query\":{\"query\":{\"value\":\"SELECT r FROM region\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/pg\"}}}}}");
        ParseResult p = new GenericResourceParser().parse(ctx(r), new ResourceLookup("/ic/region", "Region", null, "inputControl", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.INPUT_CONTROL);
        assertThat(p.assets().get(0).attributes()).containsEntry("controlType", 4);
        assertThat(p.refs()).containsExactly(new Ref("/ic/region", "/ds/pg", Ref.RefKind.DATASOURCE));
    }

    @Test void unknownType_becomesOtherWithoutFailing() throws Exception {
        FakeRepository r = new FakeRepository();   // descriptor 404s
        ParseResult p = new GenericResourceParser().parse(ctx(r), new ResourceLookup("/x/odd", "odd", null, "mysteryType", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.OTHER);
        assertThat(p.assets().get(0).attributes()).containsEntry("resourceType", "mysteryType");
    }
}
```

`ParserRegistryTest.java`:
```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=AdhocViewParserTest,GenericResourceParserTest,ParserRegistryTest`
Expected: compilation errors.

- [ ] **Step 3: Write the parsers and registry**

`AdhocViewParser.java`:
```java
package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;

/**
 * Spec §9.3. Phase 1 needs only the backing Topic/Domain/datasource reference, which the live descriptor carries;
 * the opaque query/component state (export-archive path) is left for Phase 2 column lineage.
 */
public final class AdhocViewParser implements DescriptorParser {
    @Override public Set<String> resourceTypes() { return Set.of("adhocDataView"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri();
        JsonNode d = ctx.repo().descriptor(uri, "adhocDataView");
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", "adhocDataView");
        attrs.put("queryPresent", d.has("query") && !d.get("query").isNull());
        List<Ref> refs = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        Optional<String> ds = Descriptors.refUri(d.path("dataSource"));
        if (ds.isPresent()) { attrs.put("dataSourceUri", ds.get()); refs.add(new Ref(uri, ds.get(), Ref.RefKind.DATASOURCE)); }
        else warnings.add("ad hoc view " + uri + " exposes no data source reference in its live descriptor");
        return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, AssetType.BI_VIEW, attrs)), refs, warnings);
    }
}
```

`GenericResourceParser.java`:
```java
package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;

/** Folders, files, input controls, shared queries and any type without a dedicated parser. */
public final class GenericResourceParser implements DescriptorParser {
    @Override public Set<String> resourceTypes() { return Set.of("folder", "file", "inputControl", "query"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri(); String type = lookup.resourceType() == null ? "unknown" : lookup.resourceType();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", type);
        List<Ref> refs = new ArrayList<>(); List<String> warnings = new ArrayList<>();

        if ("folder".equals(type)) return ParseResult.of(Asset.of(ctx.serverId(), lookup, AssetType.FOLDER, attrs));

        JsonNode d;
        try { d = ctx.repo().descriptor(uri, type); }
        catch (IOException e) { warnings.add("descriptor of " + uri + " (" + type + ") unavailable: " + e.getMessage()); d = null; }

        AssetType at = switch (type) {
            case "file" -> AssetType.FILE;
            case "inputControl" -> AssetType.INPUT_CONTROL;
            case "query" -> AssetType.QUERY;
            default -> AssetType.OTHER;
        };
        if (d != null) {
            switch (at) {
                case FILE -> attrs.put("fileType", Descriptors.text(d, "type"));
                case QUERY -> {
                    attrs.put("queryLanguage", Descriptors.text(d, "language"));
                    String v = Descriptors.text(d, "value");
                    attrs.put("queryText", ctx.config().redactSqlLiterals() ? SqlRedactor.redact(v) : v);
                    Descriptors.refUri(d.path("dataSource")).ifPresent(u -> { attrs.put("dataSourceUri", u); refs.add(new Ref(uri, u, Ref.RefKind.DATASOURCE)); });
                }
                case INPUT_CONTROL -> {
                    attrs.put("controlType", d.hasNonNull("type") ? d.get("type").asInt() : null);
                    attrs.put("mandatory", d.path("mandatory").asBoolean(false));
                    JsonNode q = d.path("query");
                    Descriptors.refUri(q).ifPresent(u -> attrs.put("queryUri", u));            // shared query resource
                    JsonNode embedded = q.path("query");                                         // embedded query
                    if (!embedded.isMissingNode()) {
                        String v = Descriptors.text(embedded, "value");
                        attrs.put("queryText", ctx.config().redactSqlLiterals() ? SqlRedactor.redact(v) : v);
                        Descriptors.refUri(embedded.path("dataSource")).ifPresent(u -> { attrs.put("dataSourceUri", u); refs.add(new Ref(uri, u, Ref.RefKind.DATASOURCE)); });
                    }
                    Descriptors.refUri(d.path("dataType")).ifPresent(u -> attrs.put("dataTypeUri", u));
                    Descriptors.refUri(d.path("listOfValues")).ifPresent(u -> attrs.put("listOfValuesUri", u));
                }
                default -> { }
            }
        }
        return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, at, attrs)), refs, warnings);
    }
}
```

`ParserRegistry.java`:
```java
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
                new ReportUnitParser(), new DataSourceParser(), new DomainParser(), new AdhocViewParser(), new GenericResourceParser()));
    }

    public DescriptorParser forType(String resourceType) {
        return resourceType == null ? fallback : byType.getOrDefault(resourceType, fallback);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=AdhocViewParserTest,GenericResourceParserTest,ParserRegistryTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(parse): ad hoc view parser, generic resource parser, parser registry"
```

---

### Task 12: Export archive reader and dashboard parser

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/parse/ExportArchive.java`, `DashboardParser.java`
- Modify: `src/main/java/com/hcl/jrsmeta/parse/ParserRegistry.java` (`defaults()` adds `new DashboardParser()`)
- Test: `src/test/java/com/hcl/jrsmeta/parse/ExportArchiveTest.java`, `DashboardParserTest.java`, `src/test/java/com/hcl/jrsmeta/testutil/Zips.java`

**Interfaces:**
- Consumes: `JrsRepository.exportArchive` (Task 5), `DescriptorParser` (Task 8).
- Produces:
  - `ExportArchive.read(byte[] zip)` → `ExportArchive`; `Optional<byte[]> findBySuffix(String suffix)`; `Set<String> entryNames()`. Caps total uncompressed size at 256 MB.
  - `DashboardParser` for `dashboard` → `BI_DASHBOARD` + `DASHBOARD_TILE` children; refs `DASHLET` (`fromUri`=dashboard, `toUri`=tile resource, attributes `tileId`, `tileType`). Dashboard attributes: `parseMode` (`export`|`descriptor`), `tileCount`, `wiring` (list of `{producer, consumers}`). Tile attributes: `tileType`, `resourceUri`, `dataSourceUri`, `x`, `y`, `width`, `height`.
  - Test util `Zips.of(Map<String, byte[]> entries)` builds a zip in memory.

Export zip layout (verified in the jasper-deploy skill's `dashboard-model.md`): `resources/<path>.xml` descriptor + `resources/<path>_files/components.data` (JSON array; element 0 is the `dashboardProperties` singleton), `.../layout` (HTML `<div data-componentId data-x data-y data-width data-height>`), `.../wiring.data` (JSON array of `{producer, consumers[]}` events). Only report/ad hoc tiles carry a `resource` URI.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/hcl/jrsmeta/testutil/Zips.java`:
```java
package com.hcl.jrsmeta.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Zips {
    private Zips() {}
    public static byte[] of(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); ZipOutputStream z = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); }
            z.finish();
            return bos.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
```

`ExportArchiveTest.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.testutil.Zips;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ExportArchiveTest {
    @Test void read_indexesEntriesAndFindsBySuffix() throws Exception {
        byte[] zip = Zips.of(Map.of("index.xml", "<i/>".getBytes(), "resources/dashboards/exec_files/components.data", "[]".getBytes(), "resources/dashboards/exec.xml", "<d/>".getBytes()));
        ExportArchive a = ExportArchive.read(zip);
        assertThat(a.entryNames()).hasSize(3);
        assertThat(a.findBySuffix("_files/components.data")).map(String::new).contains("[]");
        assertThat(a.findBySuffix("_files/layout")).isEmpty();
    }
    @Test void read_rejectsGarbage() {
        assertThatThrownBy(() -> ExportArchive.read("not a zip".getBytes())).isInstanceOf(java.io.IOException.class);
    }
}
```

`DashboardParserTest.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import com.hcl.jrsmeta.testutil.Zips;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DashboardParserTest {
    static final String COMPONENTS = """
        [{"type":"dashboardProperties","id":"dashboardProperties","canvasBackgroundColor":"#fff"},
         {"type":"reportUnit","id":"Sales_Report","name":"Sales Report","label":"Sales Report","resource":"/reports/sales","dataSourceUri":"/ds/pg","showTitleBar":true},
         {"type":"adhocDataView","id":"Trend","name":"Trend","resource":"/adhoc/trend"},
         {"type":"text","id":"Title","label":"Text","text":"Executive view"},
         {"type":"image","id":"Logo","label":"Image","url":"repo:/img/logo"}]
        """;
    static final String LAYOUT = """
        <div data-componentId='Sales_Report' data-x='0' data-y='0' data-width='20' data-height='10'></div>
        <div data-width='20' data-height='10' data-componentId="Trend" data-x="20" data-y="0"></div>
        <div data-componentId='Title' data-x='0' data-y='10' data-width='40' data-height='2'></div>
        """;
    static final String WIRING = """
        [{"producer":"dashboardProperties:@init","consumers":["Sales_Report:@refresh","Trend:@refresh"]},
         {"producer":"Sales_Report:Region","consumers":["Trend:Region"]}]
        """;
    ResourceLookup lk = new ResourceLookup("/dashboards/exec", "Exec", null, "dashboard", null, "2024-01-01T00:00:00", 2);

    private byte[] zip() {
        return Zips.of(Map.of("index.xml", "<index/>".getBytes(), "resources/dashboards/exec.xml", "<dashboardModelResource/>".getBytes(),
                "resources/dashboards/exec_files/components.data", COMPONENTS.getBytes(),
                "resources/dashboards/exec_files/layout", LAYOUT.getBytes(),
                "resources/dashboards/exec_files/wiring.data", WIRING.getBytes()));
    }

    @Test void parse_exportModeProducesTilesAndDashletRefs() throws Exception {
        FakeRepository r = new FakeRepository().export("/dashboards/exec", zip());
        ParseResult p = new DashboardParser().parse(new ParseContext(r, "srv", TestConfig.base().build()), lk);
        Asset dash = p.assets().get(0);
        assertThat(dash.type()).isEqualTo(AssetType.BI_DASHBOARD);
        assertThat(dash.attributes()).containsEntry("parseMode", "export").containsEntry("tileCount", 4);
        assertThat(p.assets()).filteredOn(a -> a.type() == AssetType.DASHBOARD_TILE).extracting(Asset::id)
                .containsExactly("srv|/dashboards/exec#tile:Sales_Report", "srv|/dashboards/exec#tile:Trend", "srv|/dashboards/exec#tile:Title", "srv|/dashboards/exec#tile:Logo");
        Asset sales = p.assets().get(1);
        assertThat(sales.attributes()).containsEntry("tileType", "reportUnit").containsEntry("resourceUri", "/reports/sales").containsEntry("dataSourceUri", "/ds/pg")
                .containsEntry("x", 0).containsEntry("y", 0).containsEntry("width", 20).containsEntry("height", 10);
        Asset trend = p.assets().get(2);
        assertThat(trend.attributes()).containsEntry("x", 20).containsEntry("width", 20);     // attribute order independent
        assertThat(p.refs()).containsExactly(
                new Ref("/dashboards/exec", "/reports/sales", Ref.RefKind.DASHLET, Map.of("tileId", "Sales_Report", "tileType", "reportUnit")),
                new Ref("/dashboards/exec", "/adhoc/trend", Ref.RefKind.DASHLET, Map.of("tileId", "Trend", "tileType", "adhocDataView")));
        assertThat((java.util.List<?>) dash.attributes().get("wiring")).hasSize(2);
    }

    @Test void parse_descriptorModeWhenDeepParseDisabled() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/dashboards/exec", "{\"uri\":\"/dashboards/exec\",\"label\":\"Exec\"}");
        ParseResult p = new DashboardParser().parse(new ParseContext(r, "srv", TestConfig.base().deepParseDashboards(false).build()), lk);
        assertThat(p.assets()).hasSize(1);
        assertThat(p.assets().get(0).attributes()).containsEntry("parseMode", "descriptor");
        assertThat(p.refs()).isEmpty();
        assertThat(p.warnings()).anyMatch(w -> w.contains("deepParseDashboards"));
    }

    @Test void parse_exportFailureDegradesToDescriptorWithWarning() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/dashboards/exec", "{\"uri\":\"/dashboards/exec\"}");   // no export registered -> IOException
        ParseResult p = new DashboardParser().parse(new ParseContext(r, "srv", TestConfig.base().build()), lk);
        assertThat(p.assets()).hasSize(1);
        assertThat(p.assets().get(0).attributes()).containsEntry("parseMode", "descriptor");
        assertThat(p.warnings()).anyMatch(w -> w.contains("export"));
    }

    @Test void parse_archiveWithoutComponentsWarns() throws Exception {
        FakeRepository r = new FakeRepository().export("/dashboards/exec", Zips.of(Map.of("index.xml", "<i/>".getBytes())));
        ParseResult p = new DashboardParser().parse(new ParseContext(r, "srv", TestConfig.base().build()), lk);
        assertThat(p.assets()).hasSize(1);
        assertThat(p.warnings()).anyMatch(w -> w.contains("components.data"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=ExportArchiveTest,DashboardParserTest`
Expected: compilation errors.

- [ ] **Step 3: Write the archive reader and dashboard parser**

`ExportArchive.java`:
```java
package com.hcl.jrsmeta.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** In-memory view of a JRS export zip (spec §5.2 export mode). */
public final class ExportArchive {
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private final Map<String, byte[]> entries;

    private ExportArchive(Map<String, byte[]> entries) { this.entries = entries; }

    public static ExportArchive read(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] data = z.readAllBytes();
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new IOException("export archive exceeds " + MAX_TOTAL_BYTES + " bytes uncompressed");
                out.put(e.getName().replace('\\', '/'), data);
            }
        }
        if (out.isEmpty()) throw new IOException("not a zip archive or archive is empty");
        return new ExportArchive(out);
    }

    public Set<String> entryNames() { return Collections.unmodifiableSet(entries.keySet()); }

    public Optional<byte[]> findBySuffix(String suffix) {
        return entries.entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).map(Map.Entry::getValue).findFirst();
    }
}
```

`DashboardParser.java`:
```java
package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spec §9.4: dashboard export archive -> BI_DASHBOARD + tiles + DASHLET refs; falls back to the bare descriptor. */
public final class DashboardParser implements DescriptorParser {
    private static final Pattern DIV = Pattern.compile("<div\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_ATTR = Pattern.compile("data-(\\w+)\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override public Set<String> resourceTypes() { return Set.of("dashboard"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", "dashboard");
        List<Ref> refs = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        ExportArchive archive = null;

        if (!ctx.config().deepParseDashboards()) {
            warnings.add("dashboard " + uri + ": deepParseDashboards=false, tiles and dashlet lineage not extracted");
        } else {
            try { archive = ExportArchive.read(ctx.repo().exportArchive(List.of(uri))); }
            catch (IOException e) { warnings.add("dashboard " + uri + ": export failed, falling back to descriptor: " + e.getMessage()); }
        }

        if (archive == null) {
            try { ctx.repo().descriptor(uri, "dashboard"); } catch (IOException e) { warnings.add("dashboard " + uri + " descriptor unavailable: " + e.getMessage()); }
            attrs.put("parseMode", "descriptor");
            return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, AssetType.BI_DASHBOARD, attrs)), refs, warnings);
        }

        attrs.put("parseMode", "export");
        Optional<byte[]> components = archive.findBySuffix("_files/components.data");
        if (components.isEmpty()) warnings.add("dashboard " + uri + ": export archive has no components.data");
        Map<String, Map<String, Integer>> layout = archive.findBySuffix("_files/layout").map(b -> parseLayout(new String(b, StandardCharsets.UTF_8))).orElse(Map.of());
        archive.findBySuffix("_files/wiring.data").ifPresent(b -> {
            try { attrs.put("wiring", mapper.readValue(b, List.class)); } catch (IOException e) { warnings.add("dashboard " + uri + ": wiring.data unreadable: " + e.getMessage()); }
        });

        List<Asset> tiles = new ArrayList<>();
        Asset dash = Asset.of(ctx.serverId(), lookup, AssetType.BI_DASHBOARD, attrs);
        if (components.isPresent()) {
            JsonNode arr;
            try { arr = mapper.readTree(components.get()); }
            catch (IOException e) { warnings.add("dashboard " + uri + ": components.data unreadable: " + e.getMessage()); arr = mapper.createArrayNode(); }
            for (JsonNode c : arr) {
                String type = Descriptors.text(c, "type"); String id = Descriptors.text(c, "id");
                if (id == null || "dashboardProperties".equals(type) || "dashboardProperties".equals(id)) continue;
                String resource = Descriptors.text(c, "resource");
                Map<String, Object> ta = Attrs.of("tileType", type, "resourceUri", resource, "dataSourceUri", Descriptors.text(c, "dataSourceUri"));
                Map<String, Integer> pos = layout.get(id);
                if (pos != null) ta.putAll(pos);
                String label = Descriptors.text(c, "label") != null ? Descriptors.text(c, "label") : (Descriptors.text(c, "name") != null ? Descriptors.text(c, "name") : id);
                tiles.add(Asset.child(dash, "tile", id, AssetType.DASHBOARD_TILE, label, ta));
                if (resource != null && !resource.isBlank())
                    refs.add(new Ref(uri, resource, Ref.RefKind.DASHLET, Map.of("tileId", id, "tileType", type == null ? "" : type)));
            }
        }
        List<Asset> assets = new ArrayList<>();
        assets.add(dash.withAttributes(Map.of("tileCount", tiles.size())));
        assets.addAll(tiles);
        return new ParseResult(assets, refs, warnings);
    }

    /** componentId -> {x,y,width,height} from the 40-column grid HTML. Attribute order varies between designer versions. */
    static Map<String, Map<String, Integer>> parseLayout(String html) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        Matcher div = DIV.matcher(html);
        while (div.find()) {
            Map<String, String> raw = new HashMap<>();
            Matcher a = DATA_ATTR.matcher(div.group(1));
            while (a.find()) raw.put(a.group(1).toLowerCase(Locale.ROOT), a.group(2));
            String id = raw.get("componentid");
            if (id == null) continue;
            Map<String, Integer> pos = new LinkedHashMap<>();
            for (String k : List.of("x", "y", "width", "height")) if (raw.containsKey(k)) { try { pos.put(k, Integer.parseInt(raw.get(k).trim())); } catch (NumberFormatException ignored) { } }
            out.put(id, pos);
        }
        return out;
    }
}
```

Modify `ParserRegistry.defaults()`:
```java
        return new ParserRegistry(new GenericResourceParser(), List.of(
                new ReportUnitParser(), new DataSourceParser(), new DomainParser(), new AdhocViewParser(),
                new DashboardParser(), new GenericResourceParser()));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=ExportArchiveTest,DashboardParserTest,ParserRegistryTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(parse): export archive reader and dashboard parser with tiles and dashlet refs"
```

---

### Task 13: OLAP — Mondrian schema, connections, OLAP views

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/parse/MondrianSchema.java`, `MondrianSchemaParser.java`, `OlapParser.java`
- Modify: `ParserRegistry.defaults()` adds `new OlapParser()`
- Test: `src/test/java/com/hcl/jrsmeta/parse/MondrianSchemaParserTest.java`, `OlapParserTest.java`

**Interfaces:**
- Produces:
  - `record MondrianSchema(String name, List<Cube> cubes)`; `Cube(name, factTable, List<Measure> measures, List<Dimension> dimensions)`; `Measure(name, column, aggregator)`; `Dimension(name, List<Level> levels)`; `Level(name, table, column)`.
  - `MondrianSchemaParser.parse(byte[] xml)` → `MondrianSchema` (resolves shared `<Dimension>` via `<DimensionUsage source>`).
  - `OlapParser` for `olapMondrianSchema` → `OLAP_SCHEMA` + `OLAP_CUBE` children (attributes `factTable`, `measures`, `dimensions`); `secureMondrianConnection`/`mondrianConnection` → `OLAP_CONNECTION` with refs `DATASOURCE` and `OLAP_SCHEMA`; `olapUnit` → `OLAP_VIEW` with ref `OLAP_CONNECTION` and attribute `mdxQuery`.

- [ ] **Step 1: Write the failing tests**

`MondrianSchemaParserTest.java`:
```java
package com.hcl.jrsmeta.parse;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MondrianSchemaParserTest {
    static final String XML = """
        <Schema name="FoodMart">
          <Dimension name="Geography">
            <Hierarchy hasAll="true" primaryKey="store_id" primaryKeyTable="store">
              <Join leftKey="region_id" rightKey="region_id"><Table name="store"/><Table name="region"/></Join>
              <Level name="Country" table="region" column="sales_country" type="String"/>
              <Level name="Store Name" table="store" column="store_name" type="String"/>
            </Hierarchy>
          </Dimension>
          <Cube name="Sales">
            <Table name="sales_fact_2006"/>
            <DimensionUsage source="Geography" name="Geography" foreignKey="store_id"/>
            <Dimension name="Product" foreignKey="product_id">
              <Hierarchy hasAll="true"><Table name="product"/><Level name="Brand" column="brand_name"/></Hierarchy>
            </Dimension>
            <Measure name="Unit Sales" column="unit_sales" aggregator="sum"/>
            <Measure name="Store Cost" column="store_cost" aggregator="sum"/>
          </Cube>
        </Schema>
        """;

    @Test void parse_cubesMeasuresLevelsAndSharedDimensions() throws Exception {
        MondrianSchema s = new MondrianSchemaParser().parse(XML.getBytes());
        assertThat(s.name()).isEqualTo("FoodMart");
        assertThat(s.cubes()).hasSize(1);
        MondrianSchema.Cube c = s.cubes().get(0);
        assertThat(c.factTable()).isEqualTo("sales_fact_2006");
        assertThat(c.measures()).containsExactly(new MondrianSchema.Measure("Unit Sales", "unit_sales", "sum"), new MondrianSchema.Measure("Store Cost", "store_cost", "sum"));
        assertThat(c.dimensions()).extracting(MondrianSchema.Dimension::name).containsExactly("Geography", "Product");
        assertThat(c.dimensions().get(0).levels()).containsExactly(new MondrianSchema.Level("Country", "region", "sales_country"), new MondrianSchema.Level("Store Name", "store", "store_name"));
        assertThat(c.dimensions().get(1).levels()).containsExactly(new MondrianSchema.Level("Brand", "product", "brand_name"));   // single-table hierarchy inherits table
    }
}
```

`OlapParserTest.java`:
```java
package com.hcl.jrsmeta.parse;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OlapParserTest {
    ParseContext ctx(FakeRepository r) { return new ParseContext(r, "srv", TestConfig.base().build()); }

    @Test void schema_fileProducesCubeChildren() throws Exception {
        FakeRepository r = new FakeRepository().file("/analysis/fm_schema",
            "<Schema name=\"FM\"><Cube name=\"Sales\"><Table name=\"sales_fact\"/><Measure name=\"Units\" column=\"unit_sales\" aggregator=\"sum\"/></Cube></Schema>");
        ParseResult p = new OlapParser().parse(ctx(r), new ResourceLookup("/analysis/fm_schema", "FM", null, "olapMondrianSchema", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.OLAP_SCHEMA);
        assertThat(p.assets()).filteredOn(a -> a.type() == AssetType.OLAP_CUBE).hasSize(1);
        Asset cube = p.assets().get(1);
        assertThat(cube.id()).isEqualTo("srv|/analysis/fm_schema#cube:Sales");
        assertThat(cube.attributes()).containsEntry("factTable", "sales_fact");
        assertThat(cube.attributes().toString()).contains("unit_sales");
    }

    @Test void connection_refsDatasourceAndSchema() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/analysis/conn",
            "{\"uri\":\"/analysis/conn\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/fm\"}},\"schema\":{\"schemaReference\":{\"uri\":\"/analysis/fm_schema\"}}}");
        ParseResult p = new OlapParser().parse(ctx(r), new ResourceLookup("/analysis/conn", "c", null, "secureMondrianConnection", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.OLAP_CONNECTION);
        assertThat(p.refs()).containsExactlyInAnyOrder(new Ref("/analysis/conn", "/ds/fm", Ref.RefKind.DATASOURCE), new Ref("/analysis/conn", "/analysis/fm_schema", Ref.RefKind.OLAP_SCHEMA));
    }

    @Test void olapUnit_refsConnectionAndKeepsMdx() throws Exception {
        FakeRepository r = new FakeRepository().descriptor("/analysis/view",
            "{\"uri\":\"/analysis/view\",\"mdxQuery\":\"select {[Measures].[Units]} on columns from Sales\",\"olapConnection\":{\"olapConnectionReference\":{\"uri\":\"/analysis/conn\"}}}");
        ParseResult p = new OlapParser().parse(ctx(r), new ResourceLookup("/analysis/view", "v", null, "olapUnit", null, null, 1));
        assertThat(p.assets().get(0).type()).isEqualTo(AssetType.OLAP_VIEW);
        assertThat(p.assets().get(0).attributes()).containsEntry("queryLanguage", "mdx").containsEntry("mdxQuery", "select {[Measures].[Units]} on columns from Sales");
        assertThat(p.refs()).containsExactly(new Ref("/analysis/view", "/analysis/conn", Ref.RefKind.OLAP_CONNECTION));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=MondrianSchemaParserTest,OlapParserTest`
Expected: compilation errors.

- [ ] **Step 3: Write the OLAP classes**

`MondrianSchema.java`:
```java
package com.hcl.jrsmeta.parse;

import java.util.List;

public record MondrianSchema(String name, List<Cube> cubes) {
    public record Cube(String name, String factTable, List<Measure> measures, List<Dimension> dimensions) {}
    public record Measure(String name, String column, String aggregator) {}
    public record Dimension(String name, List<Level> levels) {}
    public record Level(String name, String table, String column) {}
}
```

`MondrianSchemaParser.java`:
```java
package com.hcl.jrsmeta.parse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import java.io.IOException;
import java.util.*;

/** Spec §9.6: cube/dimension/level/measure -> physical table.column references. */
public final class MondrianSchemaParser {

    public MondrianSchema parse(byte[] xml) throws IOException {
        Document doc = Xml.parse(xml);
        Element root = doc.getDocumentElement();
        Map<String, MondrianSchema.Dimension> shared = new LinkedHashMap<>();
        for (Element d : Xml.children(root, "Dimension")) shared.put(Xml.attr(d, "name"), dimension(d));

        List<MondrianSchema.Cube> cubes = new ArrayList<>();
        for (Element c : Xml.children(root, "Cube")) {
            String fact = Xml.children(c, "Table").stream().findFirst().map(t -> Xml.attr(t, "name")).orElse(null);
            List<MondrianSchema.Measure> measures = new ArrayList<>();
            for (Element m : Xml.children(c, "Measure")) measures.add(new MondrianSchema.Measure(Xml.attr(m, "name"), Xml.attr(m, "column"), Xml.attr(m, "aggregator")));
            List<MondrianSchema.Dimension> dims = new ArrayList<>();
            for (Element child : Xml.children(c, "DimensionUsage")) {
                MondrianSchema.Dimension src = shared.get(Xml.attr(child, "source"));
                if (src != null) dims.add(new MondrianSchema.Dimension(Xml.attr(child, "name") != null ? Xml.attr(child, "name") : src.name(), src.levels()));
            }
            for (Element child : Xml.children(c, "Dimension")) dims.add(dimension(child));
            cubes.add(new MondrianSchema.Cube(Xml.attr(c, "name"), fact, measures, dims));
        }
        return new MondrianSchema(Xml.attr(root, "name"), cubes);
    }

    private static MondrianSchema.Dimension dimension(Element d) {
        List<MondrianSchema.Level> levels = new ArrayList<>();
        for (Element h : Xml.children(d, "Hierarchy")) {
            List<Element> tables = Xml.descendants(h, "Table");
            String single = tables.size() == 1 ? Xml.attr(tables.get(0), "name") : null;   // levels omit table= when the hierarchy has one table
            for (Element l : Xml.children(h, "Level"))
                levels.add(new MondrianSchema.Level(Xml.attr(l, "name"), Xml.attr(l, "table") != null ? Xml.attr(l, "table") : single, Xml.attr(l, "column")));
        }
        return new MondrianSchema.Dimension(Xml.attr(d, "name"), levels);
    }
}
```

`OlapParser.java`:
```java
package com.hcl.jrsmeta.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.*;
import java.io.IOException;
import java.util.*;

public final class OlapParser implements DescriptorParser {
    private final MondrianSchemaParser schemaParser = new MondrianSchemaParser();

    @Override public Set<String> resourceTypes() { return Set.of("olapMondrianSchema", "secureMondrianConnection", "mondrianConnection", "olapUnit"); }

    @Override public ParseResult parse(ParseContext ctx, ResourceLookup lookup) throws IOException {
        String uri = lookup.uri(); String type = lookup.resourceType();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("resourceType", type);
        List<Ref> refs = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        switch (type) {
            case "olapMondrianSchema" -> {
                List<Asset> assets = new ArrayList<>();
                MondrianSchema ms = null;
                try { ms = schemaParser.parse(ctx.repo().file(uri, "application/xml")); }
                catch (IOException e) { warnings.add("mondrian schema " + uri + " unavailable or unparseable: " + e.getMessage()); }
                if (ms != null) { attrs.put("schemaName", ms.name()); attrs.put("cubeCount", ms.cubes().size()); }
                Asset schema = Asset.of(ctx.serverId(), lookup, AssetType.OLAP_SCHEMA, attrs);
                assets.add(schema);
                if (ms != null) for (MondrianSchema.Cube c : ms.cubes()) {
                    Map<String, Object> ca = Attrs.of("factTable", c.factTable(),
                            "measures", c.measures().stream().map(m -> Attrs.of("name", m.name(), "column", m.column(), "aggregator", m.aggregator())).toList(),
                            "dimensions", c.dimensions().stream().map(d -> Attrs.of("name", d.name(),
                                    "levels", d.levels().stream().map(l -> Attrs.of("name", l.name(), "table", l.table(), "column", l.column())).toList())).toList());
                    assets.add(Asset.child(schema, "cube", c.name(), AssetType.OLAP_CUBE, c.name(), ca));
                }
                return new ParseResult(assets, refs, warnings);
            }
            case "secureMondrianConnection", "mondrianConnection" -> {
                JsonNode d = ctx.repo().descriptor(uri, type);
                Descriptors.refUri(d.path("dataSource")).ifPresent(u -> { attrs.put("dataSourceUri", u); refs.add(new Ref(uri, u, Ref.RefKind.DATASOURCE)); });
                Descriptors.refUri(d.path("schema")).ifPresent(u -> { attrs.put("schemaUri", u); refs.add(new Ref(uri, u, Ref.RefKind.OLAP_SCHEMA)); });
                return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, AssetType.OLAP_CONNECTION, attrs)), refs, warnings);
            }
            case "olapUnit" -> {
                JsonNode d = ctx.repo().descriptor(uri, type);
                attrs.put("queryLanguage", "mdx"); attrs.put("mdxQuery", Descriptors.text(d, "mdxQuery"));
                Descriptors.refUri(d.path("olapConnection")).ifPresent(u -> { attrs.put("olapConnectionUri", u); refs.add(new Ref(uri, u, Ref.RefKind.OLAP_CONNECTION)); });
                return new ParseResult(List.of(Asset.of(ctx.serverId(), lookup, AssetType.OLAP_VIEW, attrs)), refs, warnings);
            }
            default -> throw new IllegalStateException("unsupported OLAP type " + type);
        }
    }
}
```

Modify `ParserRegistry.defaults()` to the final list:
```java
        return new ParserRegistry(new GenericResourceParser(), List.of(
                new ReportUnitParser(), new DataSourceParser(), new DomainParser(), new AdhocViewParser(),
                new DashboardParser(), new OlapParser(), new GenericResourceParser()));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=MondrianSchemaParserTest,OlapParserTest,ParserRegistryTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(parse): Mondrian schema parser and OLAP schema/connection/view parser"
```

---

### Task 14: Operational metadata enrichment (schedules, recipients, permissions)

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/enrich/OperationalMetadataEnricher.java`
- Test: `src/test/java/com/hcl/jrsmeta/enrich/OperationalMetadataEnricherTest.java`

**Interfaces:**
- Consumes: `JrsRepository.jobs/job/permissions` (Task 5), `Asset` (Task 2), `ParseContext` (Task 8).
- Produces: `OperationalMetadataEnricher.enrich(ParseContext ctx, List<Asset> assets, List<String> warnings)` → new list. For every `BI_REPORT`: attribute `schedules` = list of `{id, label, owner, nextFireTime, previousFireTime, state, outputFormats, mailTo, repositoryFolder}`. For every top-level asset with a URI (parentId null): `permissions` = list of `{recipient, mask}`. Failures per asset become warnings; the asset is still returned.

Spec §6.2 operational metadata; `mailTo` is the "export surface" compliance needs (§4.1). Note: the jobs summary omits output formats and recipients, so each job is fetched in full (one extra GET per schedule).

- [ ] **Step 1: Write the failing test**

```java
package com.hcl.jrsmeta.enrich;

import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.parse.ParseContext;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class OperationalMetadataEnricherTest {
    Asset report = new Asset("srv|/reports/x", "srv", "/reports/x", AssetType.BI_REPORT, "X", null, "/reports", null, null, null, 1, Map.of());
    Asset field = new Asset("srv|/reports/x#field:a", "srv", null, AssetType.REPORT_FIELD, "a", null, "/reports", "srv|/reports/x", null, null, 1, Map.of());
    Asset folder = new Asset("srv|/reports", "srv", "/reports", AssetType.FOLDER, "Reports", null, "/", null, null, null, 1, Map.of());

    @Test void enrich_addsSchedulesWithRecipientsAndPermissions() throws Exception {
        FakeRepository r = new FakeRepository()
            .jobs("/reports/x", "{\"id\":7,\"label\":\"nightly\",\"owner\":\"jasperadmin\",\"reportUnitURI\":\"/reports/x\",\"state\":{\"nextFireTime\":\"2026-10-13T02:00:00\",\"previousFireTime\":\"2026-10-12T02:00:00\",\"value\":\"NORMAL\"}}")
            .job(7, "{\"id\":7,\"label\":\"nightly\",\"outputFormats\":{\"outputFormat\":[\"PDF\",\"XLSX\"]},\"mailNotification\":{\"toAddresses\":{\"address\":[\"ops@example.com\"]}},\"repositoryDestination\":{\"folderURI\":\"/out\"}}")
            .permissions("/reports/x", "{\"uri\":\"repo:/reports/x\",\"recipient\":\"role:/ROLE_USER\",\"mask\":2}")
            .permissions("/reports", "{\"uri\":\"repo:/reports\",\"recipient\":\"role:/ROLE_ADMINISTRATOR\",\"mask\":1}");
        List<String> warnings = new ArrayList<>();
        List<Asset> out = new OperationalMetadataEnricher().enrich(new ParseContext(r, "srv", TestConfig.base().build()), List.of(report, field, folder), warnings);

        Asset rep = out.get(0);
        List<?> schedules = (List<?>) rep.attributes().get("schedules");
        assertThat(schedules).hasSize(1);
        Map<?, ?> s = (Map<?, ?>) schedules.get(0);
        assertThat(s).containsEntry("id", 7L).containsEntry("label", "nightly").containsEntry("owner", "jasperadmin").containsEntry("nextFireTime", "2026-10-13T02:00:00")
                .containsEntry("outputFormats", List.of("PDF", "XLSX")).containsEntry("mailTo", List.of("ops@example.com")).containsEntry("repositoryFolder", "/out");
        assertThat((List<?>) rep.attributes().get("permissions")).hasSize(1);
        assertThat(out.get(1).attributes()).doesNotContainKey("permissions");       // child: skipped
        assertThat((List<?>) out.get(2).attributes().get("permissions")).hasSize(1);  // folder: permissions only
        assertThat(out.get(2).attributes()).doesNotContainKey("schedules");
        assertThat(warnings).isEmpty();
    }

    @Test void enrich_failuresBecomeWarningsNotExceptions() throws Exception {
        FakeRepository r = new FakeRepository().jobs("/reports/x", "{\"id\":9}");   // job(9) missing -> 404
        List<String> warnings = new ArrayList<>();
        List<Asset> out = new OperationalMetadataEnricher().enrich(new ParseContext(r, "srv", TestConfig.base().build()), List.of(report), warnings);
        assertThat(out).hasSize(1);
        List<?> schedules = (List<?>) out.get(0).attributes().get("schedules");
        assertThat(schedules).hasSize(1);                                             // summary kept even if detail fails
        assertThat(warnings).anyMatch(w -> w.contains("job 9"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=OperationalMetadataEnricherTest`
Expected: compilation error.

- [ ] **Step 3: Write the enricher**

```java
package com.hcl.jrsmeta.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.hcl.jrsmeta.model.Asset;
import com.hcl.jrsmeta.model.AssetType;
import com.hcl.jrsmeta.model.Attrs;
import com.hcl.jrsmeta.parse.Descriptors;
import com.hcl.jrsmeta.parse.ParseContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Spec §6.2 operational metadata: schedules (+ email recipients, output formats) and effective permissions. */
public final class OperationalMetadataEnricher {

    public List<Asset> enrich(ParseContext ctx, List<Asset> assets, List<String> warnings) {
        List<Asset> out = new ArrayList<>(assets.size());
        for (Asset a : assets) {
            if (a.parentId() != null || a.uri() == null) { out.add(a); continue; }
            Map<String, Object> extra = Attrs.of();
            if (a.type() == AssetType.BI_REPORT) extra.put("schedules", schedules(ctx, a.uri(), warnings));
            try {
                List<Map<String, Object>> perms = new ArrayList<>();
                for (JsonNode p : ctx.repo().permissions(a.uri()))
                    perms.add(Attrs.of("recipient", Descriptors.text(p, "recipient"), "mask", p.hasNonNull("mask") ? p.get("mask").asInt() : null));
                extra.put("permissions", perms);
            } catch (IOException e) { warnings.add("permissions of " + a.uri() + " unavailable: " + e.getMessage()); }
            out.add(a.withAttributes(extra));
        }
        return out;
    }

    private List<Map<String, Object>> schedules(ParseContext ctx, String reportUri, List<String> warnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<JsonNode> summaries;
        try { summaries = ctx.repo().jobs(reportUri); }
        catch (IOException e) { warnings.add("jobs of " + reportUri + " unavailable: " + e.getMessage()); return out; }
        for (JsonNode s : summaries) {
            long id = s.path("id").asLong();
            Map<String, Object> m = Attrs.of("id", id, "label", Descriptors.text(s, "label"), "owner", Descriptors.text(s, "owner"),
                    "nextFireTime", Descriptors.text(s.path("state"), "nextFireTime"), "previousFireTime", Descriptors.text(s.path("state"), "previousFireTime"),
                    "state", Descriptors.text(s.path("state"), "value"));
            try {
                JsonNode full = ctx.repo().job(id);
                List<String> formats = new ArrayList<>(); full.path("outputFormats").path("outputFormat").forEach(f -> formats.add(f.asText()));
                List<String> mail = new ArrayList<>(); full.path("mailNotification").path("toAddresses").path("address").forEach(x -> mail.add(x.asText()));
                m.put("outputFormats", formats); m.put("mailTo", mail);
                m.put("repositoryFolder", Descriptors.text(full.path("repositoryDestination"), "folderURI"));
            } catch (IOException e) { warnings.add("job " + id + " of " + reportUri + " detail unavailable: " + e.getMessage()); }
            out.add(m);
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=OperationalMetadataEnricherTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(enrich): schedules with recipients/output formats and effective permissions"
```

---

### Task 15: Stitch map and stitcher (BI datasource → physical asset)

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/lineage/StitchMap.java`, `Stitcher.java`, `config/stitch-map.example.json`
- Test: `src/test/java/com/hcl/jrsmeta/lineage/StitcherTest.java`

**Interfaces:**
- Consumes: `Asset`, `LineageEdge`, `Fingerprint` (Task 2).
- Produces:
  - `StitchMap.load(Path)` (missing file → empty map); `Optional<String> physicalAssetId(String fingerprintKey)` (case-insensitive key match).
  - `record StitchResult(List<Asset> shadowAssets, List<LineageEdge> edges, int matched, int shadowed)`.
  - `Stitcher(StitchMap map)`; `StitchResult stitch(String serverId, List<Asset> assets)` — for each `BI_DATASOURCE` with a `fingerprint` attribute: matched → `STITCH` edge physicalAssetId → datasource (`EXACT`); unmatched → shadow asset `shadow|<key>` of type `PHYSICAL_DATASOURCE_SHADOW` (deduplicated) + `STITCH` edge (`INFERRED`, attribute `needsMapping=true`).

Stitch map file format:
```json
{ "stitches": [ { "fingerprint": "postgresql://db.example.com:5432/salesdb", "physicalAssetId": "catalog:datasource:1234", "note": "prod warehouse" } ] }
```

- [ ] **Step 1: Write the failing test**

```java
package com.hcl.jrsmeta.lineage;

import com.hcl.jrsmeta.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class StitcherTest {
    @TempDir Path tmp;

    Asset ds(String uri, String fp) {
        return new Asset("srv|" + uri, "srv", uri, AssetType.BI_DATASOURCE, uri, null, "/ds", null, null, null, 1, fp == null ? Map.of() : Map.of("fingerprint", fp));
    }

    @Test void stitch_matchedUnmatchedAndVirtual() throws Exception {
        Path f = tmp.resolve("stitch.json");
        Files.writeString(f, "{\"stitches\":[{\"fingerprint\":\"POSTGRESQL://db.example.com:5432/salesdb\",\"physicalAssetId\":\"cat:ds:1\"}]}");
        Stitcher s = new Stitcher(StitchMap.load(f));
        List<Asset> in = List.of(ds("/ds/pg", "postgresql://db.example.com:5432/salesdb"), ds("/ds/ora", "oracle://ora:1521/orcl"), ds("/ds/ora2", "oracle://ora:1521/orcl"), ds("/ds/virtual", null),
                new Asset("srv|/reports/x", "srv", "/reports/x", AssetType.BI_REPORT, "x", null, "/", null, null, null, 1, Map.of("fingerprint", "ignored")));
        Stitcher.StitchResult r = s.stitch("srv", in);

        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.shadowed()).isEqualTo(2);
        assertThat(r.shadowAssets()).hasSize(1);                                   // same fingerprint -> one shadow node
        Asset shadow = r.shadowAssets().get(0);
        assertThat(shadow.id()).isEqualTo("shadow|oracle://ora:1521/orcl");
        assertThat(shadow.type()).isEqualTo(AssetType.PHYSICAL_DATASOURCE_SHADOW);
        assertThat(r.edges()).containsExactlyInAnyOrder(
                new LineageEdge("cat:ds:1", "srv|/ds/pg", EdgeType.STITCH, Confidence.EXACT, Map.of("fingerprint", "postgresql://db.example.com:5432/salesdb")),
                new LineageEdge("shadow|oracle://ora:1521/orcl", "srv|/ds/ora", EdgeType.STITCH, Confidence.INFERRED, Map.of("fingerprint", "oracle://ora:1521/orcl", "needsMapping", true)),
                new LineageEdge("shadow|oracle://ora:1521/orcl", "srv|/ds/ora2", EdgeType.STITCH, Confidence.INFERRED, Map.of("fingerprint", "oracle://ora:1521/orcl", "needsMapping", true)));
    }

    @Test void stitchMap_missingFileIsEmpty() throws Exception {
        StitchMap m = StitchMap.load(tmp.resolve("nope.json"));
        assertThat(m.physicalAssetId("x")).isEmpty();
        assertThat(m.size()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=StitcherTest`
Expected: compilation error.

- [ ] **Step 3: Write the stitch classes**

`StitchMap.java`:
```java
package com.hcl.jrsmeta.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Admin-maintained fingerprint -> physical catalog asset id mapping (spec §7.4 "admin mapping"). */
public final class StitchMap {
    private final Map<String, String> byKey;

    private StitchMap(Map<String, String> byKey) { this.byKey = byKey; }

    public static StitchMap load(Path file) throws IOException {
        Map<String, String> m = new HashMap<>();
        if (file != null && Files.exists(file)) {
            JsonNode root = new ObjectMapper().readTree(Files.readString(file));
            for (JsonNode s : root.path("stitches")) {
                String fp = s.path("fingerprint").asText(null); String id = s.path("physicalAssetId").asText(null);
                if (fp != null && id != null) m.put(fp.toLowerCase(Locale.ROOT), id);
            }
        }
        return new StitchMap(m);
    }

    public static StitchMap empty() { return new StitchMap(Map.of()); }

    public Optional<String> physicalAssetId(String fingerprintKey) {
        return fingerprintKey == null ? Optional.empty() : Optional.ofNullable(byKey.get(fingerprintKey.toLowerCase(Locale.ROOT)));
    }

    public int size() { return byKey.size(); }
}
```

`Stitcher.java`:
```java
package com.hcl.jrsmeta.lineage;

import com.hcl.jrsmeta.model.*;
import java.util.*;

public final class Stitcher {
    public record StitchResult(List<Asset> shadowAssets, List<LineageEdge> edges, int matched, int shadowed) {}

    private final StitchMap map;

    public Stitcher(StitchMap map) { this.map = map; }

    public StitchResult stitch(String serverId, List<Asset> assets) {
        Map<String, Asset> shadows = new LinkedHashMap<>();
        List<LineageEdge> edges = new ArrayList<>();
        int matched = 0, shadowed = 0;
        for (Asset a : assets) {
            if (a.type() != AssetType.BI_DATASOURCE) continue;
            Object key = a.attributes().get("fingerprint");
            if (!(key instanceof String fp) || fp.isBlank()) continue;      // virtual datasources stitch via their sub-datasources
            Optional<String> physical = map.physicalAssetId(fp);
            if (physical.isPresent()) {
                matched++;
                edges.add(new LineageEdge(physical.get(), a.id(), EdgeType.STITCH, Confidence.EXACT, Map.of("fingerprint", fp)));
            } else {
                shadowed++;
                String shadowId = "shadow|" + fp;
                shadows.computeIfAbsent(shadowId, id -> new Asset(id, serverId, null, AssetType.PHYSICAL_DATASOURCE_SHADOW, fp,
                        "Unmapped external datasource. Add its fingerprint to the stitch map to connect BI lineage to the physical asset.",
                        null, null, null, null, null, Attrs.of("fingerprint", fp, "fingerprintParts", a.attributes().get("fingerprintParts"))));
                edges.add(new LineageEdge(shadowId, a.id(), EdgeType.STITCH, Confidence.INFERRED, Map.of("fingerprint", fp, "needsMapping", true)));
            }
        }
        return new StitchResult(new ArrayList<>(shadows.values()), edges, matched, shadowed);
    }
}
```

`config/stitch-map.example.json`:
```json
{
  "stitches": [
    { "fingerprint": "postgresql://db.example.com:5432/salesdb", "physicalAssetId": "catalog:datasource:1234", "note": "prod warehouse" },
    { "fingerprint": "jndi://jdbc/foodmart", "physicalAssetId": "catalog:datasource:77", "note": "JNDI name resolved by app-server config" }
  ]
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=StitcherTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src config && git commit -m "feat(lineage): stitch map and stitcher with shadow datasource nodes"
```

---

### Task 16: Lineage builder (refs → confidence-scored edges)

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/lineage/LineageBuilder.java`
- Test: `src/test/java/com/hcl/jrsmeta/lineage/LineageBuilderTest.java`

**Interfaces:**
- Consumes: `Ref`, `LineageEdge` (Task 2).
- Produces: `record LineageResult(List<LineageEdge> edges, List<String> warnings)`; `LineageBuilder.build(String serverId, List<Ref> refs, Set<String> knownUris)`.

Rules (spec §7.1, all descriptor-derived so `EXACT`): every ref's `toUri` is the producer and `fromUri` the consumer, so the edge is `idOf(toUri) → idOf(fromUri)`. `INPUT_CONTROL` refs become `PARAMETER` edges; everything else `DATA_FLOW`. Edge attributes carry `refKind` plus the ref's own attributes; a target not in `knownUris` (outside included folders, deleted, or no permission) gets `unresolved=true` and a warning. Duplicates (same source, target, type) collapse.

- [ ] **Step 1: Write the failing test**

```java
package com.hcl.jrsmeta.lineage;

import com.hcl.jrsmeta.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class LineageBuilderTest {
    @Test void build_directionTypesAndUnresolved() {
        List<Ref> refs = List.of(
                new Ref("/reports/r", "/ds/pg", Ref.RefKind.DATASOURCE),
                new Ref("/reports/r", "/reports/sub", Ref.RefKind.SUBREPORT),
                new Ref("/reports/r", "/reports/r_files/Region", Ref.RefKind.INPUT_CONTROL),
                new Ref("/dash/d", "/reports/r", Ref.RefKind.DASHLET, Map.of("tileId", "T1")),
                new Ref("/dash/d", "/reports/gone", Ref.RefKind.DASHLET, Map.of("tileId", "T2")),
                new Ref("/ds/virtual", "/ds/pg", Ref.RefKind.SUB_DATASOURCE),
                new Ref("/reports/r", "/ds/pg", Ref.RefKind.DATASOURCE));                   // duplicate
        Set<String> known = Set.of("/reports/r", "/ds/pg", "/reports/sub", "/reports/r_files/Region", "/dash/d", "/ds/virtual");
        LineageBuilder.LineageResult r = new LineageBuilder().build("srv", refs, known);

        assertThat(r.edges()).hasSize(6);
        assertThat(r.edges()).contains(
                new LineageEdge("srv|/ds/pg", "srv|/reports/r", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("refKind", "DATASOURCE")),
                new LineageEdge("srv|/reports/sub", "srv|/reports/r", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("refKind", "SUBREPORT")),
                new LineageEdge("srv|/reports/r_files/Region", "srv|/reports/r", EdgeType.PARAMETER, Confidence.EXACT, Map.of("refKind", "INPUT_CONTROL")),
                new LineageEdge("srv|/reports/r", "srv|/dash/d", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("tileId", "T1", "refKind", "DASHLET")),
                new LineageEdge("srv|/ds/pg", "srv|/ds/virtual", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("refKind", "SUB_DATASOURCE")));
        LineageEdge gone = r.edges().stream().filter(e -> e.sourceId().equals("srv|/reports/gone")).findFirst().orElseThrow();
        assertThat(gone.attributes()).containsEntry("unresolved", true).containsEntry("tileId", "T2");
        assertThat(gone.confidence()).isEqualTo(Confidence.INFERRED);
        assertThat(r.warnings()).hasSize(1).first().asString().contains("/reports/gone");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=LineageBuilderTest`
Expected: compilation error.

- [ ] **Step 3: Write the builder**

```java
package com.hcl.jrsmeta.lineage;

import com.hcl.jrsmeta.model.*;
import java.util.*;

/** Spec §7.1: descriptor references -> asset-level lineage edges. Data flows producer(toUri) -> consumer(fromUri). */
public final class LineageBuilder {
    public record LineageResult(List<LineageEdge> edges, List<String> warnings) {}

    public LineageResult build(String serverId, List<Ref> refs, Set<String> knownUris) {
        Map<String, LineageEdge> edges = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Ref ref : refs) {
            if (ref.toUri() == null || ref.fromUri() == null) continue;
            String source = Asset.idOf(serverId, ref.toUri());
            String target = Asset.idOf(serverId, ref.fromUri());
            EdgeType type = ref.kind() == Ref.RefKind.INPUT_CONTROL ? EdgeType.PARAMETER : EdgeType.DATA_FLOW;
            Map<String, Object> attrs = new LinkedHashMap<>(ref.attributes());
            attrs.put("refKind", ref.kind().name());
            Confidence conf = Confidence.EXACT;
            if (!knownUris.contains(ref.toUri())) {
                attrs.put("unresolved", true);
                conf = Confidence.INFERRED;
                warnings.add(ref.fromUri() + " references " + ref.toUri() + " (" + ref.kind() + ") which is not in the crawled inventory");
            }
            String key = source + "->" + target + ":" + type;
            edges.putIfAbsent(key, new LineageEdge(source, target, type, conf, attrs));
        }
        return new LineageResult(new ArrayList<>(edges.values()), warnings);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=LineageBuilderTest`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(lineage): build asset-level edges from parser refs with unresolved flagging"
```

---

### Task 17: JSON file emitter

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/emit/Emitter.java`, `EmitBatch.java`, `JsonFileEmitter.java`
- Test: `src/test/java/com/hcl/jrsmeta/emit/JsonFileEmitterTest.java`

**Interfaces:**
- Consumes: `Asset`, `LineageEdge` (Task 2).
- Produces:
  - `record EmitBatch(String serverId, String runId, String startedAt, String mode, List<Asset> assets, List<LineageEdge> edges, List<String> deletedAssetIds, List<String> warnings, Map<String,Object> stats)`
  - `interface Emitter { void emit(EmitBatch batch) throws IOException; }`
  - `JsonFileEmitter(Path outputDir)` writes `<outputDir>/<runId>/assets.json`, `lineage.json`, `run-report.json` and refreshes `<outputDir>/latest.txt` with the run id.

Output contract (what a catalog ingester reads): `assets.json` = `{"serverId","runId","mode","assets":[Asset...]}`; `lineage.json` = `{"serverId","runId","mode","edges":[LineageEdge...]}`; `run-report.json` = `{"serverId","runId","startedAt","finishedAt","mode","deletedAssetIds":[...],"stats":{...},"warnings":[...]}`. In `incremental` mode the files contain only changed assets and the edges derived from them; the ingester merges by `id`, removes `deletedAssetIds`, and replaces outgoing edges of re-emitted assets.

- [ ] **Step 1: Write the failing test**

```java
package com.hcl.jrsmeta.emit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcl.jrsmeta.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class JsonFileEmitterTest {
    @TempDir Path tmp;

    @Test void emit_writesThreeFilesAndLatestPointer() throws Exception {
        Asset a = new Asset("srv|/r", "srv", "/r", AssetType.BI_REPORT, "R", null, "/", null, "2024-01-01T00:00:00", null, 1,
                Map.of("fingerprintParts", new Fingerprint("postgresql", "h", 5432, "db", null, "jdbc:postgresql://h/db")));
        LineageEdge e = new LineageEdge("srv|/ds", "srv|/r", EdgeType.DATA_FLOW, Confidence.EXACT, Map.of("refKind", "DATASOURCE"));
        EmitBatch b = new EmitBatch("srv", "20261012T120000Z", "2026-10-12T12:00:00Z", "full", List.of(a), List.of(e), List.of("srv|/old"), List.of("w1"), Map.of("assets", 1));
        new JsonFileEmitter(tmp).emit(b);

        Path dir = tmp.resolve("20261012T120000Z");
        ObjectMapper m = new ObjectMapper();
        JsonNode assets = m.readTree(Files.readString(dir.resolve("assets.json")));
        assertThat(assets.get("assets")).hasSize(1);
        assertThat(assets.get("assets").get(0).get("type").asText()).isEqualTo("BI_REPORT");
        assertThat(assets.get("assets").get(0).get("attributes").get("fingerprintParts").get("host").asText()).isEqualTo("h");
        JsonNode lineage = m.readTree(Files.readString(dir.resolve("lineage.json")));
        assertThat(lineage.get("edges").get(0).get("confidence").asText()).isEqualTo("EXACT");
        JsonNode report = m.readTree(Files.readString(dir.resolve("run-report.json")));
        assertThat(report.get("deletedAssetIds").get(0).asText()).isEqualTo("srv|/old");
        assertThat(report.get("warnings").get(0).asText()).isEqualTo("w1");
        assertThat(report.get("stats").get("assets").asInt()).isEqualTo(1);
        assertThat(report.hasNonNull("finishedAt")).isTrue();
        assertThat(Files.readString(tmp.resolve("latest.txt")).trim()).isEqualTo("20261012T120000Z");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=JsonFileEmitterTest`
Expected: compilation error.

- [ ] **Step 3: Write the emitter**

`Emitter.java`:
```java
package com.hcl.jrsmeta.emit;

import java.io.IOException;

/** Spec §8.1 "Emitter": maps to the catalog's ingestion format. Phase 1 ships the JSON file implementation. */
public interface Emitter {
    void emit(EmitBatch batch) throws IOException;
}
```

`EmitBatch.java`:
```java
package com.hcl.jrsmeta.emit;

import com.hcl.jrsmeta.model.Asset;
import com.hcl.jrsmeta.model.LineageEdge;
import java.util.List;
import java.util.Map;

public record EmitBatch(String serverId, String runId, String startedAt, String mode, List<Asset> assets, List<LineageEdge> edges,
                        List<String> deletedAssetIds, List<String> warnings, Map<String, Object> stats) {}
```

`JsonFileEmitter.java`:
```java
package com.hcl.jrsmeta.emit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonFileEmitter implements Emitter {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path outputDir;

    public JsonFileEmitter(Path outputDir) { this.outputDir = outputDir; }

    @Override public void emit(EmitBatch b) throws IOException {
        Path dir = outputDir.resolve(b.runId());
        Files.createDirectories(dir);
        Map<String, Object> assets = header(b); assets.put("assets", b.assets());
        Map<String, Object> lineage = header(b); lineage.put("edges", b.edges());
        Map<String, Object> report = header(b);
        report.put("startedAt", b.startedAt()); report.put("finishedAt", Instant.now().toString());
        report.put("deletedAssetIds", b.deletedAssetIds()); report.put("stats", b.stats()); report.put("warnings", b.warnings());
        MAPPER.writeValue(dir.resolve("assets.json").toFile(), assets);
        MAPPER.writeValue(dir.resolve("lineage.json").toFile(), lineage);
        MAPPER.writeValue(dir.resolve("run-report.json").toFile(), report);
        Files.writeString(outputDir.resolve("latest.txt"), b.runId() + System.lineSeparator());
    }

    private static Map<String, Object> header(EmitBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverId", b.serverId()); m.put("runId", b.runId()); m.put("mode", b.mode());
        return m;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=JsonFileEmitterTest`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add src && git commit -m "feat(emit): JSON file emitter writing assets, lineage and run report per run"
```

---

### Task 18: Orchestrator and CLI

**Files:**
- Create: `src/main/java/com/hcl/jrsmeta/run/RunReport.java`, `ConnectorRun.java`, `src/main/java/com/hcl/jrsmeta/cli/Main.java`
- Test: `src/test/java/com/hcl/jrsmeta/run/ConnectorRunTest.java`, `src/test/java/com/hcl/jrsmeta/cli/MainTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `record RunReport(String runId, String mode, String edition, int inventory, int processed, int failed, int unchanged, int deleted, int assets, int edges, int stitched, int shadowed, List<String> warnings)`
  - `ConnectorRun(ConnectorConfig cfg, JrsRepository repo, ParserRegistry parsers, Emitter emitter, SyncState state, StitchMap stitchMap)`; `RunReport execute(boolean full, boolean dryRun)`.
  - `Main` picocli commands: `crawl --config <file> [--full] [--dry-run]`, `probe --config <file>`. Exit codes: 0 success, 1 fatal error, 2 completed with per-resource failures.

Run algorithm (spec §8.2): `serverInfo` probe → list every include folder recursively → `FolderFilter` → `Crawler.plan` → (dry-run stops here) → `state.beginRun(); state.save()` → parse each lookup via registry, catching per-resource exceptions, `state.markProcessed` on success and `state.save()` every `checkpointEvery` → remove deleted URIs from state → enrich (if configured) → `LineageBuilder` over all refs with `knownUris` = full inventory → `Stitcher` → `emitter.emit` → `state.completeRun(); state.save()`. The CE edition prints a notice that Domains/dashboards/ad hoc views will not appear.

- [ ] **Step 1: Write the failing tests**

`ConnectorRunTest.java`:
```java
package com.hcl.jrsmeta.run;

import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.crawl.SyncState;
import com.hcl.jrsmeta.emit.EmitBatch;
import com.hcl.jrsmeta.emit.Emitter;
import com.hcl.jrsmeta.lineage.StitchMap;
import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.parse.ParserRegistry;
import com.hcl.jrsmeta.testutil.FakeRepository;
import com.hcl.jrsmeta.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ConnectorRunTest {
    @TempDir Path tmp;

    static final class CapturingEmitter implements Emitter {
        final List<EmitBatch> batches = new ArrayList<>();
        public void emit(EmitBatch b) { batches.add(b); }
    }

    private FakeRepository repo() {
        FakeRepository r = new FakeRepository();
        r.listing.add(new ResourceLookup("/reports", "Reports", null, "folder", null, "2024-01-01T00:00:00", 1));
        r.listing.add(new ResourceLookup("/reports/rev", "Revenue", null, "reportUnit", null, "2024-01-01T00:00:00", 1));
        r.listing.add(new ResourceLookup("/ds/pg", "PG", null, "jdbcDataSource", null, "2024-01-01T00:00:00", 1));
        r.listing.add(new ResourceLookup("/reports/broken", "Broken", null, "reportUnit", null, "2024-01-01T00:00:00", 1));   // no descriptor -> fails
        r.listing.add(new ResourceLookup("/temp/x", "Temp", null, "reportUnit", null, "2024-01-01T00:00:00", 1));           // excluded
        r.descriptor("/reports/rev", "{\"uri\":\"/reports/rev\",\"dataSource\":{\"dataSourceReference\":{\"uri\":\"/ds/pg\"}},\"jrxml\":{\"jrxmlFileReference\":{\"uri\":\"/reports/rev_files/main_jrxml\"}}}")
         .file("/reports/rev_files/main_jrxml", "<jasperReport><queryString><![CDATA[SELECT 1 AS one]]></queryString><field name=\"one\" class=\"java.lang.Integer\"/></jasperReport>")
         .descriptor("/ds/pg", "{\"uri\":\"/ds/pg\",\"driverClass\":\"org.postgresql.Driver\",\"connectionUrl\":\"jdbc:postgresql://h/db\"}");
        return r;
    }

    private ConnectorConfig cfg() {
        return TestConfig.base().excludeFolders(List.of("/temp")).fetchOperationalMetadata(false).stateDir(tmp).outputDir(tmp.resolve("out")).checkpointEvery(1).build();
    }

    @Test void execute_fullRunEmitsAssetsEdgesAndStitching() throws Exception {
        CapturingEmitter em = new CapturingEmitter();
        SyncState state = SyncState.load(tmp.resolve("sync-state.json"));
        RunReport rep = new ConnectorRun(cfg(), repo(), ParserRegistry.defaults(), em, state, StitchMap.empty()).execute(true, false);

        assertThat(rep.inventory()).isEqualTo(4);          // /temp/x filtered out
        assertThat(rep.processed()).isEqualTo(3);
        assertThat(rep.failed()).isEqualTo(1);
        assertThat(rep.shadowed()).isEqualTo(1);
        assertThat(rep.warnings()).anyMatch(w -> w.contains("/reports/broken"));
        assertThat(em.batches).hasSize(1);
        EmitBatch b = em.batches.get(0);
        assertThat(b.mode()).isEqualTo("full");
        assertThat(b.assets()).extracting(Asset::type).contains(AssetType.FOLDER, AssetType.BI_REPORT, AssetType.REPORT_FIELD, AssetType.BI_DATASOURCE, AssetType.PHYSICAL_DATASOURCE_SHADOW);
        assertThat(b.edges()).anyMatch(e -> e.type() == EdgeType.DATA_FLOW && e.sourceId().equals("test|/ds/pg") && e.targetId().equals("test|/reports/rev"));
        assertThat(b.edges()).anyMatch(e -> e.type() == EdgeType.STITCH);
        assertThat(SyncState.load(tmp.resolve("sync-state.json")).lastRunOk()).isTrue();
        assertThat(SyncState.load(tmp.resolve("sync-state.json")).knownUris()).containsExactlyInAnyOrder("/reports", "/reports/rev", "/ds/pg");   // failed one not marked
    }

    @Test void execute_incrementalSkipsUnchangedAndReportsDeleted() throws Exception {
        CapturingEmitter em = new CapturingEmitter();
        SyncState state = SyncState.load(tmp.resolve("sync-state.json"));
        FakeRepository r = repo();
        ConnectorRun run = new ConnectorRun(cfg(), r, ParserRegistry.defaults(), em, state, StitchMap.empty());
        run.execute(true, false);
        r.listing.removeIf(l -> l.uri().equals("/ds/pg"));
        r.listing.replaceAll(l -> l.uri().equals("/reports/rev") ? new ResourceLookup("/reports/rev", "Revenue", null, "reportUnit", null, "2024-02-01T00:00:00", 2) : l);
        RunReport rep = run.execute(false, false);
        assertThat(rep.mode()).isEqualTo("incremental");
        assertThat(rep.processed()).isEqualTo(1);
        assertThat(rep.unchanged()).isEqualTo(1);
        assertThat(rep.deleted()).isEqualTo(1);
        assertThat(em.batches.get(1).deletedAssetIds()).containsExactly("test|/ds/pg");
        assertThat(em.batches.get(1).assets()).extracting(Asset::uri).contains("/reports/rev");
    }

    @Test void execute_dryRunNeitherFetchesNorEmitsNorSaves() throws Exception {
        CapturingEmitter em = new CapturingEmitter();
        FakeRepository r = repo();
        RunReport rep = new ConnectorRun(cfg(), r, ParserRegistry.defaults(), em, SyncState.load(tmp.resolve("s.json")), StitchMap.empty()).execute(false, true);
        assertThat(rep.inventory()).isEqualTo(4);
        assertThat(r.requestedUris).isEmpty();
        assertThat(em.batches).isEmpty();
        assertThat(tmp.resolve("s.json")).doesNotExist();
    }
}
```

`MainTest.java`:
```java
package com.hcl.jrsmeta.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class MainTest {
    @TempDir Path tmp;

    @Test void crawl_withoutCredentialsExitsOneWithMessage() throws Exception {
        Path cfg = tmp.resolve("c.json");
        Files.writeString(cfg, "{\"serverId\":\"s\",\"baseUrl\":\"http://localhost:1\"}");
        StringWriter err = new StringWriter();
        CommandLine cl = new CommandLine(new Main(java.util.Map.of()));
        cl.setErr(new PrintWriter(err));
        int code = cl.execute("crawl", "--config", cfg.toString());
        assertThat(code).isEqualTo(1);
        assertThat(err.toString()).contains("JRS_META_USER");
    }

    @Test void help_listsCommands() {
        StringWriter out = new StringWriter();
        CommandLine cl = new CommandLine(new Main(java.util.Map.of()));
        cl.setOut(new PrintWriter(out));
        assertThat(cl.execute("--help")).isZero();
        assertThat(out.toString()).contains("crawl").contains("probe");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=ConnectorRunTest,MainTest`
Expected: compilation errors.

- [ ] **Step 3: Write the orchestrator and CLI**

`RunReport.java`:
```java
package com.hcl.jrsmeta.run;

import java.util.List;

public record RunReport(String runId, String mode, String edition, int inventory, int processed, int failed, int unchanged, int deleted,
                        int assets, int edges, int stitched, int shadowed, List<String> warnings) {
    public String summary() {
        return String.format("run %s (%s, edition %s): inventory=%d processed=%d failed=%d unchanged=%d deleted=%d assets=%d edges=%d stitched=%d shadowed=%d warnings=%d",
                runId, mode, edition, inventory, processed, failed, unchanged, deleted, assets, edges, stitched, shadowed, warnings.size());
    }
}
```

`ConnectorRun.java`:
```java
package com.hcl.jrsmeta.run;

import com.hcl.jrsmeta.client.JrsRepository;
import com.hcl.jrsmeta.client.ServerInfo;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.crawl.*;
import com.hcl.jrsmeta.emit.EmitBatch;
import com.hcl.jrsmeta.emit.Emitter;
import com.hcl.jrsmeta.enrich.OperationalMetadataEnricher;
import com.hcl.jrsmeta.lineage.*;
import com.hcl.jrsmeta.model.*;
import com.hcl.jrsmeta.parse.ParseContext;
import com.hcl.jrsmeta.parse.ParserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Spec §8: crawl -> parse -> enrich -> lineage -> stitch -> emit, resumable and read-only. */
public final class ConnectorRun {
    private static final Logger log = LoggerFactory.getLogger(ConnectorRun.class);
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ConnectorConfig cfg;
    private final JrsRepository repo;
    private final ParserRegistry parsers;
    private final Emitter emitter;
    private final SyncState state;
    private final Stitcher stitcher;

    public ConnectorRun(ConnectorConfig cfg, JrsRepository repo, ParserRegistry parsers, Emitter emitter, SyncState state, StitchMap stitchMap) {
        this.cfg = cfg; this.repo = repo; this.parsers = parsers; this.emitter = emitter; this.state = state; this.stitcher = new Stitcher(stitchMap);
    }

    public RunReport execute(boolean full, boolean dryRun) throws IOException {
        Instant started = Instant.now();
        String runId = RUN_ID.format(started);
        List<String> warnings = new ArrayList<>();

        ServerInfo info = repo.serverInfo();
        log.info("JRS {} edition={} ({})", info.version(), info.edition(), info.editionName());
        if (!info.isPro()) warnings.add("Community Edition detected: Domains, dashboards and ad hoc views are not available on this server");

        FolderFilter filter = new FolderFilter(cfg.includeFolders(), cfg.excludeFolders());
        List<ResourceLookup> inventory = new ArrayList<>();
        for (String folder : cfg.includeFolders()) inventory.addAll(repo.listResources(folder, true));
        inventory = inventory.stream().filter(l -> filter.accepts(l.uri())).toList();
        Set<String> knownUris = inventory.stream().map(ResourceLookup::uri).collect(Collectors.toSet());

        ChangeSet plan = new Crawler().plan(inventory, state, full);
        String mode = full || plan.forcedFull() ? "full" : "incremental";
        if (plan.forcedFull()) warnings.add("previous run did not complete; forcing a full crawl");
        log.info("{} crawl: {} to process, {} unchanged, {} deleted", mode, plan.toProcess().size(), plan.unchanged(), plan.deletedUris().size());
        if (dryRun) return new RunReport(runId, mode + " (dry-run)", info.edition(), inventory.size(), plan.toProcess().size(), 0, plan.unchanged(), plan.deletedUris().size(), 0, 0, 0, 0, warnings);

        state.beginRun(); state.save();
        ParseContext ctx = new ParseContext(repo, cfg.serverId(), cfg);
        List<Asset> assets = new ArrayList<>(); List<Ref> refs = new ArrayList<>();
        int processed = 0, failed = 0, sinceCheckpoint = 0;
        for (ResourceLookup l : plan.toProcess()) {
            try {
                ParseResult r = parsers.forType(l.resourceType()).parse(ctx, l);
                assets.addAll(r.assets()); refs.addAll(r.refs()); warnings.addAll(r.warnings());
                state.markProcessed(l); processed++;
            } catch (Exception e) {
                failed++;
                warnings.add("FAILED " + l.uri() + " (" + l.resourceType() + "): " + e.getMessage());
                log.warn("failed to process {} ({}): {}", l.uri(), l.resourceType(), e.toString());
            }
            if (++sinceCheckpoint >= cfg.checkpointEvery()) { state.save(); sinceCheckpoint = 0; }
        }
        for (String u : plan.deletedUris()) state.remove(u);

        if (cfg.fetchOperationalMetadata()) assets = new OperationalMetadataEnricher().enrich(ctx, assets, warnings);

        LineageBuilder.LineageResult lineage = new LineageBuilder().build(cfg.serverId(), refs, knownUris);
        warnings.addAll(lineage.warnings());
        Stitcher.StitchResult stitch = stitcher.stitch(cfg.serverId(), assets);
        List<Asset> allAssets = new ArrayList<>(assets); allAssets.addAll(stitch.shadowAssets());
        List<LineageEdge> allEdges = new ArrayList<>(lineage.edges()); allEdges.addAll(stitch.edges());
        List<String> deletedIds = plan.deletedUris().stream().map(u -> Asset.idOf(cfg.serverId(), u)).toList();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("inventory", inventory.size()); stats.put("processed", processed); stats.put("failed", failed); stats.put("unchanged", plan.unchanged());
        stats.put("deleted", deletedIds.size()); stats.put("assets", allAssets.size()); stats.put("edges", allEdges.size());
        stats.put("stitched", stitch.matched()); stats.put("shadowed", stitch.shadowed()); stats.put("serverVersion", info.version()); stats.put("edition", info.edition());
        emitter.emit(new EmitBatch(cfg.serverId(), runId, started.toString(), mode, allAssets, allEdges, deletedIds, warnings, stats));

        state.completeRun(); state.save();
        return new RunReport(runId, mode, info.edition(), inventory.size(), processed, failed, plan.unchanged(), deletedIds.size(),
                allAssets.size(), allEdges.size(), stitch.matched(), stitch.shadowed(), warnings);
    }
}
```

`Main.java`:
```java
package com.hcl.jrsmeta.cli;

import com.hcl.jrsmeta.client.*;
import com.hcl.jrsmeta.config.ConfigLoader;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.crawl.SyncState;
import com.hcl.jrsmeta.emit.JsonFileEmitter;
import com.hcl.jrsmeta.lineage.StitchMap;
import com.hcl.jrsmeta.model.ResourceLookup;
import com.hcl.jrsmeta.parse.ParserRegistry;
import com.hcl.jrsmeta.run.ConnectorRun;
import com.hcl.jrsmeta.run.RunReport;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "jrs-meta", mixinStandardHelpOptions = true, version = "jrs-meta-connector 0.1.0",
        description = "Read-only Jaspersoft metadata & lineage connector (REST v2).",
        subcommands = {Main.Crawl.class, Main.Probe.class})
public final class Main {
    final Map<String, String> env;

    public Main() { this(System.getenv()); }
    Main(Map<String, String> env) { this.env = env; }

    public static void main(String[] args) { System.exit(new CommandLine(new Main()).execute(args)); }

    ConnectorConfig load(Path config) throws Exception { return ConfigLoader.load(config, env); }

    @Command(name = "crawl", description = "Crawl the repository and emit assets + lineage.")
    static final class Crawl implements Callable<Integer> {
        @ParentCommand Main parent;
        @Spec CommandSpec spec;                       // picocli's out/err writers, so tests can capture output
        @Option(names = "--config", required = true, description = "connector JSON config") Path config;
        @Option(names = "--full", description = "ignore sync state and process everything") boolean full;
        @Option(names = "--dry-run", description = "plan only: list counts, fetch no descriptors, emit nothing") boolean dryRun;

        @Override public Integer call() throws Exception {
            PrintWriter out = spec.commandLine().getOut(), err = spec.commandLine().getErr();
            ConnectorConfig cfg;
            try { cfg = parent.load(config); }
            catch (IllegalArgumentException e) { err.println(e.getMessage()); err.flush(); return 1; }
            Path audit = cfg.stateDir().resolve("audit-" + System.currentTimeMillis() + ".jsonl");
            try (AuditLog log = new JsonlAuditLog(audit, cfg.username())) {
                JrsRepository repo = new RestJrsRepository(new JrsClient(cfg, log), cfg);
                SyncState state = SyncState.load(cfg.stateDir().resolve("sync-state.json"));
                ConnectorRun run = new ConnectorRun(cfg, repo, ParserRegistry.defaults(), new JsonFileEmitter(cfg.outputDir()), state, StitchMap.load(cfg.stitchMapFile()));
                RunReport rep = run.execute(full, dryRun);
                out.println(rep.summary());
                rep.warnings().stream().limit(50).forEach(w -> out.println("  warning: " + w));
                if (rep.warnings().size() > 50) out.println("  ... " + (rep.warnings().size() - 50) + " more warnings in run-report.json");
                out.flush();
                return rep.failed() > 0 ? 2 : 0;
            } catch (Exception e) {
                err.println("crawl failed: " + e.getMessage()); err.flush();
                return 1;
            }
        }
    }

    @Command(name = "probe", description = "Check connectivity, edition and top-level folders without crawling.")
    static final class Probe implements Callable<Integer> {
        @ParentCommand Main parent;
        @Spec CommandSpec spec;
        @Option(names = "--config", required = true) Path config;

        @Override public Integer call() throws Exception {
            PrintWriter out = spec.commandLine().getOut(), err = spec.commandLine().getErr();
            ConnectorConfig cfg;
            try { cfg = parent.load(config); }
            catch (IllegalArgumentException e) { err.println(e.getMessage()); err.flush(); return 1; }
            try {
                JrsRepository repo = new RestJrsRepository(new JrsClient(cfg, AuditLog.NOOP), cfg);
                ServerInfo info = repo.serverInfo();
                out.printf("server: %s  version: %s  edition: %s (%s)%n", cfg.baseUrl(), info.version(), info.edition(), info.editionName());
                out.println("capabilities: reports+datasources=yes, domains/dashboards/adhoc=" + (info.isPro() ? "yes" : "no (CE)"));
                List<ResourceLookup> top = repo.listResources("/", false);
                out.println("top-level resources: " + top.size());
                top.forEach(l -> out.printf("  %-24s %s%n", l.resourceType(), l.uri()));
                out.flush();
                return 0;
            } catch (Exception e) {
                err.println("probe failed: " + e.getMessage()); err.flush();
                return 1;
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=ConnectorRunTest,MainTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Build the jar and run the full suite**

Run: `mvn -q package`
Expected: `target/jrs-meta-connector.jar` exists; all tests pass. Then:
```powershell
java -jar target/jrs-meta-connector.jar --help
```
Expected: usage text listing `crawl` and `probe`.

- [ ] **Step 6: Commit**

```bash
git add src && git commit -m "feat(run,cli): orchestrated crawl with checkpoints, and crawl/probe CLI"
```

---

### Task 19: Live smoke test (env-gated) and README

**Files:**
- Create: `src/test/java/com/hcl/jrsmeta/LiveSmokeIT.java`, `README.md`

**Interfaces:**
- Consumes: `ConfigLoader`, `RestJrsRepository`, `ConnectorRun` (Tasks 3, 5, 18).
- Produces: a manual integration check against a real JRS (surefire excludes `*IT.java` by default; run explicitly) and user docs.

- [ ] **Step 1: Write the integration test**

```java
package com.hcl.jrsmeta;

import com.hcl.jrsmeta.client.*;
import com.hcl.jrsmeta.config.ConnectorConfig;
import com.hcl.jrsmeta.crawl.SyncState;
import com.hcl.jrsmeta.emit.JsonFileEmitter;
import com.hcl.jrsmeta.lineage.StitchMap;
import com.hcl.jrsmeta.model.ResourceLookup;
import com.hcl.jrsmeta.parse.ParserRegistry;
import com.hcl.jrsmeta.run.ConnectorRun;
import com.hcl.jrsmeta.run.RunReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs only when JRS_META_LIVE=1 and JRS_META_BASE_URL / JRS_META_USER / JRS_META_PASSWORD point at a reachable server.
 * Optional JRS_META_LIVE_FOLDER (default /public) limits the crawl. Read-only.
 *   mvn -q test -Dtest=LiveSmokeIT -Dsurefire.excludes=none
 */
@EnabledIfEnvironmentVariable(named = "JRS_META_LIVE", matches = "1")
class LiveSmokeIT {
    @TempDir Path tmp;

    private ConnectorConfig cfg(String folder) {
        return ConnectorConfig.builder().serverId("live").baseUrl(System.getenv("JRS_META_BASE_URL"))
                .username(System.getenv("JRS_META_USER")).password(System.getenv("JRS_META_PASSWORD").toCharArray())
                .includeFolders(List.of(folder)).rateLimitPerSecond(5).stateDir(tmp.resolve("state")).outputDir(tmp.resolve("out")).build();
    }

    @Test void serverInfoAndTopLevelListing() throws Exception {
        ConnectorConfig cfg = cfg("/");
        JrsRepository repo = new RestJrsRepository(new JrsClient(cfg, AuditLog.NOOP), cfg);
        ServerInfo info = repo.serverInfo();
        assertThat(info.version()).isNotBlank();
        List<ResourceLookup> top = repo.listResources("/", false);
        assertThat(top).isNotEmpty();
        System.out.println("live: " + info + " top-level=" + top.size());
    }

    @Test void fullCrawlOfOneFolderEmitsFiles() throws Exception {
        String folder = System.getenv().getOrDefault("JRS_META_LIVE_FOLDER", "/public");
        ConnectorConfig cfg = cfg(folder);
        JrsRepository repo = new RestJrsRepository(new JrsClient(cfg, AuditLog.NOOP), cfg);
        ConnectorRun run = new ConnectorRun(cfg, repo, ParserRegistry.defaults(), new JsonFileEmitter(cfg.outputDir()), SyncState.load(cfg.stateDir().resolve("sync-state.json")), StitchMap.empty());
        RunReport rep = run.execute(true, false);
        System.out.println(rep.summary());
        rep.warnings().forEach(w -> System.out.println("  " + w));
        assertThat(rep.processed()).isGreaterThan(0);
        assertThat(tmp.resolve("out").resolve(rep.runId()).resolve("assets.json")).exists();
        // Spec Phase 1 exit criterion: >= 95% of resources ingested.
        assertThat((double) rep.processed() / Math.max(1, rep.processed() + rep.failed())).isGreaterThanOrEqualTo(0.95);
    }
}
```

- [ ] **Step 2: Run it against a live server if one is available**

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
$env:JRS_META_LIVE="1"; $env:JRS_META_BASE_URL="http://localhost:8080/jasperserver-pro"; $env:JRS_META_USER="<READ_ONLY_USER>"; $env:JRS_META_PASSWORD="<PASSWORD>"
& "C:\apache-maven-3.9.9\bin\mvn.cmd" -q test "-Dtest=LiveSmokeIT" "-Dsurefire.excludes=none" "-Dsurefire.failIfNoSpecifiedTests=false"
```
Expected: 2 tests pass and the summary line prints. If no server is reachable, record that in the commit message and move on; the unit suite is the gate.

- [ ] **Step 3: Write `README.md`**

````markdown
# jrs-meta-connector

Read-only Jaspersoft (JasperReports Server) metadata & lineage connector. Crawls a JRS repository over REST v2,
catalogs every artifact as a typed asset, derives asset-level lineage (data source -> Domain/Topic -> ad hoc view
-> report -> dashboard), fingerprints data sources for stitching to physical databases, and emits JSON for a
catalog to ingest. Phase 1 of the design in `docs/` (column-level lineage is Phase 2).

## Build (this machine)

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
& "C:\apache-maven-3.9.9\bin\mvn.cmd" -q package      # -> target/jrs-meta-connector.jar
```
Java 17 language level; any JDK 17+ builds it.

## Configure

1. Copy `config/connector.example.json` to `config/connector.json` and set `serverId`, `baseUrl`, folders, rate limit.
2. Credentials come **only** from the environment (a read-only JRS user with repository read + export rights):
   `JRS_META_USER`, `JRS_META_PASSWORD` (optional `JRS_META_BASE_URL` override). Multi-tenant servers: `user|orgId`.
3. Optional `config/stitch-map.json` (see the example) maps datasource fingerprints to physical catalog asset ids.

## Run

```powershell
java -jar target/jrs-meta-connector.jar probe --config config/connector.json
java -jar target/jrs-meta-connector.jar crawl --config config/connector.json --dry-run
java -jar target/jrs-meta-connector.jar crawl --config config/connector.json --full
java -jar target/jrs-meta-connector.jar crawl --config config/connector.json          # incremental
```
Exit codes: 0 ok, 1 fatal, 2 finished with per-resource failures (see warnings).

## Output

`out/<runId>/assets.json`, `lineage.json`, `run-report.json`; `out/latest.txt` holds the last run id.
Asset id = `serverId|repositoryUri`; children use `parentId#kind:name`. Edges are `sourceId -> targetId`
(data flow) with a confidence (`EXACT` for descriptor-derived, `INFERRED` for unresolved targets and unmapped
stitches). Incremental runs emit only changed assets plus `deletedAssetIds`; an ingester merges by id.

## State and safety

- `state/sync-state.json`: per-URI updateDate/version; checkpointed every `checkpointEvery` resources. A run
  that dies before emitting forces the next run to be full.
- `state/audit-<ts>.jsonl`: every request (method, path, status, ms, account). Never contains credentials.
- The connector only issues `GET` plus `POST /rest_v2/export` (dashboard deep parse). Set
  `deepParseDashboards=false` to avoid exports entirely.
- `redactSqlLiterals=true` replaces literals in stored SQL with `?`.

## Known limitations (Phase 1)

- No column-level lineage yet; Domain item->column and Mondrian measure/level->column mappings are captured as
  attributes for Phase 2.
- Ad hoc views use the live descriptor's data-source reference only (export-archive state parse is Phase 2).
- Resource owner is not exposed by REST v2 descriptors; effective permissions are captured instead.
- Stitching requires the stitch map; unmapped fingerprints become `PHYSICAL_DATASOURCE_SHADOW` assets.

## Tests

`mvn -q test` runs the unit suite (WireMock for HTTP, in-memory fake for parsers).
`LiveSmokeIT` runs only with `JRS_META_LIVE=1` against a real server (read-only).
````

- [ ] **Step 4: Run the whole unit suite one last time**

Run: `mvn -q test`
Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src README.md && git commit -m "docs: README and env-gated live smoke test"
```

---

## Self-review notes (done while writing)

**Spec coverage (Phase 1 scope):**
- G1 all artifact types → Tasks 8–13 (reports, dashboards, ad hoc, Domains, Topics are `reportUnit`, data sources, OLAP, input controls, files/folders via generic).
- G2 technical/business/operational metadata → attributes in each parser; schedules, recipients, permissions in Task 14.
- G3 asset-level lineage → Task 16 from refs emitted by Tasks 8–13.
- G5 stitching → Task 9 fingerprints + Task 15 stitch map/shadow nodes.
- G6 incremental, schedulable, read-only, server-safe → Tasks 4 (rate limit, retry, cookie reuse), 6 (state, change detection, forced-full recovery), 18 (checkpoints, dry-run).
- §5.2 endpoints: `/resources` paging, typed descriptors, `_files` payloads, export flow, `jobs`, `permissions`, `serverInfo` → Task 5. `/domains/{uri}/metadata` is not used; `schema.xml` (also listed in §9.2) carries the same mapping with less server work.
- §6.3 identity → Task 2 (`idOf`, `childIdOf`). Rename/move detection by content hash is deferred: a moved resource appears as delete + add in this phase.
- §8.3 auth: Basic only. Token/pre-auth header is not implemented; add a `tokenHeader` option when a customer needs it.
- §10 CE vs Pro probe → Task 18 warning; degradation is natural because CE repositories contain no Pro types.
- §13 audit trail → Task 4; SQL redaction → Task 8.
- G4 / §7.2 column lineage, §7.3 PARSED/DERIVED confidences, §9.3 ad hoc deep state, Phase 3/4 items → **separate plans**, by design.

**Type consistency checked:** `Ref.RefKind` values used in Tasks 8–13 and consumed in Task 16 match; `Fingerprint.key()` format in Task 2 matches the stitch-map keys in Task 15 and the datasource attribute in Task 9; `ParseContext(repo, serverId, config)` is the same shape everywhere; `JrsRepository` method set in Task 5 is what `FakeRepository` (Task 8) implements.

**Review Focus pins:** items 1–7 map to tests named in the section above (Tasks 5, 4, 8, 9, 7, 5, 6 respectively).
