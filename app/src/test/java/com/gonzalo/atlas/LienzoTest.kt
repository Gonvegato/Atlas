package com.gonzalo.atlas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.editor.Lamina
import com.gonzalo.atlas.lienzo.Lienzo
import com.gonzalo.atlas.motor.Biblioteca
import com.gonzalo.atlas.motor.Lugar
import com.gonzalo.atlas.motor.Mapa
import com.gonzalo.atlas.motor.Rotulo
import com.gonzalo.atlas.motor.Territorio
import com.gonzalo.atlas.ui.Uniones
import com.gonzalo.atlas.vista.Capas
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class LienzoTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val capturas = File(System.getProperty("capturas") ?: "build/capturas")

    /** Dibuja «a mano» (sin Ink) en una capa: líneas de tinta como las que haría el lápiz. */
    private fun dibuja(lz: Lienzo, capa: String, color: Int, ancho: Float, vararg puntos: Float) {
        val c = lz.capa(capa)!!
        val path = android.graphics.Path(); path.moveTo(puntos[0], puntos[1]); var i = 2; while (i < puntos.size) { path.lineTo(puntos[i], puntos[i + 1]); i += 2 }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ancho; this.color = color; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val b = android.graphics.RectF(); path.computeBounds(b, true)
        for (ty in Math.floorDiv((b.top - ancho).toInt(), 256)..Math.floorDiv((b.bottom + ancho).toInt(), 256)) for (tx in Math.floorDiv((b.left - ancho).toInt(), 256)..Math.floorDiv((b.right + ancho).toInt(), 256)) {
            val k = Lienzo.clave(tx, ty)
            val t = c.teselas[k]?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val cv = Canvas(t); cv.translate(-tx * 256f, -ty * 256f); cv.drawPath(path, p)
            c.teselas[k] = t; c.sucias += k
        }
    }

    private fun espera(lz: Lienzo) {
        val l = java.util.concurrent.CountDownLatch(1); lz.enHilo { l.countDown() }; l.await()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun png(b: Bitmap, n: String) { capturas.mkdirs(); File(capturas, n).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    @Test fun rellenoTerritoriosDeshacerGuardarYUnir() {
        val bib = Biblioteca(Files.createTempDirectory("atlas").toFile())
        val w = bib.creaMundo("Prueba")
        val m = bib.creaMapa(w.id, "Uno", 1024, 768)
        val ed = Editor(m, ctx.resources)
        val lz = ed.lienzo
        val lineas = m.capas.first { it.referencia }.id
        val color = m.capas.first { !it.referencia }.id
        // Una costa cerrada y una frontera que la parte en dos
        dibuja(lz, lineas, 0xFF2A2118.toInt(), 5f, 150f, 150f, 870f, 120f, 900f, 600f, 500f, 680f, 120f, 560f, 150f, 150f)
        dibuja(lz, lineas, 0xFF2A2118.toInt(), 3f, 500f, 120f, 520f, 400f, 480f, 680f)
        // Relleno: dentro de la costa, a la izquierda de la frontera
        lz.activa = color
        val e1 = lz.rellena(300, 400, 0xFFAFBB72.toInt(), 1f)
        assertNotNull(e1); lz.apunta(e1!!)
        val px = lz.colorEn(300, 400)
        assertEquals(0xAF, Color.red(px)); assertEquals(0xBB, Color.green(px))
        assertTrue("fuera de la costa no se pinta", lz.colorEn(50, 50) == m.papel)
        assertTrue("al otro lado de la frontera no", lz.colorEn(700, 400) == m.papel)
        assertNull("sobre una línea no se rellena", lz.rellena(150, 150, 0xFFFF0000.toInt(), 1f))
        // Territorios
        m.territorios += Territorio(1, "Valdor", "Reino", 0xFFB5523B.toInt())
        m.territorios += Territorio(2, "Ithria", "Reino", 0xFF3E6A8A.toInt())
        m.territorios += Territorio(3, "Imperio de Sur", "Imperio", 0xFF6E8B3D.toInt())
        m.territorio(1)!!.padre = 3
        lz.apunta(lz.territorioEnZona(300, 400, 1)!!)
        lz.apunta(lz.territorioEnZona(700, 400, 2)!!)
        assertEquals(1, lz.territorioEn(300, 400)); assertEquals(2, lz.territorioEn(700, 400)); assertEquals(0, lz.territorioEn(40, 40))
        val cuenta = lz.cuentaTerritorios()
        assertTrue(cuenta.pixeles[1] > 50_000 && cuenta.pixeles[2] > 50_000)
        // Deshacer y rehacer
        lz.deshacer(); espera(lz)
        assertEquals(0, lz.territorioEn(700, 400))
        lz.rehacer(); espera(lz)
        assertEquals(2, lz.territorioEn(700, 400))
        // Lugares y rótulos, y la imagen entera con todo
        m.lugares += Lugar("p1", "Valdrín", "capital", 320f, 380f, 7)
        m.lugares += Lugar("p2", "Puerto Olmar", "puerto", 760f, 560f, 4)
        m.rotulos += Rotulo("t1", "Mar de las Nieblas", 520f, 60f, 34f, 0f, "cursiva", 0.2f)
        m.leyenda.visible = true; m.leyenda.x = 640f; m.leyenda.y = 560f; m.leyenda.escala = 0.6f
        ed.programaCentros(0); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1)); espera(lz); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, ed.centros.count { it.nivel == 1 } + ed.centros.count { it.nivel == 0 } - 1)
        val b = Lamina.pinta(ed, 1024, Capas(ctx))
        png(b, "lamina.png")
        // Guardar y volver a abrir
        lz.guarda()
        val m2 = bib.abre(m.id)!!
        val lz2 = Lienzo(m2, ctx.resources).also { it.carga() }
        assertEquals(1, lz2.territorioEn(300, 400))
        assertEquals(Color.red(px), Color.red(lz2.colorEn(300, 400)))
        assertEquals("Valdrín", m2.lugares.first().nombre)
        assertEquals(3, m2.territorio(1)!!.padre)
        // Unir con otro mapa del mundo
        val otro = bib.creaMapa(w.id, "Dos", 512, 768)
        otro.territorios += Territorio(1, "Kar", "Reino", 0xFF8A5A9E.toInt())
        val lzo = Lienzo(otro, ctx.resources).also { it.carga() }
        dibuja(lzo, otro.capas.first { it.referencia }.id, 0xFF2A2118.toInt(), 4f, 100f, 100f, 400f, 100f, 400f, 500f, 100f, 500f, 100f, 100f)
        lzo.apunta(lzo.territorioEnZona(250, 300, 1)!!)
        lzo.guarda()
        val id = Uniones.une(bib, m.id, otro.id, "E")
        val u = bib.abre(id)!!
        assertEquals(1536, u.ancho)
        val lzu = Lienzo(u, ctx.resources).also { it.carga() }
        assertEquals(1, lzu.territorioEn(300, 400))
        val kar = lzu.territorioEn(1024 + 250, 300)
        assertTrue(kar != 0 && kar != 1)
        assertEquals("Kar", u.territorio(kar)!!.nombre)
        val edu = Editor(u, ctx.resources)
        png(Lamina.pinta(edu, 1200, Capas(ctx)), "unido.png")
    }
}
