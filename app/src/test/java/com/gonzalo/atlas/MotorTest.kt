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
        for (l in h.lonas.values) if (l.tocada) d.funde(c, l, escala, 0.0, 0.0)
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

    private fun opacos(b: Bitmap): Int { val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); return px.count { (it ushr 24) > 128 } }

    /** A 65.536 aumentos y lejos de la esquina del lienzo: el trazo queda guardado como datos, nítido, y se abre igual. */
    @Test fun deMuyCerca() {
        val bib = Biblioteca(Files.createTempDirectory("atlas").toFile())
        val m = bib.creaMapa(bib.creaMundo("Prueba").id, "Ciudad", 0, 0)
        val ed = Editor(m, ctx.resources); val lz = ed.lienzo
        espera(lz)
        val capa = lz.capaActiva()!!
        val e = 16; val z = (1 shl e).toFloat()
        val ox = 4321.123456789; val oy = 2987.654321
        // Un trazo de 360 px de pantalla de largo y 5 de grueso: en el lienzo, 5 milésimas de píxel de largo
        val v = lz.empiezaTrazo(Pinceles.def("rotulador"), 0xFF203040.toInt(), 5f / z, 1f, TrazoDatos.PINTAR, z, true, -e, ox, oy)!!
        val pts = onda(0f, 360f, 0f, 24f)
        for (i in 0 until pts.size / Caminante.P) { pts[i * Caminante.P] /= z; pts[i * Caminante.P + 1] /= z }
        for (i in 0 until 70 step 7) v.agrega(pts.copyOfRange(i * Caminante.P, (i + 7) * Caminante.P))
        var hecho = false
        v.termina { ed2, err -> assertNotNull(ed2); assertTrue(err == null); lz.apunta(ed2!!); hecho = true }
        espera(lz)
        assertTrue(hecho)
        // A tamaño real no deja nada (es mil veces más fino que un píxel), pero queda guardado como datos en su tesela
        assertEquals(1, capa.trazos.size); assertTrue(capa.fondo.isNotEmpty()); assertTrue(capa.teselas.isEmpty())
        val d = capa.trazos[0].datos
        assertTrue(d.seVe(z)); assertTrue(!d.seVe(z / 4096f))
        // De cerca: las teselas nítidas que lo contienen, juntas en una imagen
        val tx = Math.floor(ox * z / 256).toInt(); val ty = Math.floor(oy * z / 256).toInt()
        fun vista(l: Lienzo, c: com.gonzalo.atlas.lienzo.CapaPx): Bitmap {
            val b = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888); val cv = Canvas(b); cv.drawColor(0xFFF1E6C8.toInt())
            for (dy in 0..1) for (dx in 0..2) cv.drawBitmap(l.nitidez.construye(c, e, tx + dx, ty - 1 + dy), dx * 256f, dy * 256f, null)
            return b
        }
        val cerca = vista(lz, capa)
        png(cerca, "muy_cerca.png")
        val tinta = IntArray(768 * 512).also { cerca.getPixels(it, 0, 768, 0, 0, 768, 512) }.count { it == 0xFF203040.toInt() }
        assertTrue("tinta: $tinta", tinta in 1200..2600)
        // Lo que se veía mientras se dibujaba es lo que queda: la pintura húmeda y la tesela rehecha tienen la misma tinta
        for ((k, l) in v.humedo.lonas) if (l.tocada) {
            val a = opacos(l.imagen(0x203040)); val b = opacos(lz.nitidez.construye(capa, e, Lienzo.tx(k), Lienzo.ty(k)))
            assertTrue("húmedo $a, tesela $b", Math.abs(a - b) <= 2 + a / 100)
        }
        // Deshacer y rehacer
        lz.deshacer(); espera(lz); assertEquals(0, capa.trazos.size)
        lz.rehacer(); espera(lz); assertEquals(1, capa.trazos.size)
        // Guardar y volver a abrir: el trazo sigue ahí, en su sitio exacto
        val l = java.util.concurrent.CountDownLatch(1); lz.enHilo { lz.guarda(); l.countDown() }; l.await()
        assertTrue(File(m.dir, "capas/${capa.info.id}/trazos/0.bin").exists())
        val ed3 = Editor(bib.abre(m.id)!!, ctx.resources); espera(ed3.lienzo)
        val capa3 = ed3.lienzo.capaActiva()!!
        assertEquals(1, capa3.trazos.size)
        assertEquals(ox, capa3.trazos[0].datos.ox, 0.0)
        assertTrue(cerca.sameAs(vista(ed3.lienzo, capa3)))
    }

    /** El bote de pintura de cerca: rellena el círculo dibujado a 4.096 aumentos, ceñido a su línea, y queda como forma. */
    @Test fun rellenoDeCerca() {
        val bib = Biblioteca(Files.createTempDirectory("atlas").toFile())
        val m = bib.creaMapa(bib.creaMundo("Prueba").id, "Ciudad", 0, 0)
        val ed = Editor(m, ctx.resources); val lz = ed.lienzo
        espera(lz)
        val capa = lz.capaActiva()!!
        val e = 12; val z = (1 shl e).toFloat()
        val ox = 1234.56789; val oy = -876.54321
        // Un círculo de 120 px de pantalla de radio alrededor de (ox, oy)
        val n = 90; val pts = FloatArray((n + 1) * Caminante.P)
        for (i in 0..n) { val a = i * 2.3 * PI / n; val o = i * Caminante.P; pts[o] = (cos(a) * 120 / z).toFloat(); pts[o + 1] = (sin(a) * 120 / z).toFloat(); pts[o + 2] = 0.8f; pts[o + 3] = 0.3f; pts[o + 5] = i * 8f }
        val v = lz.empiezaTrazo(Pinceles.def("rotulador"), 0xFF1B1712.toInt(), 6f / z, 1f, TrazoDatos.PINTAR, z, true, -e, ox, oy)!!
        v.agrega(pts); v.termina { ed2, _ -> lz.apunta(ed2!!) }; espera(lz)
        val vis = doubleArrayOf(ox - 500 / z, oy - 350 / z, ox + 500 / z, oy + 350 / z)
        // Dentro: se rellena
        val (hecho, porque) = lz.rellena(ox, oy, 0xFF3E7CB1.toInt(), 1f, z, vis)
        assertNotNull(porque, hecho); lz.apunta(hecho!!)
        assertEquals(2, capa.trazos.size); assertNotNull(capa.trazos[1].datos.relleno)
        fun vista(): Bitmap {
            val tx = Math.floor(ox * z / 256).toInt(); val ty = Math.floor(oy * z / 256).toInt()
            val b = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888); val cv = Canvas(b); cv.drawColor(0xFFF1E6C8.toInt())
            for (dy in -1..1) for (dx in -1..1) cv.drawBitmap(lz.nitidez.construye(capa, e, tx + dx, ty + dy), (dx + 1) * 256f, (dy + 1) * 256f, null)
            return b
        }
        val img = vista(); png(img, "relleno_cerca.png")
        val px = IntArray(768 * 768).also { img.getPixels(it, 0, 768, 0, 0, 768, 768) }
        val azul = px.count { it == 0xFF3E7CB1.toInt() }
        // (El círculo mide π·120² ≈ 45.000 px; la línea se come unos 3 px de su borde)
        assertTrue("azul: $azul", azul in 40000..46000)
        assertEquals(0xFFF1E6C8.toInt(), px[4 * 768 + 4])
        // Fuera: la zona se sale de lo que se ve, así que no se rellena y se dice por qué
        val (nada, aviso) = lz.rellena(ox + 300 / z, oy, 0xFF3E7CB1.toInt(), 1f, z, vis)
        assertTrue(nada == null && aviso != null)
        // Tocando otra vez dentro, sobre el azul: se cambia de color (y la línea sigue en su sitio)
        val (otra, _) = lz.rellena(ox + 30.0 / z, oy - 20.0 / z, 0xFFB5523A.toInt(), 1f, z, vis)
        assertNotNull(otra); lz.apunta(otra!!)
        val img2 = vista(); png(img2, "relleno_cerca2.png")
        val px2 = IntArray(768 * 768).also { img2.getPixels(it, 0, 768, 0, 0, 768, 768) }
        val rojo = px2.count { it == 0xFFB5523A.toInt() }; val queda = px2.count { it == 0xFF3E7CB1.toInt() }; val linea = px2.count { it == 0xFF1B1712.toInt() }
        assertTrue("rojo $rojo, azul $queda, línea $linea", rojo in 40000..46000 && queda == 0 && linea > 1500)
        lz.deshacer(); espera(lz)
        // Deshacer quita el relleno; rehacer lo devuelve
        lz.deshacer(); espera(lz); assertEquals(1, capa.trazos.size)
        lz.rehacer(); espera(lz); assertEquals(2, capa.trazos.size)
        // Guardado y abierto de nuevo, se ve igual
        val l = java.util.concurrent.CountDownLatch(1); lz.enHilo { lz.guarda(); l.countDown() }; l.await()
        val ed3 = Editor(bib.abre(m.id)!!, ctx.resources); espera(ed3.lienzo)
        assertNotNull(ed3.lienzo.capaActiva()!!.trazos[1].datos.relleno)
    }

    /** Un dibujo pequeño del Taller se guarda grande y nítido (rehecho desde sus trazos), no con los pocos píxeles que ocupa. */
    @Test fun dibujoDelTallerEnAlta() {
        val bib = Biblioteca(Files.createTempDirectory("atlas").toFile())
        val m = bib.creaMapa(bib.creaMundo("Prueba").id, "Dibujo", 0, 0)
        val ed = Editor(m, ctx.resources); val lz = ed.lienzo
        espera(lz)
        // Una montañita de unos 70 px de ancho, a tamaño real
        fun trazo(vararg p: Float) {
            val n = p.size / 2; val pts = FloatArray(n * Caminante.P)
            for (i in 0 until n) { val o = i * Caminante.P; pts[o] = p[i * 2]; pts[o + 1] = p[i * 2 + 1]; pts[o + 2] = 0.8f; pts[o + 3] = 0.3f; pts[o + 5] = i * 30f }
            val v = lz.empiezaTrazo(Pinceles.def("pluma"), 0xFF2A2118.toInt(), 2.2f, 1f, TrazoDatos.PINTAR, 1f, true, 0)!!
            v.agrega(pts); v.termina { e, _ -> lz.apunta(e!!) }; espera(lz)
        }
        trazo(100f, 160f, 118f, 128f, 130f, 112f, 141f, 126f, 150f, 118f, 170f, 160f)
        trazo(126f, 124f, 123f, 140f); trazo(131f, 122f, 129f, 146f); trazo(137f, 128f, 136f, 150f)
        val antes = lz.recorta(lz.contenido()!!)
        val alta = lz.imagenNitida(1536)!!
        png(antes, "taller_antes.png"); png(alta, "taller_alta.png")
        assertTrue("antes ${antes.width}", antes.width in 60..90)
        assertTrue("ahora ${alta.width} × ${alta.height}", alta.width in 900..1536 && alta.height in 500..1536)
        // La misma forma: la proporción no cambia (salvo los márgenes)
        assertEquals(antes.width.toFloat() / antes.height, alta.width.toFloat() / alta.height, 0.2f)
        assertTrue(lz.tieneTrazos())
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
        // Un mapa guardado con la 2.0–2.2 (todos los trazos en un archivo, sin origen ni rellenos) se abre igual
        run {
            val cd = File(m.dir, "capas/${capa2.info.id}")
            val o = java.io.DataOutputStream(java.util.zip.DeflaterOutputStream(File(cd, "trazos.bin").outputStream()))
            o.writeInt(Lienzo.FORMATO_2); o.writeLong(capa2.seq)
            val fs = capa2.fondo.entries.toList(); o.writeInt(fs.size); for ((k, f) in fs) { o.writeLong(k); o.writeLong(f.desde) }
            o.writeInt(capa2.trazos.size)
            for (t in capa2.trazos) {
                val b = java.io.ByteArrayOutputStream(); t.datos.escribe(java.io.DataOutputStream(b))
                // (Lo que escribía la versión anterior: lo mismo sin los 17 bytes finales del origen y el relleno)
                o.writeLong(t.seq); o.write(b.toByteArray(), 0, b.size() - 17)
            }
            o.close(); File(cd, "trazos").deleteRecursively()
            val viejo = Editor(Biblioteca(bib.raiz).abre(m.id)!!, ctx.resources); espera(viejo.lienzo)
            val cv = viejo.lienzo.capaActiva()!!
            assertEquals(2, cv.trazos.size)
            assertTrue(cerca.sameAs(viejo.lienzo.nitidez.construye(cv, 2, 2, 2)))
            // Y al guardarlo queda ya en el formato nuevo, igual
            cv.trazosSucios = true
            val l2 = java.util.concurrent.CountDownLatch(1); viejo.lienzo.enHilo { viejo.lienzo.guarda(); l2.countDown() }; l2.await()
            assertTrue(File(cd, "trazos/0.bin").exists())
            val otra = Editor(Biblioteca(bib.raiz).abre(m.id)!!, ctx.resources); espera(otra.lienzo)
            assertTrue(cerca.sameAs(otra.lienzo.nitidez.construye(otra.lienzo.capaActiva()!!, 2, 2, 2)))
        }
        // Rellenar encima no estropea los trazos: el relleno se guarda como una forma más y las teselas siguen rehaciéndose de cerca
        ed2.lienzo.rellena(200, 200, 0xFF88AA55.toInt(), 1f)?.let { ed2.lienzo.apunta(it) }
        assertEquals(3, capa2.trazos.size); assertNotNull(capa2.trazos[2].datos.relleno)
        assertTrue(capa2.fondo.size >= capa.fondo.size)
        assertEquals(0xFF88AA55.toInt(), ed2.lienzo.recorta(android.graphics.Rect(200, 200, 201, 201)).getPixel(0, 0))
        png(ed2.lienzo.nitidez.construye(capa2, 2, 2, 2), "trazos_cerca_relleno.png")
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
