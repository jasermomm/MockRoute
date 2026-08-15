# MockRoute v1.0.4

MockRoute is an Android mock-location simulator built for development, QA, demonstrations, and controlled testing.

## Highlights

- Corrective map build with the supplied working `OsmMapView` display logic ported directly
- Static mock locations
- Direct A → B Travel with waypoints
- Road-following Drive using full OSRM geometry
- User-verified Leaflet/OpenStreetMap display path with no API key
- Multilingual place search
- Speed profiles, realism, delay, pause/resume, and seek
- Saved places, routes, recents, JSON backup, and GPX
- Background simulation with notification controls
- Light/dark/system themes and accent colors

## Setup

Enable Developer options, then select **MockRoute** under **Select mock location app**.

## Privacy

No accounts, analytics, telemetry, ads, or cloud database. Map rendering loads pinned Leaflet files from unpkg or jsDelivr; map, search, and Drive use OpenStreetMap-related online services.

## Important

Android intentionally marks test-provider locations as mock. MockRoute does not attempt to conceal this.

The version shown at the bottom of Settings must read **MockRoute 1.0.4 • build 5**.
