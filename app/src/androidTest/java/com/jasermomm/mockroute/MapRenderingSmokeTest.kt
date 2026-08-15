package com.jasermomm.mockroute

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapRenderingSmokeTest {
    @Test
    fun provenLeafletPathLoadsTilesAndProducesANonBlankFrame() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var rendered = false
            var nonBlank = false
            repeat(160) {
                scenario.onActivity { activity ->
                    val webView = findWebView(activity.window.decorView)
                    rendered = webView?.parent?.let { parent ->
                        (parent as? View)?.tag == "mockroute-web-map-tile-rendered"
                    } == true
                    val bitmap = if (rendered && webView != null && webView.width > 0 && webView.height > 0) {
                        createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also {
                            webView.draw(Canvas(it))
                        }
                    } else null
                    nonBlank = bitmap?.hasVisibleMapDetail() == true
                }
                if (rendered && nonBlank) return@use
                Thread.sleep(250)
            }
            assertTrue("Leaflet never reported a successfully loaded OSM tile", rendered)
            assertTrue("The rendered WebView map frame was blank", nonBlank)
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun Bitmap.hasVisibleMapDetail(): Boolean {
        val colors = HashSet<Int>()
        val xStep = (width / 12).coerceAtLeast(1)
        val yStep = (height / 20).coerceAtLeast(1)
        var y = yStep / 2
        while (y < height) {
            var x = xStep / 2
            while (x < width) {
                colors += getPixel(x, y)
                if (colors.size >= 12) return true
                x += xStep
            }
            y += yStep
        }
        return false
    }
}
