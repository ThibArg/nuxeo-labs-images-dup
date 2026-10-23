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

## Testing

- **No external service is needed.** `CoreFeature` defaults to H2 in-memory VCS and `CoreSearchFeature`
  defaults to `nuxeo.test.search=repository`, so the NXQL search in `ImageHashServiceImpl.search()` runs
  against the repository backend. Plain `mvn test` works offline.
- **MongoDB run** (verified): Nuxeo has no embedded/testcontainers MongoDB, you must supply one.

  ```bash
  docker run -d --rm --name nuxeo-test-mongo -p 27017:27017 mongo:8
  mvn -pl nuxeo-labs-images-dup-core test -Dnuxeo.test.core=mongodb
  docker stop nuxeo-test-mongo
  ```

  This is the only way to exercise the `indexOrder="ascending"` declaration on `imgh:bands`
  (`imagehash-schemas-contrib.xml`): the index is created automatically at repository init because the
  schema is attached to the `ImageHash` facet. Verified present as `imgh:bands_1` in db `unittests`,
  collection `test`. On VCS/PostgreSQL the declaration is ignored and the index must be created by hand.
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
