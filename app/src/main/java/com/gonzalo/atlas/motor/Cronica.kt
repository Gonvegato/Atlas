package com.gonzalo.atlas.motor

import org.json.JSONObject
import java.io.File

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
        /** Más sitios del mapa (o de otros mapas del mundo) que son esto mismo: el reino en el mapa del continente y en el de su región. */
        val mas: MutableList<String> = ArrayList(),
    ) {
        val enlaces: List<String> get() = (listOf(enlace) + mas).filter { it.isNotBlank() }
    }

    val entradas = LinkedHashMap<String, Entrada>()
    /** La situación política del mundo, contada de una vez (quién manda, quién amenaza a quién). */
    var situacion = ""

    init { lee() }

    private fun lee() {
        val o = runCatching { JSONObject(archivo.readText()) }.getOrNull() ?: return
        situacion = o.optString("situacion")
        val e = o.optJSONObject("entradas") ?: return
        for (k in e.keys()) {
            val j = e.getJSONObject(k)
            val c = LinkedHashMap<String, String>()
            j.optJSONObject("campos")?.let { cc -> for (n in cc.keys()) c[n] = cc.optString(n) }
            val mas = ArrayList<String>(); j.optJSONArray("mas")?.let { a -> for (i in 0 until a.length()) mas += a.optString(i) }
            entradas[k] = Entrada(k, j.optString("cat", "otro"), j.optString("titulo"), c, j.optString("texto"), j.optString("enlace"), j.optLong("u"), mas)
        }
    }

    @Synchronized fun guarda() {
        val e = JSONObject()
        for ((k, v) in entradas) e.put(k, JSONObject().put("cat", v.cat).put("titulo", v.titulo).put("campos", JSONObject(v.campos as Map<*, *>))
            .put("texto", v.texto).put("enlace", v.enlace).put("u", v.u).put("mas", org.json.JSONArray(v.mas)))
        archivo.parentFile?.mkdirs()
        val tmp = File(archivo.path + ".tmp"); tmp.writeText(JSONObject().put("v", 1).put("situacion", situacion).put("entradas", e).toString())
        if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
    }

    fun nueva(cat: String, titulo: String, enlace: String = ""): Entrada {
        val e = Entrada("e" + Biblioteca.id36(), cat, titulo, LinkedHashMap(categoria(cat).campos.associateWith { "" }), "", enlace)
        entradas[e.id] = e; guarda(); return e
    }

    fun deEnlace(enlace: String) = entradas.values.firstOrNull { it.enlace == enlace || enlace in it.mas }

    /** Liga la ficha a otro sitio del mapa (además de los que ya tenga). */
    fun liga(e: Entrada, enlace: String) { if (e.enlace.isBlank()) e.enlace = enlace else if (e.enlace != enlace && enlace !in e.mas) e.mas += enlace; guarda() }

    /** La ficha de algo del mapa: la que ya está ligada, o la que se llama igual (y queda ligada), o una nueva. */
    fun fichaDe(cat: String, titulo: String, enlace: String): Entrada =
        deEnlace(enlace) ?: de(cat).firstOrNull { it.titulo.trim().equals(titulo.trim(), ignoreCase = true) }?.also { liga(it, enlace) } ?: nueva(cat, titulo, enlace)

    // ---------------------------------------------------------------- política: relaciones entre potencias y guerras

    /** Qué es [b] para [a] ("" nada dicho). */
    fun relacion(a: Entrada, b: Entrada): String = a.campos["_rel:" + b.id].orEmpty()

    /** Pone la relación de [a] con [b] y la que le corresponde a [b] con [a] (el vasallo de uno tiene al otro por señor). */
    fun ponRelacion(a: Entrada, b: Entrada, r: String) {
        fun pon(x: Entrada, y: Entrada, v: String) { if (v.isBlank()) x.campos.remove("_rel:" + y.id) else x.campos["_rel:" + y.id] = v; x.u = System.currentTimeMillis() }
        pon(a, b, r); pon(b, a, when (r) { "vasallo" -> "senor"; "senor" -> "vasallo"; else -> r })
        guarda()
    }

    /** Las potencias de un bando ("a" o "b") de una guerra. */
    fun bando(g: Entrada, k: String): List<Entrada> = g.campos["_$k"].orEmpty().split(',').mapNotNull { entradas[it] }
    fun ponEnBando(g: Entrada, k: String, potencia: Entrada, dentro: Boolean) {
        for (otro in listOf("a", "b")) { val l = bando(g, otro).map { it.id }.toMutableList(); l.remove(potencia.id); if (otro == k && dentro) l += potencia.id; g.campos["_$otro"] = l.joinToString(",") }
        g.u = System.currentTimeMillis(); guarda()
    }
    fun guerrasDe(potencia: Entrada) = de("guerra").filter { g -> bando(g, "a").any { it.id == potencia.id } || bando(g, "b").any { it.id == potencia.id } }
    fun borra(id: String) { entradas.remove(id); guarda() }
    fun de(cat: String) = entradas.values.filter { it.cat == cat }.sortedBy { it.titulo.lowercase() }

    companion object {
        val CATEGORIAS = listOf(
            Categoria("reino", "Reinos e imperios", listOf("Tipo", "Gobierno", "Gobernante", "Capital", "Situación actual", "Ejército", "Economía", "Religión", "Pueblos", "Lema", "Fundación")),
            Categoria("guerra", "Guerras", listOf("Estado", "Empieza", "Termina", "Causa", "Dónde se lucha", "Resultado")),
            Categoria("personaje", "Personajes", listOf("Título", "Pueblo", "Reino", "Nacimiento", "Muerte", "Familia", "Rasgos")),
            Categoria("lugar", "Ciudades y lugares", listOf("Tipo", "Reino", "Población", "Gobernante", "Conocido por")),
            Categoria("region", "Regiones", listOf("Tipo", "Clima", "Habitantes", "Peligros")),
            Categoria("pueblo", "Pueblos y razas", listOf("Origen", "Lengua", "Aspecto", "Costumbres", "Dónde viven")),
            Categoria("historia", "Historia", listOf("Fecha", "Lugar", "Protagonistas", "Consecuencias")),
            Categoria("fe", "Religiones y fe", listOf("Dioses", "Clero", "Ritos", "Lugares sagrados")),
            Categoria("otro", "Otros", emptyList()),
        )
        fun categoria(c: String) = CATEGORIAS.firstOrNull { it.clave == c } ?: CATEGORIAS.last()
        /** Las que tienen su propio apartado en el mundo (Política y Guerras): no salen en la Crónica. */
        val APARTE = setOf("reino", "guerra")
        /** Qué es la otra potencia para esta (lo que se elige en su ficha). */
        val RELACIONES = listOf("" to "Nada", "aliado" to "Aliados", "comercio" to "Comercian", "tregua" to "En tregua", "enemigo" to "Enemigos", "guerra" to "En guerra", "vasallo" to "Es vasallo suyo", "senor" to "Es su señor")
        fun nombreRelacion(r: String) = RELACIONES.firstOrNull { it.first == r }?.second ?: r
        /** Para resumir en una línea («Vasallos: X, Y»). */
        val RESUMEN = listOf("guerra" to "En guerra con", "enemigo" to "Enemigos", "aliado" to "Aliados", "senor" to "Su señor", "vasallo" to "Vasallos", "comercio" to "Comercia con", "tregua" to "En tregua con")
        val ESTADOS_GUERRA = listOf("Se avecina", "En curso", "En tregua", "Terminada")
    }
}

