package com.gonzalo.atlas

import android.graphics.RectF
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.lienzo.Lienzo
import com.gonzalo.atlas.motor.AcabadoCosta
import com.gonzalo.atlas.motor.Biblioteca
import com.gonzalo.atlas.motor.CambioTerritorio
import com.gonzalo.atlas.motor.Cronica
import com.gonzalo.atlas.motor.FormaTierra
import com.gonzalo.atlas.motor.Mapa
import com.gonzalo.atlas.motor.Territorio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/** La línea del tiempo (fronteras por año), los tiempos de viaje y el acabado de la costa. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class MundoTiempoTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun espera(lz: Lienzo) { repeat(3) { val l = java.util.concurrent.CountDownLatch(1); lz.enVivo { lz.enHilo { l.countDown() } }; l.await(); shadowOf(Looper.getMainLooper()).idle() } }

    private val ROJO = 0xFFB3362E.toInt(); private val AZUL = 0xFF3E5C9A.toInt()

    private fun mapaConReinos(): Mapa {
        val m = Mapa("m1", "w1", "Ithria", 1024, 768, null)
        m.colorPorNivel = 0   // por imperios: el color del de arriba del todo
        m.territorios += Territorio(1, "Halven", color = ROJO)
        m.territorios += Territorio(2, "Corvia", color = AZUL)
        m.territorios += Territorio(3, "Ostra", tipo = "Provincia", color = 0xFF777777.toInt(), padre = 1)
        m.territorios += Territorio(4, "Nueva Marca", tipo = "Marca", color = 0xFF55AA55.toInt())
        m.cambios += CambioTerritorio("c1", 3, 341, "pasa", padre = 2, ocupado = true)
        m.cambios += CambioTerritorio("c2", 3, 365, "pasa", padre = 1)
        m.cambios += CambioTerritorio("c3", 4, 350, "aparece")
        return m
    }

    @Test fun lasFronterasCambianConLosAnos() {
        val m = mapaConReinos()
        // Sin año: tal como está dibujado
        assertNull(m.anoVista); assertEquals(ROJO, m.colorDe(3)); assertTrue(m.existe(4))
        // Antes de la guerra, Ostra es de Halven
        assertEquals(ROJO, m.colorDe(3, 340)); assertFalse(m.ocupado(3, 340))
        // En 341 la ocupa Corvia: su color, rayada con el de antes
        assertEquals(AZUL, m.colorDe(3, 341)); assertTrue(m.ocupado(3, 341)); assertEquals(ROJO, m.colorAntesDe(3, 341))
        assertEquals(2, m.padreDe(m.territorio(3)!!, 360))
        // En 365 vuelve a Halven por tratado (no es ocupación)
        assertEquals(ROJO, m.colorDe(3, 365)); assertFalse(m.ocupado(3, 365))
        // La Nueva Marca no existe antes de 350
        assertFalse(m.existe(4, 349)); assertEquals(0, m.colorDe(4, 349)); assertTrue(m.existe(4, 350))
        // Con el año puesto en el mapa, lo de siempre (linaje, hijos) sigue al año
        m.anoVista = 341
        assertEquals(listOf("Ostra", "Corvia"), m.linaje(3).map { it.nombre })
        assertEquals(listOf("Ostra"), m.hijos(2).map { it.nombre })
        assertTrue(m.hijos(1).isEmpty())
        // Se guarda y se lee igual
        val leido = Mapa("m1", "w1", "Ithria", 1024, 768, null).also { it.ponVector(m.vectorJson()) }
        assertEquals(3, leido.cambios.size)
        assertEquals(listOf(true, false, false), leido.cambios.sortedBy { it.ano }.map { it.ocupado })
        assertNull("el año que se ve no se guarda", leido.anoVista)
    }

    @Test fun losTiemposDeViajeSalenDelLargoYDeLasVelocidades() {
        val archivo = File(Files.createTempDirectory("cr").toFile(), "w.json")
        val cr = Cronica(archivo)
        val t = cr.tiempos(214.0, "calzada").toMap()
        assertEquals(214.0 / 27, t["pie"]!!, 1e-9); assertEquals(214.0 / 55, t["caballo"]!!, 1e-9); assertEquals(214.0 / 36, t["carro"]!!, 1e-9)
        // Por sendero se tarda más; por mar, en barco; las fronteras no se viajan
        assertTrue(cr.tiempos(214.0, "sendero").toMap()["pie"]!! > t["pie"]!!)
        assertEquals(listOf("barco"), cr.tiempos(300.0, "maritima").map { it.first })
        assertTrue(cr.tiempos(300.0, "frontera").isEmpty())
        // Las velocidades del mundo y el año de ahora se guardan
        cr.velocidades["pie"] = 30.0; cr.anoActual = 342; cr.guarda()
        val otra = Cronica(archivo)
        assertEquals(30.0, otra.velocidades["pie"]!!, 0.0); assertEquals(342, otra.anoActual)
        // Los años de las guerras se leen del texto
        assertEquals(340, Cronica.ano("año 340 de la Segunda Era")); assertEquals(-12, Cronica.ano("-12")); assertNull(Cronica.ano("hace mucho"))
    }

    @Test fun elAcabadoDeLaCostaRodeaLaTierraSinPisarla() {
        val bib = Biblioteca(Files.createTempDirectory("atlas").toFile())
        val ed = Editor(bib.creaMapa(bib.creaMundo("Prueba").id, "Uno", 0, 0), ctx.resources)
        val lz = ed.lienzo; espera(lz)
        ed.ponContinente(FormaTierra(7L, "continente", 0.4f, 0.6f, 0.5f), RectF(-900f, -600f, 900f, 600f), 3600.0, 0xFF2A2118.toInt(), 0xFFE4D4A8.toInt(), 0xFFB9D3DB.toInt(), false)
        espera(lz)
        assertNull(lz.capa(Editor.CAPA_ACABADO))
        ed.ponAcabadoCosta(AcabadoCosta(lineas = 3))
        espera(lz)
        val a = lz.capa(Editor.CAPA_ACABADO)
        assertNotNull(a); assertTrue(a!!.teselas.isNotEmpty())
        // Va justo encima de «Tierra y mar»
        assertEquals(lz.capas.indexOfFirst { it.info.id == Editor.CAPA_TIERRA } + 1, lz.capas.indexOf(a))
        // En el centro del continente (tierra) no hay nada del acabado
        val t = a.teselas[Lienzo.clave(0, 0)]
        if (t != null) assertEquals(0, t.getPixel(0, 0) ushr 24)
        // (Una imagen para mirarla: build/capturas/acabado_costa.png)
        run {
            val b = android.graphics.Bitmap.createBitmap(880, 640, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b); c.drawColor(0xFFF1E6C8.toInt()); c.scale(0.4f, 0.4f); c.translate(1100f, 800f)
            lz.componeZona(c, android.graphics.Rect(-1100, -800, 1100, 800))
            File(System.getProperty("capturas") ?: "build/capturas").apply { mkdirs() }.let { d -> File(d, "acabado_costa.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        }
        // Quitarlo lo quita; deshacer lo vuelve a poner
        ed.ponAcabadoCosta(null); espera(lz)
        assertNull(lz.capa(Editor.CAPA_ACABADO)); assertNull(ed.mapa.acabadoCosta)
        lz.deshacer(); espera(lz)
        assertNotNull(lz.capa(Editor.CAPA_ACABADO))
    }
}
