package com.gonzalo.atlas.lienzo

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Los pinceles: los de serie (por categorías), los tuyos (duplicados y cambiados en el estudio, en `pinceles.json`) y los
 * importados de Procreate (su punta y su grano, en `recursos/pinceles`).
 */
object Pinceles {
    @Volatile var raiz: File? = null
    private val propios = CopyOnWriteArrayList<DefPincel>()
    private val importados = CopyOnWriteArrayList<DefPincel>()
    private val versionEstado = androidx.compose.runtime.mutableIntStateOf(0)
    var version: Int
        get() = versionEstado.intValue
        set(v) { versionEstado.intValue = v }

    /** Todos los pinceles (los tuyos primero si cambian uno de serie con el mismo id). */
    fun defs(): List<DefPincel> {
        val mios = propios.associateBy { it.id }
        return Catalogo2.SERIE.map { mios[it.id] ?: it } + propios.filter { p -> Catalogo2.SERIE.none { it.id == p.id } } + importados
    }

    fun def(clave: String): DefPincel = defs().firstOrNull { it.id == clave } ?: Catalogo2.SERIE[0]

    /** Lee tus pinceles y los importados. */
    fun carga(raizAtlas: File) {
        raiz = raizAtlas
        runCatching { JSONArray(File(raizAtlas, "pinceles.json").readText()) }.getOrNull()?.let { a ->
            propios.clear(); for (i in 0 until a.length()) runCatching { propios += DefPincel.de(a.getJSONObject(i)) }
        }
        cargaImportados(File(raizAtlas, "recursos"))
    }

    private fun avisa() { runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post { version++ } } }

    private fun guardaPropios() {
        val r = raiz
        if (r != null) File(r, "pinceles.json").writeText(JSONArray().also { a -> propios.forEach { a.put(it.json()) } }.toString())
        avisa()
    }

    /** Guarda un pincel cambiado en el estudio (si es de serie, queda como tuyo con el mismo id: «Restablecer» lo devuelve). */
    fun guarda(d: DefPincel) {
        if (importados.any { it.id == d.id }) {
            importados.replaceAll { if (it.id == d.id) d.copy() else it }
            raiz?.let { r -> runCatching { val f = File(Procreate.dir(File(r, "recursos")), "${d.id}/pincel.json"); f.writeText(JSONObject(f.readText()).put("def", d.json()).toString()) } }
            avisa(); return
        }
        propios.removeAll { it.id == d.id }; propios += d.copy(); guardaPropios()
    }

    fun duplica(d: DefPincel): DefPincel {
        val n = d.copy(id = "m" + System.nanoTime().toString(36), nombre = d.nombre + " (copia)", categoria = "Míos", propio = true)
        propios += n; guardaPropios(); return n
    }

    fun restablece(id: String) { propios.removeAll { it.id == id }; guardaPropios() }
    fun borra(id: String) {
        propios.removeAll { it.id == id }
        if (importados.removeAll { it.id == id }) raiz?.let { File(Procreate.dir(File(it, "recursos")), id).deleteRecursively() }
        guardaPropios()
    }
    fun esDeSerie(id: String) = Catalogo2.SERIE.any { it.id == id }
    fun cambiado(id: String) = esDeSerie(id) && propios.any { it.id == id }
    fun esImportado(id: String) = importados.any { it.id == id }

    /** Los pinceles importados (de Procreate): su punta y su grano como texturas del motor. */
    fun cargaImportados(recursos: File) {
        val l = ArrayList<DefPincel>()
        Procreate.dir(recursos).listFiles()?.sortedBy { it.name }?.forEach { d ->
            val j = runCatching { JSONObject(File(d, "pincel.json").readText()) }.getOrNull() ?: return@forEach
            val id = d.name
            android.graphics.BitmapFactory.decodeFile(File(d, "forma.png").path)?.let { Texturas.pon("$id:forma", Textura.de(it, 512, true)) }
            val conGrano = android.graphics.BitmapFactory.decodeFile(File(d, "grano.png").path)?.let { Texturas.pon("$id:grano", Textura.de(it, 1024)); true } ?: false
            val def = j.optJSONObject("def")?.let { runCatching { DefPincel.de(it) }.getOrNull() }
            l += def ?: Procreate.def(id, j, conGrano)
        }
        importados.clear(); importados.addAll(l)
        avisa()
    }
}
