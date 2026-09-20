package com.example.teliktv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

private class PlaySession(val group: Int, val start: String)

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val channels by vm.channels.collectAsState()
    val status by vm.status.collectAsState()
    val failures by vm.failures.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val epg by vm.epg.collectAsState()
    val epgStatus by vm.epgStatus.collectAsState()

    // Есть избранное — открываемся на нём, иначе на «Все каналы».
    var groupIndex by rememberSaveable {
        mutableStateOf(if (favorites.isNotEmpty()) Groups.FAVORITES else Groups.ALL)
    }
    var lastPlayed by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<PlaySession?>(null) }

    val s = session
    if (s == null) {
        ChannelListScreen(
            channels = channels,
            favorites = favorites,
            epg = epg,
            epgStatus = epgStatus,
            status = status,
            failures = failures,
            groupIndex = groupIndex,
            onGroupChange = { groupIndex = it },
            lastPlayed = lastPlayed,
            onPlay = { slug, group ->
                lastPlayed = slug
                session = PlaySession(group, slug)
            },
            onToggleFavorite = { vm.toggleFavorite(it) },
            onRefresh = { vm.refresh() },
        )
    } else {
        PlayerScreen(
            channels = channels,
            favorites = favorites,
            epg = epg,
            startGroup = s.group,
            startSlug = s.start,
            reloadChannel = { vm.reloadChannel(it) },
            onToggleFavorite = { vm.toggleFavorite(it) },
            onCurrent = { slug, group ->
                lastPlayed = slug
                groupIndex = group     // вернёмся в список на той же группе, откуда смотрели
            },
            onExit = { session = null },
        )
    }
}
