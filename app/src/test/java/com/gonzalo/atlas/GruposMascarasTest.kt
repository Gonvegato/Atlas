package com.gonzalo.atlas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.lienzo.CapaPx
import com.gonzalo.atlas.lienzo.Grupos
import com.gonzalo.atlas.lienzo.Lienzo
import com.gonzalo.atlas.motor.Biblioteca
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
import java.nio.file.Files

/** Las máscaras de capa y los grupos: cómo se ven, cómo se ordenan, que se guardan y que deshacer los devuelve. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class GruposMascarasTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun espera(lz: Lienzo) {
        val l = java.util.concurrent.CountDownLatch(1); lz.enHilo { l.countDown() }; l.await()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Llena la tesela (0, 0) de [c] de [color] en la franja [x0, x1) (toda la altura). */
    private fun pinta(c: CapaPx, color: Int, x0: Int = 0, x1: Int = 256) {
        val t = c.teselas[Lienzo.clave(0, 0)]?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        Canvas(t).drawRect(x0.toFloat(), 0f, x1.toFloat(), 256f, android.graphics.Paint().apply { this.color = color })
        c.teselas[Lienzo.clave(0, 0)] = t; c.sucias += Lienzo.clave(0, 0)
    }

    /** El color compuesto (sin papel) en (x, y). */
    private fun color(lz: Lienzo, x: Int, y: Int): Int {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.translate(-x.toFloat(), -y.toFloat()); lz.componeZona(cv, Rect(x, y, x + 1, y + 1))
        return b.getPixel(0, 0)
    }

    private fun nuevo(): Pair<Editor, Lienzo> {
        val bib = Biblioteca(Files.createTempDirectory("atlas").toFile())
        val m = bib.creaMapa(bib.creaMundo("Prueba").id, "Uno", 1024, 768)
        val ed = Editor(m, ctx.resources)
        espera(ed.lienzo)
        // Sin las capas de serie que traiga: empezamos con una vacía
        return ed to ed.lienzo
    }

    @Test fun laMascaraEscondeSinBorrarYSeAplicaOSeInvierte() {
        val (_, lz) = nuevo()
        val c = lz.capas.first { !it.info.referencia }
        lz.capas.filter { it !== c }.forEach { it.info.visible = false }
        pinta(c, 0xFFFF0000.toInt())
        lz.cambiaCapas("Máscara") { Grupos.nuevaMascara(lz, c) }; espera(lz)
        val m = Grupos.mascara(lz, c)
        assertNotNull(m); assertEquals(m!!.info.id, lz.activa)
        // Vacía: no esconde nada
        assertEquals(0xFFFF0000.toInt(), color(lz, 200, 10))
        // Pintada la mitad izquierda: esa mitad no se ve; la capa sigue entera
        pinta(m, 0xFF000000.toInt(), 0, 128)
        assertEquals(0, color(lz, 10, 10) ushr 24)
        assertEquals(0xFFFF0000.toInt(), color(lz, 200, 10))
        assertEquals(0xFFFF0000.toInt(), c.teselas[Lienzo.clave(0, 0)]!!.getPixel(10, 10))
        // Apagada: se ve todo
        lz.cambiaCapas("Apagar") { m.info.visible = false }; espera(lz)
        assertEquals(0xFFFF0000.toInt(), color(lz, 10, 10))
        lz.cambiaCapas("Encender") { m.info.visible = true }; espera(lz)
        // Invertida: ahora se esconde la otra mitad
        lz.cambiaCapas("Invertir") { Grupos.invierteMascara(lz, c) }; espera(lz)
        assertEquals(0xFFFF0000.toInt(), color(lz, 10, 10))
        assertEquals(0, color(lz, 200, 10) ushr 24)
        // Aplicada: lo escondido se borra de la capa y la máscara desaparece
        lz.cambiaCapas("Aplicar") { Grupos.aplicaMascara(lz, c) }; espera(lz)
        assertNull(Grupos.mascara(lz, c))
        assertEquals(0, c.teselas[Lienzo.clave(0, 0)]!!.getPixel(200, 10) ushr 24)
        assertEquals(0xFFFF0000.toInt(), c.teselas[Lienzo.clave(0, 0)]!!.getPixel(10, 10))
        // Deshacer la devuelve
        lz.deshacer(); espera(lz)
        assertNotNull(Grupos.mascara(lz, lz.capa(c.info.id)!!))
    }

    @Test fun losGruposVanJuntosSeEscondenEnterosYSeGuardan() {
        val (ed, lz) = nuevo()
        val base = lz.capas.toList()
        val a = base[0]; val b = base[1]
        pinta(a, 0xFF00FF00.toInt()); pinta(b, 0xFF0000FF.toInt(), 128, 256)
        // Una tercera capa y un grupo con a y la tercera
        val c3 = CapaPx(com.gonzalo.atlas.motor.CapaInfo("c3", "Tercera"))
        lz.cambiaCapas("Nueva") { lz.capas.add(c3) }; espera(lz)
        lz.cambiaCapas("Agrupar") { val g = Grupos.agrupa(lz, c3, null); Grupos.agrupa(lz, a, g) }; espera(lz)
        val g = ed.mapa.grupos.single()
        val l = lz.capas.toList()
        val enGrupo = l.withIndex().filter { it.value.info.grupo == g.id }.map { it.index }
        assertEquals("seguidas", enGrupo.last() - enGrupo.first() + 1, enGrupo.size)
        assertEquals(2, enGrupo.size)
        // El grupo escondido: a no se ve; b sí
        lz.cambiaCapas("Esconder") { g.visible = false }; espera(lz)
        assertFalse(a.info.seVe)
        assertEquals(0, color(lz, 10, 10) ushr 24)
        assertEquals(0xFF0000FF.toInt(), color(lz, 200, 10))
        lz.cambiaCapas("Ver") { g.visible = true }; espera(lz)
        assertTrue(a.info.seVe)
        // Dentro del grupo, una capa no sale moviéndose: hay que sacarla
        val arriba = lz.capas.last { it.info.grupo == g.id }
        var movida = true
        lz.cambiaCapas("Ordenar") { movida = Grupos.mueve(lz, arriba, true) }; espera(lz)
        assertFalse(movida)
        lz.cambiaCapas("Sacar") { Grupos.desagrupa(lz, arriba) }; espera(lz)
        assertEquals("", arriba.info.grupo)
        // Se guarda y se vuelve a leer con su grupo
        ed.mapa.capas.clear(); ed.mapa.capas.addAll(lz.capas.map { it.info })
        val o = ed.mapa.vectorJson()
        val leido = com.gonzalo.atlas.motor.Mapa(ed.mapa.id, ed.mapa.mundo, ed.mapa.nombre, ed.mapa.ancho, ed.mapa.alto, null).also { it.ponVector(o) }
        assertEquals(listOf(g.id), leido.grupos.map { it.id })
        val quedo = lz.capas.single { it.info.grupo == g.id }
        assertEquals(g.id, leido.capas.first { it.id == quedo.info.id }.grupo)
        // Sin capas, el grupo desaparece solo
        lz.cambiaCapas("Deshacer grupo") { Grupos.deshazGrupo(lz, g.id) }; espera(lz)
        assertTrue(ed.mapa.grupos.isEmpty())
    }
}
