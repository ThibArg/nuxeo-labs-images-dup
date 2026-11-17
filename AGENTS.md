# AGENTS.md

Nuxeo LTS 2025 plugin. Detects near-duplicate images on `Picture`-faceted documents using a
64-bit DCT perceptual hash stored on the document and searched with NXQL on the search engine.

## Repository conventions

- The git remote is `ThibArg/nuxeo-labs-images-dup` (personal account) while the README points to
  `nuxeo-sandbox`. **This is intentional, do not "fix" it.** Once validated the plugin is forked to
  `nuxeo-sandbox` and this repo becomes an archive.
- Everything written (README, this file, code comments, Javadoc, commit messages, UI labels) is in
  **English**. The one exception is the French Web UI translation, `messages-fr.json`.
- `AGENTS.md` is deliberately **not** in `.gitignore` here, it is committed.
- Javadoc tag is `@since 2025.1.0` everywhere, not `@since 2025.XX`.
- No CI, no linter, no formatter config. Java 21, Maven 3.8. `.github/modernize/` is a self-ignored
  VS Code tool artifact, not CI.

## Modules and entry points

| Module | Content |
|---|---|
| `nuxeo-labs-images-dup-core` | All Java code, schema, OSGI-INF contributions, Web UI files, tests |
| `nuxeo-labs-images-dup-package` | Marketplace assembly (`assembly.xml`) + the `nuxeo-labs-images-dup` configuration template, no code |

Bundle symbolic name used in `@Deploy`: `nuxeo.labs.images.dup.nuxeo-labs-images-dup-core`.

Everything funnels into `ImageHashServiceImpl`:

- `ImageHashListener`, async post-commit on `pictureViewsGenerationDone` → `updateHashAndDetectDuplicates`
  → fires `duplicatedImage`.
- `ImageHash.FindSimilar` operation and `imagehash_similar_images` page provider → `findSimilar`.
- `computeImageHash` bulk action → `updateHash`. Fires no event. `httpEnabled="false"`, so through
  `Bulk.RunAction` or REST only administrators can launch it.
- `detectImageDuplicates` bulk action → `detectDuplicates` → fires `duplicatedImage`. Computes and stores
  nothing. Same `httpEnabled="false"`.

## Commands

```bash
mvn clean install                                        # build + all tests + marketplace zip (~55 s warm)
mvn -o clean install                                     # same, offline, once dependencies are cached
mvn -pl nuxeo-labs-images-dup-core test                  # tests only
mvn -pl nuxeo-labs-images-dup-core test -Dtest=TestPHash
mvn -pl nuxeo-labs-images-dup-core test -Dtest='TestPHash#testOrientations'
```

- `TestPHash` and `TestHashBands` are plain unit tests (< 1 s). `TestImageHashService` boots Nuxeo plus an
  embedded OpenSearch node (~45 s), `TestFulltextExclusion` ~5 s.
- `ERROR ... NUXEO INSTANCE REGISTRATION FILE IS MISSING` in the test output is harmless.
- Run `mvn clean install` before committing Java/XML changes (not needed for README / AGENTS.md /
  `.gitignore`-only commits).
- Package artifact: `nuxeo-labs-images-dup-package/target/nuxeo-labs-images-dup-package-2025.1.0.zip`.

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

The three `imgh:*` properties are technical strings: band keys (`f2eabf4f:0:cb94`), the hash in hex, and
the signature (`phash64v2`). **All three are excluded, never only `imgh:bands`**: the signature then
was `phash64v1/Small`, and once analyzed it made a fulltext search for "small" return every hashed
picture (review finding H2). They can
leak into **two unrelated** fulltext indexes. Fixing one does nothing for the other: `ecm:fulltextSimple` is never shipped to the search engine
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

**Each fix is duplicated, keep the copies in sync.** The templates are not loaded by tests, so tests run
hand-written counterparts: `src/test/resources/OSGI-INF/test-opensearch-mapping-contrib.xml` (index
`nxutest`) and `test-fulltext-exclude-contrib.xml` (repository `test`). The README also reproduces the
fulltext XML. A change to a template that is not mirrored there is untested. Adding a string property to
the `imagehash` schema means touching all of them: the mapping macro, both fulltext branches, the
two test contributions and the README. `TestFulltextExclusion` pins the exact set of excluded `imgh:*`
paths.

