package com.gonzalo.atlas.motor

import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * La crónica de un mundo: su enciclopedia (reinos e imperios, personajes, ciudades, regiones, pueblos, historia, fe…). Cada
 * ficha puede ir ligada a algo del mapa (un reino, un lugar, una región): entonces enseña sus datos y lleva al mapa.
 */
class Cronica(private val archivo: File) {
    class Categoria(val clave: String, val nombre: String, val campos: List<String>)
    class Entrada(
        val id: String, var cat: String, var titulo: String, val campos: MutableMap<String, String> = LinkedHashMap(),
        var texto: String = "",
        /** "<mapa>:r:<id>" (reino), "<mapa>:g:<id>" (región), "<mapa>:p:<lugar>" (lugar) o "". */
        var enlace: String = "",
        var u: Long = System.currentTimeMillis(),
    )

    val entradas = LinkedHashMap<String, Entrada>()

    init { lee() }

    private fun lee() {
        val o = runCatching { JSONObject(archivo.readText()) }.getOrNull() ?: return
        val e = o.optJSONObject("entradas") ?: return
        for (k in e.keys()) {
            val j = e.getJSONObject(k)
            val c = LinkedHashMap<String, String>()
            j.optJSONObject("campos")?.let { cc -> for (n in cc.keys()) c[n] = cc.optString(n) }
            entradas[k] = Entrada(k, j.optString("cat", "otro"), j.optString("titulo"), c, j.optString("texto"), j.optString("enlace"), j.optLong("u"))
        }
    }

    @Synchronized fun guarda() {
        val e = JSONObject()
        for ((k, v) in entradas) e.put(k, JSONObject().put("cat", v.cat).put("titulo", v.titulo).put("campos", JSONObject(v.campos as Map<*, *>))
            .put("texto", v.texto).put("enlace", v.enlace).put("u", v.u))
        archivo.parentFile?.mkdirs()
        val tmp = File(archivo.path + ".tmp"); tmp.writeText(JSONObject().put("v", 1).put("entradas", e).toString())
        if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
    }

    fun nueva(cat: String, titulo: String, enlace: String = ""): Entrada {
        val e = Entrada("e" + Biblioteca.id36(), cat, titulo, LinkedHashMap(categoria(cat).campos.associateWith { "" }), "", enlace)
        entradas[e.id] = e; guarda(); return e
    }

    fun deEnlace(enlace: String) = entradas.values.firstOrNull { it.enlace == enlace }
    fun borra(id: String) { entradas.remove(id); guarda() }
    fun de(cat: String) = entradas.values.filter { it.cat == cat }.sortedBy { it.titulo.lowercase() }

    companion object {
        val CATEGORIAS = listOf(
            Categoria("reino", "Reinos e imperios", listOf("Tipo", "Gobierno", "Gobernante", "Capital", "Lema", "Religión", "Pueblos", "Fundación", "Aliados", "Enemigos")),
            Categoria("personaje", "Personajes", listOf("Título", "Pueblo", "Reino", "Nacimiento", "Muerte", "Familia", "Rasgos")),
            Categoria("lugar", "Ciudades y lugares", listOf("Tipo", "Reino", "Población", "Gobernante", "Conocido por")),
            Categoria("region", "Regiones", listOf("Tipo", "Clima", "Habitantes", "Peligros")),
            Categoria("pueblo", "Pueblos y razas", listOf("Origen", "Lengua", "Aspecto", "Costumbres", "Dónde viven")),
            Categoria("historia", "Historia", listOf("Fecha", "Lugar", "Protagonistas", "Consecuencias")),
            Categoria("fe", "Religiones y fe", listOf("Dioses", "Clero", "Ritos", "Lugares sagrados")),
            Categoria("otro", "Otros", emptyList()),
        )
        fun categoria(c: String) = CATEGORIAS.firstOrNull { it.clave == c } ?: CATEGORIAS.last()
    }
}

/** Cuentas sobre el mapa: superficies, lo que hay dentro de cada reino… */
object Medidas {
    class Reinos(val pixeles: LongArray, val tierra: Long)

    /** Píxeles de cada reino (índice = id) en todo el mapa (finito) o en lo tocado (infinito). */
    fun reinos(m: Mapa): Reinos {
        val px = LongArray(256); var tierra = 0L
        val l = m.limites()
        for (cy in (l[1] shr Trozo.BITS) until ((l[3] + Trozo.LADO - 1) shr Trozo.BITS)) for (cx in (l[0] shr Trozo.BITS) until ((l[2] + Trozo.LADO - 1) shr Trozo.BITS)) {
            // Para no generar trozos enteros solo para contar: se cuenta en el nivel 2 (1 de cada 16 píxeles) si no está tocado
            val t = m.trozoSiEsta(cx, cy)
            if (t != null || Trozo.clave(cx, cy) in m.clavesEnDisco()) {
                val tt = t ?: m.trozo(cx, cy)!!
                for (i in 0 until Trozo.N) { if (tt.alt[i] >= 0f) { tierra++; px[tt.reino[i].toInt() and 255]++ } }
            } else {
                val mm = m.mip(cx, cy, 2) ?: continue
                for (i in mm.alt.indices) if (mm.alt[i] >= 0f) { tierra += 16; px[mm.reino[i].toInt() and 255] += 16 }
            }
        }
        return Reinos(px, tierra)
    }

    fun km2(m: Mapa, pixeles: Long): Double = pixeles.toDouble() * m.kmPorPx * m.kmPorPx

    fun formatoKm2(v: Double): String = when {
        v <= 0.0 -> "0 km²"
        v >= 1e6 -> String.format("%.2f millones de km²", v / 1e6).replace('.', ',')
        else -> String.format("%,d km²", Math.round(v)).replace(',', '.')
    }

    fun formatoKm(v: Double): String = if (v < 10) String.format("%.1f km", v).replace('.', ',') else String.format("%,d km", Math.round(v)).replace(',', '.')

    /** El paso «bonito» (1, 2, 5 × 10ⁿ) más cercano a [v]. */
    fun bonito(v: Double): Double {
        if (v <= 0) return 1.0
        val e = Math.pow(10.0, Math.floor(Math.log10(v)))
        val f = v / e
        return e * when { f < 1.5 -> 1.0; f < 3.5 -> 2.0; f < 7.5 -> 5.0; else -> 10.0 }
    }

    /** Los lugares dentro de un reino. */
    fun lugaresDe(m: Mapa, reino: Int) = m.lugares.filter { m.dentroPx(it.x.toInt(), it.y.toInt()) && m.reino(it.x.toInt(), it.y.toInt()) == reino }

    /** El reino más cercano en el que está un punto (0 si ninguno). */
    fun reinoEn(m: Mapa, x: Float, y: Float) = if (m.dentroPx(x.toInt(), y.toInt())) m.reino(x.toInt(), y.toInt()) else 0

    @Suppress("unused") private fun nada() = abs(0)
}
