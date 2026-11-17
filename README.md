# nuxeo-labs-images-dup

> [!CAUTION]
> This is **work in progress**. Do not use as is.
>
> Once ready this plugin should be forked to [nuxeo-sandbox](https://github.com/nuxeo-sandbox) repository, and current repo will be archived.
>
> But it can take time to be ready, there is a lot of real-life check to perform :-)

Nuxeo LTS 2025 plugin for detecting near-duplicate images on documents with the Picture facet. It computes and stores a perceptual hash when picture views are generated, then searches for similar images while respecting the current user's permissions.

The duplicate search runs on a search engine client (OpenSearch or Elasticsearch), see [Search Backend](#search-backend).

## How to Use

Install the Marketplace package. New or updated Picture documents are hashed automatically after their picture views are generated. When a duplicate is detected, the plugin fires the `duplicatedImage` event on the picture, with three properties: `duplicates`, a map of duplicate document id to Hamming distance, closest first and limited to the 100 closest, `duplicatesCount`, the number of duplicates found, and `duplicatesTruncated`, `true` when the search itself may be incomplete, see [Candidate Limit](#candidate-limit). The duplicates listed are those readable by the user who triggered the views generation: a listener forwarding the list to someone else may disclose documents that person cannot read.

To search for similar images, call the `ImageHash.FindSimilar` Automation operation with a Picture document as input. Optional parameters are:

| Parameter | Description | When unset |
|-----------|-------------|------------|
| `threshold` | Maximum Hamming distance, from 0 to 64. | `<threshold>`, default `8` |
| `radius` | Search radius, from 0 to 2. Higher values find more candidates but cost more. | `<searchRadius>`, default `1` |
| `orientations` | Also compare rotated and mirrored images. | `<searchOrientations>`, default `true` |
| `maxResults` | Maximum returned documents, at least 1. | `50`, not configurable |

The first three fall back to the [configuration](#configuration), so the usual way to change them for every caller is to contribute, not to pass them on each call.

Out-of-range values are rejected as a bad request (`400`), the same way by the operation, the page provider and the Java service.

`radius` and `orientations` multiply the number of keys queried, so the load each search puts on the search cluster. The configured `searchRadius` and `searchOrientations` are therefore a ceiling for users who are not administrators: with the defaults, they can search at radius 0 or 1, with or without orientations, and a request for radius 2 is refused (`403`). Administrators are not limited.

The operation sets the `imageHashTruncated` context variable: `true` when the returned list may be incomplete, see [Candidate Limit](#candidate-limit).

**Hamming distance** is the number of differing bits between two 64-bit hashes: `0` means identical hashes, and the lower the value, the more similar the images. `threshold` is the distance at or below which two images are considered duplicates.

**Search radius** is how far the candidate query looks around the hash. The hash is split into four 16-bit bands, and candidates are the documents sharing at least one band. A higher radius also queries the variants of each band that differ by 1 bit (`radius=1`) or by 1 or 2 bits (`radius=2`), which finds more candidates but queries many more keys: 4 keys at radius 0, 68 at radius 1, 548 at radius 2. A radius `r` is guaranteed to find every image within a distance of `4r + 3`.

**`threshold` and `radius` are coupled**: keep `threshold` close to `4 * radius + 3`, which is 3 at radius 0, 7 at radius 1 and 11 at radius 2. Beyond that distance a pair is found only when its differing bits happen not to be spread evenly across the four bands, so raising `threshold` alone widens the accepted distance without widening the search. The default pair, threshold `8` at radius `1`, sits one above the guarantee: around 5% of the pairs at a distance of exactly 8 are missed, which is acceptable on the last accepted distance. Raising `threshold` to 10 while leaving `radius` at 1 would miss roughly a quarter of the pairs it claims to accept, so raise `radius` to 2 as well, and pay the 548 keys.

**A search starts from the stored hashes**, those of the 8 orientations included, so it reads no image. Only a picture that is not hashed yet, or hashed by an earlier version of the algorithm, has its view fetched and decoded for the search.

**`maxResults` does not make the search faster.** The candidate query and the distance comparison always run on the whole candidate set, then the results are sorted by distance and only then truncated. So `maxResults` only reduces the number of documents loaded from the repository and the size of the response. What drives the search cost is `radius` and `orientations`, which multiplies the number of queried keys by 8. The hard limit on the candidate set is `maxCandidates`, see [Candidate Limit](#candidate-limit).

**Duplicates arriving together are not detected on the spot.** The automatic detection searches the search index, and a picture enters it a moment after its hash is stored. Two copies hashed within that delay of each other, typically the same file uploaded twice in one import, do not see each other and no event is fired for either. The delay is the indexing latency: seconds on an idle platform, more during a mass import. The `detectImageDuplicates` bulk action is the second pass that catches them, see below.

To compute or refresh hashes for existing documents, submit the `computeImageHash` bulk action with a query selecting Picture documents. Set its optional `force` parameter to `true` to recompute hashes that already use the current configuration. This action fires no `duplicatedImage` event.

To detect duplicates among pictures that are already hashed, submit the `detectImageDuplicates` bulk action once they are indexed, on demand or from a scheduler: after an import, to catch the copies that arrived together, or after `computeImageHash`. For example, for the pictures created since a given date:

```
POST /nuxeo/api/v1/automation/Bulk.RunAction
{"params": {"action": "detectImageDuplicates", "query": "SELECT * FROM Document WHERE ecm:mixinType = 'Picture' AND dc:created >= DATE '2026-11-01'"}}
```

It computes nothing: for each selected picture hashed with the current configuration it runs the detection search and fires `duplicatedImage` when duplicates are found. The others are skipped. Three things differ from the automatic detection:

- the duplicates listed are those readable by the user who runs the action, an administrator, not by the user who uploaded the picture;
- a pair is reported from both sides when both pictures are selected, and pairs reported earlier are reported again, so listeners of `duplicatedImage` must cope with repetitions;
- a failing search counts in the `errorCount` of the command status. The number of pictures with duplicates is in its `result`, under `picturesWithDuplicates`.

Like `computeImageHash`, it can only be launched by administrators.

The hash is stored in four properties of the `ImageHash` facet: `imgh:phash`, `imgh:bands`, `imgh:orientations`, which holds the hashes of the 8 orientations of the image, rotations and mirrors, and `imgh:signature`. They are declared `secured`: only administrators and the system can write them, so a user who can edit a picture cannot blank or forge its hash to hide it from detection. Custom code calling `ImageHashService.updateHash` must therefore run under an administrator or system login.

Storing the hash is a second save of the picture, after the one of its views. It creates no version, does not change the modification date or the last contributor, writes no audit entry and sends no notification. Two things cannot be avoided, though: the picture is indexed a second time in the search engine, and the save fires `beforeDocumentModification` and `documentModified` like any other, so event handlers on document modification, Studio ones included, run once more per picture, as the system user.

A picture that cannot be hashed does not stop the others. A view that cannot be decoded is logged and the next configured view is tried; a picture with no readable view is left without a hash. In the bulk action, a picture whose hash cannot be saved is logged and counted in the `errorCount` of the command status, and the rest of its batch is still processed.

### Web UI

With Web UI, documents with the Picture facet get a **Similar Images** tab. Web UI is only an optional dependency of the package: without it, everything else works the same.

The tab lists the near duplicates the current user can read, closest first, in the standard grid (thumbnails) and list views. The list view shows the Hamming distance of each document.

The toolbar at the top tunes the search with the same parameters as the operation: **Threshold**, **Radius**, **Orientations** and **Max results**. An empty field, or **Default**, uses the configured value. The toolbar offers every value to every user: a user who is not an administrator and picks more than the configured radius or orientations gets the generic search error. Click **Search** or press Enter to run the search, **Reset** goes back to the configured values. Nothing is searched until the tab is displayed.

A warning is displayed above the list when the search reached the [candidate limit](#candidate-limit) and the list may be incomplete. The warning travels with the listed documents, so it cannot be shown when the list is empty.

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

The usual paging parameters (`pageSize`, `currentPageIndex`, `offset`) apply, and invalid values return a `400`. `resultsCount` is an upper bound: a document deleted or made unreadable since it was indexed is skipped when its page is loaded. Each page request runs the whole search again, so a `pageSize` covering `limit` avoids repeating it.

Add the `imageSimilarity` enricher, for example with the `enrichers-document: imageSimilarity` header, on the page provider or on the operation, to get the distance of each document in `contextParameters.imageSimilarity.distance`. The same object holds `truncated`, identical for every document of a response: `true` when the search reached the [candidate limit](#candidate-limit) and the result may be incomplete.

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
      <detectionEnabled>true</detectionEnabled>
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

`detectionEnabled` set to `false` switches the automatic detection off: pictures are still hashed after their views generation, but no search runs and no `duplicatedImage` event is fired. This is meant for a mass import, where the automatic detection costs one search per picture on a search cluster busy indexing, and misses the copies imported together anyway. The recipe is: import with `detectionEnabled` at `false`, wait for the indexing to catch up, then run the `detectImageDuplicates` bulk action on the imported pictures. The explicit search and that bulk action ignore this setting.

The first available view of the list is used for hashing. A perceptual hash does not depend on the size of the image it is computed from, so pictures hashed from different views are compared with each other, and changing `views` does not invalidate the hashes already stored.

Each hash is stored with a signature, the version of the algorithm that computed it, currently `phash64v2`. Hashes stored with another signature are never compared: they are ignored by every search until they are recomputed. So after upgrading from a build that stored hashes with another signature, `phash64v1/Small` for example, run the `computeImageHash` bulk action on all the pictures; `force` is not needed, a hash with an outdated signature is always recomputed. Until then, the pictures hashed before the upgrade are not found as duplicates.

Two settings live in `nuxeo.conf` rather than in the extension point, because the template installed by the Marketplace package needs them before the platform starts:

| Property | Default | Role |
|---|---|---|
| `nuxeo.labs.images.dup.fulltext.repository` | `default` | Repository the fulltext exclusion is contributed to. **Must be changed if your repository is not named `default`**, see [Keeping the Hash Properties Out of Fulltext](#keeping-the-hash-properties-out-of-fulltext) |
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

To check a deployment manually, index a picture, then run a fulltext search on one of the values of its `imgh:bands` property, for example `f2eabf4f:0:cb94`, on its `imgh:phash` value, and on its `imgh:signature` value, `phash64v2`. None must return the picture: otherwise the mapping template was not applied and the hash properties are polluting the fulltext index. This is exactly what the automated tests assert on OpenSearch 1.x.

The query always targets the search index, never the repository, so no MongoDB or PostgreSQL index is needed. Without a search engine client, hashes are still computed and stored, but duplicate detection is disabled and `ImageHash.FindSimilar` fails with an explicit error.

The index actually queried is the one declared as default for the repository, so a blue/green reindex keeps working: the plugin follows the blue index until you swap.

`maxCandidates` must not exceed the index `max_result_window`, which defaults to 10000. The plugin default is exactly 10000, so lower it if you lower the window: above the window, the search engine rejects every candidate query.

When the duplicate search fails during the automatic detection, because of such a rejection or because the search cluster is unavailable, the hash is still stored and the failure is logged as an error, but the `duplicatedImage` event is not fired for that picture and the detection is not replayed later.

### Candidate Limit

A search examines at most `maxCandidates` documents, 10000 by default. The number of candidates grows with the repository: each queried key matches about one hashed picture out of 65536, so a search fetches around `keys * pictures / 65536` candidates, and more on real photos, whose hashes are less evenly spread than this estimate assumes.

| Search | Keys | Limit of 10000 reached around |
|---|---|---|
| Automatic detection, radius 1 | 68 | 9.6 million pictures |
| Tab and operation defaults, radius 1 with orientations | 544 | 1.2 million pictures |
| Radius 2 | 548 | 1.2 million pictures |
| Radius 2 with orientations | 4384 | 150000 pictures |

Beyond the limit the result is **truncated**: some similar images may be missing. The plugin then does two things.

- It says so. The operation sets the `imageHashTruncated` context variable, the page provider flags each returned document through the `imageSimilarity` enricher, the tab displays a warning, the `duplicatedImage` event carries `duplicatesTruncated`, and a warning is logged.
- It chooses what is dropped. The candidates are fetched again from the closest keys outwards: first the documents sharing an exact band with the hash, then the 1-bit variants, then the 2-bit ones, each with what is left of the limit. So every image within a distance of 3 is still found as long as the exact bands alone fit in the limit, and the candidates dropped are the least likely duplicates. This costs up to three more queries, only for the searches that reach the limit.

To get complete results on a large repository, lower `radius` or turn `orientations` off, which divides the number of keys by 8. Raising `maxCandidates` is only possible up to the index `max_result_window`.

### Keeping the Hash Properties Out of Fulltext

The plugin stores four technical properties on each picture: the band keys in `imgh:bands`, opaque strings such as `f2eabf4f:0:cb94`, the hash in `imgh:phash`, the hashes of its 8 orientations in `imgh:orientations`, and `imgh:signature`, the version of the hash algorithm, for example `phash64v2`. None of them is content. Left in fulltext, every hashed picture matches a search for its signature, and early versions of this plugin, whose signature ended with the name of the hashed view, made a search for "small" return all of them. Two different fulltext indexes can pick them up, and they are independent of each other.

**Search engine fulltext is handled for you.** The Marketplace package installs and activates a template that maps the four `imgh:*` properties explicitly, which overrides the default dynamic mapping and removes the `copy_to` into the fulltext field. It detects the search client in use, covers OpenSearch 1.x, OpenSearch 2.x and Elasticsearch 9.x, and also covers the green index of a blue/green reindex. Nothing to configure.

The mapping is applied when an index is created, so on an existing deployment you must drop the index and reindex for it to take effect. This also applies when upgrading from a build of this plugin that only mapped `imgh:bands`.

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
