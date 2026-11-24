package com.gonzalo.atlas

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.gonzalo.atlas.lienzo.Procreate
import com.gonzalo.atlas.motor.Calco
import com.gonzalo.atlas.motor.Lugar
import com.gonzalo.atlas.motor.Mapa
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ProcreateTest {
    @Test fun importaUnBrushset() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val datos = javaClass.classLoader!!.getResourceAsStream("prueba.brushset").readBytes()
        val dir = Files.createTempDirectory("rec").toFile()
        // Sin crear el pincel de Ink (código nativo): solo la lectura del archivo
        val n = runCatching { Procreate.importa(datos, dir, ctx.resources) }.getOrElse { e -> if (e is UnsatisfiedLinkError || e.cause is UnsatisfiedLinkError) 1 else throw e }
        assertEquals(1, n)
        val d = Procreate.dir(dir).listFiles()!!.single()
        val j = JSONObject(File(d, "pincel.json").readText())
        assertEquals("Tinta seca", j.getString("n"))
        assertEquals(0.25, j.getDouble("esp"), 1e-6)
        assertTrue(j.getBoolean("grano"))
        val forma = BitmapFactory.decodeFile(File(d, "forma.png").path)
        assertTrue("el centro de la punta pinta", Color.alpha(forma.getPixel(32, 32)) > 240)
        assertTrue("el borde no", Color.alpha(forma.getPixel(2, 2)) < 10)
    }

    @Test fun vinculosYCalcoSeGuardan() {
        val m = Mapa("m", "w", "Ciudad", 0, 0, Files.createTempDirectory("m").toFile())
        m.lugares += Lugar("p", "Iglesia de San Olm", "templo", 10f, 20f, 3, "", "m_iglesia")
        m.calco = Calco(true, 5f, 6f, 700f, 12f, 0.4f, true)
        m.guardaMeta()
        val m2 = Mapa.lee(m.dir!!)!!
        assertEquals("m_iglesia", m2.lugares.single().mapa)
        assertEquals(700f, m2.calco!!.ancho, 0f)
        assertTrue(m2.calco!!.encima)
        assertTrue(m2.infinito)
    }
}
