package biz.lungo.wifiscanner.data

import biz.lungo.wifiscanner.R
import java.time.LocalDateTime

sealed class Status(val state: String, val since: LocalDateTime?, val textColor: Int) {
    class Online(since: LocalDateTime?) : Status(ONLINE, since, R.color.green_online)
    class Offline(since: LocalDateTime?) : Status(OFFLINE, since, R.color.red_offline)
}

fun String?.toStatus(since: LocalDateTime?) = when (this) {
    ONLINE -> Status.Online(since)
    OFFLINE -> Status.Offline(since)
    else -> null
}

fun String?.toSince() = if (this == null) null else LocalDateTime.parse(this)

private const val ONLINE = "online"
private const val OFFLINE = "offline"