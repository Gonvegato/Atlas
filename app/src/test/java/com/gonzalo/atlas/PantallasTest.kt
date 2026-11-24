package com.gonzalo.atlas

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

    @Test fun mapa() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        File(ctx.filesDir, "atlas").deleteRecursively()
        val e = EstadoApp(ctx)
        val w = e.bib.creaMundo("Ithria")
        e.nuevoMapa(w.id, "Las Tierras del Norte", 2048, 2048, 0xFFF1E6C8.toInt())
        val ed = e.editor!!
        ed.mapa.territorios += Territorio(1, "Valdor", "Reino", 0xFFB5523B.toInt())
        e.seccion = "mapa"
        compose.setContent { App(e) }
        compose.waitForIdle()
        capturas.mkdirs()
        compose.onRoot().captureRoboImage(File(capturas, "mapa.png").path)
        ed.herramienta = com.gonzalo.atlas.editor.Herramienta.TERRITORIO
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(capturas, "mapa_territorios.png").path)
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
