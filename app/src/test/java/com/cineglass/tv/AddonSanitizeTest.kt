package com.cineglass.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Add-on list entries are untrusted until checked: these rules keep every source legal and bounded. */
class AddonSanitizeTest {
    @Test fun stremioAddonNeedsHttpsManifestAndPlainHosts() {
        val ok = Addons.sanitize(Addon("x", "X", "", Addons.STREMIO, manifestUrl = "https://addon.example.org/manifest.json",
            allowedHosts = listOf("Archive.org", "*.evil.com", "1.2.3", "media.example.org/path")))
        assertNotNull(ok)
        assertEquals(listOf("archive.org"), ok!!.allowedHosts)
        assertNull(Addons.sanitize(Addon("x", "X", "", Addons.STREMIO, manifestUrl = "http://addon.example.org/manifest.json", allowedHosts = listOf("archive.org"))))
        assertNull(Addons.sanitize(Addon("x", "X", "", Addons.STREMIO, manifestUrl = "https://addon.example.org/manifest.json", allowedHosts = emptyList())))
        assertNull(Addons.sanitize(Addon("x", "X", "", Addons.STREMIO, manifestUrl = "https://addon.example.org/", allowedHosts = listOf("archive.org"))))
    }

    @Test fun unknownEnginesAreDropped() {
        assertNull(Addons.sanitize(Addon("x", "X", "", "torrent", query = "collection:(feature_films)")))
    }

    @Test fun archiveQueriesOutsideCuratedCollectionsNeedALicence() {
        val curated = Addons.sanitize(Addon("x", "X", "", Addons.ARCHIVE, "collection:(feature_films OR silent_films)"))!!
        assertFalse(curated.requireLicence)
        val broad = Addons.sanitize(Addon("x", "X", "", Addons.ARCHIVE, "collection:(moviesandfilms)"))!!
        assertTrue(broad.requireLicence)
        val mixed = Addons.sanitize(Addon("x", "X", "", Addons.ARCHIVE, "subject:(blender) AND collection:(feature_films)"))!!
        assertTrue(mixed.requireLicence)
        assertNull(Addons.sanitize(Addon("x", "X", "", Addons.ARCHIVE, "collection:(a); DROP")))
    }
}
