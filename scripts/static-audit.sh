#!/usr/bin/env bash
set -euo pipefail

source_root="app/src/main"
if rg -n -i 'coming soon|placeholder map|fake search|dummy route|anti[- ]detection|bypass.*isMock|TODO\b|FIXME\b' "$source_root"; then
  echo "Static contract audit failed: placeholder or bypass marker found" >&2
  exit 1
fi

required=(
  'ACCESS_MOCK_LOCATION'
  'FOREGROUND_SERVICE_SPECIAL_USE'
  'PROPERTY_SPECIAL_USE_FGS_SUBTYPE'
  'MockLocationController'
  'setTestProviderLocation'
  'NominatimClient'
  'OsrmClient'
  'overview=full'
  'geometries=geojson'
  'GpxCodec'
  'ACTION_PAUSE'
  'ACTION_RESUME'
  'ACTION_STOP'
  'android.webkit.WebView'
  'loadDataWithBaseURL'
  'unpkg.com/leaflet@1.9.4'
  'cdn.jsdelivr.net/npm/leaflet@1.9.4'
  'tile.openstreetmap.org'
  'onTileReady'
  'MockRoute/1.0.4'
)
for contract in "${required[@]}"; do
  rg -q "$contract" "$source_root" || { echo "Missing contract: $contract" >&2; exit 1; }
done

if rg -n 'geometry\.reversed\(\).*DRIVE|mode == SimulationMode\.DRIVE.*geometry\.reversed' app/src/main/java; then
  echo "Drive geometry must not be blindly reversed" >&2
  exit 1
fi

if rg -n 'MapLibre|android-sdk-opengl|map_style\.json|WebViewAssetLoader' app/src/main app/build.gradle; then
  echo "The production map must use only the proven reference display path" >&2
  exit 1
fi

echo "Static contract audit passed"
