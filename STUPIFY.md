# Stupify

Stupify is the Android app for [stupify.iaffro.com](https://stupify.iaffro.com), a fork of
[Tempus](https://github.com/eddyizm/tempus) by eddyizm and contributors (GPL-3.0). It adds:

- the `stupify` product flavor (`com.iaffro.stupify`): the tempus flavor (Cast, Android Auto) with Stupify branding
- in-app updates from the Stupify server (`app/src/main/java/com/eddyizm/tempus/stupify/StupifyUpdater.kt`)

Stupify code lives in `com.eddyizm.tempus.stupify` and `app/src/stupify/`, with small hooks in upstream files,
so upstream Tempus releases can be merged in.

## Building

    ./gradlew assembleStupifyDebug

Release builds are signed with a key kept outside the repo (`~/.stupify_app_keystore.properties`:
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; or point `STUPIFY_SIGNING` at another file).
`./release.sh "changelog"` builds and publishes a release to the server after bumping `versionCode`/`versionName`
in the `stupify` flavor.
