package org.schabi.newpipe.util

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ocpsoft.prettytime.PrettyTime

class LocalizationTest {
    @Before
    fun setUp() {
        val field = Localization::class.java.getDeclaredField("prettyTime")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test(expected = NullPointerException::class)
    fun `relativeTime() must fail without initializing pretty time`() {
        Localization.relativeTime(OffsetDateTime.of(2021, 1, 6, 0, 0, 0, 0, ZoneOffset.UTC))
    }

    @Test
    fun `relativeTime() with a OffsetDateTime must work`() {
        val prettyTime = PrettyTime(LocalDate.of(2021, 1, 1), ZoneOffset.UTC)
        prettyTime.locale = Locale.ENGLISH
        Localization.initPrettyTime(prettyTime)

        val offset = OffsetDateTime.of(2021, 1, 6, 0, 0, 0, 0, ZoneOffset.UTC)
        val actual = Localization.relativeTime(offset)

        assertEquals("5 days from now", actual)
    }

    @Test
    fun `relativeTime() uses days instead of a single week`() {
        val prettyTime = PrettyTime(LocalDate.of(2021, 1, 15), ZoneOffset.UTC)
        prettyTime.locale = Locale.ENGLISH
        Localization.initPrettyTime(prettyTime)

        assertEquals(
            "7 days ago",
            Localization.relativeTime(
                OffsetDateTime.of(2021, 1, 8, 0, 0, 0, 0, ZoneOffset.UTC)
            )
        )
        assertEquals(
            "13 days ago",
            Localization.relativeTime(
                OffsetDateTime.of(2021, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC)
            )
        )
        assertEquals(
            "2 weeks ago",
            Localization.relativeTime(
                OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
            )
        )
    }

    @Test
    fun `relativeTimeShort() uses days until two weeks`() {
        val now = OffsetDateTime.now()

        assertEquals("13d ago", Localization.relativeTimeShort(now.minusDays(13)))
        assertEquals("2w ago", Localization.relativeTimeShort(now.minusDays(14)))
    }
}
