package biz.lungo.wifiscanner.data

import java.time.LocalDateTime

sealed class Status(val state: String, val since: LocalDateTime?) {
    class Online(since: LocalDateTime?) : Status(ONLINE, since)
    class Offline(since: LocalDateTime?) : Status(OFFLINE, since)
}

fun String?.toStatus(since: LocalDateTime?) = when (this) {
    ONLINE -> Status.Online(since)
    OFFLINE -> Status.Offline(since)
    else -> null
}

fun String?.toSince() = if (this == null) null else LocalDateTime.parse(this)

private const val ONLINE = "online"
private const val OFFLINE = "offline"