### Engine mapping (`imagehash-search-mapping-config.xml.nxftl`)

- The `strings` template carrying `copy_to: "all_field"` and the `IndexConfig.merge()` logic are
  **identical on all three stacks**, down to the line numbers.
- **The fix is ordering sensitive.** `merge()` preserves the base mapping only when the *incoming*
  descriptor has `append="true"`; in the other direction `extraMappingContents` is silently dropped.
  Every block declares `<require>` on the component holding the base mapping. Remove it and the
  override vanishes without any error.
- **One `mapping` macro, called six times** (review finding L7): blue and green for opensearch1,
  opensearch2 and elasticsearch9. It takes the index name, the component to `<require>` and the target
  component, and emits nothing when the index name is empty. Two FreeMarker traps: the argument must be
  written `((a.b.c)!)`, with the outer parentheses, or `!` swallows the next parameter name as its
  default value; and the macro must stay inside `<#escape>`, which is lexical. Each call is guarded by its
  own `nuxeo.search.client.{default,green}.<stack>.index.name` property.
- **Why guarding on an absent property is safe.** `TextTemplate` builds the FreeMarker model as a nested
  map by splitting dotted keys (`TextTemplate.java:382-416`), so a property from a template that was
  never activated is simply missing. The parenthesised form `(a.b.c)!` covers a missing
  *intermediate* step and yields an empty string, which the macro tests with `?has_content`; Nuxeo relies on the same mechanism across packages in
  `opensearch1-search-client-config.xml.nxftl:7`, which guards on `(nuxeo.opensearch1.embed.enabled)!`,
  a property defined only by the separate opensearch1-embed package. `nuxeo.search.client.default` is
  always a Map because `common-base/nuxeo.defaults:150-151` defines `.name` and `.scroller.index.name`.
- **Rendering was verified empirically**, not inferred: the template was run through `TextTemplate` with
  the property sets of seven deployments (no engine, each stack alone, green empty, blue+green,
  OS1 -> ES9 migration) and emitted exactly the expected blocks. It was run again when the six blocks
  became a macro, with an eighth set whose index name holds XML special characters: every output parsed
  to the same requires, targets, index names and mapping as before. Re-run that check if the guards or
  the macro change; it needs a throwaway `main` calling `new TextTemplate(props).processFreemarker(in,
  out)` on the test classpath, nothing in the repository does it.
- Because the `<require>` sits inside the `<#if>` of the macro, only installed stacks emit one, so the component
  always resolves. **It is a single component though**, so a `<require>` that cannot resolve would take
  all the blocks down with it; that needs an index-name property set without the matching package
  installed, and shows up as `Pending` in the startup banner.
- Mappings are applied **only at index creation**. Existing deployments need a drop + reindex, including
  those created by a build that mapped `imgh:bands` only.
- `imgh:phash` and `imgh:signature` are mapped `keyword` with `ignore_above: 256`, the dynamic template
  minus `copy_to`. The search reads `imgh:phash` from `_source`, so `ignore_above` cannot hide a hash.
- Only opensearch1 is covered by a test (`testBandKeysAreNotIndexedInFulltext` and
  `testHashAndSignatureAreNotIndexedInFulltext`, embedded node). The
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

### Repository fulltext (`imagehash-fulltext-exclude-config.xml.nxftl`)

- Beyond noise, a hash store marks the written paths dirty and schedules an extra `FulltextExtractorWork`;
  `store()` disables versioning, DC, audit and notifications but cannot disable that. The work is skipped
  only when **every** dirty path is excluded (`DBSTransactionState.java:1440-1469` sets `dirtyStrings` on
  the first one that is not), and `store()` writes the three properties together: excluding
  `imgh:bands` alone never suppressed the work.
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
- `imgh:phash` and `imgh:signature` are scalars: one bare-path `<excludeField>` each serves both consumers
  below.
