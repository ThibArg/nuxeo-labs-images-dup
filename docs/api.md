# API Reference

[Back to the README](../README.md)

- [The `duplicatedImage` Event](#the-duplicatedimage-event)
- [The `ImageHash.FindSimilar` Operation](#the-imagehashfindsimilar-operation)
- [Bulk Actions](#bulk-actions)
- [Web UI](#web-ui)
- [REST API](#rest-api)

## The `duplicatedImage` Event

After installation of the plugin, new or updated Picture documents are hashed automatically after their picture views are generated. When a duplicate is detected, the plugin fires the `duplicatedImage` event on the picture, with three properties:

* `duplicates`, a map of duplicate document id to Hamming distance, closest first and limited to the 100 closest
* `duplicatesCount`, the number of duplicates found
* and `duplicatesTruncated`, `true` when the search itself may be incomplete, see [Candidate Limit](scaling.md#candidate-limit). The duplicates listed are those readable by the user who triggered the views generation: a listener forwarding the list to someone else may disclose documents that person cannot read.

Two copies uploaded together may not see each other, see [Automatic Detection](how-it-works.md#automatic-detection).

## The `ImageHash.FindSimilar` Operation

To search for similar images, call the `ImageHash.FindSimilar` Automation operation with a Picture document as input. Optional parameters are:

| Parameter | Description | When unset |
|-----------|-------------|------------|
| `threshold` | Maximum Hamming distance, from 0 to 64. | `<threshold>`, default `8` |
| `radius` | Search radius, from 0 to 2. Higher values find more candidates but cost more. | `<searchRadius>`, default `1` |
| `orientations` | Also compare images rotated by 90, 180 or 270 degrees, and mirrored images. No other angle, see [Orientations](how-it-works.md#orientations). | `<searchOrientations>`, default `true` |
| `maxResults` | Maximum returned documents, at least 1. | `50`, not configurable |

The first three fall back to the [configuration](../README.md#configuration)

> [!TIP]
> The recommended way to change them for every caller is to contribute, not to pass them on each call.

Out-of-range values are rejected as a bad request (`400`), the same way by the operation, the page provider and the Java service.

A user who is not an administrator cannot search beyond the configured `searchRadius` and `searchOrientations`: the request is refused (`403`), see [What a Search Costs](how-it-works.md#what-a-search-costs).

The operation sets the `imageHashTruncated` context variable: `true` when the returned list may be incomplete, see [Candidate Limit](scaling.md#candidate-limit).

What `threshold`, `radius` and `orientations` mean, and how to choose them, is explained in [How It Works](how-it-works.md#distance-radius-and-threshold).

## Bulk Actions

Both actions can only be launched by administrators.

### `computeImageHash`

To compute or refresh hashes for existing documents, submit the `computeImageHash` bulk action with a query selecting Picture documents. Set its optional `force` parameter to `true` to recompute hashes that already use the current configuration. This action fires no `duplicatedImage` event.

### `detectImageDuplicates`

To detect duplicates among pictures that are already hashed, submit the `detectImageDuplicates` bulk action once they are indexed, on demand or from a scheduler: after an import, to catch the copies that arrived together, or after `computeImageHash`. For example, for the pictures created since a given date:

```
POST /nuxeo/api/v1/automation/Bulk.RunAction
{"params": {"action": "detectImageDuplicates", "query": "SELECT * FROM Document WHERE ecm:mixinType = 'Picture' AND dc:created >= DATE '2026-11-01'"}}
```

It computes nothing: for each selected picture hashed with the current configuration it runs the detection search and fires `duplicatedImage` when duplicates are found. The others are skipped. Three things differ from the automatic detection:

- the duplicates listed are those readable by the user who runs the action, an administrator, not by the user who uploaded the picture;
- a pair is reported from both sides when both pictures are selected, and pairs reported earlier are reported again, so listeners of `duplicatedImage` must cope with repetitions;
- a failing search counts in the `errorCount` of the command status. The number of pictures with duplicates is in its `result`, under `picturesWithDuplicates`.

## Web UI

With Web UI, documents with the Picture facet get a **Similar Images** tab. Web UI is only an optional dependency of the package: without it, everything else works the same.

The tab lists the near duplicates the current user can read, closest first, in the standard grid (thumbnails) and list views. The list view shows the Hamming distance of each document.

The toolbar at the top tunes the search with the same parameters as the operation: **Threshold**, **Radius**, **Orientations** and **Max results**. An empty field, or **Default**, uses the configured value. The toolbar offers every value to every user: a user who is not an administrator and picks more than the configured radius or orientations gets the generic search error. Click **Search** or press Enter to run the search, **Reset** goes back to the configured values. Nothing is searched until the tab is displayed.

A warning is displayed above the list when the search reached the [candidate limit](scaling.md#candidate-limit) and the list may be incomplete. The warning travels with the listed documents, so it cannot be shown when the list is empty.

## REST API

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

Add the `imageSimilarity` enricher, for example with the `enrichers-document: imageSimilarity` header, on the page provider or on the operation, to get the distance of each document in `contextParameters.imageSimilarity.distance`. The same object holds `truncated`, identical for every document of a response: `true` when the search reached the [candidate limit](scaling.md#candidate-limit) and the result may be incomplete.
