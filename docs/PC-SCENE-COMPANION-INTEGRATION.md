# PC Scene Companion to Android Roadleaf: additive integration plan

Status: **Android does not yet import the PC scene-performance sidecar**. This branch
provides local, lightweight text read-ahead and isolated voice-cast import controls.
It does not replace the Android player or its speech engine.

## Existing inputs and outputs

- NovelAI is the writer and owner of the story and Lorebooks.
- PC Scene Companion v0.1 exports `Roadleaf_cast_import.json` as a
  `roadleaf-voice-cast` v1 file. Android's existing **Import cast or lorebook** action
  supports this format.
- The PC companion also produces `scene_performance.json`
  (`roadleaf-scene-performance` v1) and `pc_narration_plan.json`
  (`roadleaf-pc-narration-plan` v1). **These remain PC-only** until the phone receives
  a compatible, explicitly opted-in performance sidecar importer.
- The PC files are suggestions, not canon. All imported story text is preserved verbatim.

## Safe transfer to Android (current)

1. On Windows, analyze the exported manuscript with the companion and export the cast.
2. On Android, save the corresponding NovelAI manuscript as its own named story:
   Roadleaf Library > NovelAI > **Save / link a specific NovelAI story**.
3. Return to the NovelAI editor and choose **From top** in the Roadleaf bubble to
   capture that named manuscript. Its Library entry has an independent ID and progress.
4. Open the *specific* book's **Character voices** panel.
5. Choose **Import cast or lorebook**, then **Add to this cast** or **Replace this cast**;
   select the PC-generated `Roadleaf_cast_import.json`.
6. Assign any missing installed voice IDs and save. The PC has not installed or
   cloned any voice on the Android device.

## Next optional integration: performance sidecar v1

To add richer PC planning without regressing the reader:
- Add an opt-in per-book import of scene-performance annotations.
- Bind the sidecar to a **specific book ID plus exact source text hash**, not a
  website URL, title or current selected story.
- Validate input size, schema, text offsets, character aliases, and voice IDs.
  Reject a mismatched manuscript or show an explicit reanalysis requirement.
- Apply only performance metadata (voice routing, paragraphs, pauses, delivery);
  never modify the manuscript, saved Lorebooks, or external NovelAI state.
- Keep an offline fallback to `SceneCast` and `AdaptiveCast` when no annotation
  exists, is stale, or the PC is unavailable.
- Leave Media3, Bluetooth, Android Auto, bookmarks, queue, caching and paragraph
  checkpoints in Roadleaf's existing service.
- Never make a cloud connection or PC connection mandatory for playback.

The preceding contract is a **future implementation plan**, not a claim that
Android can currently import `scene_performance.json`.
