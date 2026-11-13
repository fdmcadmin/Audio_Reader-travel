# Story Preview 0.3.28

This development branch combines the NovelAI story work from PR #1, the direct Kokoro implementation from PR #2, and the 0.3.27 Expedition/library fixes. It does not replace the original app or merge into main.

## Installation identity

- Compact: `app.roadleaf.storypreview`, label **Roadleaf Story Preview**, version 0.3.28, code 31.
- Instrumented development build: `app.roadleaf.storypreview.dev`; tests target only this package.
- Existing `app.roadleaf.debug`, `app.roadleaf.scenecompanion`, and `app.roadleaf.kokoropreview` remain independent.
- All preview data and the imported model belong to the preview package. No original app uninstall or data clearing is needed.

Use `scripts/fetch-sherpa-aar.sh` before building. The upstream 2.0.1 AAR checksum is pinned. The model is imported separately; it is never bundled into the APK. CI runs unit tests and both lint variants before assembling the compact APK.

## Features

- Save an individual NovelAI `https://novelai.net/stories?id=...` bookmark for each named manuscript. Capturing story text preserves that exact URL.
- Imported character voices and reading progress stay attached to that manuscript. A different story ID exposed by the native browser address rejects a configured story profile. Where Chrome collapses the address and hides its ID, the existing explicit profile selection and saved-text guards still apply; the app cannot prove an ID that Chrome does not expose.
- Direct native Kokoro v1.0 uses independent speaker IDs per speech segment: Bella `kokoro:2`, Puck `kokoro:18`, George `kokoro:26`.
- The existing dual POV/read-ahead and cue-based pitch rules remain. These are bounded heuristics, not semantic understanding of an entire character arc or emotional acting.
- Read selection accepts forward/backward highlights and rejects a plain insertion caret. Visible top clips character boxes to the display and ignores invalid/offscreen locations. Unsupported browser character locations produce an explicit highlight fallback.
- The official model importer accepts ordinary trailing slashes on tar directory entries while continuing to reject traversal, malformed paths and linked files.
- 0.3.27's Expedition Chapter 1 TXT compatibility, numbered JSON advance, offline fallback and oldest-first library ordering remain.

## Verification on November 13, 2026

Local unit tests: **245 passed**. Both debug and compact lint: **0 errors, 10 warnings**. Compact APK v2 signature verified. Native Pixel 10 Pro XL tests used original neutral fixture text and the official INT8 v1.0 model archive.

- Production archive import completed in approximately 30.5 seconds.
- Production `OfflineSpeech` produced distinct valid WAV files for af_bella, am_puck and bm_george. First synthesis took about 6.3, 4.0 and 5.0 seconds respectively for one short sentence. These are short checks, not sustained throughput measurements.
- Exact bookmark retention, persisted cast isolation, unmatched-story rejection, and dual POV speaker choice passed.
- Android geometry checks rejected offscreen and non-finite character boxes.
- The live Chrome probe exposed the rich NovelAI story editor separately from page/sidebar content. A collapsed native address did not expose the manuscript ID.
- A live highlighted-range check passed and restored the original caret afterward. On this Chrome/NovelAI layout, the character-location capability was advertised but no usable visible offset was returned. Use **Read selection** here; **Visible top** reports the limitation and requests a highlight.
- Process PSS after the synthesis checks was approximately 176 MiB. Battery life, car interruptions and long listening sessions were not measured by this run.

For a repeatable native speech check, install the debug APK and its androidTest APK. Copy the official `kokoro-int8-multi-lang-v1_0.tar.bz2` into `/sdcard/Android/data/app.roadleaf.storypreview.dev/files/`, then run:

```
adb shell am instrument -w -e kokoroChecks true app.roadleaf.storypreview.dev.test/app.roadleaf.ReaderRegressionInstrumentation
```

For an owner-authorized, metadata-only live Chrome check, leave the story editor visible and run the same runner with `-e browserProbe true`. Add `-e browserSelectionProbe true` to temporarily select a small range and verify Android reports its start. The probe restores the original selection; it does not type, generate, or change the manuscript.

No personal manuscript, story URL, character setup, Lorebook, signing key or speech model is committed to this branch.
