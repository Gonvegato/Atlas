package com.gonzalo.atlas

import android.os.SystemClock
import com.gonzalo.atlas.lienzo.Medidor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Medir el trazo: las cifras salen de los tiempos apuntados y el informe las lleva. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MedidorTest {
    @Test fun cuentaElRetrasoLasImagenesYElEstampado() {
        Medidor.empieza()
        // 20 eventos de lápiz, cada uno pintado 12 ms después, una imagen cada 16 ms
        repeat(20) {
            val t = SystemClock.uptimeMillis()
            Medidor.evento(t, 4)
            Medidor.estampa(2_000_000)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(12))
            Medidor.imagen(5_000_000)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(4))
        }
        Medidor.para()
        val r = Medidor.resultado()
        assertEquals(20, r.muestras)
        assertEquals(12.0, r.latenciaMediana, 0.5)
        assertEquals(62.5, r.fps, 1.0)
        assertEquals(5.0, r.pintarMedia, 0.01); assertEquals(2.0, r.estamparMedia, 0.01)
        assertTrue(r.puntosPorSegundo > 200)
        val inf = Medidor.informe("2.4.0", "lapiz6b", 6f, 1f, 3, 120f)
        assertTrue(inf, "Del lápiz al dibujo: mediana 12.0 ms" in inf && "120.0 Hz" in inf)
    }
}
