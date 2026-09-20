package com.example.teliktv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Палитра: чернильный фон и один тёплый янтарный акцент — на ТВ фокус должен читаться с дивана.
val Ink = Color(0xFF0D1014)
val Panel = Color(0xFF151A21)
val PanelSelected = Color(0xFF232B36)
val Amber = Color(0xFFF2B33D)
val OnAmber = Color(0xFF16110A)
val TextMain = Color(0xFFE8ECF1)
val TextDim = Color(0xFF8E99A8)
val ErrorRed = Color(0xFFFF7A70)

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 20.sp,
    color: Color = TextMain,
    weight: FontWeight? = null,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Фокусируемая строка для управления пультом. content получает признак фокуса,
 * чтобы менять цвет текста на контрастный. onKeyEvent позволяет ловить MENU/OK и т.п.
 */
@Composable
fun FocusItem(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Amber
        selected -> PanelSelected
        else -> Color.Transparent
    }
    val base = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier
    val withKeys = if (onKeyEvent != null) base.onPreviewKeyEvent(onKeyEvent) else base
    Row(
        modifier = withKeys
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(focused)
    }
}