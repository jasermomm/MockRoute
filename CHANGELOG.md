# Changelog

## 1.0.4 — 2026-08-15

- Initial production release.
- Added Static, direct Travel, and road-following Drive simulation.
- Added multilingual place search, saved places/routes, recents, JSON backup, and GPX support.
- Added background simulation, notification controls, pause/resume/seek, speed profiles, realism, themes, and accent colors.
- Ported the user-tested `OsmMapView` display path directly: WebView `loadDataWithBaseURL`, Leaflet 1.9.4 primary/fallback CDNs, OpenStreetMap raster tiles, route overlays, markers, and visual-state-confirmed readiness.
- Removed the extra WebView lifecycle/render hooks used by the broken adaptation.
- Added a visible version/build label in Settings so tester APKs cannot be confused.
