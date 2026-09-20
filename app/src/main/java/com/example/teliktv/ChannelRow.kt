package com.example.teliktv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Строка канала: номер, логотип, название, «сейчас в эфире» и отдельная кнопка-звезда справа
 * (← → на пульте переходят между строкой и звездой, OK на звезде — добавить/убрать из избранного).
 */
@Composable
fun ChannelRow(
    number: Int,
    channel: Channel,
    logoUrl: String?,
    nowTitle: String?,
    favorite: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    isCurrent: Boolean = false,
    rowFocusRequester: FocusRequester? = null,
    onStarFocusChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FocusItem(
            modifier = Modifier.weight(1f),
            selected = isCurrent,
            focusRequester = rowFocusRequester,
            onClick = onClick,
        ) { focused ->
            Txt(
                number.toString(),
                Modifier.width(if (compact) 36.dp else 52.dp),
                size = if (compact) 17.sp else 20.sp,
                color = if (focused) OnAmber else TextDim,
            )
            ChannelLogo(
                logoUrl,
                channel.title,
                Modifier.size(width = if (compact) 56.dp else 68.dp, height = if (compact) 36.dp else 42.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(
                    channel.title,
                    size = if (compact) 20.sp else 22.sp,
                    color = if (focused) OnAmber else TextMain,
                )
                if (nowTitle != null) {
                    Txt(nowTitle, size = 15.sp, color = if (focused) OnAmber else TextDim)
                }
            }
            if (channel.streams.size > 1) {
                Spacer(Modifier.width(8.dp))
                Txt("×${channel.streams.size}", size = 16.sp, color = if (focused) OnAmber else TextDim)
            }
        }
        Spacer(Modifier.width(6.dp))
        FocusItem(onClick = onToggleFavorite, onFocusChange = onStarFocusChange) { focused ->
            StarIcon(filled = favorite, color = if (focused) OnAmber else Amber)
        }
    }
}
