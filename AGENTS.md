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

## Fulltext: two independent layers, both fixed here

Band keys are opaque strings (`f2eabf4f:0:cb94`). They can leak into **two unrelated** fulltext indexes.
Fixing one does nothing for the other: `ecm:fulltextSimple` is never shipped to the search engine
(`DefaultIndexingJsonWriter.writeSystemProperties` emits only `binarytext`).

| Layer | Cause | Status |
|---|---|---|
| Engine `all_field` | `copy_to` on the dynamic `strings` template | **fixed**, mapping override |
| Repository `ecm:fulltextSimple` | Nuxeo indexes every string property of every schema | **fixed**, `<excludeField>` |

Both fixes ship in the **same** auto-activated template, `install/templates/nuxeo-labs-images-dup/`, one
`.nxftl` each under `nxserver/config/`. `assembly.xml` activates it with `<config addtemplate=.../>`, so
the admin configures nothing. Output file names must keep the `-config.xml` suffix,
`OSGiRuntimeService.loadConfig` deploys nothing else, and must never be `default-repository-config.xml`,
which would overwrite the database template's own output.

### Engine mapping (`imagehash-search-mapping-config.xml.nxftl`)

- The `strings` template carrying `copy_to: "all_field"` and the `IndexConfig.merge()` logic are
  **identical on all three stacks**, down to the line numbers.
- **The fix is ordering sensitive.** `merge()` preserves the base mapping only when the *incoming*
  descriptor has `append="true"`; in the other direction `extraMappingContents` is silently dropped.
  Every block declares `<require>` on the component holding the base mapping. Remove it and the
  override vanishes without any error.
- **One template, auto-activated, covering every stack.**
  `install/templates/nuxeo-labs-images-dup/` ships a single `.nxftl` with six `<#if>` blocks: blue and
  green for opensearch1, opensearch2 and elasticsearch9. `assembly.xml` activates it with
  `<config addtemplate=.../>`, so the admin configures nothing.
- **Why guarding on an absent property is safe.** `TextTemplate` builds the FreeMarker model as a nested
  map by splitting dotted keys (`TextTemplate.java:382-416`), so a property from a template that was
  never activated is simply missing. The parenthesised form `(a.b.c)?has_content` covers a missing
  *intermediate* step; Nuxeo relies on the same mechanism across packages in
  `opensearch1-search-client-config.xml.nxftl:7`, which guards on `(nuxeo.opensearch1.embed.enabled)!`,
  a property defined only by the separate opensearch1-embed package. `nuxeo.search.client.default` is
  always a Map because `common-base/nuxeo.defaults:150-151` defines `.name` and `.scroller.index.name`.
- **Rendering was verified empirically**, not inferred: the template was run through `TextTemplate` with
  the property sets of seven deployments (no engine, each stack alone, green empty, blue+green,
  OS1 -> ES9 migration) and emitted exactly the expected blocks. Re-run that check if the guards change.
- Because the `<require>` sits inside its `<#if>`, only installed stacks emit one, so the component
  always resolves. **It is a single component though**, so a `<require>` that cannot resolve would take
  all the blocks down with it; that needs an index-name property set without the matching package
  installed, and shows up as `Pending` in the startup banner.
- Mappings are applied **only at index creation**. Existing deployments need a drop + reindex.
- Only opensearch1 is covered by a test (`testBandKeysAreNotIndexedInFulltext`, embedded node). The
  opensearch2 and elasticsearch9 blocks are built from verified source metadata but **not automatically
  tested**: the two clients are private SNAPSHOT artifacts, their OpenSearch jars conflict with
  opensearch1 on the classpath, and elasticsearch9 ships no embedded server.
- **Do not re-propose testing them without a reason to change that trade-off.** The design was worked
  out and rejected on purpose: property-activated Maven profiles (`-Dsearch=opensearch2`) swapping the
  test-jars and the test mapping contribution, with `nuxeo.test.search.feature` carrying the feature
  FQCN as a *string* so a single test class serves every stack with no compile-time dependency
  (`DynamicFeaturesLoader.loadFeature(String)`, `@since 2025.4`). It works, but it makes
  `mvn clean install` depend on `packages.nuxeo.com/maven-private` and, for elasticsearch9, on a Docker
  container — unacceptable for a repository meant to be public. A `RunnerFeature` cannot soften this:
  Maven resolves dependencies before any test code runs. The README states the support level instead.
