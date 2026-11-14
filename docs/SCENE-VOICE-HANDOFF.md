# Scene voice handoff in Story Preview 0.3.31

An imported, linked cast can use its assigned voices in the first speech passage. An unlinked book still begins with the neutral narrator while automatic learning starts.

Roadleaf can choose a scene's focal voice from an explicit `[POV: Character]` line, a named thought or sensory cue, or repeated third-person focal actions. It carries that choice across later third- or second-person paragraphs until a scene break (`***`, `---`, `⁂`, `###`, or a scene heading) or stronger evidence changes it. A bare `you`, `he`, `she`, name mention or quoted dialogue does not establish viewpoint. Ambiguous scenes use the book narrator. `[POV: Character]` metadata is omitted from narration for scene-scoped casts.

The choice remains local to the saved book and its speech checkpoint. A manual chapter viewpoint in Voice → Characters still takes priority at chapter start. Perspective detection is a bounded text heuristic; it cannot infer every viewpoint change from prose alone. Speaker attribution for dialogue remains separate from the narrative voice.

This update changes narrator selection. It does not resolve the separate Kokoro audio whine or generation pauses observed on the Pixel; compare those with Supertonic before relying on Kokoro for long listening.