- **Both `excludeField` forms of the array are required, for two different consumers.** `imgh:bands` stops the
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
  `searchDisabled="true"`. It **did fail** (2 of 3) before the contribution was deployed, and again
  (3 of 4) before `imgh:phash` and `imgh:signature` were added. On
  `-Dnuxeo.test.core=mongodb` the class-level `IgnoreIfNotVCSRepository` fires **before** `@Deploy`
  (verified: 0.3 s, no runtime started), so the VCS contribution is never registered on a DBS run.
  The DBS shape stays untested.

## Testing

- **No external service, no Docker, works offline.** `TestImageHashService` uses
  `OpenSearchCoreSearchFeature`, and `OpenSearchFeature` starts an **embedded in-JVM OpenSearch node**
  when `nuxeo.test.opensearch1.servers` is unset (~10 s of startup). The repository is H2 in-memory VCS.
  Point the suite at a real cluster with `-Dnuxeo.test.opensearch1.servers=http://localhost:9200`.
- Required test-jars, both version-managed by the Nuxeo parent:
  `org.nuxeo.ecm.core:nuxeo-search-client-opensearch1` and
  `org.nuxeo.runtime:nuxeo-runtime-opensearch1-embed`.
- The technical index is `nxutest` in tests, hence the separate
  `test-opensearch-mapping-contrib.xml`, which must be `@Deploy`ed explicitly.
- `testBandKeysAreNotIndexedInFulltext` and `testHashAndSignatureAreNotIndexedInFulltext` are the
  regression tests for the mapping override. Both **did fail** before their fix, so if one ever passes
  trivially, check that the mapping is still being applied.
- To run against MongoDB instead of H2 (nothing in the plugin depends on it any more, the band query
  never reaches the repository): `docker run -d --rm -p 27017:27017 mongo:8` then
  `mvn -pl nuxeo-labs-images-dup-core test -Dnuxeo.test.core=mongodb`.
- `TestImageHashService` deploys only `picture-schemas-contrib.xml` + `picture-adapter-contrib.xml`,
  never the views-generation components, so **ImageMagick is not required**. Picture views are faked by
  writing `picture:views` directly (`createPicture`) and `pictureViewsGenerationDone` is fired by hand
  (`viewsGenerated`). Keep it that way.
- `TestImages.unreadableGif()` is the fixture for a view that fails with a **runtime** exception: a GIF
  whose frame width is patched to zero, on which the reader throws `IllegalArgumentException: Empty
  region!` (JDK 21.0.3). `ImageIO.read()` wraps such exceptions into an `IIOException`, so
  `testUnreadableViewFallsBackToNextView` first asserts the exception type on
  `ImageHashServiceImpl.readSubsampled` itself: if a JDK update turns it into an `IOException`, the test
  fails there instead of passing trivially. It did its job once already: the first fixture was a PNG
  declaring a 16 million pixel width, which stopped throwing when `subsamplingStep` got its pixel bound.
  With that bound, fuzzing the first 120 bytes of each format found runtime exceptions on GIF, BMP and
  TIFF only, none on JPEG or PNG, and one `OutOfMemoryError` on a TIFF, which nothing catches.
- `FailingSaveListener` (`test-failing-save-listener-contrib.xml`, deployed on the whole class) makes
  `saveDocument` fail for documents **named `unsavable`**, with a bubbling exception that does not mark
  the transaction rollback-only. It is what exercises the per-document `catch` of the two loops; the
  unreadable view alone is absorbed by `readImage`.
- `testCandidateCapKeepsTheClosestCandidatesAndIsReported` deploys `test-few-candidates-contrib.xml` on
  the method (`maxCandidates` 5) and builds its candidates with `createHashed`, which writes the three
  `imgh:*` properties directly on `File` documents: 16 decoys one bit away on band 0 and inverted
  elsewhere, indexed **before** the real duplicate so that an unsorted capped query leaves it out. It
  **did fail** (empty result) before the tiered re-query.
