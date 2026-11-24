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

    /**
     * Escribe una lista de propiedades binaria. Valores: mapas (claves de texto), listas, textos, enteros, decimales,
     * booleanos, [Uid] y bytes. Cada valor va como un objeto aparte (sin compartir repetidos).
     */
    fun escribe(raiz: Any?): ByteArray {
        val objs = ArrayList<Any?>()
        fun junta(v: Any?): Int {
            val i = objs.size; objs += v
            when (v) {
                is Map<*, *> -> { val ks = v.keys.map { junta(it.toString()) }; val vs = v.values.map { junta(it) }; objs[i] = Triple('D', ks, vs) }
                is List<*> -> objs[i] = Triple('A', v.map { junta(it) }, emptyList<Int>())
            }
            return i
        }
        junta(raiz)
        val tamRef = if (objs.size < 256) 1 else 2
        val o = java.io.ByteArrayOutputStream()
        o.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offs = IntArray(objs.size)
        fun entero(v: Long, n: Int) { for (k in n - 1 downTo 0) o.write(((v shr (8 * k)) and 0xff).toInt()) }
        fun cabeza(tipo: Int, n: Int) { if (n < 15) o.write((tipo shl 4) or n) else { o.write((tipo shl 4) or 0xf); if (n < 256) { o.write(0x10); o.write(n) } else { o.write(0x11); entero(n.toLong(), 2) } } }
        for ((i, v) in objs.withIndex()) {
            offs[i] = o.size()
            when (v) {
                null -> o.write(0x00)
                is Boolean -> o.write(if (v) 0x09 else 0x08)
                is Int, is Long -> { val n = (v as Number).toLong(); if (n in 0..255) { o.write(0x10); o.write(n.toInt()) } else if (n in 0..65535) { o.write(0x11); entero(n, 2) } else if (n in 0..0xffffffffL) { o.write(0x12); entero(n, 4) } else { o.write(0x13); entero(n, 8) } }
                is Float, is Double -> { o.write(0x23); entero(java.lang.Double.doubleToLongBits((v as Number).toDouble()), 8) }
                is Uid -> { if (v.i < 256) { o.write(0x80); o.write(v.i) } else { o.write(0x81); entero(v.i.toLong(), 2) } }
                is ByteArray -> { cabeza(0x4, v.size); o.write(v) }
                is String -> if (v.all { it.code < 128 }) { cabeza(0x5, v.length); o.write(v.toByteArray(Charsets.US_ASCII)) } else { cabeza(0x6, v.length); o.write(v.toByteArray(Charsets.UTF_16BE)) }
                is Triple<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST") val a = v.second as List<Int>; @Suppress("UNCHECKED_CAST") val b = v.third as List<Int>
                    cabeza(if (v.first == 'D') 0xD else 0xA, a.size)
                    for (r in a) entero(r.toLong(), tamRef); for (r in b) entero(r.toLong(), tamRef)
                }
                else -> o.write(0x00)
            }
        }
        val tabla = o.size()
        val tamOff = if (tabla < 65536) 2 else 4
        for (x in offs) entero(x.toLong(), tamOff)
        repeat(6) { o.write(0) }
        o.write(tamOff); o.write(tamRef)
        entero(objs.size.toLong(), 8); entero(0L, 8); entero(tabla.toLong(), 8)
        return o.toByteArray()
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
 * Importar y exportar pinceles de Procreate (.brush y .brushset): son zips con, por pincel, un Brush.archive (sus ajustes,
 * en un NSKeyedArchiver), Shape.png (la punta) y Grain.png (el grano), cuando los trae. La traducción de los ajustes está en
 * [deAjustes]: sale de decodificar pinceles reales y compararlos con la miniatura que Procreate guarda de cada uno.
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
            .filter { c -> val u = c.substringAfterLast('/'); !u.startsWith("Sub") && u != "Reset" }.sorted()
        var n = 0
        for (c in carpetas) {
            fun f(nombre: String) = archivos.entries.firstOrNull { (k, _) -> k.substringBeforeLast('/', "") == c && k.substringAfterLast('/').equals(nombre, true) }?.value
            val ajustes = runCatching { Bplist.raizArchivo(f("Brush.archive")!!) }.getOrDefault(emptyMap())
            val forma = f("Shape.png")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            val grano = f("Grain.png")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            val id = "pc" + System.nanoTime().toString(36)
            val d = File(dir(recursos), id); d.mkdirs()
            // En los archivos de Procreate lo blanco pinta (y «shapeInverted» o «textureInverted» le dan la vuelta). Si el archivo
            // no dice nada de la punta, se separa del fondo (el color de sus bordes)
            val inv = { k: String -> ajustes[k] == true }
            if (forma != null) aAlfa(forma, !ajustes.containsKey("shapeInverted"), inv("shapeInverted")).let { b -> File(d, "forma.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            grano?.let { g -> aAlfa(g, false, inv("textureInverted")).let { b -> File(d, "grano.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
            val nombre = (ajustes["name"] as? String)?.takeIf { it.isNotBlank() } ?: c.substringAfterLast('/').ifBlank { "Pincel de Procreate" }.removeSuffix(".brush")
            val def = deAjustes(id, ajustes, nombre, forma != null, grano != null, grano?.let { maxOf(it.width, it.height) } ?: 0)
            File(d, "pincel.json").writeText(JSONObject().put("n", nombre).put("def", def.json()).put("interna", forma == null && (ajustes["bundledShapePath"] as? String).orEmpty().isNotBlank()).toString())
            n++
        }
        if (n > 0) Pinceles.cargaImportados(recursos)
        return n
    }

    /**
     * Los ajustes de un Brush.archive de Procreate, traducidos a un pincel de ATLAS. Procreate no documenta su formato: esto
     * sale de decodificar 561 pinceles reales (los de serie de Procreate y colecciones de autores) y de comparar el
     * resultado con la miniatura del trazo que Procreate guarda en cada uno. Lo comprobado así: el espaciado se guarda al
     * cuadrado (plotSpacing); shapeRotation es seguir la dirección del trazo; shapeScatter 1 es un giro al azar de unos
     * ±45°; los demás (presión, inclinación, velocidad, azar, grano, flujo, afilado, StreamLine) van de 0 a 1 tal cual.
     * Muchos pinceles no traen su punta ni su grano: usan imágenes internas de Procreate (bundledShapePath); para esos se
     * pone la punta o el grano de ATLAS que más se parece por el nombre. Si el pincel lo exportó ATLAS, trae sus ajustes
     * exactos («atlasDef»).
     */
    fun deAjustes(id: String, a: Map<String, Any?>, nombre: String, conForma: Boolean, conGrano: Boolean, ladoGrano: Int = 0): DefPincel {
        (a["atlasDef"] as? String)?.let { t -> runCatching { DefPincel.de(JSONObject(t)) }.getOrNull()?.let { o ->
            return o.copy(id = id, categoria = "Importados", forma = if (o.forma == "redonda" || o.forma == "plana" || !conForma) o.forma.takeIf { it == "redonda" || it == "plana" } ?: "redonda" else "$id:forma",
                grano = if (o.grano.isBlank() || !conGrano) "" else "$id:grano", propio = false)
        } }
        fun num(k: String): Double? = when (val v = a[k]) { is Number -> v.toDouble(); is Boolean -> if (v) 1.0 else 0.0; else -> null }
        fun n(k: String, def: Double) = (num(k) ?: def).toFloat()
        fun u(k: String, def: Double = 0.0) = kotlin.math.abs(n(k, def)).coerceIn(0f, 1f)
        val punta = (a["bundledShapePath"] as? String).orEmpty().lowercase(); val papel = (a["bundledGrainPath"] as? String).orEmpty().lowercase()
        fun tiene(t: String, vararg ps: String) = ps.any { it in t }
        var forma = "$id:forma"; var dureza = 1f; var achatado = n("shapeRoundness", 1.0).coerceIn(0.05f, 1f)
        if (!conForma) when {
            tiene(punta, "ultra-soft") || (tiene(punta, "soft") && !tiene(punta, "medium")) -> { forma = "redonda"; dureza = 0f }
            tiene(punta, "medium-hard") -> { forma = "redonda"; dureza = 0.8f }
            tiene(punta, "medium") -> { forma = "redonda"; dureza = 0.5f }
            punta.isBlank() || tiene(punta, "hard", "point") -> forma = "redonda"
            tiene(punta, "oval") -> { forma = "redonda"; achatado = if (tiene(punta, "slim")) 0.3f else 0.5f }
            tiene(punta, "charcoal", "crayon", "chalk", "pastel", "pencil", "brick", "scribble", "conte", "stamp") -> forma = "t_tiza"
            tiene(punta, "blotch", "mess", "splatter", "dots", "spray", "water", "sponge", "ink") -> forma = "t_esponja"
            tiene(punta, "streak", "bristle", "dash", "oil") -> forma = "t_cerdas"
            else -> forma = "t_aspera"
        }
        val grano = if (conGrano) "$id:grano" else when {
            papel.isBlank() || tiene(papel, "blank") || papel == "none" -> ""
            tiene(papel, "canvas", "board", "leather", "bark", "wood", "linen") -> "g_lienzo"
            tiene(papel, "cloud", "wash", "stain", "water", "foam", "mix") -> "g_acuarela"
            tiene(papel, "noise", "procedural") -> "g_ruido"
            tiene(papel, "splatter", "grit", "sand", "spray", "aggate") -> "g_arena"
            tiene(papel, "stone", "concrete", "rock") -> "g_piedra"
            else -> "g_papel"
        }
        val esp = Math.sqrt(n("plotSpacing", 0.0).coerceAtLeast(0f).toDouble()).toFloat()
        val scat = n("shapeScatter", 0.0).coerceIn(0f, 2f)
        val maxT = n("maxSize", 0.3); val minT = n("minSize", 0.0); val pos = n("paintSize", 0.3).coerceIn(0f, 1f)
        val afila = u("taperSize", 1.0)
        return DefPincel(id, nombre, "Importados", forma = forma, dureza = dureza,
            tamano = ((minT + (maxT - minT) * pos) * 256f).coerceIn(2f, 120f),
            opacidad = u("maxOpacity", 1.0).coerceIn(0.05f, 1f),
            flujo = ((num("dynamicsGlazedFlow") ?: num("dynamicsGlazeFlow") ?: 1.0).toFloat()).coerceIn(0.05f, 1f),
            presionTamano = u("dynamicsPressureSize"), presionOpacidad = u("dynamicsPressureOpacity"),
            velocidadTamano = n("dynamicsSpeedSize", 0.0).coerceIn(-0.9f, 1f), velocidadOpacidad = -u("dynamicsSpeedOpacity").coerceAtMost(0.9f),
            inclinacion = u("dynamicsTiltSize"), inclinacionOpacidad = u("dynamicsTiltOpacity"),
            afiladoInicio = u("taperStartLength") * afila * 6f, afiladoFin = u("taperEndLength") * afila * 6f,
            // «Sin espaciado» en una punta con dibujo: muy junto, para que sus motas se fundan en rayas a lo largo del trazo
            espaciado = if (esp < 0.025f) (if (forma == "redonda") 0f else 0.025f) else esp.coerceAtMost(3f), dispersion = n("plotJitter", 0.0).coerceIn(0f, 4f),
            numero = (1 + Math.round(n("shapeCount", 0.0).coerceIn(0f, 1f) * 16f)).coerceIn(1, 8),
            // (Una punta de ATLAS puesta en lugar de la interna de Procreate se gira al azar: si no, su dibujo se repite a rayas)
            giroAzar = if (!conForma && forma.startsWith("t_") && forma != "t_cerdas") 1f else scat * 0.25f, sigueDireccion = kotlin.math.abs(n("shapeRotation", 0.0)) > 0.5f, acimut = a["shapeAzimuth"] == true, volteo = a["shapeFlipXJitter"] == true,
            achatado = achatado, grano = grano,
            // El grano del archivo, a su tamaño (un punto suyo, un píxel del lienzo) por su escala; los de ATLAS, a la suya
            escalaGrano = (n("textureScale", 1.0).let { if (it <= 0.001f) 1f else it } * (if (conGrano && ladoGrano > 0) ladoGrano.toFloat() else 128f)).coerceIn(24f, 4096f),
            profGrano = u("grainDepth", 1.0), granoMovil = n("textureMovement", 1.0) < 0.5f,
            velado = true, bordes = u("wetEdgesAmount"),
            azarTamano = u("dynamicsJitterSize"), azarOpacidad = u("dynamicsJitterOpacity"), azarTono = u("dynamicsJitterHue"), azarSat = u("dynamicsJitterSaturation"), azarBrillo = u("dynamicsJitterDarkness"),
            mezcla = u("dynamicsMix") * 0.9f, estabilizacion = u("plotSmoothing"))
    }

    /**
     * Exporta un pincel como archivo .brush (un zip con Brush.archive, Shape.png y Grain.png, como los de Procreate). Lleva
     * dentro los ajustes exactos de ATLAS, así que otro ATLAS lo importa idéntico; para Procreate se escriben además las
     * claves conocidas de su formato (sin poder garantizar que Procreate lo acepte: su formato no está documentado).
     */
    fun exporta(d: DefPincel): ByteArray {
        val punta = Texturas.de(d.forma) ?: redonda(d.dureza)
        val grano = Texturas.de(d.grano)
        fun gris(t: Textura): ByteArray { val b = Bitmap.createBitmap(IntArray(t.lado * t.lado) { val v = t.a[it].toInt() and 255; Color.rgb(v, v, v) }, t.lado, t.lado, Bitmap.Config.ARGB_8888); return java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        val raiz = LinkedHashMap<String, Any?>()
        raiz["\$class"] = Bplist.Uid(3); raiz["name"] = Bplist.Uid(2); raiz["version"] = 2
        // Como en los archivos reales: el tamaño es un sitio (paintSize) entre minSize y maxSize, que van en unidades de 256 px
        raiz["paintSize"] = 0.5; raiz["minSize"] = 0.0; raiz["maxSize"] = (d.tamano / 128f).toDouble(); raiz["maxOpacity"] = d.opacidad.toDouble(); raiz["minOpacity"] = 0.0; raiz["paintOpacity"] = 1.0
        raiz["shapeInverted"] = false; raiz["textureInverted"] = false; raiz["plotSpacingVersion"] = 1
        val esp = if (d.espaciado < 0.02f) 0.07 else d.espaciado.toDouble()
        raiz["plotSpacing"] = esp * esp; raiz["plotJitter"] = d.dispersion.toDouble(); raiz["plotSmoothing"] = d.estabilizacion.toDouble()
        raiz["shapeScatter"] = (d.giroAzar / 0.25f).coerceAtMost(2f).toDouble(); raiz["shapeRotation"] = if (d.sigueDireccion) 1.0 else 0.0; raiz["shapeAzimuth"] = d.acimut; raiz["shapeCount"] = d.numero
        raiz["shapeFlipXJitter"] = d.volteo; raiz["shapeRoundness"] = d.achatado.toDouble()
        raiz["dynamicsPressureSize"] = d.presionTamano.toDouble(); raiz["dynamicsPressureOpacity"] = d.presionOpacidad.toDouble()
        raiz["dynamicsJitterSize"] = d.azarTamano.toDouble(); raiz["dynamicsJitterOpacity"] = d.azarOpacidad.toDouble(); raiz["dynamicsSpeedSize"] = d.velocidadTamano.toDouble()
        raiz["dynamicsGlazedFlow"] = d.flujo.toDouble(); raiz["grainDepth"] = d.profGrano.toDouble(); raiz["textureScale"] = (d.escalaGrano / (grano?.lado ?: 128)).toDouble()
        raiz["textureMovement"] = if (d.granoMovil) 0.0 else 1.0
        raiz["taperStartLength"] = (d.afiladoInicio / 6f).coerceIn(0f, 1f).toDouble(); raiz["taperEndLength"] = (d.afiladoFin / 6f).coerceIn(0f, 1f).toDouble(); raiz["taperSize"] = 1.0
        raiz["dynamicsTiltSize"] = d.inclinacion.toDouble().coerceAtMost(1.0); raiz["dynamicsTiltOpacity"] = d.inclinacionOpacidad.toDouble(); raiz["dynamicsMix"] = d.mezcla.toDouble(); raiz["wetEdgesAmount"] = d.bordes.toDouble()
        raiz["dynamicsJitterHue"] = d.azarTono.toDouble(); raiz["dynamicsJitterSaturation"] = d.azarSat.toDouble(); raiz["dynamicsJitterDarkness"] = d.azarBrillo.toDouble()
        raiz["atlasDef"] = Bplist.Uid(4)
        val archivo = Bplist.escribe(linkedMapOf("\$version" to 100000, "\$archiver" to "NSKeyedArchiver", "\$top" to mapOf("root" to Bplist.Uid(1)),
            "\$objects" to listOf("\$null", raiz, d.nombre, mapOf("\$classname" to "SilicaBrush", "\$classes" to listOf("SilicaBrush", "ValkyrieBrush", "NSObject")), d.json().toString())))
        val o = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(o).use { z ->
            fun pon(n: String, b: ByteArray) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() }
            pon("Brush.archive", archivo); pon("Shape.png", gris(punta)); if (grano != null) pon("Grain.png", gris(grano))
            pon("QuickLook/Thumbnail.png", (MuestrasPincel.de(d, 600, 180) ?: Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)).let { b -> java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() })
        }
        return o.toByteArray()
    }

    /** La punta redonda con su dureza, como textura (para exportarla). */
    private fun redonda(dureza: Float): Textura {
        val l = 256; val c = (l - 1) / 2f; val blando = (1f - dureza).coerceIn(0.02f, 1f)
        return Textura(l, ByteArray(l * l) { i -> val r = kotlin.math.hypot(i % l - c, i / l - c) / c; val t = ((1f - r) / blando).coerceIn(0f, 1f); ((t * t * (3f - 2f * t)) * 255).toInt().toByte() })
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
        // Exportado por ATLAS: sus ajustes exactos, con la punta y el grano que trae el archivo
        j.optJSONObject("atlas")?.let { a -> runCatching { DefPincel.de(a) }.getOrNull()?.let { o ->
            return o.copy(id = id, categoria = "Importados", forma = if (o.forma == "redonda" || o.forma == "plana") o.forma else "$id:forma", grano = if (o.grano.isBlank() || !conGrano) "" else "$id:grano", propio = false)
        } }
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
