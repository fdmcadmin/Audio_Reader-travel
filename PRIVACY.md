# Roadleaf privacy

Roadleaf is a reader. It turns text you already have access to into speech on your own device. It has no accounts, ads, analytics, cloud narration or server of its own, and it sends nothing about you to the developer.

## What stays on your device

- Your library, imported books, reading progress, voice and character choices, word fixes and settings are stored only in the app's private storage. Android backup and device transfer are switched off for this data.
- Speech is generated offline by the text-to-speech engine you install. Roadleaf does not upload text for narration.

## Network use

- Roadleaf connects to the internet only when you open a webpage in its reader, follow its automatic next-chapter link, or search Project Gutenberg's catalog. Those requests go directly to the site you chose, as in any browser.
- Websites can store their own cookies and sign-in in Roadleaf's reader, just as in a browser. Roadleaf never sees or keeps your passwords; you type them into the site's own page. Cookies from other sites (third-party cookies) are blocked, and known advertising and tracking hosts are not loaded.
- Searches typed into the reader's address bar go to the search engine you choose.

## Permissions

| Permission | Why | When |
| --- | --- | --- |
| Internet | Load webpages and catalogs you open. | Only while you read or browse a page. |
| Foreground service, media playback, wake lock | Keep narration playing with the screen off or behind another app, with notification and Bluetooth controls. | Only while narrating. |
| Phone state (optional) | In "Headphones with car music" mode, pause the book during calls. Only the call state and audio mode are checked; never numbers, contacts, call history or microphone audio. | Only if you turn that mode on and allow the permission. |
| Notifications (Android 13+) | Show playback controls. | While narrating. |

## Optional browser reader (Android accessibility service)

Roadleaf's browser reader is an accessibility service, off unless you enable it in Android settings.

- **What it does.** It reads text you choose in your own browser, on your phone: the NovelAI story editor, or a page when you tap Read page. While a supported page is open, it shows a small floating bubble that opens the controls when you tap it. Drag the bubble onto ✕ to close it until you next open Roadleaf.
- **What it never does.** It does not type, tap or follow links, read passwords or form fields, read other apps, collect browsing history, copy cookies or upload anything.
- **What it keeps.** Captured text is used only for narration on your device. A story is saved to your library only when you choose to save it.
- **How to stop it.** Turn the service off in Android's Accessibility settings at any time.

## Contact

Questions or requests about privacy can be raised as an issue on the project's repository.
