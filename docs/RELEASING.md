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
