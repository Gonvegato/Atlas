package com.gonzalo.atlas.motor

import org.junit.Test
import java.io.File

class VerMapaTest {
    private fun png(px: IntArray, w: Int, h: Int, f: File) {
        Png.escribe(px, w, h, f)
    }

    @Test fun generaYPinta() {
        val dir = File(System.getProperty("capturas") ?: "build/capturas")
        val m = Mapa("m1", "w1", "Prueba", 12345L, Generador.Estilo.CONTINENTES, 8, 6, 0.45f, null)
        val p = Pintor(m)
        var t = System.nanoTime()
        val w = 1024; val h = 768
        val out = IntArray(w * h)
        p.pinta(0f, 0f, 2f, w, h, out)
        println("lejos: " + (System.nanoTime() - t) / 1e6 + " ms")
        png(out, w, h, File(dir, "mapa_lejos.png"))
        t = System.nanoTime()
        val o2 = IntArray(768 * 512)
        p.pinta(300f, 900f, 0.5f, 768, 512, o2)
        t = System.nanoTime(); p.pinta(0f, 0f, 2f, w, h, out); println("lejos otra vez: " + (System.nanoTime() - t) / 1e6 + " ms")
        t = System.nanoTime(); p.pinta(300f, 900f, 0.5f, 768, 512, o2); println("cerca otra vez: " + (System.nanoTime() - t) / 1e6 + " ms")
        val o3 = IntArray(768 * 512); p.pinta(1300f, 1000f, 1f, 768, 512, o3); png(o3, 768, 512, File(dir, "mapa_medio.png"))
        println("cerca: " + (System.nanoTime() - t) / 1e6 + " ms")
        png(o2, 768, 512, File(dir, "mapa_cerca.png"))
    }
}
