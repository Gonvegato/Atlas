package com.gonzalo.atlas.lienzo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** Lee una lista de propiedades binaria de Apple («bplist00»), la de los Brush.archive de Procreate. */
object Bplist {
    class Uid(val i: Int)

    fun lee(b: ByteArray): Any? {
        require(b.size > 40 && String(b, 0, 8, Charsets.US_ASCII) == "bplist00") { "No es una lista binaria de Apple" }
        val t = ByteBuffer.wrap(b, b.size - 32, 32)
        t.position(b.size - 32 + 6)
        val tamOff = t.get().toInt(); val tamRef = t.get().toInt()
        val n = t.long.toInt(); val raiz = t.long.toInt(); val tabla = t.long.toInt()
        fun entero(pos: Int, tam: Int): Long { var v = 0L; for (i in 0 until tam) v = (v shl 8) or (b[pos + i].toLong() and 0xff); return v }
        val offs = IntArray(n) { entero(tabla + it * tamOff, tamOff).toInt() }
        val cache = HashMap<Int, Any?>()
        fun obj(i: Int, prof: Int = 0): Any? {
            if (prof > 64) return null
            cache[i]?.let { return it }
            var p = offs[i]
            val m = b[p].toInt() and 0xff
            val tipo = m shr 4; var info = m and 0xf
            p++
            fun largo(): Int {
                if (info != 0xf) return info
                val mm = b[p].toInt() and 0xff; val tam = 1 shl (mm and 0xf); val v = entero(p + 1, tam).toInt(); p += 1 + tam; return v
            }
            val r: Any? = when (tipo) {
                0x0 -> when (info) { 8 -> false; 9 -> true; else -> null }
                0x1 -> { val tam = 1 shl info; entero(p, tam).let { if (tam == 8) it else it } }
                0x2 -> if (info == 2) ByteBuffer.wrap(b, p, 4).float.toDouble() else ByteBuffer.wrap(b, p, 8).double
                0x3 -> ByteBuffer.wrap(b, p, 8).double
                0x4 -> { val l = largo(); b.copyOfRange(p, p + l) }
                0x5 -> { val l = largo(); String(b, p, l, Charsets.US_ASCII) }
                0x6 -> { val l = largo(); String(b, p, l * 2, Charsets.UTF_16BE) }
                0x8 -> Uid(entero(p, info + 1).toInt())
                0xA, 0xC -> { val l = largo(); List(l) { k -> obj(entero(p + k * tamRef, tamRef).toInt(), prof + 1) } }
                0xD -> { val l = largo(); val mapa = LinkedHashMap<String, Any?>(); for (k in 0 until l) { val clave = obj(entero(p + k * tamRef, tamRef).toInt(), prof + 1); mapa[clave.toString()] = obj(entero(p + (l + k) * tamRef, tamRef).toInt(), prof + 1) }; mapa }
                else -> null
            }
            cache[i] = r
            return r
        }
        return obj(raiz)
    }

    /** El objeto raíz de un NSKeyedArchiver, con sus valores ya resueltos (los Uid apuntan a $objects). */
    fun raizArchivo(b: ByteArray): Map<String, Any?> {
        val top = lee(b) as? Map<*, *> ?: return emptyMap()
        val objetos = top["\$objects"] as? List<*> ?: return emptyMap()
        fun resuelve(v: Any?): Any? = if (v is Uid) objetos.getOrNull(v.i)?.takeIf { it != "\$null" } else v
        val raiz = (top["\$top"] as? Map<*, *>)?.values?.firstOrNull()?.let { resuelve(it) } as? Map<*, *>
        // Si no hay $top claro, el primer diccionario con ajustes de pincel
        val r = raiz ?: objetos.filterIsInstance<Map<*, *>>().firstOrNull { it.containsKey("plotSpacing") || it.containsKey("paintSize") } ?: return emptyMap()
        return r.entries.associate { (k, v) -> k.toString() to resuelve(v) }
    }
}

/**
 * Importar pinceles de Procreate (.brush y .brushset): son zips con, por pincel, un Brush.archive (sus ajustes, en un
 * NSKeyedArchiver), Shape.png (la punta) y, si tiene, Grain.png (el grano). La punta se usa como sello repetido a lo largo
 * del trazo y el grano como textura. Procreate no documenta su formato: el nombre, el espaciado y el tamaño se leen de las
 * claves que usan los lectores conocidos («name», «plotSpacing», «paintSize», «textureScale»); el resto de sus ajustes
 * (dinámicas, afilado, color…) no se traducen.
 */
object Procreate {
    fun dir(recursos: File) = File(recursos, "pinceles")

