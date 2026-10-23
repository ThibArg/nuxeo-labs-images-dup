# nuxeo-labs-images-dup

> [!CAUTION]
> This is **work in progress**. Do not use as is.
>
> Once ready this plugin should be forked to [nuxeo-sandbox](https://github.com/nuxeo-sandbox) repository, and current repo will be archived.
>
> But it can take time to be ready, there is a lot of real-life chek to perform :-)

Nuxeo LTS 2025 plugin for detecting near-duplicate images on documents with the Picture facet. It computes and stores a perceptual hash when picture views are generated, then searches for similar images while respecting the current user's permissions.

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

The first available view is used for hashing. Changing the first view changes the hash signature; run the `computeImageHash` bulk action to update existing documents. `maxCandidates` should not exceed the search index's maximum result window.

### Search Backend

Similar images are found by querying the `imgh:bands` property through the Search service. What you have to set up depends on the backend serving that query:

- **MongoDB repository:** nothing to do. The plugin declares the required index and Nuxeo creates it automatically.
- **PostgreSQL repository (VCS):** this backend ignores the index declaration. Create the index manually, otherwise every search performs a full scan of the table:

  ```sql
  CREATE INDEX imgh_bands_item_idx ON imgh_bands (item);
  ```

  The `imgh_bands` table is created when the server starts with the plugin installed, so run the statement above after the first startup.
- **OpenSearch or Elasticsearch search client:** nothing to do, the property is mapped automatically. Be aware that band keys are also copied into the fulltext field, so they can show up in fulltext searches.

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
