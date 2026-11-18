# Search Backend

[Back to the README](../README.md)

- [Supported Search Stacks](#supported-search-stacks)
- [Distance Hint](#distance-hint)
- [Index Mapping](#index-mapping)
- [The Queried Index](#the-queried-index)
- [Keeping the Hash Properties Out of Fulltext](#keeping-the-hash-properties-out-of-fulltext)

## Supported Search Stacks

Similar images are found by querying the `imgh:bands` property through the Search service. This requires a **search engine** search client: the `repository` client, which queries the repository directly, cannot serve it. Install one of:

| Search stack | Package | Status |
|---|---|---|
| OpenSearch 1.x, Elasticsearch 7.x–8.x | `nuxeo-search-client-opensearch1` | Supported, covered by automated tests |
| OpenSearch 2.x | `nuxeo-search-client-opensearch2` | Supported, **not covered by automated tests**, checked manually on OpenSearch 2.19 |
| Elasticsearch 9.x | `nuxeo-search-client-elasticsearch9` | Supported, **not covered by automated tests**, never run |

`nuxeo-search-client-opensearch1` and `nuxeo-search-client-opensearch2` are mutually exclusive. MongoDB Atlas Search (`nuxeo-search-client-mongoatlas`) is **not supported**: it has not been tested and its indexing model differs.

> [!IMPORTANT]
> Only OpenSearch 1.x, and therefore Elasticsearch 7.x–8.x which uses the same client, is exercised by the test suite. It runs against an embedded OpenSearch node, given the Painless module for the distance hint.
>
> OpenSearch 2.x and Elasticsearch 9.x are supported by design: the plugin resolves the search index from the configuration rather than from a client name, the fulltext mapping template ships a dedicated block for each stack, written from the official sources of each client, and the distance hint sends the same query on the three stacks, in a `wrapper` query. OpenSearch 2.x was checked manually, Elasticsearch 9.x never ran: validate on a staging environment before relying on them in production.
>
> This is a deliberate trade-off. Those two search clients are distributed as private snapshot artifacts, and Elasticsearch 9 has no embeddable server. Testing them would make `mvn clean install` require a Docker container, on top of the Nuxeo credentials it already needs.

## Distance Hint

On these three stacks, the search engine also computes the distance between the hashes, through an NXQL hint the Marketplace template registers for the installed search client, see [Candidate Limit](scaling.md#candidate-limit). The hint runs a Painless script: Painless is part of every OpenSearch and Elasticsearch distribution, but a cluster that disables scripting cannot serve it, and every duplicate search then fails. Set `nuxeo.labs.images.dup.search.hint=false` in `nuxeo.conf` on such a cluster.

The package installs three jars on every server: `nuxeo-labs-images-dup-core`, `nuxeo-labs-images-dup-opensearch2` and `nuxeo-labs-images-dup-elasticsearch9`. The last two only hold the hint for their search client, and are not loaded when that client is not the one installed: they do nothing and cause no error.

## Index Mapping

The Marketplace package maps the four `imgh:*` properties in the search index, to keep them out of fulltext (see [Keeping the Hash Properties Out of Fulltext](#keeping-the-hash-properties-out-of-fulltext)).

> [!WARNING]
> **The mapping is only applied when the index is created.** An index created before the package was installed has no `imgh:*` mapping, and the hash properties end up in the fulltext field. This happens with a Docker image that starts Nuxeo once before installing its packages. Recreate the index with a full reindex, `POST /nuxeo/api/v1/management/search/reindex` **without** a `query` parameter, as an administrator: only that form drops and recreates the index. A reindex with a query rewrites the documents into the existing index, which keeps its old mapping.

To check a deployment manually, index a picture, then run a fulltext search on one of the values of its `imgh:bands` property, for example `f2eabf4f:0:cb94`, on its `imgh:phash` value, and on its `imgh:signature` value, `phash64v2`. None must return the picture: otherwise the mapping template was not applied and the hash properties are polluting the fulltext index. This is exactly what the automated tests assert on OpenSearch 1.x. Also check that `nxserver/config/imagehash-search-hint-config.xml` registers the hint for your search client: with no search engine detected, it is an empty component.

## The Queried Index

The query always targets the search index, never the repository, so no MongoDB or PostgreSQL index is needed. Without a search engine client, hashes are still computed and stored, but duplicate detection is disabled and `ImageHash.FindSimilar` fails with an explicit error.

The index actually queried is the one declared as default for the repository, so a blue/green reindex keeps working: the plugin follows the blue index until you swap.

`maxCandidates` must not exceed the index `max_result_window`, which defaults to 10000. The plugin default is exactly 10000, so lower it if you lower the window: above the window, the search engine rejects every duplicate search.

When the duplicate search fails during the automatic detection, because of such a rejection or because the search cluster is unavailable, the hash is still stored and the failure is logged as an error, but the `duplicatedImage` event is not fired for that picture and the detection is not replayed later.

## Keeping the Hash Properties Out of Fulltext

The plugin stores four technical properties on each picture: the band keys in `imgh:bands`, opaque strings such as `f2eabf4f:0:cb94`, the hash in `imgh:phash`, the hashes of its 8 orientations in `imgh:orientations`, and `imgh:signature`, the version of the hash algorithm, for example `phash64v2`. None of them is content. Left in fulltext, every hashed picture matches a search for its signature, and early versions of this plugin, whose signature ended with the name of the hashed view, made a search for "small" return all of them. Two different fulltext indexes can pick them up, and they are independent of each other.

**Search engine fulltext is handled for you.** The Marketplace package installs and activates a template that maps the four `imgh:*` properties explicitly, which overrides the default dynamic mapping and removes the `copy_to` into the fulltext field. It detects the search client in use, covers OpenSearch 1.x, OpenSearch 2.x and Elasticsearch 9.x, and also covers the green index of a blue/green reindex. Nothing to configure.

The mapping is applied when an index is created, so on an existing deployment you must drop the index and reindex for it to take effect, see [Index Mapping](#index-mapping). This also applies when upgrading from a build of this plugin that only mapped `imgh:bands`.

**Repository fulltext is handled for you too, but check the repository name.** By default Nuxeo puts every string property of every schema into `ecm:fulltextSimple`, including the `imgh:*` ones. Besides adding noise, it means each stored hash marks the document dirty for fulltext and schedules an extra extraction job per picture. The same template contributes the exclusion, picking the right form from `nuxeo.db.type`: MongoDB, PostgreSQL, SQL Server, Oracle, MySQL, MariaDB and H2 are covered. The contribution is purely additive, so the exclusions already declared by the Nuxeo database template, such as `thumb:thumbnail`, are preserved.

> [!IMPORTANT]
> The contribution targets the repository named `default`. **If your repository has another name, set it in `nuxeo.conf`**, otherwise a second, unconfigured repository is declared and the server fails to start:
>
> ```
> nuxeo.labs.images.dup.fulltext.repository=myrepository
> ```
>
> Set `nuxeo.labs.images.dup.fulltext.exclude=false` to disable the exclusion entirely.

Two caveats. The exclusions are read when the repository is initialised, so a restart is needed, and values already written to `ecm:fulltextSimple` stay until the documents are saved again. And the saving is not the same on both backends: MongoDB gets both the extraction filtering and the suppression of the extra job, while a VCS repository only gets the extraction filtering, because the dirty-path check that skips the job scheduling exists on DBS only.

For reference, or for a deployment where the template is not usable, this is what gets contributed:

```xml
<!-- MongoDB -->
<extension target="org.nuxeo.ecm.core.storage.mongodb.MongoDBRepositoryService" point="repository">
  <repository name="default">
    <fulltext>
      <index>
        <excludeField>imgh:bands</excludeField>
        <excludeField>imgh:bands/*</excludeField>
        <excludeField>imgh:orientations</excludeField>
        <excludeField>imgh:orientations/*</excludeField>
        <excludeField>imgh:phash</excludeField>
        <excludeField>imgh:signature</excludeField>
      </index>
    </fulltext>
  </repository>
</extension>
```

```xml
<!-- PostgreSQL (VCS) -->
<extension target="org.nuxeo.ecm.core.storage.sql.RepositoryService" point="repository">
  <repository name="default">
    <indexing>
      <fulltext>
        <index name="default">
          <excludeField>imgh:bands</excludeField>
          <excludeField>imgh:bands/*</excludeField>
          <excludeField>imgh:orientations</excludeField>
          <excludeField>imgh:orientations/*</excludeField>
          <excludeField>imgh:phash</excludeField>
          <excludeField>imgh:signature</excludeField>
        </index>
      </fulltext>
    </indexing>
  </repository>
</extension>
```

All six `<excludeField>` entries are needed. Each of the two lists takes two: `imgh:bands` suppresses the text extraction, `imgh:bands/*` suppresses the dirty-path trigger that schedules the job, and the same goes for `imgh:orientations`. `imgh:phash` and `imgh:signature` are single values, one entry each does both, and the job is only skipped when every property written with the hash is excluded. Keep the `<index>` name as shown, it is not the same on the two backends: it has to match the one declared by the Nuxeo template, otherwise the contribution adds a second index instead of merging into the existing one.
