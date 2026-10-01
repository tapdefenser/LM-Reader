package com.lmreader.core.vision

import com.lmreader.core.model.*
import kotlin.math.*

/**
 * Keep text-block detections until ownership is resolved, as upstream's VlPageLayout does.
 * One merged balloon may contain several independent text blocks. They need separate OCR
 * crops and identities; removing all interior text detections loses that information.
 */
fun selectSegRegions(seg: SegResult, scope: SegTextScope = SegTextScope.ALL): List<SegRegion> {
    val bubbles = seg.regions.filter { it.kind == RegionKind.BUBBLE && it.bounds.area > 0 }
    val texts = seg.regions.filter { it.kind == RegionKind.FREE_TEXT && it.bounds.area > 0 }
    val owners = texts.associate { it.id to regionOwner(it.bounds, bubbles) }
    val result = buildList {
        for (bubble in bubbles) {
            val members = texts.filter { owners[it.id]?.id == bubble.id }
            when {
                // A single text prediction can be incomplete. Keep the full balloon crop
                // so its other OCR lines are not silently lost.
                members.size == 1 -> add(bubble)
                members.size > 1 -> members.forEach { text ->
                    val bounds = paddedTextBounds(text.bounds, seg)
                    add(SegRegion("${bubble.id}:text:${text.id}", RegionKind.BUBBLE, bounds,
                        min(bubble.confidence, text.confidence), clipContour(bubble.contour, bounds), bounds))
                }
                // A coarse enclosing prediction must not duplicate its occupied child balloons.
                bubbles.any { child -> child.id != bubble.id && child.bounds.area < bubble.bounds.area &&
                    overlap(child.bounds, bubble.bounds) / child.bounds.area > .9f &&
                    owners.values.any { it?.id == child.id } } -> Unit
                else -> add(bubble)
            }
        }
        texts.filter { owners[it.id] == null }.forEach { text ->
            add(text.copy(bounds = paddedTextBounds(text.bounds, seg), extractionBounds = paddedTextBounds(text.bounds, seg)))
        }
    }
    return result.filter { scope.includes(it.kind) }.sortedWith(compareBy<SegRegion> { it.bounds.top }.thenBy { it.bounds.left }.thenBy { it.id })
}

/** Polygon coverage prevents a caption in an irregular balloon's bounding box becoming speech. */
internal fun regionCoverage(region: SegRegion, bounds: PixelRect): Float {
    if (bounds.area <= 0) return 0f
    val boxCoverage = overlap(region.bounds, bounds) / bounds.area
    if (region.contour.size < 3 || boxCoverage == 0f) return boxCoverage
    var inside = 0
    val samples = 7
    for (y in 0 until samples) for (x in 0 until samples) {
        if (polygonContains(region.contour, bounds.left + (x + .5f) * bounds.width / samples,
                bounds.top + (y + .5f) * bounds.height / samples)) inside++
    }
    return min(boxCoverage, inside.toFloat() / (samples * samples))
}

internal fun regionOwner(bounds: PixelRect, regions: List<SegRegion>): SegRegion? = regions
    .map { it to regionCoverage(it, bounds) }.filter { it.second >= .5f }
    .sortedWith(compareByDescending<Pair<SegRegion, Float>> { it.second }.thenBy { it.first.bounds.area })
    .firstOrNull()?.first

private fun paddedTextBounds(bounds: PixelRect, seg: SegResult): PixelRect {
    val pad = max(3f, min(bounds.width, bounds.height) * .08f)
    return PixelRect((bounds.left - pad).coerceAtLeast(0f), (bounds.top - pad).coerceAtLeast(0f),
        (bounds.right + pad).coerceAtMost(seg.width.toFloat()), (bounds.bottom + pad).coerceAtMost(seg.height.toFloat()))
}

/** Sutherland-Hodgman clipping keeps each child overlay inside its own crop. */
internal fun clipContour(contour: List<PixelPoint>, rect: PixelRect): List<PixelPoint> {
    var points = contour
    fun clip(inside: (PixelPoint) -> Boolean, crossing: (PixelPoint, PixelPoint) -> PixelPoint) {
        if (points.isEmpty()) return
        val input = points; val output = ArrayList<PixelPoint>()
        var previous = input.last()
        for (point in input) {
            if (inside(point) != inside(previous)) output += crossing(previous, point)
            if (inside(point)) output += point
            previous = point
        }
        points = output
    }
    fun atX(a: PixelPoint, b: PixelPoint, x: Float) = PixelPoint(x, a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x))
    fun atY(a: PixelPoint, b: PixelPoint, y: Float) = PixelPoint(a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y), y)
    clip({ it.x >= rect.left }, { a, b -> atX(a, b, rect.left) })
    clip({ it.x <= rect.right }, { a, b -> atX(a, b, rect.right) })
    clip({ it.y >= rect.top }, { a, b -> atY(a, b, rect.top) })
    clip({ it.y <= rect.bottom }, { a, b -> atY(a, b, rect.bottom) })
    return points
}

/** Adjacent/connected balloons can overlap substantially without being duplicate predictions. */
internal fun duplicateSegBounds(a: PixelRect, b: PixelRect): Boolean {
    if (min(a.area, b.area) <= 0) return false
    val widthRatio = min(a.width, b.width) / max(a.width, b.width)
    val heightRatio = min(a.height, b.height) / max(a.height, b.height)
    val centered = abs((a.left + a.right) - (b.left + b.right)) / 2 < min(a.width, b.width) * .18f &&
        abs((a.top + a.bottom) - (b.top + b.bottom)) / 2 < min(a.height, b.height) * .18f
    return iou(a, b) > .85f || (widthRatio > .75f && heightRatio > .75f && centered &&
        overlap(a, b) / min(a.area, b.area) > .8f)
}

internal fun keepSegRegions(regions: List<SegRegion>): List<SegRegion> {
    val kept = ArrayList<SegRegion>()
    for (region in regions.sortedByDescending { it.confidence }) {
        if (kept.none { previous -> previous.kind == region.kind && duplicateSegBounds(previous.bounds, region.bounds) &&
                (previous.contour.size < 3 || region.contour.size < 3 ||
                    regionCoverage(previous, contourBounds(region.contour)) >= .7f &&
                    regionCoverage(region, contourBounds(previous.contour)) >= .7f) }) kept += region
    }
    return kept
}

internal fun contourBounds(points: List<PixelPoint>) = PixelRect(points.minOf { it.x }, points.minOf { it.y },
    points.maxOf { it.x }, points.maxOf { it.y })
