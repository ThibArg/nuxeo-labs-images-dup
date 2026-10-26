# AGENTS.md

Nuxeo LTS 2025 plugin. Detects near-duplicate images on `Picture`-faceted documents using a
64-bit DCT perceptual hash stored on the document and searched with NXQL.

## Repository conventions

- The git remote is `ThibArg/nuxeo-labs-images-dup` (personal account) while the README points to
  `nuxeo-sandbox`. **This is intentional, do not "fix" it.** Once validated the plugin is forked to
  `nuxeo-sandbox` and this repo becomes an archive.
- Everything written (README, this file, code comments, Javadoc, commit messages, UI labels) is in
  **English**.
- `AGENTS.md` is deliberately **not** in `.gitignore` here, it is committed.
- Javadoc tag is `@since 2025.1.0` everywhere, not `@since 2025.XX`.
- No CI, no linter, no formatter config in this repo. Java 21, Maven 3.8.

## Modules

| Module | Content |
|---|---|
| `nuxeo-labs-images-dup-core` | All Java code, schema, OSGI-INF contributions, tests |
| `nuxeo-labs-images-dup-package` | Marketplace package assembly only (ant-assembly-maven-plugin), no code |

Bundle symbolic name used in `@Deploy`: `nuxeo.labs.images.dup.nuxeo-labs-images-dup-core`.

## Commands

```bash
mvn clean install                                        # full build + tests + marketplace zip (~15 s warm)
mvn -pl nuxeo-labs-images-dup-core test                  # tests only
mvn -pl nuxeo-labs-images-dup-core test -Dtest=TestPHash
mvn -pl nuxeo-labs-images-dup-core test -Dtest='TestPHash#testOrientations'
```

Run `mvn clean install` before committing Java/XML changes (not needed for README / AGENTS.md /
`.gitignore`-only commits).

Package artifact: `nuxeo-labs-images-dup-package/target/nuxeo-labs-images-dup-package-2025.1.0.zip`.

## Search backend: a search engine is required

`ImageHashServiceImpl.search()` issues `WHERE imgh:bands IN (...)` through `SearchService` and **always
targets the search index**. No repository-level index is involved, so there is nothing to create on
MongoDB or PostgreSQL. Do not reintroduce `indexOrder="ascending"` on `imgh:bands`; it was removed on
purpose, only `MongoDBIndexCreator` ever read it.

**Resolve the configured default index, then reject the `repository` client. Never match on engine
client names.** `findSearchIndex()` calls `getDefaultIndexName(repo)` and returns `null` only when that
index's `client()` is `repository`. Two traps this avoids, both of which the previous implementation hit:

- Engine client names are **stack specific**: `opensearch` for OpenSearch 1 *and* 2, but
  **`elasticsearch`** for Elasticsearch 9. A `startsWith("opensearch")` filter silently excludes ES9.
- During a blue/green reindex, `enhanced` and `enhanced-green` are both registered and the green one is
  declared `default="false"`. Taking the first engine index can land on a half-populated green index.

`repository` is the one stable name: it is declared by the `nuxeo-core-search` bundle itself
(`repository-search-client-factory-contrib.xml`), not by a template.

### Verified per-stack metadata

Cross-checked against the opensearch1 sources plus the private `nuxeo-opensearch2` and
`nuxeo-elasticsearch9` repos (branch `lts-2025`).

| | opensearch1 | opensearch2 | elasticsearch9 |
|---|---|---|---|
| Covers | OS 1.x, **ES 7–8** | OS 2.x | ES 9.x |
| `searchClient` name | `opensearch` | `opensearch` | **`elasticsearch`** |
| green client | `opensearch-green` | `opensearch-green` | `elasticsearch-green` |
| Runtime component | `org.nuxeo.runtime.opensearch1.OpenSearchComponent` | `org.nuxeo.runtime.opensearch2.OpenSearchComponent` | `org.nuxeo.runtime.elasticsearch9.ElasticsearchComponent` |
| Config component | `...search.client.opensearch1.contrib` | `...opensearch2.contrib` | `...elasticsearch9.contrib` |
| Property namespace | `nuxeo.search.client.default.opensearch1.*` | `...opensearch2.*` | `...elasticsearch9.*` |

- opensearch1 and opensearch2 declare `<conflicts>` on each other, which is why they can share the
  client name `opensearch`.
- **MongoDB Atlas Search (`mongoatlas`) is out of scope**, untested, different indexing model. Do not
  add a template for it without real verification.
- Neither the search client nor the runtime bundle ships in `nuxeo-nxr-server`; all three stacks are
  addon packages. `package.xml` declares **no** dependency on purpose: the format has no OR and any of
  the three satisfies the plugin.
- `maxCandidates` default (10000) sits exactly on the `max_result_window` default.

## Fulltext: two independent layers, only one is fixed here

Band keys are opaque strings (`f2eabf4f:0:cb94`). They can leak into **two unrelated** fulltext indexes.
Fixing one does nothing for the other: `ecm:fulltextSimple` is never shipped to the search engine
(`DefaultIndexingJsonWriter.writeSystemProperties` emits only `binarytext`).

| Layer | Cause | Status |
|---|---|---|
| Engine `all_field` | `copy_to` on the dynamic `strings` template | **fixed**, mapping override |
| Repository `ecm:fulltextSimple` | Nuxeo indexes every string property of every schema | **not fixed**, documented in README |

- The `strings` template carrying `copy_to: "all_field"` and the `IndexConfig.merge()` logic are
  **identical on all three stacks**, down to the line numbers.
- **The fix is ordering sensitive.** `merge()` preserves the base mapping only when the *incoming*
  descriptor has `append="true"`; in the other direction `extraMappingContents` is silently dropped.
  Every template declares `<require>` on the component holding the base mapping. Remove it and the
  override vanishes without any error.
