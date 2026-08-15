# MockRoute

MockRoute is a local-first Android mock-location simulator for development, QA, demos, and controlled personal tests.

## Features

- Static mock locations
- Direct, geodesic Travel with multi-point routes
- Road-following Drive using complete OSRM road geometry
- Multilingual and local-script place search
- Interactive Leaflet/OpenStreetMap map using the display path verified on the target Samsung device
- Real-device location selection on demand
- Duration-locked speed profiles, realism controls, delay, pause, resume, and seek
- Saved places, saved routes, recents, JSON backup, and GPX import/export
- Foreground simulation with notification controls
- System/light/dark themes and curated accent colors

## Requirements and installation

Android 8.0 (API 26) or newer.

1. Install the APK.
2. Enable Android Developer options.
3. Open **Developer options → Select mock location app → MockRoute**.
4. Open MockRoute, choose a mode, and start the simulation.

## Modes

- **Static:** hold one selected coordinate until stopped.
- **Travel:** move directly across the globe through optional waypoints.
- **Drive:** request a road route and traverse the returned direction-aware road geometry. Drive requires a valid route and never falls back to Travel.

## Place search and Drive

Search uses the public OpenStreetMap Nominatim service only after an explicit submit. Results depend on OpenStreetMap data, so English, local-language, and native-script coverage varies by place. Requests are rate-limited and cached.

Drive uses the public OSRM demonstration service by default. The app requests full GeoJSON road geometry in longitude/latitude order, shows no-route and server failures separately, and reroutes when reversing a Drive route. Both endpoints can be changed in Settings without an app update.

## Privacy

No account, analytics, ads, telemetry, Firebase, trackers, or cloud database. Saved content remains on the device. Network access is used for the pinned Leaflet map files, map tiles, place search, and Drive routing. Leaflet loads from unpkg with jsDelivr as a fallback. Do not enter private or confidential data into place search.

## Build

Use Android Studio with JDK 17, or run:

```bash
./gradlew clean test lint assembleDebug
```

Release signing can use `MOCKROUTE_KEYSTORE_FILE`, `MOCKROUTE_KEYSTORE_PASSWORD`, `MOCKROUTE_KEY_ALIAS`, and `MOCKROUTE_KEY_PASSWORD`. Without them, local release builds use Android's normal debug signing so they remain sideloadable; no key is stored in this repository.

For signed GitHub tag releases, add Actions secrets named `MOCKROUTE_KEYSTORE_BASE64` (the Base64-encoded keystore), `MOCKROUTE_KEYSTORE_PASSWORD`, `MOCKROUTE_KEY_ALIAS`, and `MOCKROUTE_KEY_PASSWORD`. The workflow decodes the key only into the temporary runner directory.

## Limitations

- Android intentionally identifies test-provider locations as mock through `Location.isMock()`. MockRoute never conceals or bypasses that marker.
- Map, search, and Drive need internet access and depend on best-effort Leaflet CDN and OpenStreetMap ecosystem services.
- Force-stopping the app stops Android from allowing its foreground service to continue.

OpenStreetMap data © OpenStreetMap contributors (ODbL). Map rendering uses Leaflet 1.9.4 (BSD-2-Clause). Road routing uses OSRM.
