# Roadleaf native offline cast voices

Roadleaf 0.3.35 packages the official Kokoro multilingual v1.0 fp32 archive and
VITS VCTK archive. The app uses the pinned Sherpa ONNX 2.0.1 Android library.
The model archives are downloaded during the build, verified by SHA-256, and
included in the APK. They are not committed to Git.

## Kokoro fp32

- Select **Kokoro 82M** under Voice > Narrator and tap **Prepare included Kokoro voices**.
- Roadleaf extracts `model.onnx` and the associated files into its private model
  folder. It appends 103 deterministic 60/40 style blends to a copy of the
  original `voices.bin`, preserving the original speaker IDs.
- Sherpa validates `voices.bin` against ONNX metadata. Roadleaf changes the
  extracted model copy's `n_speakers` metadata from 54 to 157; the graph and
  packaged official archive remain unchanged.
- IDs 0–27 are the 28 English base speakers in the v1.0 package. IDs 54–156
  are the blended English choices. IDs 28–53 are other language voices and are
  not offered as English cast choices.
- The first preparation needs storage for the packaged archive, extracted model,
  and blended vector file. Existing book and cast files are not modified.

The official v1.0 archive documents 53 speakers, while its current `voices.bin`
contains 54 entries. Roadleaf validates the exact 54-entry vector layout before
building blends. It does not use the different v1.1 speaker IDs.

## VITS VCTK with Supertonic

- Keep **Supertonic** selected as the Android narrator engine.
- Voice > Characters lists `VCTK supporting voice 0` through `108` alongside
  the Supertonic voices. Assign any VCTK ID to a cast member; the narrator and
  lead voices can remain Supertonic.
- The VCTK archive is unpacked into app-private storage on first use. Native
  synthesis uses the unquantized `vits-vctk.onnx` model and its SID, bypassing
  the Android TTS provider handoff for those cast lines.
- VCTK speaker gender is not inferred from the numeric SID. Preview before
  assigning a voice.

The screenshot's warmup, switch-lag, and acoustic-quality claims are goals, not
measured results. The two models are large; there is no claim of a 140 MB total
app footprint. Supertonic is an independently installed Android TTS engine and
its availability is unchanged by this update.

Sources:
- https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/tts/pretrained_models/kokoro.rst
- https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/tts/pretrained_models/vits.rst
- https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1

To build locally, run `bash scripts/fetch-sherpa-aar.sh`, then
`bash scripts/fetch-native-voice-models.sh`, then `./gradlew assembleDebug`.