- The two `testFailingSearch*` tests deploy `test-failing-search-contrib.xml` **on the method**
  (`maxCandidates` 20000, above the `max_result_window` of the embedded index, so every candidate query is
  rejected). The hot deploy costs about 3 s per test and is undone afterwards: `testHashPropertiesAreSecured`
  runs after them and searches. `testFailingSearchDoesNotFailTheCaller` is the one that fails without the
  M4 fix; `testFailingSearchKeepsTheHash` also passes with the listener's per-document `catch` alone.
- `DuplicatedImageCaptureListener` is a **static** event collector. Call `clear()` in `@Before` or tests
  leak into each other.
- `txFeature.nextTransaction()` is mandatory after firing events: it both drains the async post-commit
  listener and runs the search-indexing waiter installed by `BaseCoreSearchFeature`. Without it, freshly
  stored hashes are not searchable.
- Test images are generated, not fixtures. `TestImages.sample(seed, w, h)` is deterministic per seed.
- `TestFulltextExclusion` uses `AutomationFeature`, not `CoreFeature`: the class `@Deploy`s the whole
  bundle so the test contribution can be resolved from it, and `CoreFeature` alone leaves
  `imagehash-operations-contrib.xml` as `Missing: 1` in the startup banner.
- Both test classes also `@Deploy("org.nuxeo.web.resources.core")` (test dependency
  `nuxeo-web-resources-core`): it is the target of `imagehash-webui-contrib.xml`, which is otherwise
  reported `Missing: 1`. Production is fine, the bundle is in `nuxeo-nxr-server`.
- The page provider tests build the provider through `PageProviderHelper.getSearchDocumentModel`, the
  same path as the REST endpoint, so named parameters are tested as they really arrive.

## Architecture gotchas

- **Signature gating.** `signature = ALGORITHM`, nothing else. Changing the algorithm invalidates every
  stored hash: they are silently ignored by search until the `computeImageHash` bulk action is re-run.
  The view is **not** part of it anymore (review finding M7): the hash comes from the first *available*
  view, so a picture without `Small` was hashed from `Medium` and still stamped `phash64v1/Small`, and
  reordering `views` forced a full recompute for nothing. Do not put the view back; a deployment that
  must not compare a given view simply leaves it out of `views`. `bandPrefix` is the CRC32 of the signature
  and prefixes every band key, which is what keeps signatures from matching each other at query time.
- **The DCT block skips frequency 0** (`PHash.FIRST_FREQUENCY = 1`, coefficients 1 to 8 on each axis).
  `phash64v1` kept the DC coefficient, the plain sum of the luminance, which no other coefficient can
  exceed: bit 63 was always 1, band 0 selected half as well as the others and every search fetched 25%
  more candidates (review finding M1). `ALGORITHM` is now `phash64v2`, so `phash64v1` hashes are ignored
  until `computeImageHash` is re-run (no `force` needed). `TestPHash#testBitDistribution` is the
  regression test and **did fail** on bit 63. Any change to `PHash.hash()` needs an `ALGORITHM` bump.
- **Band search, not brute force.** The 64-bit hash is split into 4 x 16-bit bands (`HashBands`).
  Candidates come from `imgh:bands IN (...)`; `queryKeys` expands to all 1- or 2-bit variants per band.
  Radius `r` guarantees finding everything within Hamming distance `4r + 3`. Key count explodes:
  radius 0 -> 4 keys, 1 -> 68, 2 -> 548.
- **`storedOrComputed`**: `findSimilar` and `detectDuplicates` start from the stored hash when its
  signature is current and the search has no orientations, and decode the image otherwise (review
  finding M5, half of it). `Computed.reduced` is then `null`, so never call `orientationHashes` on it.
  Storing the 8 orientation hashes, to spare the decoding for the default tab search too, is a schema
  change that was **not** done: a new string property has to go through every fulltext copy, see above.
- **`start()` must not call `ImageIO.setCacheDirectory`** (review finding L1): it is JVM-wide state, and
  the check that came with it failed the server start for an optional feature. ImageIO already caches in
  `java.io.tmpdir`, which `nuxeoctl` sets to the Nuxeo temporary directory (`NuxeoLauncher.java:747`).
  `testServiceLeavesTheImageIOCacheDirectoryAlone` guards it.
