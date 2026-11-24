package com.gonzalo.atlas.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

class MotorTest {
    private val capturas = File(System.getProperty("capturas") ?: "build/capturas")
    private fun tmp() = Files.createTempDirectory("atlas").toFile()

    private fun trazo(m: Mapa, h: Historial, p: Pincel, vararg pts: Float) {
        val t = Trazo(m, p)
        var i = 0
        while (i < pts.size) { t.punto(pts[i], pts[i + 1], 1f); i += 2 }
        t.termina()?.let { h.apunta(it) }
    }

    @Test fun pincelesDeshacerYGuardar() {
        val bib = Biblioteca(tmp())
        val w = bib.creaMundo("Prueba")
        val m = bib.creaMapa(w.id, "Uno", Generador.Estilo.MAR, 4, 4, 7L)
        val h = Historial(m)
        assertTrue(m.alt(300, 300) < 0f)
        trazo(m, h, Pincel(Herramienta.TIERRA, 120f, 0.8f), 300f, 300f, 700f, 300f, 700f, 700f)
        assertTrue("hay tierra", m.alt(500, 300) > 0f)
        // Pasar dos veces por el mismo sitio no acumula (cobertura por trazo)
        val antes = m.alt(500, 300)
        trazo(m, h, Pincel(Herramienta.SUBIR, 60f, 0.5f), 500f, 300f, 500f, 310f, 500f, 300f, 500f, 310f)
        val una = m.alt(500, 300) - antes
        assertTrue("sube", una > 0.05f && una < 0.12f)
        h.deshacer()
        assertEquals(antes, m.alt(500, 300), 1e-6f)
        h.rehacer()
        assertEquals(antes + una, m.alt(500, 300), 1e-6f)
        // Cañón: fondo plano y hondo en el centro, borde casi intacto lejos
        trazo(m, h, Pincel(Herramienta.SUBIR, 200f, 1f), 500f, 500f)
        val alto = m.alt(500, 500)
        trazo(m, h, Pincel(Herramienta.CANON, 30f, 1f), 400f, 500f, 600f, 500f)
        assertTrue("cañón hondo", m.alt(500, 500) < alto - 0.1f)
        // Biomas, reinos, zonas
        trazo(m, h, Pincel(Herramienta.BIOMA, 50f, 1f, valor = Catalogo.B_DESIERTO), 450f, 300f)
        assertEquals(Catalogo.B_DESIERTO, m.bio(450, 300))
        m.reinos += Reino(1, "Valdor", Catalogo.COLORES_REINO[0])
        trazo(m, h, Pincel(Herramienta.REINO, 50f, 1f, valor = 1), 650f, 300f)
        assertEquals(1, m.reino(650, 300))
        assertEquals(null, Relleno.rellena(m, 100, 100, Capa.REINO, 1))
        val relleno = Relleno.rellena(m, 700, 650, Capa.REINO, 1)
        assertNotNull(relleno)
        assertEquals(1, m.reino(700, 600))
        assertEquals("el mar no se rellena", 0, m.reino(100, 100))
        // Guardar y volver a abrir
        m.lugares += Lugar("p1", "Puerto Valdrín", "puerto", 650f, 320f, 4)
        Formato.guarda(m)
        val m2 = bib.abre(m.id)!!
        assertEquals(m.alt(500, 300), m2.alt(500, 300), 1e-4f)
        assertEquals(1, m2.reino(700, 600))
        assertEquals("Puerto Valdrín", m2.lugares.single().nombre)
        assertEquals("Valdor", m2.reino(1)!!.nombre)
        // Lo no tocado se vuelve a generar igual
        assertEquals(m.alt(10, 1000), m2.alt(10, 1000), 0f)
        val km = Medidas.reinos(m2)
        assertTrue(km.pixeles[1] > 10000)
    }