- **Three templates ship, none is activated.** `assembly.xml` deliberately no longer emits
  `<config addtemplate=.../>`; the admin picks with `nuxeo.append.templates.imagesdup=<template>`.
  Each has a blue and a green file; the green one matters because mappings only apply at index
  creation and a blue/green reindex creates the green index fresh.
- Both the `<require>` and the `<extension>` sit **inside** the `<#if ...index.name?has_content>` guard,
  so a template activated on the wrong stack renders an inert component rather than a missing target.
- Only opensearch1 is covered by a test (`testBandKeysAreNotIndexedInFulltext`, embedded node). The
  opensearch2 and elasticsearch9 templates are built from verified source metadata but **not
  automatically tested**.
- **Repository fulltext is still polluted.** Beyond noise, writing `imgh:bands` marks the path dirty and
  schedules an extra `FulltextExtractorWork` per hash store. `store()` disables versioning, DC, audit and
  notifications but cannot disable this. The `<excludeField>` cure is deployment-specific (different
  nesting for DBS vs VCS, `<index>` name must match the shipped template) so it is documented in the
  README rather than shipped.

## Testing

- **Still no external service, but no longer in-memory either.** `TestImageHashService` uses
  `OpenSearchCoreSearchFeature`, and `OpenSearchFeature` starts an **embedded in-JVM OpenSearch node**
  when `nuxeo.test.opensearch1.servers` is unset. Plain `mvn test` works offline, no Docker. It costs
  ~10 s of node startup, the class went from ~7 s to ~18 s. The repository stays H2 in-memory VCS.
  Point the suite at a real cluster with `-Dnuxeo.test.opensearch1.servers=http://localhost:9200`.
- Required test-jars, both version-managed by the Nuxeo parent:
  `org.nuxeo.ecm.core:nuxeo-search-client-opensearch1` and
  `org.nuxeo.runtime:nuxeo-runtime-opensearch1-embed`.
- The technical index is `nxutest` in tests, hence the separate
  `test-opensearch-mapping-contrib.xml`, which must be `@Deploy`ed explicitly.
- `testBandKeysAreNotIndexedInFulltext` is the regression test for the mapping override. It **did fail**
  before the fix, so if it ever passes trivially, check that the mapping is still being applied.
- To run against MongoDB instead of H2 (nothing in the plugin depends on it any more, the band query
  never reaches the repository): `docker run -d --rm -p 27017:27017 mongo:8` then
  `mvn -pl nuxeo-labs-images-dup-core test -Dnuxeo.test.core=mongodb`.
- `TestImageHashService` deploys only `picture-schemas-contrib.xml` + `picture-adapter-contrib.xml`,
  never the views-generation components, so **ImageMagick is not required**. Picture views are faked by
  writing `picture:views` directly (`createPicture`) and `pictureViewsGenerationDone` is fired by hand
  (`viewsGenerated`). Keep it that way.
- `DuplicatedImageCaptureListener` is a **static** event collector. Call `clear()` in `@Before` or tests
  leak into each other.
- `txFeature.nextTransaction()` is mandatory after firing events: it both drains the async post-commit
  listener and runs the search-indexing waiter installed by `BaseCoreSearchFeature`. Without it, freshly
  stored hashes are not searchable.
- Test images are generated, not fixtures. `TestImages.sample(seed, w, h)` is deterministic per seed.

## Architecture gotchas

- **Signature gating.** `signature = ALGORITHM + "/" + views.getFirst()`. Changing the algorithm or the
  *first* entry of the configured `views` invalidates every stored hash: they are silently ignored by
  search until the `computeImageHash` bulk action is re-run. `bandPrefix` is the CRC32 of the signature
  and prefixes every band key, which is what keeps signatures from matching each other at query time.
- **Band search, not brute force.** The 64-bit hash is split into 4 x 16-bit bands (`HashBands`).
  Candidates come from `imgh:bands IN (...)`; `queryKeys` expands to all 1- or 2-bit variants per band.
  Radius `r` guarantees finding everything within Hamming distance `4r + 3`. Key count explodes:
  radius 0 -> 4 keys, 1 -> 68, 2 -> 548.
- **Degenerate images** (smaller side < 32 px, or reduced-luminance stddev < 2.0) get a hash stored but
  an **empty** `imgh:bands`, so they are never candidates. Otherwise blank images and icons match
  everything.
- **Permissions come from the search layer.** `SearchQuery.builder(nxql, principal)` applies ACL
  filtering. Never swap it for a system-session `session.query(...)` plus post-filtering.
- **The hash is technical metadata.** `ImageHashServiceImpl.store()` disables versioning, Dublin Core,
  audit, notifications and picture-views regeneration via `putContextData` before `saveDocument`. Keep
  that block intact: removing `DISABLE_PICTURE_VIEWS_GENERATION_LISTENER` creates an infinite
  regeneration loop, removing the others bumps `dc:modified` and creates versions.
- `ImageHashListener` writes with `CoreInstance.getCoreSessionSystem(...)` so the hash is stored even
  when the triggering user cannot write the document, but the duplicate **search** uses the triggering
  principal, so the fired event only lists duplicates that user can read.
- `maxCandidates` (default 10000) is a page limit, not a total. Exceeding it only logs a warning and
  silently misses duplicates. It must stay <= the search index `max_result_window`.
- `SearchQuery` default limit is 10 — always set `.limit(...)` explicitly.
- `PICTURE_VIEWS_GENERATION_DONE_EVENT` is imported from the **deprecated** `PictureViewsGenerationWork`.
  The same `"pictureViewsGenerationDone"` constant exists on the non-deprecated `RecomputeViewsAction`.

## Status

Work in progress, not production-ready. The README carries the caution banner on purpose.
