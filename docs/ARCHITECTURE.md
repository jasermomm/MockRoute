# Architecture

- `core`: deterministic geographic, route, speed-profile, timing, noise, GPX, and validation logic.
- `network`: explicit-submit Nominatim search and direction-aware OSRM routing.
- `data`: DataStore settings plus on-device JSON saved-place/route/recents storage.
- `location`: real-location candidate selection and the Android test-provider controller.
- `service`: the single owner of the simulation loop, test provider, wake lock, and notification.
- `ui`: Compose screens plus the directly ported platform `OsmMapView` from the user-tested reference, using pinned Leaflet CDNs, OSM tiles, a narrow state bridge, and visual-state-confirmed readiness. WebView remains a normal platform view without synthetic lifecycle pausing.

The foreground service serializes control operations on one dispatcher. Stop cancels and joins the active worker before removing the provider, preventing a late iteration from recreating or publishing to it.
