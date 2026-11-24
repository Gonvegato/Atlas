package com.gonzalo.atlas

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.gonzalo.atlas.motor.Lugar
import com.gonzalo.atlas.motor.Territorio
import com.gonzalo.atlas.ui.App
import com.gonzalo.atlas.ui.EstadoApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1280dp-h800dp-land-xhdpi")
class PantallasTest {
    @get:Rule val compose = createComposeRule()
    private val capturas = File(System.getProperty("capturas") ?: "build/capturas")

    private fun captura(n: String) {
        compose.waitForIdle()
        capturas.mkdirs()
        compose.onRoot().captureRoboImage(File(capturas, n).path)
    }

    @Test fun pantallas() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(ctx.filesDir, "atlas").deleteRecursively()
        val e = EstadoApp(ctx)
        val w = e.bib.creaMundo("Ithria")
        e.nuevoMapa(w.id, "Las Tierras del Norte", 4096, 4096, 0xFFF1E6C8.toInt())
        val m = e.mapa!!
        m.territorios += Territorio(1, "Imperio de Valdor", "Imperio", 0xFFB5523B.toInt())
        m.territorios += Territorio(2, "Reino de Kar", "Reino", 0xFF3E6A8A.toInt(), 1)
        m.territorios += Territorio(3, "Marca de Olm", "Provincia", 0xFF6E8B3D.toInt(), 2)
        m.lugares += Lugar("p1", "Valdrín", "capital", 1000f, 900f, 7)
        e.seccion = "portada"
        compose.setContent { App(e) }
        captura("portada.png")
        e.fichaDe(m, "t", "1", "reino", "Imperio de Valdor")
        captura("cronica.png")
        e.seccion = "mundo"
        captura("mundo.png")
        e.seccion = "mapa"; e.dibujando = false
        captura("mapas.png")
        e.seccion = "taller"
        captura("taller.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1280dp-h800dp-land-xhdpi")
class PantallaMapaTest {
    @get:Rule val compose = createComposeRule()
    private val capturas = File(System.getProperty("capturas") ?: "build/capturas")

    private fun espera(lz: com.gonzalo.atlas.lienzo.Lienzo) { repeat(3) { val l = java.util.concurrent.CountDownLatch(1); lz.enVivo { lz.enHilo { l.countDown() } }; l.await(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() } }

    @Test fun mapa() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(ctx.filesDir, "atlas").deleteRecursively()
        val e = EstadoApp(ctx)
        val w = e.bib.creaMundo("Ithria")
        e.nuevoMapa(w.id, "Las Tierras del Norte", 2048, 2048, 0xFFF1E6C8.toInt())
        val ed = e.editor!!; val lz = ed.lienzo
        ed.mapa.territorios += Territorio(1, "Valdor", "Reino", 0xFFB5523B.toInt())
        // Un continente generado y unos trazos con varios pinceles, para ver la mesa con dibujo
        espera(lz)
        ed.ponContinente(com.gonzalo.atlas.motor.FormaTierra(42L, "continente", 0.4f, 0.55f, 0.4f), android.graphics.RectF(200f, 250f, 1850f, 1800f), 3300.0, 0xFF2A2118.toInt(), 0xFFE6D8AE.toInt(), 0xFFB9D3DB.toInt(), false)
        espera(lz)
        fun trazo(pincel: String, color: Int, tam: Float, vararg p: Float) {
            val v = lz.empiezaTrazo(com.gonzalo.atlas.lienzo.Pinceles.def(pincel), color, tam, 1f, 0, 1f, true, 0)!!
            val n = p.size / 2
            v.agrega(FloatArray(n * 6) { i -> when (i % 6) { 0 -> p[i / 6 * 2]; 1 -> p[i / 6 * 2 + 1]; 2 -> 0.8f; 5 -> (i / 6) * 30f; else -> 0.2f } })
            v.termina { ed2, _ -> ed2?.let { lz.apunta(it) } }; espera(lz)
        }
        lz.activa = lz.capas.first { it.info.referencia }.info.id
        trazo("montanas", 0xFF2A2118.toInt(), 60f, 700f, 800f, 850f, 760f, 1000f, 820f, 1150f, 900f, 1250f, 1050f)
        trazo("pinos", 0xFF2F4A2A.toInt(), 34f, 800f, 1100f, 900f, 1200f, 1050f, 1250f, 1150f, 1350f, 1000f, 1400f)
        trazo("rio", 0xFF3E6A8A.toInt(), 9f, 1000f, 850f, 1050f, 1000f, 980f, 1150f, 1060f, 1300f, 1040f, 1500f)
        e.seccion = "mapa"
        compose.setContent { App(e) }
        compose.waitForIdle()
        capturas.mkdirs()
        fun captura(n: String) { compose.waitForIdle(); Thread.sleep(300); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); compose.onRoot().captureRoboImage(File(capturas, n).path) }
        fun pulsa(desc: String) { compose.onNodeWithContentDescription(desc).performClick() }
        captura("mapa.png")
        pulsa("Pintar"); captura("mapa_pinceles.png")
        pulsa("Capas"); captura("mapa_capas.png")
        pulsa("Ajustes"); captura("mapa_ajustes.png")
        pulsa("Acciones"); captura("mapa_acciones.png")
        pulsa("Selección"); ed.ponMarco(com.gonzalo.atlas.lienzo.Seleccion.elipse(android.graphics.RectF(600f, 600f, 1400f, 1300f))); captura("mapa_seleccion.png")
        ed.ponMarco(null)
        pulsa("Mundo"); captura("mapa_mundo.png")
        ed.herramienta = com.gonzalo.atlas.editor.Herramienta.TERRITORIO
        captura("mapa_territorios.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-port-xxhdpi")
class PantallaMovilTest {
    @get:Rule val compose = createComposeRule()
    private val capturas = File(System.getProperty("capturas") ?: "build/capturas")

    @Test fun mapaEnElMovil() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(ctx.filesDir, "atlas").deleteRecursively()
        val e = EstadoApp(ctx)
        e.nuevoMapa(e.bib.creaMundo("Ithria").id, "Norte", 0, 0, 0xFFF1E6C8.toInt())
        compose.setContent { App(e) }
        compose.waitForIdle()
        capturas.mkdirs()
        compose.onRoot().captureRoboImage(File(capturas, "mapa_movil.png").path)
    }
}
