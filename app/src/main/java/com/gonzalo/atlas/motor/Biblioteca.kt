package com.gonzalo.atlas.motor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * Todo lo tuyo en el móvil: los mundos (cada uno con varios mapas independientes que luego se pueden unir), su crónica y
 * los dibujos de tu biblioteca. Carpeta raíz: `files/atlas`.
 */
class Biblioteca(val raiz: File) {
    class MundoInfo(val id: String, var nombre: String, val creado: Long)
    class MapaInfo(
        val id: String, val nombre: String, val mundo: String, val modificado: Long, val infinito: Boolean,
        val ancho: Int, val alto: Int, val obras: List<String>, val lugares: Int, val reinos: Int, val km: Float,
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
            val v = o.optJSONObject("vector") ?: JSONObject()
            MapaInfo(o.getString("id"), o.optString("nombre"), o.optString("mundo"), o.optLong("modificado"), o.optInt("w") == 0,
                o.optInt("w") * Trozo.LADO, o.optInt("h") * Trozo.LADO,
                (o.optJSONArray("obras") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } },
                v.optJSONArray("lugares")?.length() ?: 0, v.optJSONArray("reinos")?.length() ?: 0, o.optDouble("km", 1.0).toFloat())
        }.getOrNull()
    }.sortedByDescending { it.modificado }

    fun creaMapa(mundo: String, nombre: String, estilo: Generador.Estilo, anchoTrozos: Int, altoTrozos: Int, semilla: Long = System.nanoTime(), tierra: Float = 0.42f): Mapa {
        val id = "m" + id36()
        val m = Mapa(id, mundo, nombre.ifBlank { Nombres.raiz() }, semilla, estilo, anchoTrozos, altoTrozos, tierra, dirMapa(id))
        Formato.guarda(m)
        return m
    }

    fun abre(id: String): Mapa? = Mapa.lee(dirMapa(id))

    fun borraMapa(id: String) { dirMapa(id).deleteRecursively() }

    fun miniatura(id: String) = File(dirMapa(id), "miniatura.png")

    /**
     * Une [b] a [a] por el lado [lado] ("E", "O", "N", "S") en un mapa nuevo del mismo mundo. Los dos se quedan como están.
     * Los reinos y regiones de [b] cambian de número si chocan con los de [a].
     */
    fun une(a: Mapa, b: Mapa, lado: String, progreso: (Float) -> Unit = {}): Mapa {
        require(!a.infinito && !b.infinito) { "Solo se pueden unir mapas finitos" }
        val ew = lado == "E" || lado == "O"
        val w = if (ew) a.anchoTrozos + b.anchoTrozos else max(a.anchoTrozos, b.anchoTrozos)
        val h = if (ew) max(a.altoTrozos, b.altoTrozos) else a.altoTrozos + b.altoTrozos
        // Dónde va cada uno (en trozos)
        val (ax, ay, bx, by) = when (lado) {
            "E" -> listOf(0, 0, a.anchoTrozos, 0); "O" -> listOf(b.anchoTrozos, 0, 0, 0)
            "S" -> listOf(0, 0, 0, a.altoTrozos); else -> listOf(0, b.altoTrozos, 0, 0)
        }
        val id = "m" + id36()
        val m = Mapa(id, a.mundo, "${a.nombre} + ${b.nombre}", a.semilla xor b.semilla, Generador.Estilo.MAR, w, h, 0.4f, dirMapa(id))
        m.kmPorPx = a.kmPorPx; m.escalaPuesta = a.escalaPuesta
        m.obras.addAll((a.obras + b.obras).distinct())
        // Ids de b que chocan con los de a
        val remR = IntArray(256) { it }; val remG = IntArray(256) { it }
        val usadosR = a.reinos.map { it.id }.toMutableSet(); val usadosG = a.regiones.map { it.id }.toMutableSet()
        for (r in b.reinos) if (r.id in usadosR) { val n = (1..255).first { it !in usadosR && b.reinos.none { o -> o.id == it } }; remR[r.id] = n; usadosR += n } else usadosR += r.id
        for (r in b.regiones) if (r.id in usadosG) { val n = (1..255).first { it !in usadosG && b.regiones.none { o -> o.id == it } }; remG[r.id] = n; usadosG += n } else usadosG += r.id
        val total = (a.anchoTrozos * a.altoTrozos + b.anchoTrozos * b.altoTrozos).toFloat()
        var hechos = 0
        fun copia(src: Mapa, ox: Int, oy: Int, rr: IntArray?, rg: IntArray?) {
            for (cy in 0 until src.altoTrozos) for (cx in 0 until src.anchoTrozos) {
                val t = src.trozo(cx, cy)!!
                val d = m.trozo(cx + ox, cy + oy)!!
                d.copiaDe(t)
                if (rr != null) for (i in 0 until Trozo.N) { val v = d.reino[i].toInt() and 255; if (v != 0) d.reino[i] = rr[v].toByte() }
                if (rg != null) for (i in 0 until Trozo.N) { val v = d.region[i].toInt() and 255; if (v != 0) d.region[i] = rg[v].toByte() }
                m.tocado(d)
                // Lo que no hace falta en memoria se escribe ya
                Formato.escribeTrozo(m.archivoTrozo(d.cx, d.cy), d); m.apuntaEnDisco(d.cx, d.cy); d.sucio = false
                progreso(++hechos / total)
            }
            src.aligera { _, _ -> false }
        }
        copia(a, ax, ay, null, null)
        copia(b, bx, by, remR, remG)
        // Lo que quede sin cubrir (si uno es más alto que el otro) es mar: el estilo MAR ya lo genera así
        val dax = ax * Trozo.LADO.toFloat(); val day = ay * Trozo.LADO.toFloat(); val dbx = bx * Trozo.LADO.toFloat(); val dby = by * Trozo.LADO.toFloat()
        a.reinos.forEach { m.reinos += Reino(it.id, it.nombre, it.color, it.tipo, it.padre, it.capital) }
        b.reinos.forEach { m.reinos += Reino(remR[it.id], it.nombre, it.color, it.tipo, if (it.padre != 0) remR[it.padre] else 0, it.capital) }
        a.regiones.forEach { m.regiones += Region(it.id, it.nombre, it.tipo) }
        b.regiones.forEach { m.regiones += Region(remG[it.id], it.nombre, it.tipo) }
        for ((src, dx, dy) in listOf(Triple(a, dax, day), Triple(b, dbx, dby))) {
            src.lugares.forEach { m.lugares += Lugar(it.id, it.nombre, it.tipo, it.x + dx, it.y + dy, it.tamano, it.nota) }
            src.rios.forEach { m.rios += it.desplazada(dx, dy) }
            src.caminos.forEach { m.caminos += it.desplazada(dx, dy) }
            src.rotulos.forEach { m.rotulos += Rotulo(it.id, it.texto, it.x + dx, it.y + dy, it.tam, it.giro, it.estilo, it.espaciado, it.curva) }
            src.sellos.forEach { m.sellos += Sello(it.id, it.recurso, it.x + dx, it.y + dy, it.escala, it.giro, it.espejo) }
        }
        Formato.guarda(m)
        return m
    }

    companion object {
        fun id36(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e6).toLong(), 36)
    }
}
