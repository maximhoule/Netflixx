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

    @Test
    fun sameKindOfSourceForTheNextEpisode() {
        val a = file("The.Office.US.S04E05.1080p.BluRay.x265-RARBG.mkv")
        assertEquals("rarbg", Episodes.releaseGroup(a))
        assertEquals("", Episodes.releaseGroup(file("The Office S04E06 1080p WEB-DL")))
        assertEquals("subsplease", Episodes.releaseGroup(file("[SubsPlease] Show - 05 (1080p).mkv")))
        val sig = Episodes.signature(a)
        val same = file("The.Office.US.S04E06.1080p.BluRay.x265-RARBG.mkv")
        val otherGroup = file("The.Office.US.S04E06.1080p.BluRay.x264-DEMAND.mkv")
        val otherRes = StreamItem("Torrentio 720p", "The.Office.US.S04E06.720p.BluRay.x265-RARBG.mkv", "http://x", emptyMap(), emptyList())
        assertTrue(Episodes.sameKind(sig, same) > 0)
        assertEquals(0, Episodes.sameKind(sig, otherGroup))
        assertEquals(0, Episodes.sameKind(sig, otherRes))
    }

    @Test
    fun sourcesFoldIntoAFewChoices() {
        fun src(addon: String, name: String) = StreamItem(addon, name, "http://x/" + addon + name.hashCode(), emptyMap(), emptyList())
        val list = listOf(
            src("A 1080p", "Movie.2020.1080p.BluRay.x264-GRP.mkv\n8.1 GB"),
            src("B 1080p", "Movie.2020.1080p.BluRay.x264-GRP.mkv\n8.1 GB"),
            src("A 4K", "Movie.2020.2160p.UHD.BluRay.REMUX.DV.HDR-FGT.mkv\n58 GB"),
            src("A 1080p", "Movie.2020.1080p.WEB-DL.DDP5.1-NTb.mkv\n5 GB"),
            src("C", "Movie"), src("C", "Movie"),
        )
        val groups = SourceGroups.group(list)
        assertEquals(listOf("best", "4k|Blu-ray Remux", "1080|Blu-ray", "1080|WEB", "other|null"), groups.map { it.key })
        assertEquals(1, groups[2].items.size) // listed twice, shown once
        assertEquals(2, groups[4].items.size) // no file name: can't tell they're the same, both stay
        assertEquals("Blu-ray Remux", SourceGroups.kind(list[2]))
        assertEquals("WEB", SourceGroups.kind(list[3]))
        // Decorated names and the file name both count; the size shows.
        val fancy = StreamItem("\uD835\uDFF0\uD835\uDDDE \u1D48\u1D5B", "nice one", "http://y", emptyMap(), emptyList(),
            fileName = "Movie.2026.2160p.iT.WEB-DL.DDP5.1.Atmos.DV.HDR10+.H.265-GRP.mkv", sizeBytes = 27_702_000_000)
        assertEquals(2160, fancy.quality.res)
        assertTrue(fancy.quality.dv)
        assertEquals("25.8 GB", Quality.sizeLabel(fancy.quality.sizeMb!!))
        assertEquals("HEVC", SourceGroups.codec(fancy))
        assertEquals(listOf("IMAX"), SourceGroups.tags(file("Movie.2020.IMAX.1080p.BluRay.x264-GRP.mkv")))
    }

    @Test
    fun specialEditionsAreTheirOwnTitle() {
        assertEquals("superfan", Episodes.edition("The Office: Superfan Episodes"))
        assertEquals("superfan", Episodes.edition("The Office (Super Fan Edition)"))
        assertEquals("extended", Episodes.edition("The Lord of the Rings (Extended)"))
        assertNull(Episodes.edition("The Office"))
        assertNull(Episodes.edition("The Office (US)"))
    }
}