    /** Importa los pinceles del archivo. Devuelve cuántos. */
    fun importa(datos: ByteArray, recursos: File): Int {
        val archivos = HashMap<String, ByteArray>()
        ZipInputStream(datos.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && !e.name.contains("..") && !e.name.contains("__MACOSX")) archivos[e.name] = z.readBytes()
            }
        }
        val carpetas = archivos.keys.filter { it.substringAfterLast('/').equals("Brush.archive", true) }.map { it.substringBeforeLast('/', "") }
        var n = 0
        for (c in carpetas) {
            fun f(nombre: String) = archivos.entries.firstOrNull { (k, _) -> k.substringBeforeLast('/', "") == c && k.substringAfterLast('/').equals(nombre, true) }?.value
            val ajustes = runCatching { Bplist.raizArchivo(f("Brush.archive")!!) }.getOrDefault(emptyMap())
            val forma = f("Shape.png")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            val grano = f("Grain.png")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            val id = "pc" + System.nanoTime().toString(36)
            val d = File(dir(recursos), id); d.mkdirs()
            (if (forma != null) aAlfa(forma, true) else puntoRedondo()).let { b -> File(d, "forma.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            grano?.let { g -> aAlfa(g, false).let { b -> File(d, "grano.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
            val num = { k: String, def: Double -> (ajustes[k] as? Number)?.toDouble() ?: def }
            val nombre = (ajustes["name"] as? String)?.takeIf { it.isNotBlank() } ?: c.substringAfterLast('/').ifBlank { "Pincel de Procreate" }.removeSuffix(".brush")
            JSONObject().put("n", nombre).put("esp", num("plotSpacing", 0.1)).put("tam", num("paintSize", 0.3)).put("tex", num("textureScale", 0.5))
                .put("grano", grano != null).put("inv", false).let { File(d, "pincel.json").writeText(it.toString()) }
            n++
        }
        if (n > 0) Pinceles.cargaImportados(recursos)
        return n
    }

    /**
     * La imagen como transparencia sobre blanco (el motor tiñe del color del pincel lo que no es transparente). [forma]: el fondo es el color de
     * los bordes y la punta, lo que se separa de él (así da igual si es blanco sobre negro o al revés). El grano: lo claro pinta.
     */
    fun aAlfa(b: Bitmap, forma: Boolean, invertir: Boolean = false): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        fun lum(c: Int) = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f) / 255f * (Color.alpha(c) / 255f)
        var fondo = 0f
        if (forma) { var s = 0f; var k = 0; for (x in 0 until w) { s += lum(px[x]) + lum(px[(h - 1) * w + x]); k += 2 }; for (y in 0 until h) { s += lum(px[y * w]) + lum(px[y * w + w - 1]); k += 2 }; fondo = s / k }
        var max = 0.001f
        val a = FloatArray(px.size) { i -> val v = if (forma) abs(lum(px[i]) - fondo) else lum(px[i]); if (v > max) max = v; v }
        val out = IntArray(px.size) { i -> var v = (a[i] / max).coerceIn(0f, 1f); if (invertir) v = 1f - v; Color.argb((v * 255).toInt(), 255, 255, 255) }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun puntoRedondo(): Bitmap {
        val l = 64; val c = (l - 1) / 2f
        val px = IntArray(l * l) { i -> val x = i % l; val y = i / l; val d = kotlin.math.hypot(x - c, y - c) / c; Color.argb(if (d < 0.9f) 255 else if (d < 1f) ((1f - d) * 2550).toInt().coerceIn(0, 255) else 0, 255, 255, 255) }
        return Bitmap.createBitmap(px, l, l, Bitmap.Config.ARGB_8888)
    }

    /** El pincel para uno importado: su punta como sello y, si tiene, su grano; con los ajustes que se han podido leer. */
    fun def(id: String, j: JSONObject, conGrano: Boolean): DefPincel {
        fun n(k: String, d: Double) = j.optDouble(k, d).toFloat()
        return DefPincel(id, j.optString("n", "Pincel"), "Importados", forma = "$id:forma", tamano = (4.0 + j.optDouble("tam", 0.3) * 120.0).toFloat(),
            opacidad = n("op", 1.0).coerceIn(0.05f, 1f), flujo = n("fl", 1.0).coerceIn(0.02f, 1f),
            presionTamano = n("pt", 0.6).coerceIn(0f, 1f), presionOpacidad = n("po", 0.0).coerceIn(0f, 1f),
            espaciado = n("esp", 0.1).coerceIn(0.02f, 3f), dispersion = n("disp", 0.0).coerceIn(0f, 4f), giroAzar = n("giro", 0.0).coerceIn(0f, 1f),
            sigueDireccion = j.optBoolean("sigue"), acimut = j.optBoolean("acimut"), numero = j.optInt("num", 1).coerceIn(1, 16),
            afiladoInicio = n("ai", 0.0), afiladoFin = n("af", 0.0), azarTamano = n("zt", 0.0).coerceIn(0f, 1f), azarOpacidad = n("zo", 0.0).coerceIn(0f, 1f),
            acumula = j.optBoolean("acu", true), grano = if (conGrano) "$id:grano" else "", escalaGrano = (64.0 + j.optDouble("tex", 0.5) * 448.0).toFloat(),
            profGrano = n("pg", 1.0).coerceIn(0f, 1f), granoMovil = j.optBoolean("gm"), estabilizacion = n("est", 0.1).coerceIn(0f, 1f))
    }
}
