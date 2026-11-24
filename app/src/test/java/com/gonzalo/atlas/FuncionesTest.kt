package com.gonzalo.atlas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.editor.Lamina
import com.gonzalo.atlas.lienzo.Ajustes
import com.gonzalo.atlas.lienzo.CapaPx
import com.gonzalo.atlas.lienzo.Licuar
import com.gonzalo.atlas.lienzo.Lienzo
import com.gonzalo.atlas.lienzo.Seleccion
import com.gonzalo.atlas.motor.Biblioteca
import com.gonzalo.atlas.motor.CapaInfo
import com.gonzalo.atlas.motor.Continente
import com.gonzalo.atlas.motor.FormaTierra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/** Lo que se ha añadido para dibujar como en un programa profesional: selección, máscaras de recorte, ajustes, licuar y continentes generados. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class FuncionesTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val capturas = File(System.getProperty("capturas") ?: "build/capturas")
    private fun png(b: Bitmap, n: String) { capturas.mkdirs(); File(capturas, n).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    private fun espera(lz: Lienzo) { repeat(3) { val l = java.util.concurrent.CountDownLatch(1); lz.enVivo { lz.enHilo { l.countDown() } }; l.await(); shadowOf(Looper.getMainLooper()).idle() } }
    private fun editor(w: Int = 0, h: Int = 0): Editor { val bib = Biblioteca(Files.createTempDirectory("atlas").toFile()); val ed = Editor(bib.creaMapa(bib.creaMundo("Prueba").id, "Uno", w, h), ctx.resources); espera(ed.lienzo); return ed }

    @Test fun continentes() {
        val tierra = 0xFFE4D4A8.toInt(); val mar = 0xFFB9D3DB.toInt(); val tinta = 0xFF2A2118.toInt()
        val hoja = Bitmap.createBitmap(5 * 330, 3 * 220, Bitmap.Config.ARGB_8888); val c = Canvas(hoja); c.drawColor(-1)
        FormaTierra.TIPOS.forEachIndexed { i, (tipo, _) ->
            for (s in 0 until 3) {
                val p = FormaTierra(semilla = 100L + s * 17 + i, tipo = tipo, tierra = 0.36f, rugosidad = 0.3f + s * 0.3f, islas = 0.2f + s * 0.3f)
                c.drawBitmap(Editor.imagenContinente(p, 320, 210, tierra, mar, tinta), i * 330f + 5, s * 220f + 5, null)
                val a = Continente.campo(p, 160, 100)
                assertEquals(tipo, 0.36f, a.count { it > 0f } / a.size.toFloat(), 0.02f)
                val costas = Continente.costas(a, 160, 100)
                assertTrue(tipo, costas.isNotEmpty())
                if (tipo != "costa") { assertTrue(costas.none { Continente.esAbierta(it) }); for (i2 in 0 until 160) assertTrue(a[i2] < 0f && a[99 * 160 + i2] < 0f) }
            }
        }
        png(hoja, "continentes.png")
        // Puesto en un mapa: la costa como trazos en la capa de líneas, tierra y mar en la de color, y la escala fijada por el tamaño
        val ed = editor(); val lz = ed.lienzo
        val rect = RectF(-900f, -600f, 900f, 600f)
        ed.ponContinente(FormaTierra(7L, "continente", 0.4f, 0.6f, 0.5f), rect, 3600.0, tinta, tierra, mar, true)
        espera(lz)
        val lineas = lz.capas.first { it.info.referencia }; val color = lz.capas.first { !it.info.referencia }
        assertTrue(lineas.trazos.isNotEmpty()); assertTrue(lineas.fondo.isNotEmpty()); assertTrue(color.teselas.isNotEmpty())
        assertTrue(ed.mapa.escalaPuesta); assertEquals(2.0f, ed.mapa.kmPorPx, 0.01f); assertEquals(1, ed.mapa.secciones.size)
        // El relleno funciona con esa costa: tocar el mar de fuera no se cuela en la tierra
        assertEquals(mar, lz.colorEn(-880, -580)); assertEquals(tierra, Color.rgb(Color.red(lz.colorEn(0, 0)), Color.green(lz.colorEn(0, 0)), Color.blue(lz.colorEn(0, 0))))
        png(Lamina.pinta(ed, 1400, null), "continente_mapa.png")
        png(lz.nitidez.construye(lineas, 3, lineas.fondo.keys.first().let { Lienzo.tx(it) * 8 + 3 }, lineas.fondo.keys.first().let { Lienzo.ty(it) * 8 + 3 }), "continente_cerca.png")
        lz.deshacer(); espera(lz)
        assertTrue(lz.capa(lineas.info.id)!!.trazos.isEmpty()); assertTrue(lz.capa(color.info.id)!!.teselas.isEmpty())
    }

    private fun rellena(lz: Lienzo, c: CapaPx, r: RectF, color: Int) { lz.cambia(c, "x", HashMap<Long, Bitmap?>().also { m ->
        for (ty in Math.floorDiv(r.top.toInt(), 256)..Math.floorDiv(r.bottom.toInt() - 1, 256)) for (tx in Math.floorDiv(r.left.toInt(), 256)..Math.floorDiv(r.right.toInt() - 1, 256)) {
            val b = c.teselas[Lienzo.clave(tx, ty)]?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b); cv.translate(-tx * 256f, -ty * 256f); cv.drawRect(r, android.graphics.Paint().apply { this.color = color }); m[Lienzo.clave(tx, ty)] = b
        } }) }

    @Test fun seleccionRecorteAjustesYLicuar() {
        val ed = editor(); val lz = ed.lienzo
        val c = lz.capaActiva()!!
        rellena(lz, c, RectF(0f, 0f, 400f, 300f), 0xFF336699.toInt())
        // Selección elíptica: borrar dentro; lo de fuera se queda
        ed.ponMarco(Seleccion.elipse(RectF(100f, 50f, 300f, 250f)))
        lz.borraSeleccion(c)!!.let { lz.apunta(it) }
        assertEquals(0, lz.colorCapa(c, 200f, 150f) ushr 24); assertEquals(0xFF336699.toInt(), lz.colorCapa(c, 20f, 20f))
        // Sumar un rectángulo y restar otro; rellenar la selección
        ed.ponMarco(ed.marco!!.con(Seleccion.rectangulo(RectF(280f, 100f, 380f, 200f)), false).con(Seleccion.rectangulo(RectF(150f, 120f, 250f, 180f)), true))
        lz.rellenaForma(c, ed.marco!!.path, 0xFFCC3333.toInt())!!.let { lz.apunta(it) }
        assertEquals(0xFFCC3333.toInt(), lz.colorCapa(c, 350f, 150f)); assertEquals(0xFFCC3333.toInt(), lz.colorCapa(c, 120f, 150f)); assertEquals(0, lz.colorCapa(c, 200f, 150f) ushr 24)
        // Un ajuste solo cambia lo de dentro
        Ajustes.matriz(lz, c, Ajustes.matrizInvertir(), "Invertir")!!.let { lz.apunta(it) }
        assertEquals(0xFF33CCCC.toInt(), lz.colorCapa(c, 350f, 150f)); assertEquals(0xFF336699.toInt(), lz.colorCapa(c, 20f, 20f))
        // Pintar con el motor: solo dentro de la selección
        val v = lz.empiezaTrazo(com.gonzalo.atlas.lienzo.Pinceles.def("rotulador"), 0xFF000000.toInt(), 30f, 1f, 0, 1f, false, 0)!!
        v.agrega(floatArrayOf(10f, 150f, 1f, 0f, 0f, 0f, 390f, 150f, 1f, 0f, 0f, 200f)); v.termina { e, _ -> lz.apunta(e!!) }; espera(lz)
        assertEquals(0xFF336699.toInt(), lz.colorCapa(c, 20f, 150f)); assertEquals(0xFF000000.toInt(), lz.colorCapa(c, 120f, 150f)); assertEquals(0, lz.colorCapa(c, 200f, 150f) ushr 24)
        ed.invierteMarco()
        // Copiar lo seleccionado a una capa
        lz.copiaSeleccion(c, true); espera(lz)
        assertEquals(3, lz.capas.size); assertEquals(0, lz.colorCapa(c, 20f, 20f) ushr 24); assertEquals(0xFF336699.toInt(), lz.colorCapa(lz.capaActiva()!!, 20f, 20f))
        lz.deshacer(); espera(lz); assertEquals(2, lz.capas.size); assertEquals(0xFF336699.toInt(), lz.colorCapa(lz.capa(c.info.id)!!, 20f, 20f))
        ed.ponMarco(null)
        // Máscara de recorte: la capa de encima solo se ve donde la de abajo tiene algo
        val abajo = lz.capa(c.info.id)!!
        lz.cambiaCapas("Capa") { val n = CapaPx(CapaInfo("cx", "Encima", recorte = true)); lz.capas.add(lz.capas.indexOf(abajo) + 1, n); lz.activa = "cx" }; espera(lz)
        rellena(lz, lz.capa("cx")!!, RectF(0f, 0f, 500f, 400f), 0xFF00FF00.toInt())
        ed.mapa.papel = 0
        assertEquals(0xFF00FF00.toInt(), lz.colorEn(20, 20)); assertEquals(0, lz.colorEn(200, 150) ushr 24); assertEquals(0, lz.colorEn(450, 350) ushr 24)
        png(lz.recorta(Rect(0, 0, 512, 400)), "recorte.png")
        // Desenfocar, enfocar, movimiento, ruido, degradado y licuar no rompen nada y se pueden deshacer
        lz.activa = abajo.info.id
        val antes = lz.recorta(Rect(0, 0, 512, 400), abajo)
        assertNotNull(Ajustes.desenfoca(lz, abajo, 6)?.also { lz.apunta(it) })
        assertNotNull(Ajustes.movimiento(lz, abajo, 12, 30f)?.also { lz.apunta(it) })
        assertNotNull(Ajustes.enfoca(lz, abajo, 1f)?.also { lz.apunta(it) })
        assertNotNull(Ajustes.ruido(lz, abajo, 0.4f)?.also { lz.apunta(it) })
        assertNotNull(Ajustes.degradado(lz, abajo, intArrayOf(0x201040, 0xD05030, 0xFFF0B0))?.also { lz.apunta(it) })
        val li = Licuar(lz, abajo, "empujar", 60f, 1f); for (i in 0..30) li.punto(100f + i * 5f, 150f + i * 2f, 1f); assertNotNull(li.termina()?.also { lz.apunta(it) })
        val re = Licuar(lz, abajo, "remolino", 80f, 1f); for (i in 0..20) re.punto(300f, 150f, 1f); assertNotNull(re.termina()?.also { lz.apunta(it) })
        png(lz.recorta(Rect(0, 0, 512, 400), abajo), "ajustes.png")
        repeat(7) { lz.deshacer() }; espera(lz)
        assertTrue(antes.sameAs(lz.recorta(Rect(0, 0, 512, 400), lz.capa(abajo.info.id))))
    }
}
