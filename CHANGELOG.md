# Changelog

All notable changes to Roadleaf. Versions before 1.0 are test builds.

## 0.3.25

- **Output test report:** the report's status line now names the outputs the app uses, phone and Android Auto: "Phone/Android Auto speaker separation: requires a parked listening check."

## 0.3.24

- **Choose your browser for web books:** the first time you open a web book (Continue reading, Add website book or Read webpage), Roadleaf asks where: its own reader or an installed browser such as Chrome. It remembers the answer; change it in Sources → Read in your own browser → Where web books open.
- **Reading starts in your browser:** when Roadleaf opens a chapter in your browser, the browser reader starts reading it once the page shows, from where you stopped when continuing. Quick picks' More menu offers both: Continue in Roadleaf's reader, or Open in my browser.
- **Start at a chosen word, hidden for now:** Start from a word in the player, Read from here on the NovelAI bubble and the web reader's long-press Read from here are hidden until they work reliably on the phone. From top and Resume are unchanged.

## 0.3.23

- **Your browser, like a normal reader:** Roadleaf reads the page your browser shows and never turns pages itself. When a chapter read in your browser ends, open the next one there and press **Play**, on the bubble, the notification or your headset; Roadleaf reads the page now on screen. With **Continue by itself when I open the next chapter** on, it starts without Play. In the bubble, **Play** on a page with nothing read yet reads that page.
- **NovelAI only when you add it:** NovelAI no longer sits at the top of Library and Sources. Add a NovelAI link as a website (Library → Add website book, Sources, or sharing it to Roadleaf) to see the steps for reading your stories in your own browser and a NovelAI card in the Library. Reading a NovelAI story with the bubble adds it too, and **Remove NovelAI** hides it again.
- **Browser setup:** Sources → Read in your own browser explains turning on the browser reader and reading a page, for any site.

## 0.3.22

- **No more wait between webpage chapters:** near the end of a chapter, Roadleaf reads the next chapter's page in the background and prepares its opening, so the next chapter starts as soon as the current one ends. Before, it loaded the page only after the chapter ended, then waited 10 to 30 seconds.
- **Faster page reading:** when a page does need loading (starting a book, Continue reading, or a slow page), its text is read once the page has loaded and stopped changing, usually within a few seconds. The fixed 10-second first check is gone; 30 seconds is still the limit.
- **Faster narrator start:** the speech engine and narrator voice are warmed up when Roadleaf opens and while a page loads, and a story begins with a short opening passage, so the first words come sooner.

## 0.3.21

- **A bubble instead of a panel:** in your browser, Roadleaf no longer opens a panel over the page. A small round bubble waits at the edge of the screen and never opens by itself. Tap it for the controls; **−** folds them away again.
- **Move it or close it:** drag the bubble anywhere and it settles on the nearer side. Drop it on the **✕** at the bottom, or tap **✕** on its card, to close it. A reading in progress carries on, and the bubble comes back the next time you open Roadleaf.
- **Free read removed:** **From top**, **Resume** and **Read from here** cover every way to start. A story not saved to Library still plays without being saved.
- **Follow as before:** following new paragraphs in NovelAI uses the 0.3.19 code again, the version tested for hours at a time.

## 0.3.20

- **Read from here in your browser:** the NovelAI floating controls have **Read from here**. Tap a word in your story box (or select one), then tap it, and reading starts at that word.
- **Resume continues unsaved stories:** for a story you haven't saved to Library, including Free read, **Resume** continues from where you stopped once more text has been written. Roadleaf remembers a fingerprint of the text before that point and the position, not the text.
- **Steadier follow:** reading new paragraphs keeps working when the page rebuilds the story box during generation.
- **Clear messages:** every floating button says what happened, and says why when audio doesn't start, for example a selected output that isn't connected.

## 0.3.19

- **Import cast or lorebook:** set up a book's characters from a Roadleaf cast file, story voice profiles, a NovelAI lorebook (also inside a scenario or story file), lorebook JSON in the common entries form or a character card. Only names, nicknames and pronoun hints are kept, never the lorebook text.
- **Voices from your phone:** imported characters get installed voices from the selected speech app, female or male where the app labels them, without sharing until voices run out. Voices a file names but the phone lacks are replaced.
- **Export cast:** save a book's cast, with its voices and learned voices, to a file you can import into any book.
- **Get more voices:** Voice → Where to get more voices, and the Characters screen, list speech apps with offline voices and open your speech app's voice downloads.
- **Privacy:** no book starts with a preset cast, and the repository no longer contains personal test notes.

