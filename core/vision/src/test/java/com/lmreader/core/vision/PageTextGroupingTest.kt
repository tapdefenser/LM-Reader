package com.lmreader.core.vision

import com.lmreader.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PageTextGroupingTest {
    private val bubble=SegRegion("p:b",RegionKind.BUBBLE,PixelRect(10f,10f,160f,130f),.9f)
    private fun line(id: String,text: String,x: Float=30f,y: Float=30f)=OcrLine(id,PixelRect(x,y,x+50,y+20),text,.9f)
    private fun group(regions: List<SegRegion>,lines: List<OcrLine>)=groupPageText(SegResult("p",500,500,regions,0,"test"),LocalOcrResult("p",500,500,LocalOcrLanguage.JAPANESE,lines,0))
    @Test fun bubbleOwnsOverlappingTextAndPreservesOcrOrder() {
        val text=bubble.copy(id="p:free",kind=RegionKind.FREE_TEXT,bounds=PixelRect(20f,20f,130f,90f))
        val result=group(listOf(bubble,text),listOf(line("a","世界",y=60f),line("b","こんにちは")))
        assertEquals(1,result.size);assertEquals("p:b",result.single().id);assertEquals("世界こんにちは",result.single().sourceText)
        assertEquals(2,result.single().textBounds.size)
    }
    @Test fun missingSegRegionsFallbackToOcrAndEmptyBubbleDoesNotEraseArt() {
        val result=group(listOf(bubble),listOf(line("outside","OUTSIDE",300f,300f)))
        assertEquals(1,result.size);assertEquals(RegionKind.FREE_TEXT,result.single().kind)
        assertEquals("outside",result.single().id)
        assertTrue(group(listOf(bubble),emptyList()).isEmpty())
    }
    @Test fun nestedBubblesUseSmallestOwnerWithoutDuplicateLines() {
        val outer=bubble.copy(id="outer",bounds=PixelRect(0f,0f,450f,450f))
        assertEquals("p:b",group(listOf(outer,bubble),listOf(line("a","HELLO"))).single().id)
        assertEquals("HELLO WORLD",joinOcrLines(listOf("HELLO","WORLD")))
    }
    @Test fun freeTextMaskCoversOcrInkBeyondSegBounds() {
        val seg=SegRegion("text",RegionKind.FREE_TEXT,PixelRect(32f,34f,75f,49f),.9f)
        val mask=group(listOf(seg),listOf(line("ocr","INK"))).single().bounds
        assertTrue(mask.left<30f && mask.top<30f && mask.right>80f && mask.bottom>50f)
    }
    @Test fun bindingUsesIdsAndRejectsIncompleteOrForeignResults() {
        val regions=group(emptyList(),listOf(line("a","Hello"),line("b","World",300f,300f)))
        val result=bindPageTranslations(regions,listOf(LocalTranslatedText("b","World","世界"),LocalTranslatedText("a","Hello","你好")))
        assertEquals(listOf("你好","世界"),result.map {it.translatedText})
        assertThrows(IllegalArgumentException::class.java) {bindPageTranslations(regions,listOf(LocalTranslatedText("a","Hello","你好")))}
        assertThrows(IllegalArgumentException::class.java) {bindPageTranslations(regions,listOf(LocalTranslatedText("a","Wrong","你好"),LocalTranslatedText("b","World","世界")))}
    }
    @Test fun anotherPagesOcrCannotBeGrouped() {
        assertThrows(IllegalArgumentException::class.java) {groupPageText(SegResult("a",10,10,emptyList(),0,""),LocalOcrResult("b",10,10,LocalOcrLanguage.ENGLISH,emptyList(),0))}
    }
    @Test fun inscribedTextRectAvoidsBubbleTail() {
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(0f,0f,100f,150f),listOf(
            PixelPoint(0f,0f),PixelPoint(100f,0f),PixelPoint(100f,100f),PixelPoint(55f,100f),PixelPoint(50f,150f),PixelPoint(45f,100f),PixelPoint(0f,100f)),"",emptyList())
        val safe=safeTextBounds(region)
        assertTrue(safe.width>90f);assertTrue(safe.bottom<105f);assertTrue(safe.height>90f)
    }
}
