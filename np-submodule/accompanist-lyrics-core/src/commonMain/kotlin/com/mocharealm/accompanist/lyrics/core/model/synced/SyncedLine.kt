package com.mocharealm.accompanist.lyrics.core.model.synced

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine

data class SyncedLine(
    val content: String,
    val translation: String?,
    override val start: Int,
    override val end: Int,
) : ISyncedLine {
    // 容错: 乱序/异常时间戳会使 end < start, 此处钳制时长为非负
    // 避免单行构造 require 抛异常导致整份歌词解析失败
    override val duration = (end - start).coerceAtLeast(0)
}

data class UncheckedSyncedLine(
    val content: String,
    val translation: String?,
    override val start: Int,
    override val end: Int,
) : ISyncedLine {
    override val duration = (end - start).takeIf { it >= 0 } ?: 0

    fun toSyncedLine():SyncedLine {
        return SyncedLine(
            this.content,
            this.translation,
            this.start,
            this.end
        )
    }
}