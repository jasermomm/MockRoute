import assert from 'node:assert/strict';
import fs from 'node:fs';

const source = [
  'app/src/main/java/com/jasermomm/mockroute/ui/MapView.kt',
  'app/src/main/java/com/jasermomm/mockroute/ui/OsmMapView.kt',
].map((path) => fs.readFileSync(path, 'utf8')).join('\n');

for (const contract of [
  'WebView(context)',
  'loadDataWithBaseURL("https://mockroute.local/"',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js',
  'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js',
  'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
  "tiles.on('tileload'",
  "tiles.on('tileerror'",
  "map.on('click'",
  "map.on('contextmenu'",
  'map.fitBounds',
  'map.invalidateSize(false)',
  'MockRouteAndroid.onTileReady()',
  'mockroute-web-map-tile-rendered',
  'MockRoute/1.0.4',
]) {
  assert.ok(source.includes(contract), `missing proven map contract: ${contract}`);
}
assert.doesNotMatch(source, /MapLibre|android-sdk-opengl|map_style\.json/);

const match = source.match(/<script>\s*\n([\s\S]*?)\n\s*<\/script>/);
assert.ok(match, 'embedded Leaflet controller script was not found');
const script = match[1];
new Function(script);

const mapHandlers = {};
const tileHandlers = {};
const calls = {
  engineReady: 0,
  tileReady: 0,
  tileError: 0,
  taps: [],
  longPresses: [],
  markers: 0,
  polylines: 0,
  invalidates: 0,
};
const map = {
  zoom: 11,
  setView(_point, zoom) { if (Number.isFinite(zoom)) this.zoom = zoom; return this; },
  setZoom(zoom) { this.zoom = zoom; return this; },
  getZoom() { return this.zoom; },
  fitBounds() { return this; },
  panTo() { return this; },
  invalidateSize() { calls.invalidates += 1; return this; },
  on(name, callback) { mapHandlers[name] = callback; return this; },
  whenReady(callback) { callback(); return this; },
};
const removable = () => ({ remove() {}, setLatLng() { return this; } });
const L = {
  map() { return map; },
  tileLayer() {
    return {
      addTo() { return this; },
      on(name, callback) { tileHandlers[name] = callback; return this; },
    };
  },
  divIcon(options) { return options; },
  marker() { calls.markers += 1; return { ...removable(), addTo() { return this; } }; },
  polyline() { calls.polylines += 1; return { ...removable(), addTo() { return this; } }; },
};
const window = { L };
const document = { getElementById: () => ({ style: {} }) };
const MockRouteAndroid = {
  onMapError(message) { throw new Error(message); },
  onReady() { calls.engineReady += 1; },
  onTileReady() { calls.tileReady += 1; },
  onTileError() { calls.tileError += 1; },
  onMapTap(lat, lon) { calls.taps.push([lat, lon]); },
  onMapLongPress(lat, lon) { calls.longPresses.push([lat, lon]); },
  onZoomChanged() {},
};

new Function('window', 'document', 'L', 'MockRouteAndroid', 'setTimeout', script)(
  window,
  document,
  L,
  MockRouteAndroid,
  (callback) => callback(),
);

assert.equal(calls.engineReady, 1);
tileHandlers.tileload();
assert.equal(calls.tileReady, 1);
for (let index = 0; index < 6; index += 1) tileHandlers.tileerror();
assert.equal(calls.tileError, 1);

window.MR.setState(JSON.stringify({
  accent: '#2458D3',
  controls: [{ label: 'A', lat: 30.0, lon: 31.0 }, { label: 'B', lat: 30.1, lon: 31.1 }],
  route: [{ lat: 30.0, lon: 31.0 }, { lat: 30.05, lon: 31.02 }, { lat: 30.1, lon: 31.1 }],
  active: { lat: 30.02, lon: 31.01 },
  follow: true,
}));
assert.equal(calls.polylines, 1);
assert.equal(calls.markers, 3);

mapHandlers.click({ latlng: { lat: 29.9, lng: 30.9 } });
mapHandlers.contextmenu({ latlng: { lat: 29.8, lng: 30.8 } });
assert.deepEqual(calls.taps, [[29.9, 30.9]]);
assert.deepEqual(calls.longPresses, [[29.8, 30.8]]);

window.MR.setCenter(30, 31, 15);
window.MR.setZoom(16);
window.MR.fit(JSON.stringify([{ lat: 30, lon: 31 }, { lat: 30.1, lon: 31.1 }]));
window.MR.setFollow(false);
window.MR.setActive(JSON.stringify({ lat: 30.04, lon: 31.03 }));
window.MR.invalidate();
assert.ok(calls.invalidates >= 2);

console.log('Proven WebView/Leaflet map syntax, bridge, tiles, gestures, route, and controls passed');
