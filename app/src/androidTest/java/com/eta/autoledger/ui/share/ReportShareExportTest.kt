package com.eta.autoledger.ui.share

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eta.autoledger.ui.theme.OilPalette
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportShareExportTest {

    @Test
    fun graphicsLayerCapturesNonEmptyBitmap() {
        val graphicsLayer = AtomicReference<GraphicsLayer?>()
        val composeView = AtomicReference<ComposeView?>()
        val drawn = CountDownLatch(1)

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = ComposeView(activity).apply {
                    setContent {
                        val layer = rememberGraphicsLayer()
                        SideEffect { graphicsLayer.set(layer) }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    layer.record {
                                        this@drawWithContent.drawContent()
                                    }
                                    drawLayer(layer)
                                    drawn.countDown()
                                }
                                .background(OilPalette.light.page),
                        ) {
                            BasicText(
                                text = "AutoLedger report",
                                style = TextStyle(color = OilPalette.light.ink),
                            )
                        }
                    }
                }
                composeView.set(view)
                activity.setContentView(view)
            }

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val width = (160 * density).roundToInt()
                val height = (96 * density).roundToInt()
                val view = requireNotNull(composeView.get())
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width, height)
                val softwareBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(softwareBitmap))
                } finally {
                    softwareBitmap.recycle()
                }
            }
            assertTrue(drawn.await(5, TimeUnit.SECONDS))
            val bitmap = runBlocking {
                requireNotNull(graphicsLayer.get()).toImageBitmap()
            }
            assertCaptured(bitmap)
        }
    }

    @Test
    fun fileProviderUriReturnsReadableContentUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "report-shares").apply { mkdirs() }
        val file = File(dir, "report_share_provider_test.png")
        val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        file.writeBytes(expected)
        try {
            val send = ReportShareExporter.sendIntent(context, file)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("image/png", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = requireNotNull(send.clipData?.getItemAt(0)?.uri)
            assertEquals("content", uri.scheme)
            val actual = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertNotNull(actual)
            assertArrayEquals(expected, actual)
        } finally {
            file.delete()
        }
    }

    private fun assertCaptured(image: ImageBitmap) {
        assertTrue(image.width > 0)
        assertTrue(image.height > 0)
        val androidBitmap = image.asAndroidBitmap()
        val png = ByteArrayOutputStream()
        assertTrue(androidBitmap.compress(Bitmap.CompressFormat.PNG, 100, png))
        assertTrue(png.size() > 8)
        val decoded = BitmapFactory.decodeByteArray(png.toByteArray(), 0, png.size())
        assertNotNull(decoded)
        val backgroundPixel = decoded.getPixel(8, 8)
        assertTrue(backgroundPixel != 0)
        assertEquals(255, AndroidColor.alpha(backgroundPixel))
        assertEquals(OilPalette.light.page.toArgb(), backgroundPixel)
    }
}
