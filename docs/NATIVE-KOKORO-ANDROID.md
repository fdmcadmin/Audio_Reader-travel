# Roadleaf: Direct Sherpa-ONNX Kokoro voices (experimental)

This implementation is additive. Existing Android TextToSpeech engines, narration
queue, story-isolated casts, chapter state, caching and offline reader are retained.

## What works in the proposed code

- Sherpa ONNX official Android Kotlin AAR v2.0.1.
- Kokoro-82M INT8 multilingual v1.0: 28 English voices, IDs 0 through 27.
- In-app speaker selection bypasses Android's TTS one-voice limitation.
- User-imported model archive, no automatic downloads.
- After import, audio synthesis is offline and uses the WAV playback cache.
- Existing Android TTS providers remain selectable.

## Phone setup

1. Open Roadleaf > Voice > Narrator > Speech engine.
2. Choose Kokoro 82M (built into Roadleaf).
3. Tap Download official Kokoro INT8 v1.0 model; save the
   kokoro-int8-multi-lang-v1_0.tar.bz2 archive to Downloads.
4. Tap Import downloaded Kokoro model archive and pick that file.
5. Keep Roadleaf open until the status message confirms import.
6. Tap Refresh voices. Choose a narrator, assign characters in Voice > Characters,
   then save those choices.

Example:
- Vesper: kokoro:2 = af_bella (female, US)
- Cinder: kokoro:18 = am_puck (male, US)
- Narrator: kokoro:26 = bm_george (male, UK)

These are v1.0 IDs. Do not use them with Kokoro v1.1-zh, whose 103 voices
include only 3 English speakers and use different IDs.

## Storage and boundaries

The model is not bundled into the APK. The downloaded archive is large and the
extracted model may occupy around 190 MB. Import validates the v1.0 layout,
rejects symlink/hardlink entries and path traversal, limits extracted size,
and updates only Roadleaf's own model folder. NovelAI stories and Lorebooks
are never touched.

## Build locally

1. Run: bash scripts/fetch-sherpa-aar.sh
2. Run: ./gradlew assembleCompact

CI fetches the pinned AAR with SHA-256 verification before building.
Upstream library: https://github.com/k2-fsa/sherpa-onnx
Model source: https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0

## Still to verify on a real phone

The code and compilation tests do not verify actual model loading and speech.
Before merging, test speech output with a real downloaded model, assigned
speaker IDs, memory use, and Android pause/resume.
