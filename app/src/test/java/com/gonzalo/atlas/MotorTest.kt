package com.gonzalo.atlas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.lienzo.Caminante
import com.gonzalo.atlas.lienzo.Catalogo2
import com.gonzalo.atlas.lienzo.Formas
import com.gonzalo.atlas.lienzo.Humedo
import com.gonzalo.atlas.lienzo.Lienzo
import com.gonzalo.atlas.lienzo.Motor
import com.gonzalo.atlas.lienzo.Pinceles
import com.gonzalo.atlas.lienzo.Raster
import com.gonzalo.atlas.lienzo.TrazoDatos
import com.gonzalo.atlas.lienzo.TrazoVivo
import com.gonzalo.atlas.motor.Biblioteca
import com.gonzalo.atlas.vista.Letras
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** El motor de pinceles: láminas de muestra (para verlas), y que lo dibujado queda igual al soltar, al guardar y de cerca. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class MotorTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val capturas = File(System.getProperty("capturas") ?: "build/capturas")
    private fun png(b: Bitmap, n: String) { capturas.mkdirs(); File(capturas, n).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    /** Un trazo ondulado de (x0, y) a (x1, y), con la presión subiendo y bajando. */
    private fun onda(x0: Float, x1: Float, y: Float, alto: Float, n: Int = 70): FloatArray {
        val pts = FloatArray(n * Caminante.P)
        for (i in 0 until n) {
            val t = i / (n - 1f); val o = i * Caminante.P
            pts[o] = x0 + t * (x1 - x0); pts[o + 1] = y + sin(t * 2 * PI).toFloat() * alto
            pts[o + 2] = (0.08f + 0.92f * sin(t * PI).toFloat()).coerceIn(0f, 1f); pts[o + 3] = 0.3f; pts[o + 4] = 0f; pts[o + 5] = t * 600f
        }
        return pts
    }

    private fun pinta(c: Canvas, d: TrazoDatos, escala: Float = 1f) {
        val h = Humedo(escala, d.def.colorVariable)
        h.sellaTodo(Raster(d.def, escala), d.sellos())
        c.save(); c.scale(escala, escala)
        for (l in h.lonas.values) if (l.tocada) d.funde(c, l, escala)
        c.restore()
    }

    @Test fun laminaDePinceles() {
        Letras.carga(ctx)
        val defs = Catalogo2.SERIE
        val cols = 3; val cw = 560; val fh = 96
        val filas = (defs.size + cols - 1) / cols
        val b = Bitmap.createBitmap(cols * cw, filas * fh + 20, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(0xFFF1E9D2.toInt())
        val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A5040.toInt(); textSize = 15f; typeface = Letras.inter }
        val t0 = System.nanoTime()
        defs.forEachIndexed { i, d ->
            val x = (i % cols) * cw; val y = (i / cols) * fh + 10
            c.drawText(d.nombre + " · " + d.categoria, x + 14f, y + 16f, texto)
            val tam = minOf(d.tamano, 34f)
            // Con lápiz (presión) y con el dedo (sin ella)
            pinta(c, TrazoDatos(d, 0xFF2A2118.toInt(), tam, d.opacidad, TrazoDatos.PINTAR, false, listOf(onda(x + 24f, x + 270f, y + 56f, 14f)), 11, 1f, true, null, null))
            pinta(c, TrazoDatos(d, 0xFF5C3A7E.toInt(), tam, d.opacidad, TrazoDatos.PINTAR, false, listOf(onda(x + 300f, x + 536f, y + 56f, 14f)), 12, 1f, false, null, null))
        }
        println("Lámina de ${defs.size} pinceles en ${(System.nanoTime() - t0) / 1_000_000} ms")
        png(b, "pinceles.png")
        // Cada pincel deja algo
        for (d in defs) { val s = TrazoDatos(d, -1, d.tamano, 1f, 0, false, listOf(onda(0f, 300f, 0f, 20f)), 1, 1f, true, null, null).sellos(); assertTrue(d.nombre, s.n > 0) }
    }

    @Test fun deCercaSaleNitido() {
        // El mismo trazo a tamaño real y ocho veces más cerca: la versión de cerca tiene que ser el mismo dibujo, más fino
        val d = Pinceles.def("lapiz6b")
        val t = TrazoDatos(d, 0xFF000000.toInt(), 7f, 1f, TrazoDatos.PINTAR, false, listOf(onda(10f, 110f, 30f, 12f)), 5, 1f, true, null, null)
        val a = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888); pinta(Canvas(a), t, 1f)
        val g = Bitmap.createBitmap(128 * 8, 64 * 8, Bitmap.Config.ARGB_8888); pinta(Canvas(g), t, 8f)
        val junto = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val c = Canvas(junto); c.drawColor(-1)
        c.drawBitmap(a, null, android.graphics.Rect(0, 0, 1024, 512), Paint(Paint.FILTER_BITMAP_FLAG)); c.drawBitmap(g, 0f, 512f, null)
        png(junto, "de_cerca.png")
        // Cubren más o menos lo mismo
        fun tinta(b: Bitmap): Double { val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); return px.sumOf { (it ushr 24).toDouble() } / 255.0 / px.size }
        assertEquals(tinta(a), tinta(g), 0.02)
    }

    private fun espera(lz: Lienzo) {
        repeat(3) {
            val l = java.util.concurrent.CountDownLatch(1); lz.enVivo { lz.enHilo { l.countDown() } }; l.await()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun trazoVivoQuedaIgualAlSoltarYSeGuarda() {
        val bib = Biblioteca(Files.createTempDirectory("atlas").toFile())
        val m = bib.creaMapa(bib.creaMundo("Prueba").id, "Uno", 0, 0)
        val ed = Editor(m, ctx.resources); val lz = ed.lienzo
        espera(lz)
        val capa = lz.capaActiva()!!
        // Un trazo a tamaño real y otro de cerca (a 4×), llegando por trozos como los manda el lápiz
        for ((nivel, y) in listOf(0 to 60f, -2 to 140f)) {
            val v = lz.empiezaTrazo(Pinceles.def("pluma"), 0xFF203040.toInt(), 6f, 1f, TrazoDatos.PINTAR, 1f, true, nivel)!!
            val pts = onda(40f, 400f, y, 20f)
            for (i in 0 until 70 step 7) v.agrega(pts.copyOfRange(i * Caminante.P, (i + 7) * Caminante.P))
            var hecho = false
            v.termina { e, err -> assertNotNull(e); assertTrue(err == null); lz.apunta(e!!); hecho = true }
            espera(lz)
            assertTrue(hecho); assertEquals(TrazoVivo.ASENTADO, v.estado)
        }
        assertEquals(2, capa.trazos.size)
        assertTrue(capa.fondo.isNotEmpty())
        // Todos los pinceles de serie dejan pintura mientras se dibuja (no solo al soltar)
        for (d in Catalogo2.SERIE) {
            val v = lz.empiezaTrazo(d, 0xFF203040.toInt(), d.tamano, 1f, TrazoDatos.PINTAR, 1f, true, 0)!!
            v.agrega(onda(40f, 400f, 600f, 20f)); espera(lz)
            assertTrue(d.nombre, v.humedo.lonas.values.any { it.tocada })
            v.cancela()
        }
        val antes = lz.recorta(android.graphics.Rect(0, 0, 512, 256))
        // De cerca: la tesela nítida se hace con los dos trazos
        val cerca = lz.nitidez.construye(capa, 2, 2, 2)
        png(antes, "trazos.png"); png(cerca, "trazos_cerca.png")
        // Deshacer quita el último y rehacer lo devuelve
        lz.deshacer(); espera(lz); assertEquals(1, capa.trazos.size)
        lz.rehacer(); espera(lz); assertEquals(2, capa.trazos.size)
        assertTrue(antes.sameAs(lz.recorta(android.graphics.Rect(0, 0, 512, 256))))
        // Guardar y volver a abrir: mismos píxeles y los trazos siguen como datos
        lz.enHilo { lz.guarda() }; espera(lz)
        val ed2 = Editor(Biblioteca(bib.raiz).abre(m.id)!!, ctx.resources); espera(ed2.lienzo)
        val capa2 = ed2.lienzo.capaActiva()!!
        assertEquals(2, capa2.trazos.size)
        assertTrue(antes.sameAs(ed2.lienzo.recorta(android.graphics.Rect(0, 0, 512, 256))))
        assertTrue(cerca.sameAs(ed2.lienzo.nitidez.construye(capa2, 2, 2, 2)))
        // Rellenar encima deja esas teselas solo como píxeles
        ed2.lienzo.rellena(200, 200, 0xFF88AA55.toInt(), 1f)?.let { ed2.lienzo.apunta(it) }
        assertTrue(capa2.fondo.size < capa.fondo.size)
    }

    @Test fun formasRapidas() {
        fun xy(n: Int, f: (Float) -> Pair<Float, Float>): Pair<FloatArray, FloatArray> { val x = FloatArray(n); val y = FloatArray(n); for (i in 0 until n) { val p = f(i / (n - 1f)); x[i] = p.first; y[i] = p.second }; return x to y }
        val tiembla = { t: Float -> sin(t * 40f) * 3f }
        val (lx, ly) = xy(40) { t -> (100 + t * 300) to (100 + t * 40 + tiembla(t)) }
        assertEquals("Recta", Formas.reconoce(lx, ly, 40)!!.nombre)
        val (cx, cy) = xy(80) { t -> (300 + cos(t * 2 * PI.toFloat() * 0.98f) * (100 + tiembla(t))) to (300 + sin(t * 2 * PI.toFloat() * 0.98f) * (104 + tiembla(t))) }
        assertEquals("Círculo", Formas.reconoce(cx, cy, 80)!!.nombre)
        val (ex, ey) = xy(80) { t -> (300 + cos(t * 2 * PI.toFloat()) * 160) to (300 + sin(t * 2 * PI.toFloat()) * 70 + tiembla(t)) }
        assertEquals("Elipse", Formas.reconoce(ex, ey, 80)!!.nombre)
        val (rx, ry) = xy(120) { t -> val q = t * 4; when { q < 1 -> (100 + q * 300) to (100 + tiembla(t)); q < 2 -> (400 + tiembla(t)) to (100 + (q - 1) * 200); q < 3 -> (400 - (q - 2) * 300) to (300 + tiembla(t)); else -> (100 + tiembla(t)) to (300 - (q - 3) * 195) } }
        assertEquals("Rectángulo", Formas.reconoce(rx, ry, 120)!!.nombre)
        val (tx, ty) = xy(90) { t -> val q = t * 3; when { q < 1 -> (100 + q * 300) to (300 + tiembla(t)); q < 2 -> (400 - (q - 1) * 150) to (300 - (q - 1) * 220); else -> (250 - (q - 2) * 146) to (80 + (q - 2) * 216) } }
        assertEquals("Triángulo", Formas.reconoce(tx, ty, 90)!!.nombre)
        val (ax, ay) = xy(50) { t -> (100 + t * 300) to (300 - sin(t * PI.toFloat()) * 120) }
        assertEquals("Arco", Formas.reconoce(ax, ay, 50)!!.nombre)
        assertTrue(Motor.nivel(1f) == 0 && Motor.nivel(4f) == -2 && Motor.nivel(0.3f) == 1 && Motor.escala(-3) == 8f && Motor.escala(2) == 0.25f)
    }
}
