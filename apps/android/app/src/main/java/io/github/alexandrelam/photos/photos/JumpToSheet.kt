package io.github.alexandrelam.photos.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alexandrelam.photos.shared.format.Format
import io.github.alexandrelam.photos.shared.ui.Darkroom
import io.github.alexandrelam.photos.shared.ui.OutlineButton
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * "Jump to": every year with its 12 months and photo counts. Months without photos
 * are greyed out and [current] (the month on screen) is amber.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JumpToSheet(
    counts: Map<YearMonth, Int>,
    current: YearMonth?,
    onMonth: (YearMonth) -> Unit,
    onToday: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Darkroom.colors
    val years = (counts.keys.map { it.year } + YearMonth.now().year).distinct().sortedDescending()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Jump to", style = Darkroom.type.title, color = colors.text, modifier = Modifier.weight(1f))
                OutlineButton("Today", onClick = onToday, compact = true)
            }
            LazyColumn(Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 16.dp)) {
                items(years, key = { it }) { year ->
                    val months = Month.entries.map { YearMonth.of(year, it) }
                    val yearTotal = months.sumOf { counts[it] ?: 0 }
                    Column(Modifier.padding(top = 18.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = yearTotal > 0) { months.lastOrNull { (counts[it] ?: 0) > 0 }?.let(onMonth) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(year.toString(), style = Darkroom.type.section, color = colors.text, modifier = Modifier.weight(1f))
                            Text(Format.photos(yearTotal), style = Darkroom.type.caption, color = colors.textSecondary)
                        }
                        Spacer(Modifier.height(10.dp))
                        months.chunked(6).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { month ->
                                    MonthChip(month, count = counts[month] ?: 0, current = month == current, onClick = { onMonth(month) }, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthChip(month: YearMonth, count: Int, current: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = Darkroom.colors
    val has = count > 0
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    current -> colors.accent
                    has -> colors.surfaceRaised
                    else -> colors.surface
                },
            )
            .clickable(enabled = has, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            style = Darkroom.type.caption.copy(fontWeight = Darkroom.type.body.fontWeight),
            textAlign = TextAlign.Center,
            color = when {
                current -> colors.onAccent
                has -> colors.text
                else -> colors.textMuted.copy(alpha = 0.5f)
            },
        )
    }
}
