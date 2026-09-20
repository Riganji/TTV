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

private class PlaySession(val slugs: List<String>, val start: String)

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val channels by vm.channels.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val status by vm.status.collectAsState()

    var groupIndex by rememberSaveable { mutableStateOf(0) }
    var lastPlayed by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<PlaySession?>(null) }
    val bySlug = remember(channels) { channels.associateBy { it.slug } }

    val s = session
    if (s == null) {
        ChannelListScreen(
            channels = channels,
            favorites = favorites,
            status = status,
            groupIndex = groupIndex,
            onGroupChange = { groupIndex = it },
            lastPlayed = lastPlayed,
            onPlay = { slug, playlist ->
                lastPlayed = slug
                session = PlaySession(playlist, slug)
            },
            onToggleFavorite = { vm.toggleFavorite(it) },
            onRefresh = { vm.refresh() },
        )
    } else {
        PlayerScreen(
            slugs = s.slugs,
            startSlug = s.start,
            channels = bySlug,
            favorites = favorites,
            reloadChannel = { vm.reloadChannel(it) },
            onCurrentChannel = { lastPlayed = it },
            onExit = { session = null },
        )
    }
}