# Releasing

No release is created automatically by repository CI.

Before publishing a release:

1. confirm `versionCode` and `versionName` in `app/build.gradle.kts`;
2. run all required GitHub Actions checks from [VALIDATION.md](VALIDATION.md);
3. install the exact CI-built debug APK and complete installed-device acceptance;
4. configure a trusted Android signing key through repository secrets without committing credentials;
5. build and verify the signed release APK;
6. record APK SHA-256, signer identity, source commit, Actions run, and migration notice;
7. explicitly disclose that `com.thoitiettxl.eta` is a clean install replacing the old development identity `fuck.andes.browser` and requires re-pairing;
8. explicitly disclose the permissive WebView security boundary.

Creating a GitHub Release, publishing an APK, or publishing the CLI package requires separate explicit authorization.

## Signed release candidate workflow

The manually dispatched `Build signed release APK` workflow builds candidates
only from an exact commit on `main`. Configure these repository-level values:

- secret `ETA_RELEASE_KEYSTORE_B64`: base64-encoded Android JKS keystore;
- secret `ETA_RELEASE_STORE_PASSWORD`: keystore password;
- secret `ETA_RELEASE_KEY_PASSWORD`: private-key password;
- variable `ETA_RELEASE_KEY_ALIAS`: signing-key alias.

GitHub Secrets are not a recoverable backup. Keep the original keystore and its
credentials in a separate protected location because every future update to the
same Android application ID must use the same signing identity.

After required CI passes for the merged `main` commit, dispatch the workflow
with that exact 40-character commit as `expected_commit`. The workflow refuses
other branches or mismatched commits, materializes the keystore only under the
runner temporary directory, builds `:app:assembleRelease`, verifies the APK with
`apksigner`, and uploads the APK together with SHA-256, signer, source commit,
and Actions-run evidence.

Download and test that exact candidate according to [VALIDATION.md](VALIDATION.md).
Do not rebuild or substitute another APK when publishing. GitHub Release
creation and APK publication remain separately authorized operations after
installed-device acceptance.

For release `v1.0.0`, disclose that installation is clean, the retired
`fuck.andes.browser` package is not migrated, re-pairing is required, and Eta
Browser's permissive WebView is not a security sandbox.