    @Test fun unirMapas() {
        val bib = Biblioteca(tmp())
        val w = bib.creaMundo("Prueba")
        val a = bib.creaMapa(w.id, "A", Generador.Estilo.ISLA, 2, 2, 1L)
        val b = bib.creaMapa(w.id, "B", Generador.Estilo.ISLA, 3, 2, 2L)
        a.reinos += Reino(1, "Ra", 0); b.reinos += Reino(1, "Rb", 0)
        Trazo(a, Pincel(Herramienta.REINO, 40f, 1f, valor = 1, soloTierra = false)).punto(256f, 256f)
        Trazo(b, Pincel(Herramienta.REINO, 40f, 1f, valor = 1, soloTierra = false)).punto(256f, 256f)
        b.lugares += Lugar("x", "Bx", "ciudad", 100f, 100f)
        val altB = b.alt(300, 200)
        val u = bib.une(a, b, "E")
        assertEquals(5 * 256, u.ancho)
        assertEquals(altB, u.alt(512 + 300, 200), 1e-4f)
        assertEquals(1, u.reino(256, 256))
        val rb = u.reino(512 + 256, 256)
        assertTrue(rb != 1 && rb != 0)
        assertEquals("Rb", u.reino(rb)!!.nombre)
        assertEquals(612f, u.lugares.single().x, 0.01f)
        assertEquals(3, bib.mapas().size)
    }

    @Test fun accidentesYZonas() {
        val m = Mapa("x", "w", "Accidentes", 3L, Generador.Estilo.LLANO, 6, 4, 0.5f, null)
        val h = Historial(m)
        val e = Estampa(m)
        val puestos = listOf("volcan" to (200f to 200f), "caldera" to (500f to 200f), "crater" to (800f to 200f), "lago" to (1100f to 200f),
            "meseta" to (1400f to 200f), "geiser" to (200f to 500f), "sima" to (500f to 500f), "dunas" to (800f to 500f), "salar" to (1100f to 500f), "oasis" to (1400f to 520f))
        for ((k, p) in puestos) { val ed = Estampa(m).pon(k, p.first, p.second, 110f); assertNotNull(k, ed); h.apunta(ed!!) }
        // Al mar: hundir la franja de abajo y poner islas, archipiélago, atolón, arrecife y delta
        trazo(m, h, Pincel(Herramienta.MAR, 120f, 1f), 0f, 860f, 1536f, 860f)
        for ((k, p) in listOf("isla" to (250f to 880f), "archipielago" to (650f to 880f), "atolon" to (1050f to 880f), "delta" to (1400f to 760f)))
            Estampa(m).pon(k, p.first, p.second, if (k == "isla") 70f else 110f)?.let { h.apunta(it) }
        Estampa(m).alLargo("arrecife", floatArrayOf(100f, 400f), floatArrayOf(780f, 790f), 30f)
        Estampa(m).alLargo("cordillera", floatArrayOf(150f, 700f, 1300f), floatArrayOf(680f, 640f, 690f), 45f)
        Estampa(m).alLargo("fiordo", floatArrayOf(1500f, 1250f), floatArrayOf(820f, 700f), 25f)
        // Zonas: una ciudad grande, templos, minas, granjas
        for ((z, p) in listOf(1 to (330f to 330f), 3 to (640f to 340f), 4 to (950f to 340f), 5 to (1250f to 340f), 2 to (330f to 420f), 6 to (640f to 420f), 8 to (950f to 420f)))
            Trazo(m, Pincel(Herramienta.ZONA, 45f, 1f, 0.6f, valor = z)).punto(p.first, p.second)
        assertTrue(m.bio(200, 200) == Catalogo.B_LAVA)
        assertTrue(m.alt(200, 230) > m.alt(200, 330))
        val p = Pintor(m)
        val out = IntArray(1536 * 1024)
        p.pinta(0f, 0f, 1f, 1536, 1024, out)
        Png.escribe(out, 1536, 1024, File(capturas, "accidentes.png"))
        val o2 = IntArray(800 * 500)
        p.pinta(230f, 280f, 0.25f, 800, 500, o2)
        Png.escribe(o2, 800, 500, File(capturas, "zonas_cerca.png"))
        assertTrue(abs(m.alt(1, 1)) < 1f)
    }
}
