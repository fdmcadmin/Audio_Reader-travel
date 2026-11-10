# Device validation before long drives

Record phone model, Android version, speech engine/version and offline voice name with each pass.

- Import one UTF-8 TXT and an authorized multi-chapter EPUB. Confirm chapter order and text boundaries.
- Install offline voice data. Refresh Voice settings, select an engine/language/voice, change pitch/speed, preview and apply. Reopen the app and confirm those settings survive. Enable airplane mode and begin narration from uncached text; verify network-only voices are excluded.
- In Characters, compare narrator, MC, female and male with the identical preview sentence. Confirm the shown installed IDs match what is heard and are perceptually distinct. New-book role previews should work before learning, while the first actual passage stays narrator-only. One engine supplies the cast; installed apps are not combined. Save explicit voices or character roles if the automatic attribution is unclear.
- In a recurring character's editor, compare Follow the story, Natural and Lower tone. Confirm the same person remains recognizable, speech speed stays unchanged and the preview leaves the narrator settings intact. Check clear hostile/warm speech tags and repeated direct actions toward the MC; unclear scenes should retain the baseline. Reopen after a continued quote and confirm its delivery is stable. This is cue-based pitch control, not neural emotional acting.
- Use original test prose containing inline `freewebnovel.com` branding and straight/curly single-quoted he/she replies. Confirm branding is unspoken, neighboring story words remain, explicit dialogue uses the intended role and a continued quote keeps its speaker. Check actual book lines separately; generic test fixtures do not establish universal recognition.
- Lock the screen for at least 30 minutes; verify notification, Bluetooth play/pause and headset controls.
- Start YouTube Music while narrating, then return to Roadleaf. Permanent focus loss must pause and stay paused until Play. Repeat with another media app and manual Pause.
- Trigger a brief notification sound from another app, such as a ride or delivery request, then a navigation alert. Ordinary notification focus must keep narration advancing with at most a volume dip, including while that app stays in front. Repeat alerts without pressing Play between them. If the app requests exclusive/call-style or permanent focus, record the actual status; Android does not identify its source through the focus callback.
- Press Pause during a brief alert; speech must remain paused after the alert finishes. During a call, confirm narration stays silent; a temporary focus interruption can resume after the call, unless manually paused. Unplug headphones/disconnect Bluetooth and check speech stays paused rather than switching unexpectedly to the speaker.
- Pause, close/reopen the activity and resume. Force-stop/restart and resume; allow up to one checkpoint of replay. Reboot and choose the book again.
- Test sentence boundaries, punctuation, unusual Unicode names, custom pronunciation and speed settings.
- Install the compact APK over the matching local baseline and confirm the library/progress survive. Confirm the APK's signer matches before replacing an installed app.
- Test Android Auto library browsing, chapter selection, local search and Resume between rides; follow `ANDROID-AUTO.md` for sideload visibility and supported setup. Verify the media-button receiver can resume after service termination.
- Rearrange chapters, play the arranged queue, use Next chapter, finish a book and confirm resume behavior.
- Test a malformed/oversized/encrypted EPUB and a disallowed download redirect. The app must report an error and keep existing library data.
- Run one uninterrupted 8-hour airplane-mode session with screen locked and Bluetooth active. Record gaps, battery/temperature, audio interruptions, progress and cache size. Compare Battery saver and Extra buffering using the same engine/voice; no battery-saving percentage is claimed before this measurement.
- Repeat sessions on several days, including interruption/restart and low-storage conditions. Confirm that long weekly listening is sustainable on the actual device.

- Open a chapter on a site that is not FreeWebNovel. Read loaded chapter must skip menus, comments and footers. Auto-next must reach the following chapter. Sign in on a site that has accounts and confirm a members-only chapter reads afterwards. A locked or paid chapter must be reported, not read.
- With the browser reader enabled, open a chapter in a browser other than Chrome, tap the Roadleaf bubble, then Read page. Open the next chapter in that browser after the first ends and confirm narration continues.
- Listen across several webpage chapters with Auto-next on. The next chapter should start within about a second of the previous one ending, with no "Loading the chapter page" in between. Starting a webpage book or Continue reading should begin narrating a few seconds after its page loads.
- In your own browser, read a chapter with the bubble's Read page. When it ends, open the next chapter in the browser and press Play on the bubble, then on a headset: the new page is read. With "Continue by itself" on, it starts without Play.
- The first Continue reading asks where web books open. Choose your browser: the chapter opens there, and the bubble starts reading it once it shows. Change the choice in Sources → Read in your own browser.
- A fresh install shows no NovelAI in Library or Sources. Adding a NovelAI link with Add website book opens the NovelAI steps and adds a NovelAI card to the Library.
- The bubble must never open its controls by itself. Drag it to each side and to another height, then drop it on the ✕ at the bottom: it closes, the reading carries on, and it stays away on other pages until Roadleaf is opened again.

## Results on a real device

- **Car and headphones (November 2026, version 0.3.16):**
  - An incoming call interrupts the book.
  - Other music interrupts correctly.
  - With headphones connected, music and car audio keep playing through the car while the book plays.
  - The book plays through the Bluetooth headphones and stops when Bluetooth disconnects.

Phone hardware, Bluetooth, installed voices and endurance are not validated by JVM compilation. No device/emulator results should be inferred from unit tests.
