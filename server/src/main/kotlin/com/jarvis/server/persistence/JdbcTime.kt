package com.jarvis.server.persistence

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

fun PreparedStatement.setInstant(index: Int, value: Instant?) {
    if (value == null) setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
    else setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
}

fun ResultSet.getInstant(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()
