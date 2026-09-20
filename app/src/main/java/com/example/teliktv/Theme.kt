package com.example.teliktv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

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
 * чтобы менять цвет текста на контрастный.
 */
@Composable
fun FocusItem(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onFocusChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Amber
        selected -> PanelSelected
        else -> Color.Transparent
    }
    val base = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier
    Row(
        modifier = base
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
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

/** Звёздочка «избранное», нарисованная вручную (не зависит от шрифтов ТВ). */
@Composable
fun StarIcon(filled: Boolean, color: Color, iconSize: Dp = 22.dp) {
    Canvas(Modifier.size(iconSize)) {
        val r = this.size.minDimension / 2f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f + r * 0.06f
        val path = Path()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.45f
            val a = -Math.PI / 2 + i * Math.PI / 5
            val x = cx + (rad * cos(a)).toFloat()
            val y = cy + (rad * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

/** Логотип канала; пока нет картинки (или ошибка загрузки) — инициалы. */
@Composable
fun ChannelLogo(url: String?, title: String, modifier: Modifier = Modifier.size(width = 68.dp, height = 42.dp)) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1C222B)),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(3.dp),
                loading = { Initials(title) },
                error = { Initials(title) },
            )
        } else {
            Initials(title)
        }
    }
}

@Composable
private fun Initials(title: String) {
    val text = title.split(' ', '-', '_')
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
    Txt(text, size = 15.sp, color = TextDim, weight = FontWeight.Bold)
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(PanelSelected)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Amber))
    }
}

/** Текущее время, обновляется раз в 30 секунд — для «сейчас в эфире» и прогресса передачи. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}
