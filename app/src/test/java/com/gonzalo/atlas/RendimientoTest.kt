package com.gonzalo.atlas

import com.gonzalo.atlas.lienzo.Caminante
import com.gonzalo.atlas.lienzo.Humedo
import com.gonzalo.atlas.lienzo.Pinceles
import com.gonzalo.atlas.lienzo.Raster
import com.gonzalo.atlas.lienzo.Sellos
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cuánto tarda el motor en estampar (en esta máquina: sirve para comparar pinceles y cambios, no para saber lo que tarda una tableta). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class RendimientoTest {
    @Test fun estampar() {
        for ((id, tam) in listOf("pluma" to 6f, "lapiz6b" to 8f, "redondo" to 60f, "aerosuave" to 200f, "aerosuave" to 500f, "oleo" to 80f, "carboncillo" to 60f, "acuarela" to 150f)) {
            val d = Pinceles.def(id)
            var mejor = Long.MAX_VALUE; var sellos = 0; var px = 0L
            repeat(4) {
                val s = Sellos(); val c = Caminante(d, tam, 0xFF000000.toInt(), 3, true, 1f, -1f, s)
                val h = Humedo(1f, d.colorVariable); val r = Raster(d, 1f)
                val t0 = System.nanoTime()
                // Un trazo de 1.500 px que llega en 150 trozos (como los manda el lápiz), estampado según llega
                for (i in 0..150) { c.punto(100f + i * 10f, 500f + kotlin.math.sin(i * 0.15f) * 120f, 0.7f, 0.3f, 0f, i * 8L); h.sella(r, s); sellos += s.n; s.limpia() }
                c.fin(); h.sella(r, s)
                val t = System.nanoTime() - t0
                if (t < mejor) mejor = t
                px = h.lonas.size * 65536L
            }
            println("RENDIMIENTO %-12s %4.0f px: %6.1f ms el trazo de 1.500 px (%.2f ms por cada 10 px de trazo), %d teselas".format(id, tam, mejor / 1e6, mejor / 1e6 / 150, px / 65536))
        }
    }
}
