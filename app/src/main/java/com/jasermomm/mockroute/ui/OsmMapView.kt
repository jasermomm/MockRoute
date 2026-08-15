package com.jasermomm.mockroute.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.net.toUri
import com.jasermomm.mockroute.core.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Direct port of the user-tested WebView/Leaflet map host. The platform view
 * owns rendering without a synthetic map lifecycle or an intermediate SDK.
 */
internal class OsmMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), MapRuntime {
    var onPick: ((GeoPoint) -> Unit)? = null
    var onLongPick: ((GeoPoint) -> Unit)? = null
    var onTilesUnavailable: (() -> Unit)? = null
    var onTileRendered: (() -> Unit)? = null
    var onMapError: ((String) -> Unit)? = null

    private var center = GeoPoint(20.0, 0.0)
    private var zoom = 3
    private var latestState = MapUiState()
    private var renderedState: MapUiState? = null
    private var ready = false
    private var destroyed = false
    private var tileReported = false
    private var lastError: String? = null
    private var pendingFit: List<GeoPoint>? = null

    private val webView = WebView(context)

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
        loadMap()
        contentDescription = "Interactive OpenStreetMap. Tap to choose coordinates."
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(Color.rgb(233, 236, 239))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadsImagesAutomatically = true
            blockNetworkImage = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "$userAgentString MockRoute/1.0.4"
        }
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        webView.addJavascriptInterface(Bridge(), "MockRouteAndroid")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (!request.isForMainFrame || url.host == "mockroute.local") return false
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toString().toUri()))
                }
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) reportError("Map page could not load")
            }
        }
    }

    private fun loadMap() {
        val background = "#e9ecef"
        val foreground = "#202124"
        val html = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
              <meta name="referrer" content="origin" />
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" onerror="this.onerror=null;this.href='https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css'" />
              <style>
                html,body,#map{width:100%;height:100%;margin:0;padding:0;background:$background;overflow:hidden}
                .leaflet-container{background:$background;font-family:system-ui,-apple-system,sans-serif}
                .leaflet-control-attribution{font-size:10px!important;background:rgba(255,255,255,.88)!important;color:#333!important}
                .mr-pin{width:28px;height:28px;border-radius:50% 50% 50% 0;transform:rotate(-45deg);border:3px solid white;box-shadow:0 2px 7px rgba(0,0,0,.28)}
                .mr-pin span{display:flex;width:100%;height:100%;align-items:center;justify-content:center;transform:rotate(45deg);font:bold 12px system-ui;color:white}
                .mr-active{width:16px;height:16px;border-radius:50%;background:#1565c0;border:3px solid white;box-shadow:0 0 0 10px rgba(21,101,192,.20)}
                #fatal{display:none;position:absolute;inset:0;z-index:9999;background:$background;color:$foreground;align-items:center;justify-content:center;text-align:center;padding:28px;font:500 15px system-ui;box-sizing:border-box}
              </style>
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" onerror="this.onerror=null;this.src='https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js'"></script>
            </head>
            <body>
              <div id="map"></div><div id="fatal">Map unavailable<br><small>You can still enter coordinates manually.</small></div>
              <script>
              (function(){
                const fatal = document.getElementById('fatal');
                function fail(message){ fatal.style.display='flex'; try{MockRouteAndroid.onMapError(String(message||'Map unavailable'));}catch(e){} }
                if(!window.L){ fail('Leaflet failed to load'); return; }
                const map = L.map('map', {zoomControl:false, attributionControl:true, preferCanvas:true, fadeAnimation:false, zoomAnimation:true, markerZoomAnimation:false, worldCopyJump:true}).setView([20,0],3);
                const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                  minZoom:2,maxZoom:19,tileSize:256,keepBuffer:3,updateWhenIdle:true,updateWhenZooming:false,
                  attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                }).addTo(map);
                let tileFailures=0,tileReported=false;
                tiles.on('tileload',()=>{ tileFailures=0; if(!tileReported){tileReported=true;try{MockRouteAndroid.onTileReady();}catch(e){}} });
                tiles.on('tileerror',()=>{ tileFailures++; if(tileFailures===6){ try{MockRouteAndroid.onTileError();}catch(e){} } });

                let routeLine=null, markers=[], activeMarker=null, follow=false, suppressMove=false;
                const valid=p=>p && Number.isFinite(p.lat) && Number.isFinite(p.lon) && p.lat>=-90 && p.lat<=90 && p.lon>=-180 && p.lon<=180;
                const cleanLabel=value=>String(value||'').replace(/[^A-Za-z0-9]/g,'').slice(0,2);
                const pinColor=label=>label==='A'?'#238636':label==='B'?'#d73a49':(/^\d+$/.test(label)?'#f59e0b':null);
                const mkIcon=(color,label)=>L.divIcon({className:'',html:'<div class="mr-pin" style="background:'+color+'"><span>'+cleanLabel(label)+'</span></div>',iconSize:[28,28],iconAnchor:[14,26]});
                const activeIcon=L.divIcon({className:'',html:'<div class="mr-active"></div>',iconSize:[22,22],iconAnchor:[11,11]});
                const addMarker=(p,color,label)=>{if(valid(p))markers.push(L.marker([p.lat,p.lon],{icon:mkIcon(color,label),interactive:false}).addTo(map));};
                const clearGeometry=()=>{markers.forEach(m=>m.remove());markers=[];if(routeLine){routeLine.remove();routeLine=null;}};
                const setActive=p=>{
                  if(valid(p)){
                    if(!activeMarker)activeMarker=L.marker([p.lat,p.lon],{icon:activeIcon,interactive:false,zIndexOffset:1000}).addTo(map);
                    else activeMarker.setLatLng([p.lat,p.lon]);
                    if(follow)map.panTo([p.lat,p.lon],{animate:false});
                  }else if(activeMarker){activeMarker.remove();activeMarker=null;}
                };
                window.MR={
                  setState(json){
                    let s; try{s=JSON.parse(json)}catch(e){return}
                    clearGeometry();
                    const accent=/^#[0-9a-fA-F]{6}$/.test(s.accent)?s.accent:'#2458D3';
                    const route=Array.isArray(s.route)?s.route.filter(valid):[];
                    const controls=Array.isArray(s.controls)?s.controls.filter(valid):[];
                    const line=route.length>=2?route:controls;
                    if(line.length>=2)routeLine=L.polyline(line.map(p=>[p.lat,p.lon]),{color:accent,weight:6,opacity:.9,lineCap:'round',lineJoin:'round',interactive:false}).addTo(map);
                    controls.forEach(p=>{const label=cleanLabel(p.label);addMarker(p,pinColor(label)||accent,label);});
                    follow=!!s.follow;
                    setActive(s.active);
                  },
                  setCenter(lat,lon,z){ if(!Number.isFinite(lat)||!Number.isFinite(lon))return; suppressMove=true; map.setView([lat,lon],Number.isFinite(z)?z:map.getZoom(),{animate:false}); setTimeout(()=>suppressMove=false,0); },
                  setZoom(z){ suppressMove=true; map.setZoom(Math.max(2,Math.min(19,z))); setTimeout(()=>suppressMove=false,0); },
                  fit(pointsJson){ let p;try{p=JSON.parse(pointsJson)}catch(e){return} const ll=p.filter(valid).map(x=>[x.lat,x.lon]); if(!ll.length)return; suppressMove=true; if(ll.length===1)map.setView(ll[0],Math.max(map.getZoom(),14),{animate:false}); else map.fitBounds(ll,{padding:[48,72],maxZoom:17,animate:false}); setTimeout(()=>suppressMove=false,0); },
                  setFollow(v){follow=!!v;},
                  setActive(json){let p;try{p=JSON.parse(json)}catch(e){p=null}setActive(p);},
                  invalidate(){map.invalidateSize(false);}
                };
                map.on('click',e=>{try{MockRouteAndroid.onMapTap(e.latlng.lat,e.latlng.lng);}catch(err){}});
                map.on('contextmenu',e=>{try{MockRouteAndroid.onMapLongPress(e.latlng.lat,e.latlng.lng);}catch(err){}});
                map.on('zoomend',()=>{try{MockRouteAndroid.onZoomChanged(map.getZoom());}catch(err){}});
                map.whenReady(()=>{setTimeout(()=>map.invalidateSize(false),0);try{MockRouteAndroid.onReady(map.getZoom());}catch(err){}});
                setTimeout(()=>{if(!window.L||!map)fail('Map engine did not initialize');},8000);
              })();
              </script>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://mockroute.local/", html, "text/html", "UTF-8", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady(currentZoom: Int) = post {
            if (destroyed) return@post
            ready = true
            zoom = currentZoom.coerceIn(2, 19)
            renderedState = null
            pushState()
            val fit = pendingFit.also { pendingFit = null }
            if (!fit.isNullOrEmpty()) fitPoints(fit)
            else js("window.MR&&MR.setCenter(${center.latitude},${center.longitude},$zoom);")
        }

        @JavascriptInterface
        fun onTileReady() = post {
            if (destroyed || tileReported) return@post
            tileReported = true
            // A network tile event is not enough: wait until WebView confirms that
            // the corresponding visual state has been submitted for display.
            webView.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (destroyed) return
                    webView.invalidate()
                    tag = MAP_RENDERED_TAG
                    onTileRendered?.invoke()
                }
            })
        }

        @JavascriptInterface
        fun onTileError() = post {
            if (!destroyed && !tileReported) onTilesUnavailable?.invoke()
        }

        @JavascriptInterface
        fun onMapTap(lat: Double, lon: Double) = post {
            GeoPoint(lat, lon).takeIf { it.isValid }?.let { onPick?.invoke(it) }
        }

        @JavascriptInterface
        fun onMapLongPress(lat: Double, lon: Double) = post {
            GeoPoint(lat, lon).takeIf { it.isValid }?.let { onLongPick?.invoke(it) }
        }

        @JavascriptInterface
        fun onZoomChanged(value: Int) = post { zoom = value.coerceIn(2, 19) }

        @JavascriptInterface
        fun onMapError(message: String) = post { reportError(message) }
    }

    private fun reportError(message: String) {
        if (message == lastError || destroyed) return
        lastError = message
        onMapError?.invoke(message)
    }

    override fun render(state: MapUiState) {
        latestState = state
        pushState()
    }

    private fun pushState() {
        if (!ready || destroyed || renderedState == latestState) return
        val state = latestState
        val previous = renderedState
        val geometryChanged = previous == null ||
            previous.controls != state.controls ||
            previous.route != state.route ||
            previous.accent != state.accent
        if (geometryChanged) {
            val payload = JSONObject()
                .put("accent", normalizedAccent(state.accent))
                .put("controls", JSONArray().also { array ->
                    state.controls.forEach { (label, point) -> array.put(pointJson(point).put("label", label)) }
                })
                .put("route", JSONArray().also { array -> state.route.forEach { array.put(pointJson(it)) } })
                .put("active", state.active?.let(::pointJson) ?: JSONObject.NULL)
                .put("follow", state.follow)
            js("window.MR&&MR.setState(${JSONObject.quote(payload.toString())});")
        } else {
            if (previous.follow != state.follow) {
                js("window.MR&&MR.setFollow(${state.follow});")
            }
            if (previous.active != state.active || (previous.follow != state.follow && state.follow)) {
                val active = state.active?.let(::pointJson) ?: JSONObject.NULL
                js("window.MR&&MR.setActive(${JSONObject.quote(active.toString())});")
            }
        }
        renderedState = state
    }

    override fun center(point: GeoPoint, zoom: Int) {
        if (!point.isValid) return
        center = point
        pendingFit = null
        this.zoom = zoom.coerceIn(2, 19)
        js("window.MR&&MR.setCenter(${point.latitude},${point.longitude},${this.zoom});")
    }

    override fun fit(state: MapUiState) {
        val points = when {
            state.route.isNotEmpty() -> state.route
            state.controls.isNotEmpty() -> state.controls.map { it.second }
            state.active != null -> listOf(state.active)
            else -> emptyList()
        }
        fitPoints(points)
    }

    private fun fitPoints(points: List<GeoPoint>) {
        val valid = points.filter { it.isValid }
        if (valid.isEmpty()) return
        if (!ready) {
            pendingFit = valid
            return
        }
        if (valid.size == 1) {
            center(valid.first(), max(zoom, 14))
            return
        }
        val json = JSONArray().also { array -> valid.forEach { array.put(pointJson(it)) } }
        js("window.MR&&MR.fit(${JSONObject.quote(json.toString())});")
    }

    override fun zoomIn() = setZoom(zoom + 1)
    override fun zoomOut() = setZoom(zoom - 1)

    private fun setZoom(value: Int) {
        zoom = value.coerceIn(2, 19)
        js("window.MR&&MR.setZoom($zoom);")
    }

    override fun repaint() {
        js("window.MR&&MR.invalidate();")
    }

    private fun pointJson(point: GeoPoint) = JSONObject()
        .put("lat", point.latitude)
        .put("lon", point.longitude)

    private fun normalizedAccent(value: String): String =
        value.takeIf { it.matches(Regex("^#[0-9a-fA-F]{6}$")) } ?: "#2458D3"

    private fun js(script: String) {
        if (!ready || destroyed) return
        webView.post { if (!destroyed) webView.evaluateJavascript(script, null) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ready) webView.post {
            webView.evaluateJavascript("window.dispatchEvent(new Event('resize'));window.MR&&MR.invalidate();", null)
        }
    }

    fun release() {
        if (destroyed) return
        destroyed = true
        ready = false
        webView.removeJavascriptInterface("MockRouteAndroid")
        webView.stopLoading()
        webView.loadUrl("about:blank")
        removeView(webView)
        webView.destroy()
        removeAllViews()
    }
}
