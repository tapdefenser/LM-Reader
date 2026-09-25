package com.lmreader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaCard
import com.lmreader.core.model.SourceKind

/**
 * 图库/书架卡片（开发文档 8.1「卡片」）。
 *
 * 字段与状态都由 [MangaCard] 投影提供，卡片不再查库——这是 40 项分页能在万级
 * 图库上工作的前提。
 *
 * 章节状态不伪造：`chapterCountKnown = false` 时显示「已发现 N 章，更新中」，
 * 而不是把探测到的一章当成完整章节数（开发文档 5.1、8.1）。
 */
@Composable
fun MangaCardItem(
    card: MangaCard,
    coverRequest: CoverRequest?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            CoverImage(
                request = coverRequest,
                contentDescription = card.displayName,
                modifier = Modifier
                    .width(COVER_WIDTH)
                    .height(COVER_HEIGHT)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = card.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (card.inShelf) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.BookmarkAdded,
                            contentDescription = "已在书架",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (card.availability == MangaAvailability.SOURCE_UNAVAILABLE) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = "来源暂时不可用",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    // 无简介显示「无简介」而不是留空（开发文档 2）。
                    text = card.summaryPreview ?: "无简介",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Badge(
                        text = when (card.sourceKind) {
                            SourceKind.IMAGE_DIRECTORY -> "图片"
                            SourceKind.ARCHIVE_IMPORT -> "归档"
                        },
                    )
                    Badge(
                        text = when (card.layoutMode) {
                            LayoutMode.MULTI_CHAPTER -> "多章节"
                            LayoutMode.SINGLE_CHAPTER -> "单章节"
                        },
                    )
                    Text(
                        text = chapterLabel(card),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 紧凑网格卡片：只显示封面与名称（开发文档 8.1「展示方式」）。 */
@Composable
fun MangaGridItem(
    card: MangaCard,
    coverRequest: CoverRequest?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        CoverImage(
            request = coverRequest,
            contentDescription = card.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            text = card.displayName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 章节状态文案；未知总数不伪造成确定值（开发文档 5.1、8.1）。 */
internal fun chapterLabel(card: MangaCard): String = when {
    card.chapterCountKnown && card.chapterCount != null -> "共 ${card.chapterCount} 章"
    card.chapterCount != null -> "已发现 ${card.chapterCount} 章，更新中"
    else -> "章节待更新"
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private val COVER_WIDTH = 76.dp
private val COVER_HEIGHT = 104.dp
