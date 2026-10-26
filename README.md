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

| Parameter | Description |
|-----------|-------------|
| `threshold` | Maximum Hamming distance, from 0 to 64. |
| `radius` | Search radius, from 0 to 2. Higher values find more candidates but cost more. |
| `orientations` | Also compare rotated and mirrored images. |
| `maxResults` | Maximum returned documents; defaults to 50. |

To compute or refresh hashes for existing documents, submit the `computeImageHash` bulk action with a query selecting Picture documents. Set its optional `force` parameter to `true` to recompute hashes that already use the current configuration.

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

The first available view is used for hashing. Changing the first view changes the hash signature; run the `computeImageHash` bulk action to update existing documents.

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

**Repository fulltext is not handled, and you should configure it.** By default Nuxeo puts every string property of every schema into `ecm:fulltextSimple`, including `imgh:bands`. Besides adding noise, it means each stored hash marks the document dirty for fulltext and schedules an extra extraction job per picture. Contribute the exclusion matching your repository:

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

Both forms are needed: `imgh:bands` suppresses the text extraction, `imgh:bands/*` suppresses the dirty-path trigger that schedules the job. Match the repository name to your own, and keep the `<index>` name as shown: it has to match the one declared by the Nuxeo template, otherwise the contribution adds a second index instead of merging into the existing one.

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
