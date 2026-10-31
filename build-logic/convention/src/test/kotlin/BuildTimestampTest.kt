// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuildTimestampTest {
    @Test
    fun `shell epoch seconds preserve the actual build date`() {
        val normalized = normalizeBuildTimestamp("1788912298")
        assertEquals("1788912298000", normalized)
        assertEquals(
            Instant.ofEpochSecond(1788912298),
            Instant.ofEpochMilli(normalized.toLong()),
        )
    }

    @Test
    fun `epoch milliseconds keep subsecond precision`() {
        assertEquals("1788912298123", normalizeBuildTimestamp("1788912298123"))
    }

    @Test
    fun `surrounding property whitespace is ignored`() {
        assertEquals("1788912298000", normalizeBuildTimestamp(" 1788912298\n"))
    }

    @Test
    fun `invalid values fail before generating BuildConfig`() {
        listOf("", "not-a-timestamp", "1788912298.5", "9223372036854775808").forEach {
            assertFailsWith<IllegalArgumentException>(it) { normalizeBuildTimestamp(it) }
        }
    }

    @Test
    fun `ambiguous or implausible units are rejected`() {
        listOf("0", "-1788912298", "178891229", "17889122980", "178891229800", "17889122980000").forEach {
            assertFailsWith<IllegalArgumentException>(it) { normalizeBuildTimestamp(it) }
        }
    }

    @Test
    fun `source date epoch uses nonnegative whole seconds without guessing units`() {
        assertEquals("0", normalizeSourceDateEpoch("0"))
        assertEquals("123000", normalizeSourceDateEpoch("123"))
        assertEquals("1788912298000", normalizeSourceDateEpoch("1788912298"))
        assertEquals("1788912298123000", normalizeSourceDateEpoch("1788912298123"))
        assertEquals("9223372036854775000", normalizeSourceDateEpoch("9223372036854775"))
        listOf("", "-1", "+1", " 1", "1\n", "1.0", "１２", "9223372036854776", "9223372036854775808").forEach {
            assertFailsWith<IllegalArgumentException>(it) { normalizeSourceDateEpoch(it) }
        }
    }

    @Test
    fun `explicit timestamp wins then source date then existing clock fallback`() {
        val unexpectedFallback = { error("fallback must stay lazy") }
        assertEquals("1788912298000", resolveBuildTimestamp("1788912298", "invalid", unexpectedFallback))
        assertEquals("123000", resolveBuildTimestamp(null, "123", unexpectedFallback))
        assertEquals("1788912298123", resolveBuildTimestamp(null, null) { "1788912298123" })
        assertFailsWith<IllegalArgumentException> { resolveBuildTimestamp("invalid", "123", unexpectedFallback) }
        assertFailsWith<IllegalArgumentException> { resolveBuildTimestamp(null, "invalid", unexpectedFallback) }
    }
}
