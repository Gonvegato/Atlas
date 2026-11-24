package com.gonzalo.atlas

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.gonzalo.atlas.lienzo.Procreate
import com.gonzalo.atlas.motor.Calco
import com.gonzalo.atlas.motor.Lugar
import com.gonzalo.atlas.motor.Mapa
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ProcreateTest {
    @Test fun importaUnBrushset() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val datos = javaClass.classLoader!!.getResourceAsStream("prueba.brushset").readBytes()
        val dir = Files.createTempDirectory("rec").toFile()
        val n = Procreate.importa(datos, dir)
        assertEquals(1, n)
        // El pincel importado pinta con su punta y su grano
        val def = com.gonzalo.atlas.lienzo.Pinceles.defs().last { it.categoria == "Importados" }
        assertEquals("Tinta seca", def.nombre)
        assertTrue(com.gonzalo.atlas.lienzo.MuestrasPincel.de(def) != null)
        val d = Procreate.dir(dir).listFiles()!!.single()
        val dirPrimero = d
        val j = JSONObject(File(d, "pincel.json").readText())
        assertEquals("Tinta seca", j.getString("n"))
        assertEquals(0.5f, def.espaciado, 1e-4f) // el espaciado se guarda al cuadrado: 0,25 es un 50 %
        assertTrue(def.grano.endsWith(":grano") && def.forma.endsWith(":forma") && def.velado)
        // Exportar un pincel de ATLAS y volver a importarlo: vuelve idéntico (con su punta y su grano)
        val orig = com.gonzalo.atlas.lienzo.Pinceles.def("carboncillo")
        val archivo = Procreate.exporta(orig)
        File(System.getProperty("capturas") ?: "build/capturas").let { it.mkdirs(); File(it, "Carboncillo.brush").writeBytes(archivo) }
        val raiz = com.gonzalo.atlas.lienzo.Bplist.raizArchivo(java.util.zip.ZipInputStream(archivo.inputStream()).use { z -> generateSequence { z.nextEntry }.first { it.name == "Brush.archive" }; z.readBytes() })
        assertEquals("Carboncillo", raiz["name"]); assertEquals(0.08 * 0.08, (raiz["plotSpacing"] as Number).toDouble(), 1e-6)
        assertEquals(1, Procreate.importa(archivo, dir))
        val vuelto = com.gonzalo.atlas.lienzo.Pinceles.defs().last { it.categoria == "Importados" }
        assertEquals(orig.copy(id = vuelto.id, categoria = "Importados", forma = vuelto.forma, grano = vuelto.grano), vuelto)
        assertTrue(vuelto.forma.endsWith(":forma") && vuelto.grano.endsWith(":grano"))
        assertTrue(com.gonzalo.atlas.lienzo.MuestrasPincel.de(vuelto) != null)
        val forma = BitmapFactory.decodeFile(File(d, "forma.png").path)
        assertTrue("el centro de la punta pinta", Color.alpha(forma.getPixel(32, 32)) > 240)
        assertTrue("el borde no", Color.alpha(forma.getPixel(2, 2)) < 10)
    }

    @Test fun vinculosYCalcoSeGuardan() {
        val m = Mapa("m", "w", "Ciudad", 0, 0, Files.createTempDirectory("m").toFile())
        m.lugares += Lugar("p", "Iglesia de San Olm", "templo", 10f, 20f, 3, "", "m_iglesia")
        m.calco = Calco(true, 5f, 6f, 700f, 12f, 0.4f, true)
        m.guardaMeta()
        val m2 = Mapa.lee(m.dir!!)!!
        assertEquals("m_iglesia", m2.lugares.single().mapa)
        assertEquals(700f, m2.calco!!.ancho, 0f)
        assertTrue(m2.calco!!.encima)
        assertTrue(m2.infinito)
    }

    @Test fun seccionesLimitanElRelleno() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val bib = com.gonzalo.atlas.motor.Biblioteca(Files.createTempDirectory("b").toFile())
        val m = bib.creaMapa(bib.creaMundo("W").id, "S", 0, 0)
        m.secciones += com.gonzalo.atlas.motor.Seccion("s1", "Reino", 100f, 100f, 300f, 200f)
        m.seccionActiva = "s1"
        val lz = com.gonzalo.atlas.lienzo.Lienzo(m, ctx.resources).also { it.carga() }
        lz.activa = m.capas.first().id
        val e = lz.rellena(200, 200, 0xFF336699.toInt(), 1f)
        org.junit.Assert.assertNotNull(e)
        assertEquals(0x33, Color.red(lz.colorEn(390, 290)))
        assertTrue("fuera de la sección no", lz.colorEn(450, 200) == m.papel)
        m.guardaMeta()
        val m2 = Mapa.lee(m.dir!!)!!
        assertEquals("s1", m2.seccionActiva)
        assertEquals(300f, m2.secciones.single().w, 0f)
    }
}
