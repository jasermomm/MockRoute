# Contributing

Use JDK 17 and the Android SDK declared by the project. Before opening a pull request, run `./gradlew clean test lint assembleDebug` and `node scripts/web-map-contract-test.mjs`. Keep mock-location behavior transparent: do not add anti-detection, root, exploit, or hidden-API bypasses.
