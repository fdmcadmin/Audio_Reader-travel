# Roadleaf

Roadleaf is an Android app that reads text aloud with the offline voices installed on your phone: books you import, story pages you open, and your own NovelAI stories. It is built for long, hands-free listening, such as while driving.

Kotlin, Jetpack Compose, Room, Media3 and Android TextToSpeech. Android 8.0 (API 26) or later; target and compile API 35. No cloud narration service, subscription, account or API key is used.

Repository: https://github.com/fdmcadmin/Audio_Reader-travel

## What Roadleaf is

Roadleaf is a simple reader. It reads text that already exists aloud: books you import, story pages you open, your own NovelAI stories. It uses the offline voices installed on your phone. It does not generate, sell or distribute stories, and it includes no voices of its own.

What Roadleaf adds is the reading itself: a narrator that adapts to each book and stays consistent.
- Characters keep their voices.
- Point-of-view chapters get their own narrator.
- Tone follows the story.

The aim is that long listening sessions feel like someone reading to you, not like a generic text-to-speech app.

## Features

- **Read what you already have:**
  - import EPUB or UTF-8 TXT files;
  - read the main story text of any secure (https) webpage in Roadleaf's reader, which moves to the next chapter by itself;
  - read the page or NovelAI story open in your own browser, with the optional browser reader.
- **Sign-in is preferred, never required.** A site that offers accounts gets a one-time suggestion to sign in, and Roadleaf then reads only what your account can see. Locked, paid, sign-in-only and site-check pages are reported and never worked around.
- **A narrator that adapts:**
  - each book starts with the narrator; character voices emerge as clear cues repeat and stay saved for that book;
  - viewpoint chapters can have their own narrator, and each book can have its own narrator;
  - delivery follows the story, and word fixes correct pronunciations.
- **Cast files and voices:**
  - import a Roadleaf cast, a NovelAI lorebook, or another lorebook or character card to set up a book's characters. Roadleaf keeps only their names, nicknames and pronoun hints, and gives each character an installed voice;
  - export a book's cast to reuse it in another book;
  - **Voice → Where to get more voices** lists speech apps that add offline voices.
- **Start where you want:** **Resume** (which continues unsaved stories once more text is written), **From top** and a chapter queue. Starting at a chosen word is held back for now while it is fixed.
- **Made for the road:**
  - **Drive view**, plus lock-screen, notification, Bluetooth and headset controls, and Android Auto browsing;
  - short notification sounds don't stop the book, while calls and other music pause it;
  - the optional **Headphones with car music** mode plays the book on headphones while music stays on the car speakers.
- **Light on the phone:**
  - **Battery saver** is the default, with bounded speech windows and an audio cache limit;
  - the web reader never loads ads, trackers, media or fonts, and images are optional.

See [CHANGELOG.md](CHANGELOG.md) for what changed in each version.

## Install

Test builds are attached to this repository's [Releases](https://github.com/fdmcadmin/Audio_Reader-travel/releases). They are development-signed test builds (application ID ending in `.debug`), not a production release. Download the APK from a release's **Assets**, open it on your phone and choose **Install** or **Update**. A build signed with the same key updates an existing install in place and keeps its library.

Then install an offline voice: **Voice → Refresh voices**, choose a speech engine, language and voice, and use **Install / manage voices** for more voice data. Voices come from the speech engines installed on your phone; Roadleaf does not clone voices or bundle voice models.

## Build

Open this folder in Android Studio with Java 17, SDK platform 35 and build tools 35.0.0, let Gradle sync, then run it on your phone. On the command line, run `./gradlew testDebugUnitTest lintDebug lintCompact assembleDebug assembleCompact` (on Windows, `gradlew.bat`). Set `ANDROID_HOME`, or `sdk.dir` in an untracked `local.properties`. The Gradle wrapper uses 8.11.1 with its distribution checksum pinned.

On Windows, `scripts/Build-Local.ps1` finds portable Java and SDK tools placed beside the project, or takes `-JavaHome` and `-SdkRoot`. Its optional `-UseWindowsTrust` switch makes Java use Windows' trusted root certificates for that build; it does not disable HTTPS checks or change system certificates.

On this experimental branch, **`app/build/outputs/apk/compact/app-compact.apk`** installs as **Roadleaf Story Preview** (`app.roadleaf.storypreview`). It keeps the existing Roadleaf installation and library separate. The instrumented development APK uses `app.roadleaf.storypreview.dev`, so device tests also stay separate. Neither is a production release. See [Story Preview 0.3.28 setup and validation](docs/STORY-PREVIEW-0.3.28.md).

