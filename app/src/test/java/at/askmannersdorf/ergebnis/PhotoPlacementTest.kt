package at.askmannersdorf.ergebnis

import at.askmannersdorf.ergebnis.flyer.PhotoPlacement
import at.askmannersdorf.ergebnis.flyer.PhotoPlacement.Box
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPlacementTest {
    // Gesichter wie im Mannschaftsfoto (2160×980): oberes Drittel bis knapp unter die Mitte
    private val faces = listOf(
        Box(560f, 330f, 720f, 560f), Box(960f, 340f, 1040f, 440f), Box(1380f, 360f, 1520f, 520f),
        Box(700f, 610f, 840f, 780f), Box(1330f, 620f, 1480f, 790f), Box(1500f, 560f, 1700f, 780f),
    )

    private fun check(h: Float, safeTop: Float, safeBot: Float, bandTop: Float, bandBot: Float) {
        val p = PhotoPlacement.place(2160f, 980f, faces, 1080f, bandTop, bandBot, safeTop, safeBot)
        faces.forEach { f ->
            val top = f.y0 * p.scale + p.ty; val bot = f.y1 * p.scale + p.ty
            val left = f.x0 * p.scale + p.tx; val right = f.x1 * p.scale + p.tx
            assertTrue("Gesicht oben verdeckt: $top < $safeTop", top >= safeTop - 0.5f)
            assertTrue("Gesicht vom Titel verdeckt: $bot > $safeBot", bot <= safeBot + 0.5f)
            assertTrue("Gesicht links abgeschnitten", left >= 0f)
            assertTrue("Gesicht rechts abgeschnitten", right <= 1080f)
        }
    }

    @Test fun feedFacesStayInFreeZone() = check(1350f, 365f, 755f, 200f, 960f)
    @Test fun storyFacesStayInFreeZone() = check(1920f, 365f, 1320f, 200f, 1530f)
    @Test fun tightZoneShrinksPhoto() = check(1350f, 365f, 560f, 200f, 960f)

    @Test fun noFacesFillsBand() {
        val p = PhotoPlacement.place(2160f, 980f, emptyList(), 1080f, 200f, 960f, 365f, 755f)
        assertTrue(p.ty <= 200f && p.ty + 980f * p.scale >= 960f)
        assertTrue(p.tx <= 0f && p.tx + 2160f * p.scale >= 1080f)
    }

    @Test fun feedCoversBandWhenPossible() {
        val p = PhotoPlacement.place(2160f, 980f, faces, 1080f, 200f, 960f, 365f, 755f)
        assertTrue("Foto soll ganze Breite füllen", p.tx <= 0f && p.tx + 2160f * p.scale >= 1080f)
    }
}
