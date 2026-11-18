# nuxeo-labs-images-dup

> [!CAUTION]
> This is **work in progress**. Do not use as is.
>
> Once ready this plugin should be forked to the [nuxeo-sandbox](https://github.com/nuxeo-sandbox) organization, and the current repository will be archived.

- A Nuxeo LTS 2025 plugin that **detects near-duplicate images** on documents with the `Picture` facet.

## TL;DR

- **Hashes every picture.** When the picture views of a document are generated, a 64-bit perceptual hash is computed and stored on the document. Two images that look alike get hashes that differ by a few bits.
- **Detects duplicates automatically.** Right after hashing, the plugin searches for similar pictures and fires the `duplicatedImage` event when it finds some.
- **Lets users search.** Picture documents get a **Similar Images** tab in Web UI. The same search is available as the `ImageHash.FindSimilar` Automation operation and as a REST page provider.
- **Respects permissions.** A search only returns the documents the current user can read.
- **Handles existing content.** Two bulk actions hash the pictures already in the repository and detect duplicates among them.

> [!IMPORTANT]
> By essence, this plugin is complex. With listeners, custom schema, cusotm search mapping, etc.
>
> It is a **Nuxeo Presales Sandbox plugin**, showing art of the possible but not included as part of the
> Nuxeo Platform and, so, **not supported**. So, if you need it **we strongly recommend a Nuxeo Partner
> forks the code and takes ownership** (tuning, maintenance, support)

## Requirements

- Nuxeo LTS 2025.
- A **search engine**: the duplicate search runs on the search index, never on the repository.

| Search stack | Package | Status |
|---|---|---|
| OpenSearch 1.x, Elasticsearch 7.x–8.x | `nuxeo-search-client-opensearch1` |
| OpenSearch 2.x | `nuxeo-search-client-opensearch2` |
| Elasticsearch 9.x | `nuxeo-search-client-elasticsearch9` |

MongoDB Atlas Search is not supported. Details in [Search Backend](docs/search-backend.md).

> [!NOTE]
> * OpenSearch 1.x, Elasticsearch 7.x–8 are covered by automated tests
> * OpenSearch 2.x was checked manually (and a manual test on 10 millions index entries)

- Web UI is optional: without it, there is no tab and everything else works the same.

## Install

Add `nuxeo-labs-images-dup` to the dependencies of your Nuxeo Studio project, or to the `NUXEO_PACKAGES` environment variable of a Docker deployment (for a published release), or install a [locally built](#build) package:

```bash
nuxeoctl mp-install /path/to/nuxeo-labs-images-dup-package-<version>.zip
```

Two things to check at install time:

- **The search index must be created after the package is installed.** If it existed before, recreate it with a full reindex, see [Index Mapping](docs/search-backend.md#index-mapping).

> [!IMPORTANT]
> It is not a _reindexing_, it is about _recreating the index_ (and then reindex)

- **If your repository is not named `default`**, set `nuxeo.labs.images.dup.fulltext.repository` in `nuxeo.conf`, otherwise the server fails to start, see [nuxeo.conf](#nuxeoconf).

## Quick Start

#### **New pictures**

They need nothing: they are hashed after their views are generated, and `duplicatedImage` is fired when a duplicate is found.

#### **In Web UI**

Open a picture and select the **Similar Images** tab.

#### **From code**

Call the `ImageHash.FindSimilar` operation with a Picture document as input. All parameters are optional (default values set in XML contribution):

| Parameter | Description | Default |
|---|---|---|
| `threshold` | Maximum distance between two hashes, 0 to 64. Lower is stricter. | `8` |
| `radius` | How wide the search looks, 0 to 2. Higher finds more and costs more. | `1` |
| `orientations` | Also find copies rotated by 90, 180 or 270 degrees, and mirrored copies. No other angle. | `true` |
| `maxResults` | Maximum number of documents returned. | `50` |

#### **Existing pictures**

They are hashed by the `computeImageHash` bulk action, as an administrator:

```
POST /nuxeo/api/v1/automation/Bulk.RunAction
{"params": {"action": "computeImageHash", "query": "SELECT * FROM Document WHERE ecm:mixinType = 'Picture'"}}
```

It fires no event. To then detect the duplicates among hashed pictures, run the `detectImageDuplicates` bulk action the same way, once the pictures are indexed.

The event, the operation, the bulk actions, the tab and the REST page provider are described in the [API Reference](docs/api.md).

## Configuration

Contribute to the `configuration` extension point of `nuxeo.labs.images.dup.ImageHashService`. All elements are optional and contributions are merged. The values below are the defaults:

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

| Element | Role | More |
|---|---|---|
| `views` | Picture views to hash, the first readable one is used. | [Picture Views](docs/how-it-works.md#picture-views-used-for-hashing) |
| `threshold` | Distance at or below which two images are duplicates. | [Distance, Radius and Threshold](docs/how-it-works.md#distance-radius-and-threshold) |
| `detectionEnabled` | `false` hashes pictures without searching for duplicates, for a mass import. | [Automatic Detection](docs/how-it-works.md#automatic-detection) |
| `detectionRadius`, `detectionOrientations` | Radius and orientations of the automatic detection. | [Automatic Detection](docs/how-it-works.md#automatic-detection) |
| `searchRadius`, `searchOrientations` | Radius and orientations of the tab, the operation and the page provider, when the caller passes none. Also the maximum for users who are not administrators. | [What a Search Costs](docs/how-it-works.md#what-a-search-costs) |
| `maxCandidates` | Maximum number of documents a search reads from the index. Must not exceed the index `max_result_window`. | [Candidate Limit](docs/scaling.md#candidate-limit) |

> [!TIP]
> About `views`, a good size is an image of 256 to 512 pixels in its smallesside.

### nuxeo.conf

Three settings live in `nuxeo.conf`, because the template installed by the package needs them before the platform starts:

| Property | Default | Role |
|---|---|---|
| `nuxeo.labs.images.dup.fulltext.repository` | `default` | Repository the fulltext exclusion is contributed to. **Must be changed if your repository is not named `default`**, see [Keeping the Hash Properties Out of Fulltext](docs/search-backend.md#keeping-the-hash-properties-out-of-fulltext) |
| `nuxeo.labs.images.dup.fulltext.exclude` | `true` | Set to `false` to not contribute the exclusion at all. (Should stay `true` except in testing) |
| `nuxeo.labs.images.dup.search.hint` | `true` | Set to `false` on a search cluster where scripting is disabled, see [Distance Hint](docs/search-backend.md#distance-hint) |

## Good to Know

- **Copies uploaded together do not see each other.** The automatic detection misses two copies imported at the same time. Run `detectImageDuplicates` after an import. [More](docs/how-it-works.md#automatic-detection)
- **A rotated copy is only found with `orientations`**, and phone photos are often rotated copies without anyone knowing. The automatic detection does not compare orientations by default. [More](docs/how-it-works.md#orientations)
- **Only quarter turns and mirrors are found.** A copy rotated by any other angle is past the default `threshold` after 1 or 2 degrees, and as far from the original as an unrelated image a few degrees further. [More](docs/how-it-works.md#orientations)
- **`threshold` and `radius` go together.** Raising `threshold` alone does not find more. [More](docs/how-it-works.md#distance-radius-and-threshold)
- **A result can be incomplete on a very large repository, and it is always reported**: a warning in the tab, a flag on the operation, the event and the REST response. [More](docs/scaling.md#candidate-limit)
- **Users who are not administrators cannot search wider than the configuration**: a larger radius is refused. [More](docs/how-it-works.md#what-a-search-costs)
- **After an upgrade that changes the hash algorithm**, run `computeImageHash` on all the pictures: older hashes are ignored until then. [More](docs/how-it-works.md#the-stored-hash)
- **Storing the hash saves the picture a second time**, without version, audit entry or change of modification date, but event handlers on document modification run once more. [More](docs/how-it-works.md#the-stored-hash)

## Documentation

| Page | Content |
|---|---|
| [How It Works](docs/how-it-works.md) | Distance, radius and threshold, orientations, automatic detection, the stored hash, the picture views used for hashing |
| [API Reference](docs/api.md) | The `duplicatedImage` event, the operation, the bulk actions, the Web UI tab, the REST page provider |
| [Search Backend](docs/search-backend.md) | Supported stacks, distance hint, index mapping, keeping the hash properties out of fulltext |
| [Scaling and Limits](docs/scaling.md) | Candidate limit, load test figures, what to expect with hundreds of millions of pictures |

## Build

```bash
git clone https://github.com/nuxeo-sandbox/nuxeo-labs-images-dup
cd nuxeo-labs-images-dup
mvn clean install
```

To skip tests, append `-DskipTests` to the Maven command.

The marketplace package is produced at:

```text
nuxeo-labs-images-dup-package/target/nuxeo-labs-images-dup-package-<version>.zip
```

# Support

**These features are not part of the Nuxeo Production platform.**

These solutions are provided for inspiration and we encourage customers to use them as code samples and learning
resources.

> [!TIP]
> If you need this feature,  **we strongly recommend a Nuxeo Partner or your dev team forks the code
> and takes ownership** (tuning, maintenance, support)

## License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

## About Nuxeo

[Nuxeo](https://www.hyland.com/en/products/nuxeo-platform), developed by [Hyland](https://www.hyland.com),
is a Content Services platform. It provides an easy-to-use, highly customizable and extensible
architecture for building content-centric applications.
