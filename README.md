# nuxeo-labs-images-dup

> [!CAUTION]
> This is **work in progress**. Do not use as is.
>
> Once ready this plugin should be forked to [nuxeo-sandbox](https://github.com/nuxeo-sandbox) repository, and current repo will be archived.
>
> But it can take time to be ready, there is a lot of real-life chek to perform :-)

Nuxeo LTS 2025 plugin for detecting near-duplicate images on documents with the Picture facet. It computes and stores a perceptual hash when picture views are generated, then searches for similar images while respecting the current user's permissions.

The duplicate search runs on a search engine client (OpenSearch or Elasticsearch), see [Search Backend](#search-backend).

## How to Use

Install the Marketplace package. New or updated Picture documents are hashed automatically after their picture views are generated. When a duplicate is detected, the plugin fires the `duplicatedImage` event.

To search for similar images, call the `ImageHash.FindSimilar` Automation operation with a Picture document as input. Optional parameters are:

| Parameter | Description | When unset |
|-----------|-------------|------------|
| `threshold` | Maximum Hamming distance, from 0 to 64. | `<threshold>`, default `8` |
| `radius` | Search radius, from 0 to 2. Higher values find more candidates but cost more. | `<searchRadius>`, default `1` |
| `orientations` | Also compare rotated and mirrored images. | `<searchOrientations>`, default `true` |
| `maxResults` | Maximum returned documents. | `50`, not configurable |

The first three fall back to the [configuration](#configuration), so the usual way to change them for every caller is to contribute, not to pass them on each call.

**Hamming distance** is the number of differing bits between two 64-bit hashes: `0` means identical hashes, and the lower the value, the more similar the images. `threshold` is the distance at or below which two images are considered duplicates.

**Search radius** is how far the candidate query looks around the hash. The hash is split into four 16-bit bands, and candidates are the documents sharing at least one band. A higher radius also queries the variants of each band that differ by 1 bit (`radius=1`) or by 1 or 2 bits (`radius=2`), which finds more candidates but queries many more keys: 4 keys at radius 0, 68 at radius 1, 548 at radius 2. A radius `r` is guaranteed to find every image within a distance of `4r + 3`.

**`threshold` and `radius` are coupled**: keep `threshold` close to `4 * radius + 3`, which is 3 at radius 0, 7 at radius 1 and 11 at radius 2. Beyond that distance a pair is found only when its differing bits happen not to be spread evenly across the four bands, so raising `threshold` alone widens the accepted distance without widening the search. The default pair, threshold `8` at radius `1`, sits one above the guarantee: around 5% of the pairs at a distance of exactly 8 are missed, which is acceptable on the last accepted distance. Raising `threshold` to 10 while leaving `radius` at 1 would miss roughly a quarter of the pairs it claims to accept, so raise `radius` to 2 as well, and pay the 548 keys.

**`maxResults` does not make the search faster.** The candidate query and the distance comparison always run on the whole candidate set, then the results are sorted by distance and only then truncated. So `maxResults` only reduces the number of documents loaded from the repository and the size of the response. What drives the search cost is `radius` and `orientations`, which multiplies the number of queried keys by 8. The hard limit on the candidate set is `maxCandidates`, see [Configuration](#configuration).

To compute or refresh hashes for existing documents, submit the `computeImageHash` bulk action with a query selecting Picture documents. Set its optional `force` parameter to `true` to recompute hashes that already use the current configuration.

### Web UI

With Web UI, documents with the Picture facet get a **Similar Images** tab. Web UI is only an optional dependency of the package: without it, everything else works the same.

The tab lists the near duplicates the current user can read, closest first, in the standard grid (thumbnails) and list views. The list view shows the Hamming distance of each document.

The toolbar at the top tunes the search with the same parameters as the operation: **Threshold**, **Radius**, **Orientations** and **Max results**. An empty field, or **Default**, uses the configured value. Click **Search** or press Enter to run the search, **Reset** goes back to the configured values. Nothing is searched until the tab is displayed.

### REST API

The tab reads the `imagehash_similar_images` page provider, which can also be called directly:

```
GET /nuxeo/api/v1/search/pp/imagehash_similar_images/execute?docId=<document id>&threshold=8&radius=1&orientations=true&limit=50
```

| Parameter | Description |
|-----------|-------------|
| `docId` | The Picture document to compare. Without it, the result is empty. |
| `threshold`, `radius`, `orientations` | Same as the operation. When missing or empty, the configured values are used. |
| `limit` | Maximum returned documents, default `50`. Same role as the operation's `maxResults`, which is a name the REST search endpoint reserves. |

The usual paging parameters (`pageSize`, `currentPageIndex`, `offset`) apply, and invalid values return a `400`. Each page request runs the whole search again, so a `pageSize` covering `limit` avoids repeating it.

Add the `imageSimilarity` enricher, for example with the `enrichers-document: imageSimilarity` header, to get the distance of each document in `contextParameters.imageSimilarity.distance`.

## Configuration

Contribute to the `configuration` extension point of `nuxeo.labs.images.dup.ImageHashService`. All elements are optional and contributions are merged:

```xml
<component name="my.images.dup.configuration">
  <extension target="nuxeo.labs.images.dup.ImageHashService" point="configuration">
    <configuration>
      <views>
        <view>Small</view>
        <view>Medium</view>
        <view>FullHD</view>
        <view>OriginalJpeg</view>
      </views>
      <threshold>8</threshold>
      <detectionRadius>1</detectionRadius>
      <detectionOrientations>false</detectionOrientations>
      <searchRadius>1</searchRadius>
      <searchOrientations>true</searchOrientations>
      <maxCandidates>10000</maxCandidates>
    </configuration>
  </extension>
</component>
```

`threshold` is shared by both paths, but the radius and the orientations are split: `detectionRadius` and `detectionOrientations` apply to the automatic detection that runs after picture views generation, `searchRadius` and `searchOrientations` to the `ImageHash.FindSimilar` operation, the Similar Images tab and its page provider. The defaults keep the automatic path cheaper than the explicit search.

The first available view is used for hashing. Changing the first view changes the hash signature; run the `computeImageHash` bulk action to update existing documents.

Two settings live in `nuxeo.conf` rather than in the extension point, because the template installed by the Marketplace package needs them before the platform starts:

| Property | Default | Role |
|---|---|---|
| `nuxeo.labs.images.dup.fulltext.repository` | `default` | Repository the fulltext exclusion is contributed to. **Must be changed if your repository is not named `default`**, see [Keeping Band Keys Out of Fulltext](#keeping-band-keys-out-of-fulltext) |
| `nuxeo.labs.images.dup.fulltext.exclude` | `true` | Set to `false` to not contribute the exclusion at all |

### Search Backend

Similar images are found by querying the `imgh:bands` property through the Search service. This requires a **search engine** search client: the `repository` client, which queries the repository directly, cannot serve it. Install one of:

| Search stack | Package | Status |
|---|---|---|
| OpenSearch 1.x, Elasticsearch 7.x–8.x | `nuxeo-search-client-opensearch1` | Supported, covered by automated tests |
| OpenSearch 2.x | `nuxeo-search-client-opensearch2` | Supported, **not covered by automated tests** |
| Elasticsearch 9.x | `nuxeo-search-client-elasticsearch9` | Supported, **not covered by automated tests** |

`nuxeo-search-client-opensearch1` and `nuxeo-search-client-opensearch2` are mutually exclusive. MongoDB Atlas Search (`nuxeo-search-client-mongoatlas`) is **not supported**: it has not been tested and its indexing model differs.

> [!IMPORTANT]
> Only OpenSearch 1.x, and therefore Elasticsearch 7.x–8.x which uses the same client, is exercised by the test suite. It runs against an embedded OpenSearch node.
>
> OpenSearch 2.x and Elasticsearch 9.x are supported by design: the plugin resolves the search index from the configuration rather than from a client name, and the fulltext mapping template ships a dedicated block for each stack, written from the official sources of each client. But no automated test runs against them, so validate on a staging environment before relying on them in production.
>
> This is a deliberate trade-off. Those two search clients are distributed as private artifacts, and Elasticsearch 9 has no embeddable server. Testing them would make `mvn clean install` require both credentials and a Docker container, instead of working out of the box for everyone.

To check a deployment manually, index a picture, then run a fulltext search on one of the values of its `imgh:bands` property, for example `f2eabf4f:0:cb94`. It must return no result: if the picture comes back, the mapping template was not applied and band keys are polluting the fulltext index. This is exactly what the automated test asserts on OpenSearch 1.x.

The query always targets the search index, never the repository, so no MongoDB or PostgreSQL index is needed. Without a search engine client, hashes are still computed and stored, but duplicate detection is disabled and `ImageHash.FindSimilar` fails with an explicit error.

The index actually queried is the one declared as default for the repository, so a blue/green reindex keeps working: the plugin follows the blue index until you swap.

`maxCandidates` must stay below the index `max_result_window`, which defaults to 10000. The plugin default is exactly 10000, so lower it if you lower the window.

### Keeping Band Keys Out of Fulltext

Band keys are opaque strings such as `f2eabf4f:0:cb94`. Two different fulltext indexes can pick them up, and they are independent of each other.

**Search engine fulltext is handled for you.** The Marketplace package installs and activates a template that maps `imgh:bands` explicitly, which overrides the default dynamic mapping and removes the `copy_to` into the fulltext field. It detects the search client in use, covers OpenSearch 1.x, OpenSearch 2.x and Elasticsearch 9.x, and also covers the green index of a blue/green reindex. Nothing to configure.

The mapping is applied when an index is created, so on an existing deployment you must drop the index and reindex for it to take effect.

**Repository fulltext is handled for you too, but check the repository name.** By default Nuxeo puts every string property of every schema into `ecm:fulltextSimple`, including `imgh:bands`. Besides adding noise, it means each stored hash marks the document dirty for fulltext and schedules an extra extraction job per picture. The same template contributes the exclusion, picking the right form from `nuxeo.db.type`: MongoDB, PostgreSQL, SQL Server, Oracle, MySQL, MariaDB and H2 are covered. The contribution is purely additive, so the exclusions already declared by the Nuxeo database template, such as `thumb:thumbnail`, are preserved.

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
        </index>
      </fulltext>
    </indexing>
  </repository>
</extension>
```

Both `<excludeField>` entries are needed: `imgh:bands` suppresses the text extraction, `imgh:bands/*` suppresses the dirty-path trigger that schedules the job. Keep the `<index>` name as shown, it is not the same on the two backends: it has to match the one declared by the Nuxeo template, otherwise the contribution adds a second index instead of merging into the existing one.

## How to Build and Deploy

### Build and Install Locally

Clone the project and build it:

```bash
git clone https://github.com/nuxeo-sandbox/nuxeo-labs-images-dup
cd nuxeo-labs-images-dup
mvn clean install
```

To skip tests, append `-DskipTests` to the Maven command.

The marketplace package is produced at:

```text
nuxeo-labs-images-dup-package/target/nuxeo-labs-images-dup-package-2025.1.0.zip
```

Install the package on a Nuxeo server with:

```bash
nuxeoctl mp-install /path/to/nuxeo-labs-images-dup-package-2025.1.0.zip
```

### Install from the Nuxeo Marketplace

For a published release, add `nuxeo-labs-images-dup` to your Nuxeo Studio project's dependencies or include it in the `NUXEO_PACKAGES` environment variable when deploying with Docker.

## Support

**These features are not part of the Nuxeo Production platform.**

These solutions are provided for inspiration and we encourage customers to use them as code samples and learning resources.

This is a moving project (no API maintenance, no deprecation process, etc.) If any of these solutions are found to be useful for the Nuxeo Platform in general, they will be integrated directly into platform, not maintained here.

## License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)

## About Nuxeo

Nuxeo Platform is an open source highly scalable, cloud-native, enterprise content management product with rich multimedia support, written in Java. Data can be stored in both SQL & NoSQL databases.

The development of the Nuxeo Platform is mostly done by Nuxeo employees with an open development model.

The source code, documentation, roadmap, issue tracker, testing, benchmarks are all public.

More information is available at [Hyland/Nuxeo](https://www.hyland.com/en/solutions/products/nuxeo-platform).
