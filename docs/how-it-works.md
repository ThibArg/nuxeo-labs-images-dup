# How It Works

[Back to the README](../README.md)

- [Distance, Radius and Threshold](#distance-radius-and-threshold)
- [Orientations](#orientations)
- [What a Search Costs](#what-a-search-costs)
- [Automatic Detection](#automatic-detection)
- [The Stored Hash](#the-stored-hash)
- [Picture Views Used for Hashing](#picture-views-used-for-hashing)

## Distance, Radius and Threshold

**Hamming distance** is the number of differing bits between two 64-bit hashes: `0` means identical hashes, and the lower the value, the more similar the images. `threshold` is the distance at or below which two images are considered duplicates.

**Search radius** is how far the candidate query looks around the hash. The hash is split into four 16-bit bands, and candidates are the documents sharing at least one band. A higher radius also queries the variants of each band that differ by 1 bit (`radius=1`) or by 1 or 2 bits (`radius=2`), which finds more candidates but queries many more keys: 4 keys at radius 0, 68 at radius 1, 548 at radius 2. A radius `r` is guaranteed to find every image within a distance of `4r + 3`.

**`threshold` and `radius` are coupled**: keep `threshold` close to `4 * radius + 3`, which is 3 at radius 0, 7 at radius 1 and 11 at radius 2. Beyond that distance a pair is found only when its differing bits happen not to be spread evenly across the four bands, so raising `threshold` alone widens the accepted distance without widening the search. The default pair, threshold `8` at radius `1`, sits one above the guarantee: around 5% of the pairs at a distance of exactly 8 are missed, which is acceptable on the last accepted distance. Raising `threshold` to 10 while leaving `radius` at 1 would miss roughly a quarter of the pairs it claims to accept, so raise `radius` to 2 as well, and pay the 548 keys.

## Orientations

**Orientations matter for phone photos.** A perceptual hash is not rotation invariant: a copy rotated by 90 degrees is as far from the original as an unrelated image, so only a search with `orientations` finds it. A common case produces such copies without anyone rotating anything: cameras and phones often store the pixels as the sensor captured them, with an EXIF tag telling viewers how to rotate them, and Nuxeo generates the picture views, and the plugin hashes them, without applying that tag. The same photo saved upright by an editor is then a rotation of it. Checked on an OpenSearch 2 deployment: a JPEG tagged with EXIF orientation 6 and the same image rotated in its pixels got hashes 34 bits apart, and only the search with orientations paired them, at a distance of 0. The automatic detection and the `detectImageDuplicates` bulk action do not compare orientations by default (`detectionOrientations`): set it to `true` to report such pairs. With the [distance hint](scaling.md#candidate-limit), it costs 84 ms instead of 20 ms per search at 10 million pictures.

## What a Search Costs

**A search starts from the stored hashes**, those of the 8 orientations included, so it reads no image. Only a picture that is not hashed yet, or hashed by an earlier version of the algorithm, has its view fetched and decoded for the search.

**`maxResults` does not make the search faster.** The candidate query and the distance comparison always run on the whole candidate set, then the results are sorted by distance and only then truncated. So `maxResults` only reduces the number of documents loaded from the repository and the size of the response. What drives the search cost is `radius` and `orientations`, which multiplies the number of queried keys by 8. See [Candidate Limit](scaling.md#candidate-limit) for what the number of candidates costs, and for `maxCandidates`.

`radius` and `orientations` multiply the number of keys queried, so the load each search puts on the search cluster. The configured `searchRadius` and `searchOrientations` are therefore a ceiling for users who are not administrators: with the defaults, they can search at radius 0 or 1, with or without orientations, and a request for radius 2 is refused (`403`). Administrators are not limited.

## Automatic Detection

`threshold` is shared by both paths, but the radius and the orientations are split: `detectionRadius` and `detectionOrientations` apply to the automatic detection that runs after picture views generation, `searchRadius` and `searchOrientations` to the `ImageHash.FindSimilar` operation, the Similar Images tab and its page provider. The defaults keep the automatic path cheaper than the explicit search.

**Duplicates arriving together are not detected on the spot.** The automatic detection searches the search index, and a picture enters it a moment after its hash is stored. Two copies hashed within that delay of each other, typically the same file uploaded twice in one import, do not see each other and no event is fired for either. The delay is the indexing latency: seconds on an idle platform, more during a mass import. The [`detectImageDuplicates` bulk action](api.md#detectimageduplicates) is the second pass that catches them.

`detectionEnabled` set to `false` switches the automatic detection off: pictures are still hashed after their views generation, but no search runs and no `duplicatedImage` event is fired. This is meant for a mass import, where the automatic detection costs one search per picture on a search cluster busy indexing, and misses the copies imported together anyway. The recipe is: import with `detectionEnabled` at `false`, wait for the indexing to catch up, then run the `detectImageDuplicates` bulk action on the imported pictures. The explicit search and that bulk action ignore this setting.

A duplicate search that fails during the automatic detection does not lose the hash, see [The Queried Index](search-backend.md#the-queried-index).

## The Stored Hash

The hash is stored in four properties of the `ImageHash` facet: `imgh:phash`, `imgh:bands`, `imgh:orientations`, which holds the hashes of the 8 orientations of the image, rotations and mirrors, and `imgh:signature`. They are declared `secured`: only administrators and the system can write them, so a user who can edit a picture cannot blank or forge its hash to hide it from detection. Custom code calling `ImageHashService.updateHash` must therefore run under an administrator or system login.

Each hash is stored with a signature, the version of the algorithm that computed it, currently `phash64v2`. Hashes stored with another signature are never compared: they are ignored by every search until they are recomputed. So after upgrading from a build that stored hashes with another signature, `phash64v1/Small` for example, run the `computeImageHash` bulk action on all the pictures; `force` is not needed, a hash with an outdated signature is always recomputed. Until then, the pictures hashed before the upgrade are not found as duplicates.

Storing the hash is a second save of the picture, after the one of its views. It creates no version, does not change the modification date or the last contributor, writes no audit entry and sends no notification. Two things cannot be avoided, though: the picture is indexed a second time in the search engine, and the save fires `beforeDocumentModification` and `documentModified` like any other, so event handlers on document modification, Studio ones included, run once more per picture, as the system user.

A picture that cannot be hashed does not stop the others. A view that cannot be decoded is logged and the next configured view is tried; a picture with none of the configured views readable is left without a hash, and a warning is logged, see [Picture Views Used for Hashing](#picture-views-used-for-hashing). In the bulk action, a picture whose hash cannot be saved is logged and counted in the `errorCount` of the command status, and the rest of its batch is still processed.

## Picture Views Used for Hashing

The plugin does not hash `file:content`: it hashes one of the picture views Nuxeo generates from it, the first one of `views` that the picture has and that can be decoded. Views are looked up by name, the `id` of the `pictureConversion` that generates them, never by size.

**Keep `Small` first, as by default.** Hashing reduces the image to 32x32 levels of gray, so a larger view brings nothing but decoding time: the JPEG decoder processes every pixel of the view, 12 times more for `FullHD` than for `Small` and about 100 times more for a 24-megapixel `OriginalJpeg`, before the plugin keeps a fraction of them. Too small is not good either: on a view much below 256 pixels, such as `Thumbnail`, compression artifacts are as large as the 32x32 cells and can flip bits. The plugin reads views at about 256 pixels on their smaller side.

**If `Small` is overridden or not generated**, put first in `views` a view whose smaller side is between 256 and 512 pixels. Two things to know when you contribute `views`:

- **The list replaces the default one**, it is not added to it: list every view you want, in order of preference.
- **A picture with none of the listed views is not hashed.** It is never found as a duplicate, and a warning naming the configured views is logged for it.

A view dedicated to the plugin is possible, a `pictureConversion` of your own listed first in `views`, but it does not make anything faster: Nuxeo computes every view from the original, so one more view costs one more decoding of the original by ImageMagick, far more than the plugin saves. It is only worth it to change what the hash sees, for instance with an ImageMagick command applying `-auto-orient` (see [Orientations](#orientations)).

The same image hashed from two views of different sizes gets hashes a few bits apart, which uses part of the threshold. Hashes computed before a change of `views` stay valid and are still compared, but to hash every picture the same way again, run the `computeImageHash` bulk action with `force` set to `true`.
