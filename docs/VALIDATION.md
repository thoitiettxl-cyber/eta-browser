# Validation

## Required repository checks

GitHub Actions is the authority for Android build and lint because the Alpine development chroot is not an accepted Android build environment.

Required CI results:

- `:app:assembleDebug` passes and uploads `eta-browser-debug.apk`;
- `:app:lintDebug` passes;
- CLI syntax and all host tests pass;
- Pi adapter syntax and fake-bridge tests pass;
- no source or manifest reference to the retired package `fuck.andes.browser` remains.

Android unit-test sources are retained, but Android unit-test tasks are not a release gate. Runtime behavior is validated with the CI-built APK on the Android device.

## Installed-APK acceptance

For the package migration and extraction release, verify:

1. disable or uninstall the old `fuck.andes.browser` development app so it cannot retain port `18765`;
2. install the CI-built `com.thoitiettxl.eta` APK as a clean app;
3. confirm old pairing data is absent and a new pairing is required;
4. pair and enable the bridge;
5. before ever opening BrowserActivity, run CLI health, navigation, readable extraction, interaction, and screenshot; visually confirm that the first screenshot is not blank;
6. run reset and signal cancellation;
7. run Pi health, navigation, readable extraction, screenshot consumption, and `USER_CONTROL_ACTIVE` behavior;
8. confirm the listener is loopback-only and disabling the bridge removes it;
9. confirm package identity with Android package inspection;
10. capture the tested commit, Actions run, artifact ID, APK SHA-256, and device transcript.

A release must not be published while required CI or installed-APK acceptance is missing.
