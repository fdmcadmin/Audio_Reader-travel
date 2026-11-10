# Builds, signing and releases

## Repository

The project lives at `fdmcadmin/Audio_Reader-travel`. No authentication secret is in source files. Never place imported novels, private book files, lorebooks or voice files in the repository.
- **Checks workflow:** runs in private or public repositories.
- **Signed-release workflow:** runs only when the repository owner starts it on `main`.
- **Artifacts in a public repository:** any signed-in GitHub user can download them. Publish official builds through Releases or Google Play rather than relying on workflow artifacts.
- **The repository is public:** anyone can download what is attached to a release, so attach only builds meant for others to install.

The CI workflow checks compilation, parser/policy/streaming tests and Android lint for development and compact variants, then uploads both APKs with seven-day retention. Use the compact APK for device testing: it has code/resource shrinking enabled and development debugging disabled, while still using a development signing key and `.debug` package ID. Actions use pinned commit SHAs and read-only repository permissions. GitHub Actions minutes and storage limits depend on the account and the repository's visibility; narration itself has no cloud hourly charge. No paid service is enabled by the app.

A locally built compact APK updates an earlier local development install because its package ID and development key match and its version code is higher. A CI-built development APK may use another development certificate and cannot update that install. Do not uninstall a library you need to preserve merely to work around a certificate mismatch; use a consistently signed release for ongoing updates.

## Stable release key

**Test builds.** A development-signed compact APK and its SHA-256 checksum can be attached to a GitHub pre-release. It updates an existing development (`.debug`) install signed with the same key, keeping its library and voice settings: download it from the release's Assets, open it and choose Update. Local compilation, tests, lint and signature verification come first, and only the APK and checksum are uploaded. This is separate from the production release workflow below.

Create your release keystore interactively on a trusted computer, outside this repository, using Java's `keytool -genkeypair` with RSA 3072 or better and a long validity (for example 10,000 days). Let keytool prompt for passwords; do not put passwords in commands, chat or source files. Use a private backup location and keep a second encrypted backup. Losing the key prevents installing future updates over the existing signed app.

Create a GitHub environment named `private-release`. Add required reviewers and restrict deployment branches to `main` where your plan supports these protections. Store these **environment secrets**, never repository files:

* `SIGNING_KEYSTORE_BASE64`: Base64 encoding of the keystore (encoding does not encrypt it).
* `SIGNING_STORE_PASSWORD`
* `SIGNING_KEY_ALIAS`
* `SIGNING_KEY_PASSWORD`

Run **Signed APK for private installation** manually from `main`, supplying an increasing positive version code. The workflow restores the key under runner temporary storage with restrictive permissions, builds/tests/lints, verifies the APK signature, writes a SHA-256 checksum, uploads APK/checksum only, then removes the temporary key even on failure. Do not add debug signing as a release fallback. Do not enable release signing for pull requests or use `pull_request_target` to build untrusted code with secrets.

Download the artifact while signed into GitHub, unzip it on your phone/computer and install the APK. Record the signing certificate SHA-256 fingerprint from the verification step and compare it on subsequent releases. A checksum verifies file integrity; the stable signing certificate establishes update identity. Debug and release use different package IDs and do not share library data.

Local signed builds read the same four signing environment variables (store file path plus password/alias/password); provide them locally through your own secure mechanism. Release signing fails without a configured key. No release keystore creation, secret upload or signed release has been performed by the baseline setup. Android's debug build creates a development-only signing key outside the repository.