- **Range validation lives in `findSimilar`** (`checkRange`, `NuxeoException` with a 400 status, review
  finding L2), so that the operation, the page provider and Java callers share it. The page provider
  keeps its own `parseInt`, needed for values that are not integers; `maxResults` / `limit` are checked
  by each entry point, the service does not know them. The configuration still clamps silently.
  `AutomationService.run` lets the `NuxeoException` through unwrapped.
- **`subsamplingStep` bounds the decoded pixels to `MAX_READ_PIXELS` (2 million)** on top of reducing the
  smaller side to `READ_SIZE` (review finding L5). It only changes the step, so the hash, of images more
  elongated than about 8:1, and can turn a strip less than 32 px wide once decoded into a degenerate
  image. No `ALGORITHM` bump: `phash64v2` has not been deployed anywhere. Bump it if this changes again.
- **Degenerate images** (smaller side < 32 px, or reduced-luminance stddev < 2.0) get a hash stored but
  an **empty** `imgh:bands`, so they are never candidates. Otherwise blank images and icons match
  everything.
- **Permissions come from the search layer.** `SearchQuery.builder(nxql, principal)` applies ACL
  filtering. Never swap it for a system-session `session.query(...)` plus post-filtering.
- **The hash is technical metadata.** `ImageHashServiceImpl.store()` disables versioning, Dublin Core,
  audit, notifications and picture-views regeneration via `putContextData` before `saveDocument`. Keep
  that block intact: removing `DISABLE_PICTURE_VIEWS_GENERATION_LISTENER` creates an infinite
  regeneration loop, removing the others bumps `dc:modified` and creates versions.
- **The three `imgh:*` properties are `secured="true"`** (`imagehash-schemas-contrib.xml`, review finding
  M2): without it any user with Write could blank `imgh:bands` and, the signature still being current,
  stay invisible until a `force` run. The check is `isSecured() && !NuxeoPrincipal.isCurrentAdministrator()`
  (`AbstractProperty.java:375-377`): it reads the **login stack of the current thread**, not the
  `CoreSession` principal, and a thread with no login at all is refused too. The legitimate writers pass
  because the async bundle is reconnected under a system login (`ReconnectedEventBundleImpl.java:85`)
  and the bulk computation logs in as the command's user (`AbstractBaseBulkComputation.java:148-151`), so
  a `computeImageHash` command submitted from Java for a non-administrator writes nothing.
  `testHashPropertiesAreSecured` pushes john with `NuxeoLoginContext.create(JOHN).login()`; opening a
  session for john alone leaves the thread logged in as Administrator and the write is accepted.
- **Detection is two passes, and the first one is blind to simultaneous uploads (review finding H3).**
  The listener searches the engine index, and a hash only gets there after its commit, the indexing
  stream and an index refresh. Two copies hashed within that latency of each other fire no event. The
  `detectImageDuplicates` bulk action is the second pass, to run once the pictures are indexed.
  - **Do not "fix" this by comparing the hashes inside the event bundle**, as the review report suggests:
    `RecomputeViewsAction` calls `pictureViewsHelper.newTransaction()` before each document
    (`RecomputeViewsAction.java:106`), so in production a bundle holds **one** event. The report's
    reproduction, and `testDetectDuplicatesBulkAction`, put several events in one transaction only
    because the test fires them by hand.
  - `detectDuplicates` reads the **stored** hash when `detectionOrientations` is off, the default, and
    decodes the image only when it is on. It skips documents whose signature is not the current one.
  - Unlike `updateHashAndDetectDuplicates` it lets a failing search throw: there is no hash to protect,
    and the action must count it in `errorCount`.
  - Known and accepted: the event principal is the user of the command, a pair is reported from both
    sides, and pairs already reported are reported again.
  - The latency race itself is reasoned from the sources, never reproduced here.
- **`<detectionEnabled>false</detectionEnabled>` skips the search in `updateHashAndDetectDuplicates`
  only** (review finding M6): the hash is still stored, `findSimilar` and `detectImageDuplicates` ignore
  the switch. The report also proposes a context-data flag set by an importer on a document: **not
  implemented, it cannot work**. Context data is not persisted, and `pictureViewsGenerationDone` is
  fired later, in another transaction, by `RecomputeViewsAction` on a document it loads itself.
