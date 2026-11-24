package com.gonzalo.atlas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.gonzalo.atlas.lienzo.MuestrasPincel
import com.gonzalo.atlas.lienzo.Pinceles
import com.gonzalo.atlas.lienzo.Procreate
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Solo en local (no en GitHub): con pinceles reales de Procreate en la carpeta de ATLAS_PINCELES, importa cada colección y
 * pone lado a lado la miniatura del trazo que guarda Procreate y el trazo que pinta ATLAS con el pincel importado. Los
 * pinceles son de sus autores: no van en el repositorio.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ComparaProcreateTest {
    @Test fun compara() {
        val dir = System.getenv("ATLAS_PINCELES")?.let { File(it) }?.takeIf { it.isDirectory }
        Assume.assumeTrue("sin pinceles reales", dir != null)
        val salida = File(System.getenv("ATLAS_SALIDA") ?: "build/capturas").also { it.mkdirs() }
        val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; textSize = 15f }
        val filtro = Paint(Paint.FILTER_BITMAP_FLAG)
        for (f in dir!!.listFiles()!!.sorted()) {
            val bytes = f.readBytes()
            // Las miniaturas de Procreate, por carpeta (en el mismo orden en que se importan)
            val archivos = HashMap<String, ByteArray>()
            runCatching { ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory && (e.name.endsWith("Brush.archive") || e.name.endsWith("QuickLook/Thumbnail.png"))) archivos[e.name] = z.readBytes() } } }
            val carpetas = archivos.keys.filter { it.endsWith("Brush.archive") }.map { it.substringBeforeLast('/', "") }.filter { c -> val u = c.substringAfterLast('/'); !u.startsWith("Sub") && u != "Reset" }.sorted()
            if (carpetas.isEmpty()) continue
            val rec = Files.createTempDirectory("rec").toFile()
            val n = runCatching { Procreate.importa(bytes, rec) }.getOrElse { println("NO SE PUDO ${f.name}: $it"); 0 }
            val defs = Pinceles.defs().filter { it.categoria == "Importados" }
            if (n == 0 || defs.size != carpetas.size) { println("DESCUADRE ${f.name}: $n importados, ${carpetas.size} carpetas, ${defs.size} pinceles"); continue }
            val filas = minOf(defs.size, 22); val W = 560; val H = 150
            val hoja = Bitmap.createBitmap(W * 2 + 10, filas * H + 6, Bitmap.Config.ARGB_8888); val c = Canvas(hoja); c.drawColor(0xFF28282C.toInt())
            for (i in 0 until filas) {
                val d = defs[i]; val y = i * H
                archivos[(if (carpetas[i].isEmpty()) "" else carpetas[i] + "/") + "QuickLook/Thumbnail.png"]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { t -> c.drawBitmap(t, null, Rect(4, y + 22, 4 + 420, y + 22 + 128), filtro) }
                MuestrasPincel.de(d.copy(tamano = d.tamano.coerceIn(5f, 70f)), 420, 128)?.let { c.drawBitmap(it, (W + 10).toFloat(), y + 22f, null) }
                c.drawText("${d.nombre}  ·  ${d.tamano.toInt()} px esp ${"%.2f".format(d.espaciado)} disp ${"%.2f".format(d.dispersion)} giro ${"%.2f".format(d.giroAzar)} ${if (d.sigueDireccion) "sigue " else ""}pT ${"%.2f".format(d.presionTamano)} pO ${"%.2f".format(d.presionOpacidad)} ${d.forma.substringAfter(':')} ${d.grano.substringAfter(':')} eg ${d.escalaGrano.toInt()}", 6f, y + 16f, texto)
            }
            File(salida, "compara_" + f.name.substringAfterLast("_").substringBefore('.') + ".png").outputStream().use { hoja.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("COMPARADO ${f.name}: $n pinceles")
            rec.deleteRecursively()
        }
    }
}