## Using it

1. Import an EPUB or UTF-8 TXT you're allowed to use through Android's file picker, open a webpage with **Sources → Read webpage**, or add one with **Library → Add website book**.
2. Open **Voice** → **Refresh voices**. Choose an installed speech engine, language/accent and offline voice, and adjust pitch and speed. Preview the narrator, then **Save & apply**.
3. Tap **Listen** for an imported book, or **Continue reading** on a website quick pick.
   - Use lock-screen, notification, Bluetooth/headset controls or **Drive view** for larger controls.
   - Ordinary notifications keep narration moving with normal volume ducking. Calls and exclusive temporary focus pause it and resume when focus returns, unless you paused manually.
   - A manual Pause, another media app taking focus for good, and headphone or Bluetooth disconnects stay paused until Play. Apps that play sound without requesting focus can't always be detected.
   - −15s and +30s move within prepared audio; headset next/previous select real chapters.
4. **Start from a word** above the player opens the playing chapter at the paragraph you're hearing. Tap a word, then **OK**, and reading starts there.
5. **Chapters** lets you choose a start chapter and arrange the chapter order. Playing an arranged queue starts it from the top.
6. **Voice → Word fixes** sets literal whole-name or phrase pronunciations. Reload the book to apply new rules.
7. **Voice → Characters** (also reachable from each book and the reader's Book options) defaults new books to **Automatic**.
   - The opening passage stays with the narrator, then voices emerge as clear character cues repeat. Learned assignments persist per book.
   - Repeated viewpoint cues can identify a likely main character; uncertain speakers keep the narrator.
   - Frequent supporting characters can earn a spare installed matching voice. Automatic male/female voice families are currently Supertonic; with other engines, choose the defaults yourself.
   - Your main-character, name, voice and alias settings always win. **Narrator only** and **My assignments only** remain available, with a list of learned names and **Restart character learning**.
   - **Import cast or lorebook** and **Export cast** set up or save a book's characters; see [docs/PERSONAL-STORY-CONTRACT.md](docs/PERSONAL-STORY-CONTRACT.md).
   - All learning and narration happen on the phone.

Previews pause the current book and save its position. Voice, engine, accent, pitch, speed and device-usage settings survive restarts. Android Auto setup and its sideloading limits are described in [docs/ANDROID-AUTO.md](docs/ANDROID-AUTO.md).

## Source support

**Any webpage.** Roadleaf reads the main story text of any secure (https) page. Site rules only add extras: the chapter container, title and next link for Royal Road, AO3, FanFiction/FictionPress, Scribble Hub, Webnovel and Wattpad, and FreeWebNovel's own verified rules.

- **Two ways in:**
  - **Roadleaf's reader** (Sources → Read webpage, Library → Add website book, or share a link from any browser) turns pages by itself. Its address bar takes an address or search words, sent to the search engine you choose.
  - **The optional browser reader** reads a NovelAI story or the page you're on, in the browser you already use (Chrome, Firefox, Edge, Samsung Internet, Brave, Opera, DuckDuckGo and others), with that browser's sign-ins. Its controls open from a small floating bubble that you can drag anywhere or drop on ✕ to close. It reads only what the browser shows: when a chapter ends, open the next one in your browser and press Play. You choose whether web books open in Roadleaf's reader or your browser.
- **What it skips:**
  - menus, comments, ads and text the page hides from readers;
  - ad and tracking hosts, media and fonts, which are never loaded; images load only if you turn them on.
- **Live reading:** the visible page supplies chapter text to the local narrator without scrolling. Near the end of a chapter, Roadleaf reads the next chapter's page and prepares its opening, so the next chapter starts as soon as the current one ends. When a page has to load first, its text is read once the page has loaded and settled, and by 30 seconds at most. A next-chapter link must stay on the same site, though a site's own redirects are followed. New pages need a connection; this is not a chapter downloader or offline archive.
- **Website quick picks:** opening a chapter saves the book as a quick pick with its latest chapter; **Continue reading** restores its saved place.

| Adapter | Baseline behavior |
| --- | --- |
| Project Gutenberg | User-initiated XML OPDS search; download catalog-provided EPUB URLs; browser fallback. No bulk crawling. |
| AO3 | Official authorized EPUB download URLs, or open a work in the browser, use Download → EPUB, then import. No login cookies, work-page crawling or restricted-work bypass. |
| Author-direct | Obtain the author's permitted EPUB/TXT in your browser, then import from Files. |
| Royal Road / Webnovel / any site | Read the chapter page live (what your access shows), save a quick pick, or import permitted files. No chapter downloader. |

`StorySource` exposes capabilities, URL resolution and optional catalog search. `SourceRegistry` resolves exact hosts; `SourceAction` distinguishes a permitted download from browser guidance and file import. Remote downloads validate every redirect against the adapter policy, use HTTPS, have timeouts and a 32 MB limit, and never auto-retry a source failure. Confirm your rights to import each file.

Gutenberg documents its OPDS feed at https://www.gutenberg.org/ebooks/offline_catalogs.html and robot access at https://www.gutenberg.org/policy/robot_access.html. Its page currently says XML OPDS is expected to sunset in 2027; the adapter is isolated for an OPDS2 migration. AO3's download instructions: https://archiveofourown.org/faq/downloading-fanworks?language_id=en . Access and file availability remain controlled by each source.

## Offline and long-session architecture

Room stores books, ordered chapters, per-book text/audio progress, queue entries, bookmarks and pronunciation rules. Book text lives in the app's private persistent files directory, outside the evictable audio cache. EPUB spine order is preserved; encrypted EPUBs are rejected. Imports are limited to 32 MB compressed and 32 MB expanded text, with no archive extraction into arbitrary paths.

The MediaLibraryService owns ExoPlayer and a lazily connected offline speech engine. A streaming reader opens one chapter at a time and reads bounded windows rather than loading book text into memory. Pronunciation rules and voice metadata are reused for a listening session. WAV cache identity includes engine, voice, pitch and speech text; speed changes do not regenerate speech.

**Battery saver** is the default: two prepared speech segments, a 15-second playing-only checkpoint, a 64 MiB audio cache target and at most one cache scan every five minutes. **Extra buffering** prepares four segments, checkpoints every ten seconds and targets 128 MiB. Pause and transition saves are immediate; the checkpoint timer and synthesis lookahead stop when paused. Missing audio is regenerated offline from persistent text.

The design target is several hours of listening a day with no per-hour cloud narration charge. This is a target, not an endurance certification: speech quality, languages, synthesis speed, voice licensing and battery use depend on the device and engine. No DRM, cloud sync or automatic serial updates are included.

Before relying on it for a drive, work through the phone checklist in [docs/DEVICE-VALIDATION.md](docs/DEVICE-VALIDATION.md). Choose books and controls while parked.

## Builds and signing

See [docs/RELEASE.md](docs/RELEASE.md).

**Checks workflow**
- Builds and tests every push and pull request, in a private or public repository.
- Uploads test APKs only for pushes to `main` and manual runs.
- Pull requests receive no release secrets.

**Signed-release workflow**
- Runs only when the repository owner starts it on `main`, using the protected `private-release` environment.
- Release builds shrink code and resources and require a release key.
- Verifies the signature and uploads the APK and checksum for seven days.

Configure the environment with required reviewers and release secrets before running it. No release keys are included in or generated by this project.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how reading, casting and playback work.
- [docs/ANDROID-AUTO.md](docs/ANDROID-AUTO.md): Android Auto, outputs and headphones with car music.
- [docs/DEVICE-VALIDATION.md](docs/DEVICE-VALIDATION.md): the phone checklist.
- [docs/PERSONAL-STORY-CONTRACT.md](docs/PERSONAL-STORY-CONTRACT.md): story voice profiles and what stays outside the app.
- [docs/RELEASE.md](docs/RELEASE.md): builds, signing and test releases.

## License

Roadleaf's code is licensed under the **GNU General Public License, version 3 only** (GPL-3.0-only); see [LICENSE](LICENSE).

[NOTICE](NOTICE) adds the GPL's section 7 terms:
- Modified versions keep the author credit.
- They must be marked as different from the original.
- They get no rights to the Roadleaf name or icon; see [TRADEMARKS.md](TRADEMARKS.md).

These are not part of Roadleaf and are not covered by its license:
- the speech engines and voices you install;
- the stories and sites you read;
- the owner's separate projects: personal lorebooks, Space Expedition, the Story Generator LLM and the FDMC combat-balancing engine.

Privacy: [PRIVACY.md](PRIVACY.md).
