# Scaling and Limits

[Back to the README](../README.md)

- [Candidate Limit](#candidate-limit)
- [What About Hundreds of Millions of Pictures?](#what-about-hundreds-of-millions-of-pictures)

## Candidate Limit

A search first selects the candidates, the documents sharing a band key with the image (see [Search radius](how-it-works.md#distance-radius-and-threshold)). Each queried key matches about one hashed picture out of 65536, so the number of candidates grows with the repository, around `keys * pictures / 65536`. It is the best case: measured with uniformly random hashes, the counts match that estimate within 3%, and real photos, whose hashes are less evenly spread, give more.

**With the distance hint, the default**, the search engine compares the hash of every candidate with the image and returns only the documents within `threshold`. Nothing is fetched for the other candidates, and `maxCandidates`, 10000 by default, caps the number of matches, which only an image with thousands of near copies reaches. The hint is registered by the Marketplace template for OpenSearch 1.x and 2.x and Elasticsearch 7.x to 9.x, see [Search Backend](search-backend.md#distance-hint). Measured through Nuxeo on a single OpenSearch 2.19 node with 4 GB of heap, 10 million hashed pictures, one search at a time, as an administrator:

| Search | Keys | Candidates at 10 million | Median latency | Complete |
|---|---|---|---|---|
| Automatic detection, radius 1 | 68 | 10 350 | 20 ms | yes |
| Tab and operation defaults, radius 1 with orientations | 544 | 82 700 | 84 ms | yes |
| Radius 2 | 548 | 83 300 | 81 ms | yes |
| Radius 2 with orientations | 4384 | 649 000 | 454 ms | yes |

A user who is not an administrator, whose searches carry the permission filter, measured within 3 ms of these figures.

**Without the distance hint**, on another search stack or with `nuxeo.labs.images.dup.search.hint=false`, the band query alone runs: Nuxeo fetches up to `maxCandidates` candidates and compares the hashes itself. Each fetched candidate costs about 25 microseconds, and the limit is reached around (measured with uniform hashes, so later than with real photos):

| Search | Keys | Limit of 10000 reached around |
|---|---|---|
| Automatic detection, radius 1 | 68 | 9.7 million pictures |
| Tab and operation defaults, radius 1 with orientations | 544 | 1.2 million pictures |
| Radius 2 | 548 | 1.2 million pictures |
| Radius 2 with orientations | 4384 | 150000 pictures |

Beyond the limit, whether it counts candidates or matches, the result is **truncated**: some similar images may be missing. The plugin then does two things.

- It says so. The operation sets the `imageHashTruncated` context variable, the page provider flags each returned document through the `imageSimilarity` enricher, the tab displays a warning, the `duplicatedImage` event carries `duplicatesTruncated`, and a warning is logged.
- It chooses what is dropped. The documents are fetched again from the closest keys outwards: first those sharing an exact band with the hash, then the 1-bit variants, then the 2-bit ones, each with what is left of the limit. So every image within a distance of 3 is still found as long as the exact bands alone fit in the limit, and the documents dropped are the least likely duplicates. This costs up to three more queries, only for the searches that reach the limit.

Without the hint, a truncated search took about 500 ms through Nuxeo on the same server, and missed real duplicates: at 5 million pictures, the default tab search lost copies at distances 5 to 7 that the search with the hint returned. To get complete results on a large repository without the hint, lower `radius` or turn `orientations` off, which divides the number of keys by 8. Raising `maxCandidates` is only possible up to the index `max_result_window`.

## What About Hundreds of Millions of Pictures?

The duplicate search was load tested up to 10 million pictures. The search index was filled directly with 10 million documents shaped like hashed pictures, without storing 10 million images: evaluating the search only needs the hashes in the index, not the images. The searches ran through Nuxeo, from real Picture documents, on a single OpenSearch 2.19 node with one shard and 4 GB of heap, in Docker on a MacBook. With the distance hint, the default searches answered in under 100 ms, the most expensive setting (radius 2 with orientations) in about half a second, and every planted near duplicate was found. See [Candidate Limit](#candidate-limit) for the figures.

We are therefore confident the plugin handles a few tens of millions of pictures. The hashes of that test were random, which spreads them evenly: real photos look more alike and give each search more work, so validate with your own pictures.

Beyond that, it is mostly a matter of infrastructure. Nothing in the plugin has a limit in that range, and the search engine splits each search across the shards of the index, so a cluster with more nodes and shards keeps the response time down. The work of each search still grows with the number of pictures, though, so at hundreds of millions:

- prefer cheaper defaults for the tab and the operation, such as `searchOrientations` set to `false`;
- run `detectImageDuplicates` on what was just imported, not on the whole repository: it costs one search per picture;
- consider a lower `threshold`: the more pictures, the more unrelated pairs fall within a given distance by chance.

All of this requires the distance hint (see [Search Backend](search-backend.md#distance-hint)): without it, the default tab search is truncated from about 1.2 million pictures.
