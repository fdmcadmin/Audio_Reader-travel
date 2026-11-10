# Android Auto and audio interruptions

## Headphones with car music (0.3.13, experimental)

Voice → Narrator adds an opt-in **Headphones with car music** mode for the reported mutual interruption between car music/radio and headphone narration. Select the connected headphones explicitly, grant the optional Phone permission for call detection, enable the mode and wait for Ready. Start YouTube Music in the car, then press Play in Roadleaf. The mode leaves media focus with the music app and disables Roadleaf's Android Auto commands; use Roadleaf or headset controls for the book. Calls/communication audio pause the book and require a manual Play afterward. Mode/output changes pause, and a reconnect never starts audio. Ordinary focus behavior remains the default when this mode is off.

Android 12+ is required. Missing headphones, unknown call state or failed call monitoring block playback; monitoring errors restore normal focus handling. Permission/call state, communication mode, selected route and recognized car commands are separate checks. None guarantees that every head unit supports two physical outputs or exposes its controls distinctly. Music in the car with the book on headphones was later verified on a real phone and car; see [DEVICE-VALIDATION.md](DEVICE-VALIDATION.md). See [CHANGELOG.md](../CHANGELOG.md) for when each part arrived. The shareable result now includes six bounded Roadleaf control/focus events for a phone-only parked test.

## Separate narration output and a test without USB debugging (0.3.12)

Voice → Narrator includes a Roadleaf-only output choice, an audible test and a shareable result. System default / Android Auto preserves normal behavior. Select connected headphones by name while parked, run Test output and compare the sound with the device Android reports. Check other apps' alerts through the car separately, then share the small result with the connection type and what you heard. A PC is not required for these in-app actions; Android Auto does not give this development chat remote phone access.

This is an experimental preferred route for Roadleaf's own player. Android Auto can override it, and it does not route other apps. Selected-output absence, ambiguity or observed mismatch stops playback; returning headphones does not resume automatically. Callback latency means a brief OS reroute cannot be excluded. The existing call/media/notification focus policy remains in effect. Neither headphones nor vehicle was available for implementation testing. See [DEVICE-VALIDATION.md](DEVICE-VALIDATION.md) for the parked output test.

Official routing APIs: [Media3 preferred output](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer#setPreferredAudioDevice(android.media.AudioDeviceInfo)) and [AudioRouting preference versus actual device](https://developer.android.com/reference/android/media/AudioRouting#getPreferredDevice()). Roadleaf observes its own AudioTrack through the pinned Media3 AudioTrackProvider; it does not inspect another app's audio.

Roadleaf 0.2 declares Android Auto media support and exposes a Media3 `MediaLibraryService`, including the legacy media-browser action used by projected Android Auto. This is a phone media app for a compatible Android Auto display; it does not install a standalone Android Automotive OS dashboard app.

## In the car

Import a book and choose an offline voice on the phone first. Connect the phone to a compatible Android Auto display. When Roadleaf appears in the media launcher, you can add/reorder it in Android Auto's **Customize launcher** settings where available.

The car browser shows a quick **Resume** item for the most recently listened book and an **Offline library** folder. A book contains Resume and its chapters. Selecting a chapter starts there; selecting Resume restores the saved text and audio position. Media search resolves against imported local titles/authors. Standard car controls provide play/pause and seeking; the car determines the exact buttons and app-switching layout. The app never starts narration merely because the browse tree was opened.

Manual pause, audio-focus interruptions and headphone/Bluetooth disconnects save progress. Starting in **0.3.4**, ordinary notifications (`MAY_DUCK`) keep narration playing through ExoPlayer's built-in audio-focus handling, with normal temporary volume reduction. The stream uses generic media content classification because Media3's speech classification forces a pause even for notifications. Calls/exclusive temporary focus suppress playback and resume on focus return unless manually paused. Manual Pause, permanent focus loss (typically another music/video app) and output disconnects stay paused until Play; pressing Pause during a notification also prevents later resumption. Pending chapter/voice work waits through exclusive temporary focus instead of requesting focus over it. No microphone, usage-access permission or continuous monitoring of other apps is used. Android reports the focus type, not the identity of the sound; a notification app requesting exclusive or permanent focus cannot be distinguished from a call/media request through this API. Apps that ignore focus cannot be universally detected. The media-button receiver and playback-resumption callback allow a new media session to restore the last book after service termination.

## Private APK visibility

Android Auto may hide a sideloaded media app even when its manifest and media browser are correct. Google's official testing guide describes the private testing option **Allow unknown sources** inside Android Auto developer settings, and alternatives such as Google Play internal testing. Choose the appropriate method yourself on the phone. No phone security settings are changed by this project. This setup has not registered or published the app through Google Play.

Official setup and testing references:

- https://developer.android.com/training/cars/media/auto
- https://developer.android.com/training/cars/testing#unknown-sources
- https://developer.android.com/training/cars/testing/dhu
- https://developer.android.com/media/media3/session/serve-content
- https://developer.android.com/media/optimize/audio-focus

## Required device checks

Interruption behavior is covered by the phone checklist in [DEVICE-VALIDATION.md](DEVICE-VALIDATION.md). Results depend on the phone, the car and the other apps involved. No Desktop Head Unit has been tested, and compilation or audio-focus checks alone do not establish in-car behavior.

1. With the phone parked/connected, open Roadleaf in Android Auto. Confirm library/chapter order and resume position.
2. Start a book, then start YouTube Music. Confirm Roadleaf pauses immediately and does not restart over music when focus returns.
3. Pause Roadleaf yourself, switch apps and return. It must remain paused until Play.
4. Test a call, navigation prompts, Bluetooth disconnect/reconnect, screen lock and engine/ride changes.
5. Stop the service/reboot, reconnect and use Resume. Allow up to 15 seconds of replay after abrupt process loss; normal pause checkpoints are immediate.
6. Run the eight-hour offline endurance checklist with the chosen voice and resource mode.