/** Cuentas: superficies y formato de distancias. */
object Medidas {
    fun km2(m: Mapa, pixeles: Long): Double = pixeles.toDouble() * m.kmPorPx * m.kmPorPx

    private fun num(v: Double): String = if (v >= 10 || v == Math.rint(v)) String.format("%,d", Math.round(v)).replace(',', '.') else String.format("%.1f", v).replace('.', ',')

    /** Una superficie en km² (o, si es pequeña, en m² o cm²). */
    fun formatoKm2(v: Double): String = when {
        v <= 0.0 -> "0 km²"
        v >= 1e6 -> String.format("%.2f millones de km²", v / 1e6).replace('.', ',')
        v >= 1.0 -> String.format("%,d km²", Math.round(v)).replace(',', '.')
        v >= 1e-6 -> num(v * 1e6) + " m²"
        else -> num(v * 1e10) + " cm²"
    }

    /** Una distancia en km (o, si es corta, en metros o centímetros). */
    fun formatoKm(v: Double): String = when {
        v >= 10 -> String.format("%,d km", Math.round(v)).replace(',', '.')
        v >= 1 || v <= 0.0 -> String.format("%.1f km", v).replace('.', ',')
        v >= 0.001 -> num(v * 1000) + " m"
        else -> num(v * 100000) + " cm"
    }

    /** El paso «bonito» (1, 2, 5 × 10ⁿ) más cercano a [v]. */
    fun bonito(v: Double): Double {
        if (v <= 0) return 1.0
        val e = Math.pow(10.0, Math.floor(Math.log10(v)))
        val f = v / e
        return e * when { f < 1.5 -> 1.0; f < 3.5 -> 2.0; f < 7.5 -> 5.0; else -> 10.0 }
    }
}
