package app.roadleaf

/** Switches for features held back until they work on the owner's phone. */
object Features {
    /**
     * Starting at a chosen word: Start from a word in the player, Read from here on the NovelAI bubble and in the web
     * reader's long-press menu. Hidden on 2026-11-08 after the owner found it not working on the phone. The engine behind
     * it (START_AT, AT starts) stays, so turning this back on restores the buttons.
     */
    const val START_HERE = false
}
