package com.lmreader.core.vision

import android.graphics.*
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BubbleMaskRendererTest {
    @Test fun fontAndBoldRedrawSameTextWithoutChangingOriginal() {
        val original = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val region = PageTextRegion("font-test", RegionKind.BUBBLE, PixelRect(20f, 20f, 300f, 180f), emptyList(), "hello", emptyList())
        val translated = listOf(PageTranslatedRegion(region, "Hello world"))
        val renderer = BubbleMaskRenderer()
        val normal = renderer.render(original, translated, BubbleRenderSettings())
        val changed = renderer.render(original, translated, BubbleRenderSettings(font = BubbleFont.MONOSPACE, fontScalePercent = 80, bold = true))
        try {
            assertFalse(normal.sameAs(changed))
            assertEquals(Color.WHITE, original.getPixel(160, 100))
            assertEquals(Color.WHITE, changed.getPixel(0, 0))
        } finally { normal.recycle(); changed.recycle(); original.recycle() }
    }
    @Test fun preparedOverlaySurvivesSourceReleaseAndMatchesExportPixels() {
        val original=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(20f,20f,280f,180f),emptyList(),"hello",emptyList())
        val items=listOf(PageTranslatedRegion(region,"你好 🙂"))
        val renderer=BubbleMaskRenderer();val seed=renderer.prepareSource(original,listOf(region))
        val export=renderer.render(original,items,BubbleRenderSettings())
        val live=original.copy(Bitmap.Config.ARGB_8888,true);original.recycle()
        try {
            val overlay=seed.layout(items,BubbleRenderSettings())
            overlay.draw(Canvas(live))
            assertTrue(export.sameAs(live))
            assertEquals("p",overlay.hitTest(150f,100f));assertNull(overlay.hitTest(5f,5f))
            assertNull(seed.layout(emptyList(),BubbleRenderSettings()).hitTest(150f,100f))
        } finally {export.recycle();live.recycle()}
    }
    @Test fun masksAndTextLeaveOriginalAndOutsidePixelsUntouched() {
        val original=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.CYAN)}
        val canvas=Canvas(original);canvas.drawRect(40f,40f,360f,280f,Paint().apply {color=Color.WHITE})
        canvas.drawText("ORIGINAL",80f,160f,Paint().apply {color=Color.BLACK;textSize=38f})
        val pixels=IntArray(160000);original.getPixels(pixels,0,400,0,0,400,400)
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(40f,40f,360f,280f),listOf(PixelPoint(40f,40f),PixelPoint(360f,40f),PixelPoint(360f,280f),PixelPoint(40f,280f)),"ORIGINAL",listOf(PixelRect(70f,110f,330f,170f)))
        val result=BubbleMaskRenderer().render(original,listOf(PageTranslatedRegion(region,"你好世界")),BubbleRenderSettings())
        try {
            val after=IntArray(160000);original.getPixels(after,0,400,0,0,400,400);assertArrayEquals(pixels,after)
            assertEquals(Color.CYAN,result.getPixel(20,20));assertEquals(Color.CYAN,result.getPixel(200,320))
            assertEquals(Color.WHITE,result.getPixel(60,60))
            val rendered=IntArray(160000);result.getPixels(rendered,0,400,0,0,400,400);assertFalse(pixels.contentEquals(rendered))
        } finally {original.recycle();result.recycle()}
    }
    @Test fun longUnicodeTranslationFitsWithoutDroppingItsEnd() {
        val text="你好世界。".repeat(60)+"最后一句😀"
        val layout=BubbleMaskRenderer().fitLayout(text,180,140,TextPaint(Paint.ANTI_ALIAS_FLAG))
        assertEquals(text.length,layout.getLineEnd(layout.lineCount-1));assertTrue(layout.height<=140)
        assertTrue((0 until layout.lineCount).all {layout.getLineWidth(it)<=180.5f})
    }
    @Test fun maximumEditableTextInTinyRegionDoesNotCrashOrTruncate() {
        val text="译文🙂".repeat(3000)
        val layout=BubbleMaskRenderer().fitLayout(text,1,1,TextPaint(Paint.ANTI_ALIAS_FLAG))
        assertEquals(text.length,layout.getLineEnd(layout.lineCount-1));assertTrue(layout.height<=1)
    }
    @Test fun blackBubbleAndOpacitySettingsAreHonored() {
        val original=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLACK)}
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(10f,10f,290f,190f),emptyList(),"old",emptyList())
        val renderer=BubbleMaskRenderer()
        val auto=renderer.render(original,listOf(PageTranslatedRegion(region,"你好")),BubbleRenderSettings())
        val half=renderer.render(original,listOf(PageTranslatedRegion(region,"你好")),BubbleRenderSettings(BubbleFillMode.WHITE,50,8))
        try {
            assertEquals(Color.BLACK,auto.getPixel(25,25))
            assertTrue(Color.red(half.getPixel(25,25)) in 120..135)
            assertTrue((0 until auto.width).any {x -> (0 until auto.height).any {y -> Color.red(auto.getPixel(x,y))>220}})
        } finally {original.recycle();auto.recycle();half.recycle()}
    }
    @Test fun ocrFallbackSamplesBlackBackgroundOutsideItsTextBox() {
        val original=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLACK)}
        val bounds=PixelRect(20f,40f,280f,160f)
        val region=PageTextRegion("p",RegionKind.FREE_TEXT,bounds,emptyList(),"old",listOf(bounds))
        val result=BubbleMaskRenderer().render(original,listOf(PageTranslatedRegion(region,"你好")),BubbleRenderSettings())
        try {assertEquals(Color.BLACK,result.getPixel(35,55));assertTrue(Color.red(result.getPixel(150,110))>0 ||
            (40 until 160).any {y -> (20 until 280).any {x ->Color.red(result.getPixel(x,y))>220}})}
        finally {original.recycle();result.recycle()}
    }
    @Test fun bubbleMaskInsetPreservesInkOnItsContour() {
        val original=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        Canvas(original).drawRect(40f,40f,360f,280f,Paint().apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=4f})
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(40f,40f,360f,280f),listOf(
            PixelPoint(40f,40f),PixelPoint(360f,40f),PixelPoint(360f,280f),PixelPoint(40f,280f)),"OLD",emptyList())
        val result=BubbleMaskRenderer().render(original,listOf(PageTranslatedRegion(region,"你好")),BubbleRenderSettings())
        try {assertEquals(Color.BLACK,result.getPixel(200,41));assertEquals(Color.BLACK,result.getPixel(41,100))}
        finally {original.recycle();result.recycle()}
    }
    @Test fun inaccurateContourStillPreservesOutlineOutsideOcrBoxes() {
        val original=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        Canvas(original).drawRect(40f,40f,360f,280f,Paint().apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=4f})
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(10f,10f,390f,310f),listOf(
            PixelPoint(10f,10f),PixelPoint(390f,10f),PixelPoint(390f,310f),PixelPoint(10f,310f)),"OLD",listOf(PixelRect(120f,130f,280f,170f)))
        val result=BubbleMaskRenderer().render(original,listOf(PageTranslatedRegion(region,"你好")),BubbleRenderSettings())
        try {assertEquals(Color.BLACK,result.getPixel(200,40));assertEquals(Color.BLACK,result.getPixel(40,100))}
        finally {original.recycle();result.recycle()}
    }
}
