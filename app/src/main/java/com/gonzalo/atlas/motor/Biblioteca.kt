package com.gonzalo.atlas.motor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Todo lo tuyo en el móvil: los mundos (cada uno con varios mapas independientes que luego se pueden unir), su crónica y
 * los dibujos de tu biblioteca. Carpeta raíz: `files/atlas`.
 */
class Biblioteca(val raiz: File) {
    class MundoInfo(val id: String, var nombre: String, val creado: Long)
    class MapaInfo(
        val id: String, val nombre: String, val mundo: String, val modificado: Long, val infinito: Boolean,
        val ancho: Int, val alto: Int, val obras: List<String>, val lugares: Int, val reinos: Int, val km: Float, val tipo: String = "",
    )

    val dirMapas = File(raiz, "mapas")
    val dirCronica = File(raiz, "cronica")
    val dirRecursos = File(raiz, "recursos")
    private val fMundos = File(raiz, "mundos.json")

    init { dirMapas.mkdirs(); dirCronica.mkdirs(); dirRecursos.mkdirs() }

    // ---------------------------------------------------------------- mundos

    @Synchronized fun mundos(): List<MundoInfo> {
        val a = runCatching { JSONArray(fMundos.readText()) }.getOrNull() ?: JSONArray()
        val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { MundoInfo(it.optString("id"), it.optString("n"), it.optLong("c")) }.toMutableList()
        // Mundos que solo conocen sus mapas (bajados de la nube, por ejemplo)
        for (m in mapas()) if (l.none { it.id == m.mundo } && m.mundo.isNotBlank()) l += MundoInfo(m.mundo, m.nombre, m.modificado)
        return l
    }

    @Synchronized private fun guardaMundos(l: List<MundoInfo>) {
        fMundos.writeText(JSONArray().also { a -> l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.nombre).put("c", it.creado)) } }.toString())
    }

    @Synchronized fun creaMundo(nombre: String): MundoInfo {
        val m = MundoInfo("w" + id36(), nombre.ifBlank { Nombres.mundo() }, System.currentTimeMillis())
        guardaMundos(mundos() + m)
        return m
    }

    fun mundo(id: String) = mundos().firstOrNull { it.id == id }

    @Synchronized fun renombraMundo(id: String, nombre: String) {
        val l = mundos(); l.firstOrNull { it.id == id }?.nombre = nombre; guardaMundos(l)
    }

    @Synchronized fun borraMundo(id: String) {
        mapas().filter { it.mundo == id }.forEach { borraMapa(it.id) }
        guardaMundos(mundos().filter { it.id != id })
        File(dirCronica, "$id.json").delete()
    }

    // ---------------------------------------------------------------- mapas

    fun dirMapa(id: String) = File(dirMapas, id)

    fun mapas(): List<MapaInfo> = (dirMapas.listFiles() ?: emptyArray()).filter { File(it, "mapa.json").exists() }.mapNotNull { d ->
        runCatching {
            val o = JSONObject(File(d, "mapa.json").readText())
            if (o.optInt("v") < 2) return@runCatching null
            val v = o.optJSONObject("vector") ?: JSONObject()
            MapaInfo(o.getString("id"), o.optString("nombre"), o.optString("mundo"), o.optLong("modificado"), o.optInt("w") == 0,
                o.optInt("w"), o.optInt("h"), (o.optJSONArray("obras") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } },
                v.optJSONArray("lugares")?.length() ?: 0, v.optJSONArray("territorios")?.length() ?: 0, o.optDouble("km", 1.0).toFloat(), o.optString("tipo"))
        }.getOrNull()
    }.sortedByDescending { it.modificado }

    /** Un mapa nuevo (vacío: lo dibujas tú) con dos capas: «Color» debajo y «Líneas» encima (la de referencia). */
    fun creaMapa(mundo: String, nombre: String, ancho: Int, alto: Int, papel: Int = Catalogo.PAPELES[0].second, tipo: String = ""): Mapa {
        val id = "m" + id36()
        val m = Mapa(id, mundo, nombre.ifBlank { Nombres.raiz() }, ancho, alto, dirMapa(id))
        m.papel = papel; m.tipo = tipo
        m.capas += CapaInfo("c" + id36(), "Color")
        m.capas += CapaInfo("c" + id36() + "l", "Líneas", referencia = true)
        m.guardaMeta()
        return m
    }

    fun abre(id: String): Mapa? = Mapa.lee(dirMapa(id))

    fun borraMapa(id: String) { dirMapa(id).deleteRecursively() }

    fun miniatura(id: String) = File(dirMapa(id), "miniatura.png")

    companion object {
        fun id36(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e6).toLong(), 36)
    }
}