- **Repository fulltext is a separate problem**, fixed by the other `.nxftl`, see below. Beyond noise,
  writing `imgh:bands` marks the path dirty and schedules an extra `FulltextExtractorWork` per hash
  store; `store()` disables versioning, DC, audit and notifications but cannot disable that.

### Repository fulltext (`imagehash-fulltext-exclude-config.xml.nxftl`)

- **Branches on `nuxeo.db.type`**, the only backend discriminator visible to FreeMarker. Beware the
  values: H2 is **`default`**, never `h2`, and MariaDB reports **`mysql`**. `nuxeo.dbtemplate`,
  `nuxeo.core.config` and `nuxeo.template.basedir` do **not** exist; `nuxeo.templates` lists only what
  `nuxeo.conf` names, not the `nuxeo.template.includes` chain. Ordering is a non-issue:
  `ConfigurationGenerator.loadConfiguration()` loads every template's `nuxeo.defaults` before
  `parseAndCopy()` renders any `.nxftl`.
- **No `<require>` here, unlike the mapping template, and this asymmetry is deliberate.**
  `FulltextDescriptor.merge()` unions `excludeFields` (`FulltextDescriptor.java:78-87`) and
  `DescriptorRegistry.merge()` folds scalars with `getIfNull`, so the contribution is order independent
  and cannot clobber the database template. A `<require>` would buy nothing and leave the component
  `Pending` on a stack whose repository component is named differently.
- **Contribute nothing but `<excludeField>`.** Restating `disabled`, `storedInBlob`, `analyzer`,
  `<pool>`, `<cache>` and so on turns last-wins against the database template.
- **The `<index>` key differs per backend and names are compared before `null` is defaulted to
  `"default"`** (`FulltextConfigurationFactory.java:88` runs after the merge). DBS must stay **unnamed**
  (`templates/mongodb/.../default-repository-config.xml.nxftl:16`), VCS must be
  **`name="default"`** (`templates/common-sql/...:26`, same in every branch). A mismatch silently
  appends a second index, which on VCS is a DDL change (`Model.java:1392-1413`).
- **Both `excludeField` forms are required, for two different consumers.** `imgh:bands` stops the
  extraction, because `StringsExtractor.findStrings` tests an `ArrayProperty` at its bare path
  (`StringsExtractor.java:95-105`); `imgh:bands/*` stops the job scheduling, because `DirtyPathsFinder`
  appends `/*` for arrays and the result is looked up in `indexesByPropPathExcludedSimple`
  (`DBSTransactionState.java:1440-1469`). **That second check is DBS only**, VCS gets extraction
  filtering alone. Both paths resolve through `SchemaManagerImpl.getField` (`/*` →
  `resolveSubField(listType, null, true)`), so neither logs "Ignoring unknown property".
- **`db2` is excluded on purpose**: it sets `nuxeo.db.type=db2` but its `nuxeo.template.includes` is
  `common`, not `common-sql`, so it has no repository contribution to merge into.
- **`nuxeo.labs.images.dup.fulltext.repository` exists because the descriptor id *is* the repository
  name.** On a deployment whose repository is renamed, a hardcoded `name="default"` registers a second,
  bare descriptor that the service tries to instantiate with no datasource. Same reason the exclusion
  cannot ship as a plain bundle contribution: `nuxeo-core-storage-mongodb` is in `nuxeo-nxr-server` even
  on a VCS deployment, so a bundle carrying both forms would create a phantom MongoDB repository
  everywhere. The template branch is mandatory, not a convenience.
- **Rendering was verified empirically** over ten property sets (no DB template, mongodb, postgresql,
  default/H2, mssql, oracle, mysql, db2, `exclude=false`, renamed repository); each emitted exactly the
  expected extension, and the inert cases still emit a well-formed `<component>`, not an empty file.
- `TestFulltextExclusion` is the regression test, VCS shape on the default H2 run, asserting on
  `FulltextConfiguration` rather than on a search because the H2 test repository sets
  `searchDisabled="true"`. It **did fail** (2 of 3) before the contribution was deployed. On
  `-Dnuxeo.test.core=mongodb` the class-level `IgnoreIfNotVCSRepository` fires **before** `@Deploy`
  (verified: 0.3 s, no runtime started), so the VCS contribution is never registered on a DBS run.
  The DBS shape stays untested.


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
- `TestFulltextExclusion` uses `AutomationFeature`, not `CoreFeature`: the class `@Deploy`s the whole
  bundle so the test contribution can be resolved from it, and `CoreFeature` alone leaves
  `imagehash-operations-contrib.xml` as `Missing: 1` in the startup banner.

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
