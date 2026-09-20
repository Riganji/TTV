package com.example.teliktv

/** Группы каналов: «Избранное», «Все каналы» и группы из Config.GROUPS. */
object Groups {
    const val FAVORITES = 0
    const val ALL = 1

    val names: List<String> = listOf("Избранное", "Все каналы") + Config.GROUPS.map { it.first }

    /** channels должен быть уже упорядочен как Config.ALL_SLUGS. */
    fun channelsFor(index: Int, channels: List<Channel>, favorites: Set<String>): List<Channel> =
        when {
            index == FAVORITES -> channels.filter { it.slug in favorites }
            index == ALL || index !in names.indices -> channels
            else -> {
                val bySlug = channels.associateBy { it.slug }
                Config.GROUPS[index - 2].second.mapNotNull { bySlug[it] }
            }
        }

    fun count(index: Int, channels: List<Channel>, favorites: Set<String>): Int =
        channelsFor(index, channels, favorites).size
}
