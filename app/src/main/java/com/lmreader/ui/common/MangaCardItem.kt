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
import androidx.compose.material3.Checkbox
import com.lmreader.ui.i18n.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.lmreader.ui.i18n.Text
import com.lmreader.ui.i18n.localizedContentDescription
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaCard

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
    /** 多选态下是否被选中；普通态恒为 false。 */
    selected: Boolean = false,
    /** 是否处于多选态：为 true 时显示复选框，并让整张卡片参与选中切换。 */
    selectionMode: Boolean = false,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        // 选中态用容器色区分，而不是只靠边框：深色主题下边框几乎看不见。
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            // 多选态下显示复选框；普通态下完全不占宽度，避免列表跳动。
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .localizedContentDescription("选中 ${card.displayName}"),
                )
            }
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
                        localize = false,
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
                    localize = card.summaryPreview == null,
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
                    // 归档章节显示独立徽标，方便区分图片目录与 ZIP/CBZ/PDF。
                    if (card.hasArchiveChapters) {
                        Badge(text = "压缩包章节")
                    }
                    // 解释方式文案与类型列下拉共用一处（ui/common/LayoutModeLabels.kt）。
                    Badge(text = card.layoutMode.displayName())
                    // null = 不显示（单章节模式不写"共 1 章"，见 chapterLabel 的说明）。
                    chapterLabel(card)?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
    selected: Boolean = false,
    selectionMode: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
        CoverImage(
            request = coverRequest,
            contentDescription = card.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp)),
        )
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .localizedContentDescription("选中 ${card.displayName}"),
                )
            }
        }
        Text(
            text = card.displayName,
            localize = false,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 章节状态文案。
 *
 * 三种取值，只有前两种会显示：
 *
 * 1. **章节清单完整**（用户点过「更新章节」，或单章节模式的结构定义）→ `共 N 章`；
 * 2. **滚动/搜索时数过**（`countedChapterCount`）→ 也是 `共 N 章`：它是一次真实目录列举
 *    的结果，与完整清单的区别只在于没有落库章节行；有完整值时优先用完整值（更准）；
 * 3. 其余（还没数到、或数不出可读章节）→ **null，什么都不显示**。
 *
 * 第 3 条是用户口径：多章节漫画发现阶段只探测一个章节，`chapterCount` 长期是下限 1，
 * 把它显示成「已发现 1 章，更新中」有歧义（"到底是 1 章还是正在数？"）。
 * 既然章节数现在会随滚动/搜索自动补上，那就在补上之前干脆不写。
 *
 * 不复用 `chapterCountKnown` 之外的判断：那个标志同时是同步逻辑删除章节行的闸门，
 * 与显示无关（见 `MangaCard.countedChapterCount`）。
 */
internal fun chapterLabel(card: MangaCard): String? = when {
    card.layoutMode == LayoutMode.SINGLE_CHAPTER -> null
    card.chapterCountKnown && card.chapterCount != null -> "共 ${card.chapterCount} 章"
    card.countedChapterCount != null -> "共 ${card.countedChapterCount} 章"
    else -> null
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