- **Per-document failure isolation, three places (review finding M3).** `readImage` catches
  `IOException | RuntimeException` per view: the JDK PNG reader throws `IllegalArgumentException` on some
  headers, and a blob provider can throw unchecked. The loops of `ImageHashListener` and
  `ComputeImageHashAction` catch `RuntimeException` per document, with **two rethrows to keep**:
  `ConcurrentUpdateException` (transient, so the work or the batch must fail and be retried), and
  anything caught while `TransactionHelper.isTransactionMarkedRollback()` is true, because
  swallowing it would end in a silent rollback of the whole batch or bundle reported as a success. The
  bulk action also reports the document through `delta.inError(...)`, so it shows in `errorCount`.
  Retries, for the record: `AbstractWork.run()` retries on **any** `RuntimeException`
  (`AbstractWork.java:340-363`), and a listener work defaults to `retryCount` 2
  (`AsyncEventExecutor.java:140`), so a failing bundle used to run three times in a row, without delay,
  before being dropped. The review report says it retries on `ConcurrentUpdateException` only; that line
  is the logging in `cleanUp`.
- **A failing duplicate search must not fail `updateHashAndDetectDuplicates`** (review finding M4,
  reproduced before the fix): the search runs in the transaction that has just saved the hash, so an
  exception escaping it rolled the hash back. It is caught and logged in the service, not only in the
  listener loop, because the per-document `catch` of the listener would otherwise report "Cannot hash
  document" for a hash that is stored, and other callers of the service have no such `catch`.
  `fireDuplicatedImage` is deliberately left outside the `try`.
- `ImageHashListener` writes with `CoreInstance.getCoreSessionSystem(...)` so the hash is stored even
  when the triggering user cannot write the document, but the duplicate **search** uses the triggering
  principal, so the fired event only lists duplicates that user can read.
- The `duplicatedImage` event lists the `MAX_EVENT_DUPLICATES` (100) closest duplicates only, with the
  total in `duplicatesCount` (review finding L4): the map used to hold up to `maxCandidates` ids.
- `maxCandidates` (default 10000) is a page limit, not a total, and must stay <= the search index
  `max_result_window`. **Reaching it is reported, never silent** (review finding H1): `search()` returns
  `SimilarImages(images, truncated)`, and every entry point passes the flag on: the
  `imageHashTruncated` context variable of the operation, `SimilarImagesPageProvider.isTruncated()` plus
  the `imageHashTruncated` context data read by the enricher, `duplicatesTruncated` on the event, the
  tab warning. Keep all of them when touching `findSimilar`.
- **Tiered re-query on overflow.** The band query has no sort and a `terms` filter scores every hit
  alike, so a capped response is an arbitrary subset. When the single query returns `maxCandidates`
  hits, `search()` discards it and queries again per radius, 0 first, each with the remaining budget
  (`fetchCandidates`). Below the cap nothing changes: one query. `hits < limit` is the completeness
  test on purpose, not `getTotal()`: totals stop being accurate at 10000 on the engines. Tiers are not
  made disjoint with a `NOT IN`: a document can come back in a later tier, it is deduplicated by id and
  only wastes a little of that tier's limit.
- `SearchQuery` default limit is 10 — always set `.limit(...)` explicitly.
- `PICTURE_VIEWS_GENERATION_DONE_EVENT` is imported from the nested
  `RecomputeViewsAction.RecomputeViewsComputation`, not from the deprecated `PictureViewsGenerationWork`.

## Web UI

The "Similar Images" tab ships in the core bundle: HTML imports under
`web/nuxeo.war/ui/nuxeo-labs-images-dup/` (`<dom-module>` + `Polymer({...})`, same style as
`nuxeo-labs-signature-webui`), i18n under `web/nuxeo.war/ui/i18n/`, `OSGI-INF/deployment-fragment.xml`
and `imagehash-webui-contrib.xml`. Web UI is only an `<optional-dependencies>` of the package.

