# Open-source readiness: OpenAI Codex for Open Source

Checked on 7 November 2026 against:
- OpenAI's [program page](https://developers.openai.com/codex/community/codex-for-oss);
- the [Program Terms](https://learn.chatgpt.com/docs/codex-for-oss-terms) (no effective date shown).

The [application form](https://openai.com/form/codex-for-oss/) refused automated reading (HTTP 403). Open it in a browser to see its exact questions before applying.

## What the program asks for

- **Who applies:** core maintainers, or people who run a widely used public project. Either one is enough, and the owner, as sole maintainer, is a core maintainer. Projects that fit neither but play an important role in the ecosystem are told to "apply anyway and explain why".
- **Hard requirements:** a valid ChatGPT account, and accurate details about you, your repositories and your role. OpenAI may verify identity, repository affiliation, maintainer status or control.
- **How OpenAI decides:** it "may consider" repository usage, ecosystem importance, evidence of active maintenance, role or permissions, and program capacity. These are factors to weigh, not requirements. Decisions are discretionary and final.
- **Not required:** no minimum number of users, testers, stars or downloads, and no license type or project age. Usage is not needed to apply.
- **Benefits:**
  - six months of ChatGPT Pro with Codex;
  - conditional Codex Security access for core maintainers with write access, limited to repositories you own or administer;
  - API credits from the Codex Open Source Fund, for projects that use Codex in pull-request review, maintainer automation, release workflows or other core work.
  - Benefits are personal, non-transferable, have no cash value and may expire.
- **Confidentiality:** application materials are not treated as confidential, so don't include private information.

## Already in place

- **License:**
  - GPL-3.0-only, an OSI-approved open-source license (`LICENSE`);
  - `NOTICE` with the section 7 name and attribution terms;
  - `TRADEMARKS.md` and `PRIVACY.md`.
- **README:** explains what Roadleaf is: a reader for existing text, with no voices or stories of its own.
- **CI:** the checks workflow runs in public repositories; signed builds stay owner-only.
- **Maintainer role:** the owner administers `fdmcadmin/Audio_Reader-travel`.
- **Active development:** in development since 23 October 2026 (41 commits before the public history was reset); as of 0.3.24, 230 unit tests and 89 on-device checks pass.

## Needed before applying

1. **Publish the code.** The terms don't say "public repository" in so many words, but the program is for open-source maintainers and OpenAI checks your repositories, so it has to see the code. Done: the repository is public.
   - Its development history was replaced by one clean starting commit. The old commits, branches, tags and `-personal` test releases were removed from GitHub after a full local backup.
   - The repository was recreated rather than force-pushed, because GitHub keeps a pull request's commits even after a force-push.
2. **Remove personal content first.** Done, now on `main`:
   - novel titles, character names and the hard-coded main character are gone from code, tests and docs;
   - work, phone, headphone, antivirus and schedule details are replaced with general wording;
   - personal test logs (`STATUS.md`, per-version release notes, smoke-test reports) left the public tree, and `CHANGELOG.md` replaces them.
3. **Merge the reviewed branches.** Done: all reviewed work, including the GPLv3 and open-source files, was merged into `main` on 9 November 2026, before the history was reset.

## Recommended, not required

4. **Contributor files:**
   - `CONTRIBUTING.md`, including whether contributors sign a contributor agreement. A CLA keeps your option to relicense, or to ship your private engines inside your own builds, after accepting outside code.
   - `CODE_OF_CONDUCT.md`;
   - `SECURITY.md` (how to report a vulnerability; expected if you request Codex Security);
   - issue templates.
5. **Public release notes.** Done: `CHANGELOG.md`.
6. **For API credits only.** Show a Codex-powered maintainer workflow, for example Codex reviewing pull requests on the repository.
7. **Optional.** Give the public repository the app's name, for example `roadleaf`.

## Optional: makes the application stronger

Usage is one factor OpenAI may weigh, so any of these helps, but none is needed to apply:
- public GitHub Releases that people download;
- issues or feedback from people who use it;
- a store listing such as F-Droid or Google Play.

Google Play has its own rules for publishing there: new personal developer accounts must run a closed test with 12 testers for 14 days, and new apps need target API 36 and a release key. None of these apply to OpenAI's program.

## Points for the application (owner to confirm)

- **Project:** Roadleaf, an offline Android reader that narrates text the user already has: imported books, any accessible webpage, or their own NovelAI stories. It uses installed voices, with a narrator that keeps each character's voice consistent.
- **Role:** sole maintainer and owner.
- **Why it matters:**
  - hands-free, offline, private reading for people who can't look at a screen, such as drivers;
  - no cloud costs or accounts;
  - it respects each site's sign-in, locks and paywalls.
- **How Codex would be used:** feature work, test maintenance and pull-request review.
