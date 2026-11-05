package com.litefin.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleShiftTest {
    private val srt = "1\n00:00:01,000 --> 00:00:03,500\nBonjour 10:00:00,000\n\n2\n00:59:59,900 --> 01:00:00,400\nFin\n"

    @Test fun shiftsSrtForward() {
        val out = SubtitleShift.shift(srt, 250, srt = true)
        assertEquals("00:00:01,250 --> 00:00:03,750", out.lines()[1])
        assertEquals("01:00:00,150 --> 01:00:00,650", out.lines()[5])
        // Text lines are left alone, even when they look like a timing.
        assertEquals("Bonjour 10:00:00,000", out.lines()[2])
    }

    @Test fun clampsAtZero() {
        assertEquals("00:00:00,000 --> 00:00:01,500", SubtitleShift.shift(srt, -2000, srt = true).lines()[1])
    }

    @Test fun vttWithoutHours() {
        val vtt = "WEBVTT\n\n00:05.000 --> 00:07.250 align:start\nSalut\n"
        val out = SubtitleShift.shift(vtt, -500, srt = false)
        assertEquals("00:00:04.500 --> 00:00:06.750 align:start", out.lines()[2])
        assertEquals("WEBVTT", out.lines()[0])
    }
}