- **The data comes from the `imagehash_similar_images` page provider** (`SimilarImagesPageProvider`), a
  thin wrapper around `findSimilar()`. `nuxeo-results` then gets paging, grid/list, selection actions
  and enrichers for free, and permissions stay those of the search layer. Every page request reruns the
  whole search, there is no cache.
- **The named parameter is `limit`, not `maxResults`.** `QueryExecutor.getNamedParameters` filters with
  `EnumUtils.isValidEnum(QueryParams.class, key)`, which compares enum *names* (`MAX_RESULTS`), so today
  every query-string key reaches the provider, `maxResults` included. But `maxResults` is declared
  reserved (`QueryExecutor.MAX_RESULTS`) and is also an `AbstractPageProvider` property: fixing that
  comparison upstream would silently drop it.
- Named parameters arrive as `searchDocument.getContextData(PageProviderService.NAMED_PARAMETERS)`
  (`PageProviderHelper.fillSearchDocument`), through REST GET as well as `Repository.PageProvider`
  (Web UI POST mode).
- `PageProviderServiceImpl` calls `setCurrentPage()`, which fetches, then `setCurrentPageOffset()`, which
  takes precedence. The provider overrides `setCurrentPageOffset` to drop the cached page when the
  offset changes. Keep it.
- **`SimilarImagesPageProvider.loadDocuments` is the one place turning search results into documents**
  (review finding L3), used by the provider and by the operation. One `getDocument` per result, catching
  `DocumentNotFoundException` and `DocumentSecurityException`: the engine ACL filter is Browse-level, so
  a search does list documents the user can browse but not read, and the operation used to fail on them
  (`testResultsTheUserCannotReadAreSkipped`). The operation refills up to `maxResults`; the provider
  cannot, its offsets are positions in the unfiltered list, so `resultsCount` is an upper bound.
- **Distance**: the provider puts it in each document's context data and `SimilarImageJsonEnricher`
  (`imageSimilarity`) writes it. This works only because the REST writer serializes the very instances
  returned by `getCurrentPage()`.
- **The truncation flag travels the same way, per document**, because a paginable JSON response has no
  field a provider can add (`DefaultListJsonWriter.java:145-165`) and `nuxeo-page-provider` copies a fixed
  list of response fields. Consequence: **an empty truncated page cannot say so** over REST or in the
  tab; only the Java API, the operation variable, the event and the log can. `errorMessage` with
  `hasError=false` was considered and rejected as a misuse.
- **`<require>all</require>` in the deployment fragment, never `<require>org.nuxeo.web.ui</require>`.**
  The "all" marker (`FragmentDescriptor.ALL`) orders the fragment after Web UI's own, so the i18n
  `append` merges into Web UI's `messages.json` (`AppendCommand` merges JSON). Requiring the Web UI
  bundle when it is not installed skips the fragment and logs "Unknown bundle" as a deployment error.
  Without Web UI the fragment still copies its files under `nuxeo.war/ui`, which is harmless.
- i18n files are excluded from the plain unzip and `append`ed one by one; `messages-fr.json` is appended
  to both `messages-fr.json` and `messages-fr-FR.json`. A new language needs its own `<append>` lines.
- **Every document tab page is rendered, selected or not**: the views `iron-pages` only toggles the
  `visible` attribute (`selected-attribute="visible"`). `nuxeo-results` sits in a
  `<dom-if if="[[visible]]">`, otherwise its view setup would run a similarity search on every Picture
  opened.
- Do not bind `document` on `nuxeo-results`: the Web UI sources this was built against then save the
  table settings as document-level preferences on the backend. The table has no `settings-enabled`,
  resize or reorder for the same reason.
- `_params` must never be `null`, `nuxeo-page-provider` destructures its `params`.
- Globals usable from HTML imports (`Nuxeo.LayoutBehavior`, `Nuxeo.I18nBehavior`,
  `Polymer.IronResizableBehavior`...) are listed in Web UI's `legacy.js`.
- **The HTML/JS is not covered by automated tests.** Check manually that the tab shows on Pictures only
  and that opening a Picture sends no `imagehash_similar_images` request until the tab is selected.

## Status

Work in progress, not production-ready. The README carries the caution banner on purpose.
