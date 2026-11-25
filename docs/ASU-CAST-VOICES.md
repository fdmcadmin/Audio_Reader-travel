# Roadleaf ASU cast with a PC companion

Choose **Roadleaf ASU voices · PC companion** in **Voice → Narrator**. Existing casts can keep the file's assignments or use **Auto-assign** when importing. Select **Automatic · learn as I listen** to assign newly detected speakers while reading. The app saves each book's character bindings, including generated recipes, in its cast data. Each recipe itself is part of its voice ID, so an ASU cast export can restore the same assignments.

The cast picks unused recipes from the 161 starters. It prefers the matching voice group when the book provides a pronoun or the listener selects one. When that group runs out, the remaining starters are still used. After all available starters are used, Roadleaf generates one additional four-source recipe for each new character. Dedicated narrator picks are available alongside the 161 voices. Manually assigning a starter as a narrator reserves that starter in the book.

New recipes keep two sources apart if their rendered speech scored above 0.90 cosine similarity in the speaker verification audit. The rule covers every pair of occupied slots, including slots 3 and 4. The audio catalog retains all 161 lead voices and the app can still play a saved recipe from before the rule.

The 161 profiles are Roadleaf recipes derived from 17 ASU-hosted source voices. The hosted ASU API exposes stock Orpheus and Qwen CustomVoice voices and does not accept the Roadleaf blend vectors. Roadleaf is not making hosted ASU calls for playback. The PC companion performs synthesis with the local Qwen3-TTS Base 0.6B checkpoint. Roadleaf on Android requests audio over a USB or emulator ADB reverse connection. Requests stay on loopback. The app reuses cached audio across playback rates and applies speed in Media3. This setup requires a reachable companion for uncached text.

## Local setup

The repo includes the vector bank and source registry in `voice-packs/asu` and the Android catalog in `app/src/main/assets/roadleaf-asu`. The model weights come separately from the [pinned Qwen3-TTS Base release](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base/tree/5d83992436eae1d760afd27aff78a71d676296fc). Run the companion with `qwen-tts`, PyTorch, NumPy, and SoundFile installed in its Python environment.

```powershell
python scripts/roadleaf_asu_companion.py --checkpoint $qwenCheckpoint
adb -s $serial reverse tcp:18765 tcp:18765
```

`--lab-root` can point to an existing lab workspace or extracted four-slot pack. The companion reports when the model is ready. For test fixture handling, use a debug build and the app-specific `files/asu-chapter-check` folder. Provide both local Chapter 4 JSON files under their original names. The `asuChapter` instrumentation option uses both chapter parts and writes its short reference clips in the same private test folder. Do not include that folder in a release.
