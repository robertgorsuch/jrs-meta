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