## 0.3.18

- **Start from a word:** while a chapter plays, tap **Start from a word**, tap any word, then **OK**. Reading starts exactly at that word and carries on through the chapter.
- **Read from here:** in Roadleaf's web reader, long-press a word on the page and choose **Read from here**. The word is found in the chapter text, even when it repeats.

## 0.3.17

- **Read any webpage:** Roadleaf's reader reads the main story text of any secure (https) page and moves to the next chapter by itself. Menus, comments, ads and hidden text are skipped.
- **Sign-in:** preferred, never required. Locked, paid, sign-in-only and site-check pages are reported, never worked around.
- **Search:** the reader's address bar takes an address or search words, sent to the search engine you choose. There is no default engine.
- **Browser reader in other browsers:** the optional browser reader works in Firefox, Edge, Samsung Internet, Brave, Opera, DuckDuckGo and other browsers. **Read page** reads the page shown and can continue when you open the next chapter.
- **Older phones and other devices:** **Headphones with car music** works on Android 8–11, dark pages work on Android 8–12, and Wi-Fi tablets and Chromebooks are no longer excluded.
- **License:** Roadleaf is licensed under GPL-3.0-only.

## 0.3.16

- **Browser controls:** **From top**, **Resume**, **Free read** and **Check collection** controls on a NovelAI story in the browser. **Free read** reads without saving a Library book.

## 0.3.15

- **Live NovelAI reading:** reads the selected NovelAI story editor in the browser through an optional accessibility service, following new paragraphs as they appear.
- **Voice profiles:** a story can carry imported voice metadata for its characters.

## 0.3.14

- **Resume paragraph:** restarts the saved paragraph from its beginning.
- **Update story text (TXT):** refreshes a growing story while keeping its voices and place.
- **Scene profiles:** per-story character profiles switch on only in scenes where those characters appear; `[Scene: names]` headers are honored and not spoken.

## 0.3.13

- **Headphones with car music (experimental):** the book plays on the selected headphones while music keeps the car speakers. Calls pause the book.

## 0.3.12

- **Where narration plays:** a Roadleaf-only output choice, **Test output** and a shareable test result. A missing or mismatched output pauses narration.

## 0.3.11

- **Book narrator:** each book can keep its own narrator.

## 0.3.10

- **Viewpoint narration:** the chapter's viewpoint narrator is separate from the main character's voice, with per-chapter choices and pronoun hints. Read-ahead improves character continuity.

## 0.3.9

- **Delivery:** per-character **Follow the story**, **Natural** and **Lower tone**. Pitch is processed on the phone at unchanged speaking speed.

## 0.3.8

- **Cleaner webpage speech:** embedded website watermarks and title branding are removed.
- **Dialogue:** single-quoted dialogue is recognized, and voice previews use the same comparison sentence.

## 0.3.7

- **Automatic cast:** character voices are learned gradually and kept for each book.

## 0.3.6

- **Chapter titles:** spoken without the "Chapter N" label.
- **Cleanup:** webpage footer and navigation lines are filtered, and `???` replies are treated as a pause.
- **Pacing:** sentence-sized synthesis reduces rushed linking words.

## 0.3.5

- **Website quick picks:** saved books with their latest chapter, and a redesigned reader.

## 0.3.4

- **Notifications:** narration keeps playing through ordinary short notification sounds, while calls still pause it.

## 0.3.3

- **Webpage extraction:** better chapter extraction and book-title detection on dark or light pages.

## 0.3.2

- **Library:** **Add webpage book**, **Delete from library**, **Load new book** and **Read loaded chapter**.

## 0.3

- **Live webpage reading:** automatic next chapter, checked after 10 seconds and waiting up to 30 seconds.

## 0.2

- **Voices and resources:** voice tuning, lower resource use and Android Auto media browsing.

## 0.1

- **First build:** EPUB/TXT import, offline library, playback progress, chapter queue and pronunciation overrides.
