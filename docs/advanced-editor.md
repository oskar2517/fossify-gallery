# Advanced photo editor

Choose **Advanced Editor** in Gallery's **Edit** chooser. It is included in both
the `foss` and `gplay` variants and uses the existing Apache-licensed Android Image
Cropper plus Android bitmap/Canvas APIs. No proprietary editor SDK is required.

- **Crop:** drag the crop edges, optionally select an aspect ratio, then apply.
- **Rotate:** each tap rotates the image 90 degrees clockwise.
- **Flip:** choose horizontal or vertical mirroring.
- **Resize:** enter pixel dimensions, with optional aspect-ratio locking.
- **Add text:** enter text, choose color/size, then tap its top-left position.
  Text wraps at the image's right edge. It cannot be moved after insertion.
- **Draw:** use the round brush, with adjustable color and width. Tap **Done**
  to leave drawing mode.
- Toolbar **Undo/Redo** works across all tools, including individual strokes
  and text insertions. A new edit after undo discards the redo branch.
- The checkmark exports the current edit. JPEG, PNG and WebP filenames are
  supported; PNG preserves transparency. **Overwrite original** requests the
  existing storage permissions before writing. An external caller's
  `MediaStore.EXTRA_OUTPUT` URI is honored.

The preview is limited to 2048 pixels on its longest side. Export reloads the
original, applies the same edit history, and renders at the document's actual
pixel dimensions. Crop and explicit resize operations change those dimensions;
the preview size does not. Text and brush coordinates are stored in image pixels.
Transforms are combined so rendering needs one output bitmap, rather than a
bitmap for each history entry. Very large images can still exceed device memory;
the editor reports a failure and retains the edit history.

Export is encoded to a cache file before the destination is opened. Compatible
EXIF attributes are copied without reapplying the source's orientation. The
original remains untouched when saving a separate copy. This is an SDR still-image
editor; it does not preserve animation or HDR gain maps. Session history survives
activity recreation through a private cache file, rather than large Bundles.

## Verification

With an Android SDK and JDK configured:

```sh
./gradlew :app:assembleFossDebug :app:testFossDebugUnitTest :app:lintFossDebug :app:detekt
```

The tests cover pixel placement through transforms, crop/resize dimensions,
preview versus full-resolution export, brush/text rendering, undo/redo branching,
history serialization, cancellation, and touch-to-image coordinate mapping.

On a device, combine crop, drawing, text, rotation and resizing; undo and redo
across tool changes; rotate the device; then save and inspect the exported image's
dimensions and annotation placement. Also test content-provider inputs and an
external `EXTRA_OUTPUT` destination.
