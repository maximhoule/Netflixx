package com.maxim.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Office, season 4, as Cinemeta lists it (two-part episodes split in two). */
class EpisodesTest {
    private val titles = listOf(
        "Fun Run (1)", "Fun Run (2)", "Dunder Mifflin Infinity (1)", "Dunder Mifflin Infinity (2)",
        "Launch Party (1)", "Launch Party (2)", "Money (1)", "Money (2)", "Local Ad", "Branch Wars",
        "Survivor Man", "The Deposition", "Dinner Party", "Chair Model",
    )
    private val all = titles.mapIndexed { i, t -> Video("tt0386676:4:${i + 1}", t, 4, i + 1, null, null) }
    private fun ep(n: Int) = all[n - 1]
    private fun file(name: String) = StreamItem("Torrentio 1080p", name, "http://x", emptyMap(), emptyList())

    @Test fun partTitles() {
        assertEquals("dundermifflininfinity", Episodes.baseTitle("Dunder Mifflin Infinity (2)"))
        assertEquals("thejob", Episodes.baseTitle("The Job, Part 1"))
        assertTrue(Episodes.hasPart("Fun Run (Part 1)"))
        assertFalse(Episodes.hasPart("Casino Night"))
        assertEquals(listOf(3, 4), Episodes.partGroup(all, ep(4)).map { it.episode })
        assertEquals(listOf(9), Episodes.partGroup(all, ep(9)).map { it.episode })
    }

    @Test fun mergedFileNumberedAsPartOne() {
        // Picking episode 4 gets the one-hour file numbered 3: start halfway, it's the right file.
        val s = file("The.Office.US.S04E03.1080p.WEB.mkv")
        assertEquals(3..4, Episodes.span(s, all, ep(4)))
        assertFalse(Episodes.mismatch(s, all, ep(4)))
        assertNull(Episodes.span(s, all, ep(3)))
    }

    @Test fun dvdNumbering() {
        // DVD order: S04E03 is "Launch Party" (TV episodes 5 + 6).
        val lp = file("The.Office.US.S04E03.Launch.Party.1080p.mkv")
        assertFalse(Episodes.mismatch(lp, all, ep(5)))
        assertFalse(Episodes.mismatch(lp, all, ep(6)))
        assertEquals(5..6, Episodes.span(lp, all, ep(6)))
        // …and is the wrong file for TV episode 3 even though the number matches.
        assertTrue(Episodes.mismatch(lp, all, ep(3)))
        // S04E04 "Money" is not "Dunder Mifflin Infinity (2)".
        assertTrue(Episodes.mismatch(file("The Office S04E04 Money 720p"), all, ep(4)))
    }

    @Test fun ordinaryFiles() {
        assertFalse(Episodes.mismatch(file("The.Office.US.S04E09.Local.Ad.mkv"), all, ep(9)))
        assertFalse(Episodes.mismatch(file("The.Office.US.S04E09.mkv"), all, ep(9)))
        assertTrue(Episodes.mismatch(file("The.Office.US.S04E10.mkv"), all, ep(9)))
        assertTrue(Episodes.mismatch(file("The.Office.US.S03E09.mkv"), all, ep(9)))
        assertFalse(Episodes.mismatch(file("No numbers here 1080p"), all, ep(9)))
        assertEquals(3..4, Episodes.span(file("The.Office.S04E03-E04.mkv"), all, ep(4)))
        // Your Emby server's own match is trusted.
        assertFalse(Episodes.mismatch(file("S04E10").copy(embyId = "emby:1"), all, ep(9)))
    }

    @Test fun duplicatesRemoved() {
        val dup = all + Video("x:4:7", "Money (1), Money (2)", 4, 7, null, null) + all[0]
        val clean = Episodes.clean(dup)
        assertEquals(all.map { it.id }, clean.map { it.id })
        // A server's placeholder loses to the real file, whichever comes first.
        val real = Video("emby:real", "Money (1), Money (2)", 4, 7, null, null)
        val c2 = Episodes.clean(listOf(ep(6), ep(7), real, ep(8))) { if (it.id == ep(7).id) 1 else 0 }
        assertEquals(listOf(ep(6).id, "emby:real", ep(8).id), c2.map { it.id })
    }
}
