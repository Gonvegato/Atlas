@file:OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)

package com.gonzalo.atlas.lienzo

import android.content.res.Resources
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.StockTextureBitmapStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Un pincel listo para pintar: su familia de Ink (se crea al usarlo), su tamaño y opacidad al elegirlo y si borra. */
class TipoPincel(
    val clave: String,
    val nombre: String,
    val desc: String,
    val tamano: Float,
    val opacidad: Float,
    val goma: Boolean = false,
    private val familia: () -> BrushFamily,
) {
    val fam: BrushFamily by lazy { runCatching { familia() }.getOrElse { StockBrushes.pressurePen() } }
    fun brush(color: Int, tam: Float): Brush = Brush.createWithColorIntArgb(fam, color, tam.coerceAtLeast(0.5f), 0.1f)
    /** El mismo pincel, pero borrando. */
    fun comoGoma(): TipoPincel = TipoPincel(clave, nombre, desc, tamano, opacidad, true) { fam }
}

/**
 * Los pinceles: los de serie (por categorías), los tuyos (duplicados y cambiados en el estudio, en `pinceles.json`) y los
 * importados de Procreate. Las texturas (puntas y granos) se registran en el almacén de Ink.
 */
object Pinceles {
    @Volatile private var almacen: StockTextureBitmapStore? = null
    @Volatile var raiz: File? = null
    private val propios = CopyOnWriteArrayList<DefPincel>()
    private val importados = CopyOnWriteArrayList<DefPincel>()
    private val tipos = ConcurrentHashMap<String, Pair<String, TipoPincel>>()
    private val versionEstado = androidx.compose.runtime.mutableIntStateOf(0)
    var version: Int
        get() = versionEstado.intValue
        set(v) { versionEstado.intValue = v }

    fun almacen(res: Resources): StockTextureBitmapStore = almacen ?: synchronized(this) {
        almacen ?: StockTextureBitmapStore(res).also { s ->
            Texturas.todas().forEach { (k, b) -> s.addTexture(k, b) }
            runCatching { s.preloadStockBrushesTextures(StockBrushes.pencilUnstable) }
            almacen = s
        }
    }

    /** Todos los pinceles (los tuyos primero si cambian uno de serie con el mismo id). */
    fun defs(): List<DefPincel> {
        val mios = propios.associateBy { it.id }
        return Catalogo2.SERIE.map { mios[it.id] ?: it } + propios.filter { p -> Catalogo2.SERIE.none { it.id == p.id } } + importados
    }

    fun def(clave: String): DefPincel = defs().firstOrNull { it.id == clave } ?: Catalogo2.SERIE[0]

    fun de(clave: String): TipoPincel {
        val d = def(clave)
        val firma = d.json().toString()
        tipos[clave]?.let { if (it.first == firma) return it.second }
        return d.tipo().also { tipos[clave] = firma to it }
    }

    /** Lee tus pinceles y los importados. */
    fun carga(raizAtlas: File, res: Resources) {
        raiz = raizAtlas
        runCatching { JSONArray(File(raizAtlas, "pinceles.json").readText()) }.getOrNull()?.let { a ->
            propios.clear(); for (i in 0 until a.length()) runCatching { propios += DefPincel.de(a.getJSONObject(i)) }
        }
        cargaImportados(File(raizAtlas, "recursos"), res)
    }

    private fun guardaPropios() {
        val r = raiz ?: return
        File(r, "pinceles.json").writeText(JSONArray().also { a -> propios.forEach { a.put(it.json()) } }.toString())
        android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
    }

    /** Guarda un pincel cambiado en el estudio (si es de serie, queda como tuyo con el mismo id: «Restablecer» lo devuelve). */
    fun guarda(d: DefPincel) { propios.removeAll { it.id == d.id }; propios += d.copy(); tipos.remove(d.id); guardaPropios() }

    fun duplica(d: DefPincel): DefPincel {
        val n = d.copy(id = "m" + System.nanoTime().toString(36), nombre = d.nombre + " (copia)", categoria = "Míos", propio = true)
        propios += n; guardaPropios(); return n
    }

    fun restablece(id: String) { propios.removeAll { it.id == id }; tipos.remove(id); guardaPropios() }
    fun borra(id: String) { propios.removeAll { it.id == id }; importados.removeAll { it.id == id }; tipos.remove(id); guardaPropios() }
    fun esDeSerie(id: String) = Catalogo2.SERIE.any { it.id == id }
    fun cambiado(id: String) = esDeSerie(id) && propios.any { it.id == id }

    /** Los pinceles importados (de Procreate): su punta y su grano como texturas. */
    fun cargaImportados(recursos: File, res: Resources) {
        val al = almacen(res)
        val l = ArrayList<DefPincel>()
        Procreate.dir(recursos).listFiles()?.sortedBy { it.name }?.forEach { d ->
            val j = runCatching { JSONObject(File(d, "pincel.json").readText()) }.getOrNull() ?: return@forEach
            val id = d.name
            android.graphics.BitmapFactory.decodeFile(File(d, "forma.png").path)?.let { al.addTexture("$id:forma", it) }
            val conGrano = android.graphics.BitmapFactory.decodeFile(File(d, "grano.png").path)?.let { al.addTexture("$id:grano", it); true } ?: false
            l += DefPincel(id, j.optString("n", "Pincel"), "Importados", forma = "$id:forma", tamano = (4.0 + j.optDouble("tam", 0.3) * 120.0).toFloat(),
                presionTamano = 0.6f, espaciado = j.optDouble("esp", 0.1).toFloat().coerceIn(0.02f, 3f), acumula = true,
                grano = if (conGrano) "$id:grano" else "", escalaGrano = (64.0 + j.optDouble("tex", 0.5) * 448.0).toFloat(), estabilizacion = 0.1f)
        }
        importados.clear(); importados.addAll(l)
        android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
    }
}
