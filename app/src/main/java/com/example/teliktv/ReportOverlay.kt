package com.example.teliktv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class ReportLine(val text: String, val color: Color = TextMain, val heading: Boolean = false)

/**
 * Отчёт о загрузке: какие каналы не спарсились (и почему), для каких нет телепрограммы.
 * Каждая строка фокусируется, чтобы длинный список можно было листать пультом. Назад — закрыть.
 */
@Composable
fun ReportOverlay(
    failures: List<ChannelFailure>,
    epgStatus: EpgStatus,
    epgMissing: List<String>,
    onClose: () -> Unit,
) {
    val lines = remember(failures, epgStatus, epgMissing) {
        buildList {
            if (failures.isEmpty()) {
                add(ReportLine("Все каналы загрузились без ошибок", TextDim))
            } else {
                add(ReportLine("Не загрузились каналы (${failures.size})", ErrorRed, heading = true))
                for (f in failures) {
                    val tail = if (f.cached) " — показан сохранённый вариант" else ""
                    add(ReportLine("${f.name} (${f.slug}): ${f.reason}$tail"))
                }
            }
            add(ReportLine("Телепрограмма", Amber, heading = true))
            when {
                epgStatus.loading -> add(ReportLine("загружается…", TextDim))
                epgStatus.error != null -> add(ReportLine(epgStatus.error, ErrorRed))
                else -> add(ReportLine("найдена для ${epgStatus.matched} из ${epgStatus.total} каналов", TextDim))
            }
            if (epgMissing.isNotEmpty()) {
                add(ReportLine("Нет программы у (${epgMissing.size}): ${epgMissing.joinToString(", ")}", TextDim))
            }
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            first.requestFocus()
        } catch (e: Exception) {
            // список ещё не скомпонован
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF20D1014))
            .padding(horizontal = 64.dp, vertical = 40.dp),
    ) {
        Txt("Отчёт о загрузке", size = 30.sp, weight = FontWeight.Bold)
        Txt("Назад — закрыть", size = 16.sp, color = TextDim)
        Spacer(Modifier.height(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(lines) { i, line ->
                FocusItem(
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = if (i == 0) first else null,
                    onClick = {},
                ) { focused ->
                    Txt(
                        line.text,
                        color = if (focused) OnAmber else line.color,
                        size = if (line.heading) 22.sp else 18.sp,
                        weight = if (line.heading) FontWeight.Bold else null,
                        maxLines = 4,
                    )
                }
            }
        }
    }
}
