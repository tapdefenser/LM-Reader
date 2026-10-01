package com.lmreader.core.vision

import com.lmreader.core.model.*
import kotlin.math.*

/** Each OCR line belongs to exactly one region; text detections inside bubbles do not duplicate it. */
fun groupPageText(seg: SegResult, ocr: LocalOcrResult): List<PageTextRegion> {
    require(seg.imageId == ocr.imageId && seg.width == ocr.width && seg.height == ocr.height)
    require(ocr.lines.map { it.id }.distinct().size == ocr.lines.size)
    require(seg.regions.map { it.id }.distinct().size == seg.regions.size)
    val bubbles = seg.regions.filter { it.kind == RegionKind.BUBBLE }
    val candidates = (bubbles + seg.regions.filter { region ->
        region.kind == RegionKind.FREE_TEXT && bubbles.none { overlapArea(it.bounds, region.bounds) / region.bounds.area.coerceAtLeast(1f) > .6f }
    }).filter { it.bounds.area > 0 }
    val groups = linkedMapOf<String, Pair<SegRegion, MutableList<OcrLine>>>()
    for (line in ocr.lines.filter { it.text.isNotBlank() && it.bounds.area > 0 }) {
        val match = candidates.filter { overlapArea(it.bounds, line.bounds) / line.bounds.area > .55f &&
            (it.contour.size<3 || polygonContains(it.contour,(line.bounds.left+line.bounds.right)/2,(line.bounds.top+line.bounds.bottom)/2)) }
            .sortedWith(compareBy<SegRegion> { if (it.kind == RegionKind.BUBBLE) 0 else 1 }.thenBy { it.bounds.area }).firstOrNull()
            ?: SegRegion(line.id, RegionKind.FREE_TEXT, line.bounds, line.confidence)
        groups.getOrPut(match.id) { match to arrayListOf() }.second += line
    }
    return groups.values.map { (region, lines) ->
        val coverage=if(region.kind==RegionKind.FREE_TEXT) PixelRect(
            min(region.bounds.left,lines.minOf {it.bounds.left})-2,min(region.bounds.top,lines.minOf {it.bounds.top})-2,
            max(region.bounds.right,lines.maxOf {it.bounds.right})+2,max(region.bounds.bottom,lines.maxOf {it.bounds.bottom})+2) else region.bounds
        val bounds = PixelRect(coverage.left.coerceIn(0f,seg.width.toFloat()), coverage.top.coerceIn(0f,seg.height.toFloat()),
            coverage.right.coerceIn(0f,seg.width.toFloat()), coverage.bottom.coerceIn(0f,seg.height.toFloat()))
        val contour = region.contour.takeIf { points -> points.size >= 3 && points.all { it.x.isFinite() && it.y.isFinite() } }
            ?.map { PixelPoint(it.x.coerceIn(0f,seg.width.toFloat()), it.y.coerceIn(0f,seg.height.toFloat())) }.orEmpty()
        PageTextRegion(region.id,region.kind,bounds,contour,joinOcrLines(lines.map { it.text }),lines.map { it.bounds })
    }
}

fun bindPageTranslations(regions: List<PageTextRegion>, translations: List<LocalTranslatedText>): List<PageTranslatedRegion> {
    require(regions.map { it.id }.distinct().size == regions.size)
    require(translations.size == regions.size && translations.map { it.id }.toSet().size == translations.size &&
        translations.map { it.id }.toSet() == regions.map { it.id }.toSet()) { "Translation IDs do not match page regions" }
    val byId = translations.associateBy { it.id }
    return regions.map { region ->
        val translated = byId.getValue(region.id)
        require(translated.sourceText == region.sourceText) { "Translation source text does not match OCR" }
        PageTranslatedRegion(region, translated.translatedText)
    }
}

internal fun joinOcrLines(lines: List<String>): String = lines.map { it.trim() }.filter { it.isNotEmpty() }.fold("") { previous, next ->
    if (previous.isEmpty()) next else previous + if (isCjkCharacter(previous.last()) && isCjkCharacter(next.first())) next else " $next"
}
private fun isCjkCharacter(c: Char) = c in '\u2E80'..'\u9FFF' || c in '\uAC00'..'\uD7AF' || c in '\uF900'..'\uFAFF' || c in '\uFF00'..'\uFFEF'
private fun overlapArea(a: PixelRect,b: PixelRect) = (min(a.right,b.right)-max(a.left,b.left)).coerceAtLeast(0f) * (min(a.bottom,b.bottom)-max(a.top,b.top)).coerceAtLeast(0f)

internal fun polygonContains(points: List<PixelPoint>,x: Float,y: Float): Boolean {
    var inside=false; var j=points.lastIndex
    for(i in points.indices) {
        val a=points[i];val b=points[j]
        if((a.y>y)!=(b.y>y) && x < (b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x) inside=!inside
        j=i
    }
    return inside
}

/** A coarse inscribed rectangle keeps text away from tails and irregular mask edges. */
internal fun safeTextBounds(region: PageTextRegion): PixelRect {
    val bounds=region.bounds
    if(region.contour.size<3 || bounds.area<=0) return bounds
    val n=64;val heights=IntArray(n);var bestArea=0;var best=bounds
    for(y in 0 until n) {
        for(x in 0 until n) {
            val px=bounds.left+(x+.5f)*bounds.width/n;val py=bounds.top+(y+.5f)*bounds.height/n
            heights[x]=if(polygonContains(region.contour,px,py)) heights[x]+1 else 0
        }
        val stack=ArrayList<Int>()
        for(x in 0..n) {
            val height=if(x==n) 0 else heights[x]
            while(stack.isNotEmpty() && heights[stack.last()]>height) {
                val h=heights[stack.removeAt(stack.lastIndex)];val left=if(stack.isEmpty()) 0 else stack.last()+1
                val area=h*(x-left)
                if(area>bestArea) {
                    bestArea=area
                    best=PixelRect(bounds.left+left*bounds.width/n,bounds.top+(y-h+1)*bounds.height/n,
                        bounds.left+x*bounds.width/n,bounds.top+(y+1)*bounds.height/n)
                }
            }
            if(x<n) stack+=x
        }
    }
    return best
}
