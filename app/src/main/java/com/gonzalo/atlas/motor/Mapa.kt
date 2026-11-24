package com.gonzalo.atlas.motor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Un territorio: un imperio, un reino, una provincia… Se marca en el mapa tocando dentro de las fronteras que dibujas (o
 * pintándolo). [padre]: el territorio del que depende (una provincia de un reino, un reino de un imperio); 0 si ninguno.
 * La posición, tamaño y giro del nombre son automáticos mientras [lx] sea NaN.
 */
class Territorio(
    var id: Int,
    var nombre: String,
    var tipo: String = "Reino",
    var color: Int = Catalogo.COLORES[0],
    var padre: Int = 0,
    var capital: String = "",
    var lx: Float = Float.NaN, var ly: Float = Float.NaN,
    var tam: Float = 0f, var giro: Float = 0f, var curva: Float = 0f,
    var verNombre: Boolean = true,
    /** El mapa que se abre al tocarlo (otro mapa del mundo: el de esa región). */
    var mapa: String = "",
) {
    fun json() = JSONObject().put("id", id).put("n", nombre).put("t", tipo).put("c", color).put("p", padre).put("cap", capital)
        .put("lx", if (lx.isNaN()) JSONObject.NULL else lx.toDouble()).put("ly", if (ly.isNaN()) JSONObject.NULL else ly.toDouble())
        .put("s", tam.toDouble()).put("g", giro.toDouble()).put("cv", curva.toDouble()).put("vn", verNombre).put("mp", mapa)
    companion object {
        fun de(o: JSONObject) = Territorio(o.optInt("id"), o.optString("n"), o.optString("t", "Reino"), o.optInt("c"), o.optInt("p"), o.optString("cap"),
            if (o.isNull("lx")) Float.NaN else o.optDouble("lx").toFloat(), if (o.isNull("ly")) Float.NaN else o.optDouble("ly").toFloat(),
            o.optDouble("s").toFloat(), o.optDouble("g").toFloat(), o.optDouble("cv").toFloat(), o.optBoolean("vn", true), o.optString("mp"))
    }
}

/** Los datos de una capa de dibujo (sus píxeles los lleva el lienzo, en teselas). */
class CapaInfo(
    val id: String,
    var nombre: String,
    var visible: Boolean = true,
    var opacidad: Float = 1f,
    /** normal, multiplicar, pantalla, superponer, aclarar, oscurecer, añadir */
    var fusion: String = "normal",
    var bloqueoAlfa: Boolean = false,
    /** Capa de referencia: el relleno y los territorios miran sus líneas aunque dibujes en otra. */
    var referencia: Boolean = false,
) {
    fun json() = JSONObject().put("id", id).put("n", nombre).put("v", visible).put("o", opacidad.toDouble()).put("f", fusion).put("a", bloqueoAlfa).put("r", referencia)
    companion object { fun de(o: JSONObject) = CapaInfo(o.optString("id"), o.optString("n"), o.optBoolean("v", true), o.optDouble("o", 1.0).toFloat(), o.optString("f", "normal"), o.optBoolean("a"), o.optBoolean("r")) }
}

/** La imagen para calcar: detrás (o encima) del dibujo, que no sale al exportar. Mide [ancho] px del lienzo. */
class Calco(var visible: Boolean = true, var x: Float = 0f, var y: Float = 0f, var ancho: Float = 1000f, var giro: Float = 0f, var opacidad: Float = 0.5f, var encima: Boolean = false) {
    fun json() = JSONObject().put("v", visible).put("x", x.toDouble()).put("y", y.toDouble()).put("w", ancho.toDouble()).put("g", giro.toDouble()).put("o", opacidad.toDouble()).put("e", encima)
    companion object {
        fun de(o: JSONObject?) = o?.let { Calco(it.optBoolean("v", true), it.optDouble("x").toFloat(), it.optDouble("y").toFloat(), it.optDouble("w", 1000.0).toFloat(), it.optDouble("g").toFloat(), it.optDouble("o", 0.5).toFloat(), it.optBoolean("e")) }
    }
}

/** La leyenda del mapa: un cuadro que se mueve y escala como un objeto más. */
class Leyenda(
    var visible: Boolean = false, var x: Float = Float.NaN, var y: Float = Float.NaN, var escala: Float = 1f,
    var titulo: String = "Leyenda", var territorios: Boolean = true, var lugares: Boolean = true, var escalaKm: Boolean = true,
    /** Entradas tuyas: «símbolo|texto» (el símbolo es un tipo de lugar o «color:#rrggbb»). */
    val extra: MutableList<String> = ArrayList(),
) {
    fun json() = JSONObject().put("v", visible).put("x", if (x.isNaN()) JSONObject.NULL else x.toDouble()).put("y", if (y.isNaN()) JSONObject.NULL else y.toDouble())
        .put("s", escala.toDouble()).put("t", titulo).put("te", territorios).put("lu", lugares).put("km", escalaKm).put("ex", JSONArray(extra))
    companion object {
        fun de(o: JSONObject?) = if (o == null) Leyenda() else Leyenda(o.optBoolean("v"), if (o.isNull("x")) Float.NaN else o.optDouble("x").toFloat(),
            if (o.isNull("y")) Float.NaN else o.optDouble("y").toFloat(), o.optDouble("s", 1.0).toFloat(), o.optString("t", "Leyenda"),
            o.optBoolean("te", true), o.optBoolean("lu", true), o.optBoolean("km", true),
            (o.optJSONArray("ex") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) }.toMutableList() })
    }
}

class Lugar(
    val id: String,
    var nombre: String,
    var tipo: String,
    var x: Float,
    var y: Float,
    /** 1 (un caserío) a 10 (una metrópoli). */
    var tamano: Int = Catalogo.lugar(tipo).tamano,
    var nota: String = "",
    /** El mapa que se abre al tocarlo (el plano de esa ciudad, de esa iglesia…). */
    var mapa: String = "",
) {
    fun json() = JSONObject().put("id", id).put("n", nombre).put("k", tipo).put("x", x.toDouble()).put("y", y.toDouble()).put("s", tamano).put("nota", nota).put("mp", mapa)
    companion object {
        fun de(o: JSONObject) = Lugar(o.optString("id"), o.optString("n"), o.optString("k", "punto"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optInt("s", 3), o.optString("nota"), o.optString("mp"))
    }
}

/** Una línea dibujada (río, camino): puntos en píxeles del mapa y, para los ríos, el ancho en cada punto. */
class Linea(val id: String, var tipo: String, val xs: FloatArray, val ys: FloatArray, val anchos: FloatArray?, var nombre: String = "") {
    val n get() = xs.size
    fun json(): JSONObject {
        val p = JSONArray(); for (i in xs.indices) { p.put(Math.round(xs[i] * 10) / 10.0); p.put(Math.round(ys[i] * 10) / 10.0) }
        val o = JSONObject().put("id", id).put("k", tipo).put("p", p).put("n", nombre)
        anchos?.let { a -> o.put("w", JSONArray().also { j -> a.forEach { j.put(Math.round(it * 100) / 100.0) } }) }
        return o
    }
    fun desplazada(dx: Float, dy: Float, nuevoId: String = id) = Linea(nuevoId, tipo, FloatArray(n) { xs[it] + dx }, FloatArray(n) { ys[it] + dy }, anchos?.copyOf(), nombre)
    companion object {
        fun de(o: JSONObject): Linea {
            val p = o.optJSONArray("p") ?: JSONArray()
            val n = p.length() / 2
            val w = o.optJSONArray("w")
            return Linea(o.optString("id"), o.optString("k"), FloatArray(n) { p.optDouble(it * 2).toFloat() }, FloatArray(n) { p.optDouble(it * 2 + 1).toFloat() },
                if (w != null && w.length() == n) FloatArray(n) { w.optDouble(it).toFloat() } else null, o.optString("n"))
        }
    }
}

/** Un rótulo de texto libre (el nombre de un mar, de una cordillera…). */
class Rotulo(val id: String, var texto: String, var x: Float, var y: Float, var tam: Float = 28f, var giro: Float = 0f, var estilo: String = "serif", var espaciado: Float = 0.15f, var curva: Float = 0f) {
    fun json() = JSONObject().put("id", id).put("t", texto).put("x", x.toDouble()).put("y", y.toDouble()).put("s", tam.toDouble()).put("g", giro.toDouble())
        .put("e", estilo).put("sp", espaciado.toDouble()).put("cv", curva.toDouble())
    companion object {
        fun de(o: JSONObject) = Rotulo(o.optString("id"), o.optString("t"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optDouble("s", 28.0).toFloat(),
            o.optDouble("g").toFloat(), o.optString("e", "serif"), o.optDouble("sp", 0.15).toFloat(), o.optDouble("cv").toFloat())
    }
}

/** Uno de tus dibujos puesto en el mapa. [recurso] es el id del dibujo en la biblioteca. */
class Sello(val id: String, var recurso: String, var x: Float, var y: Float, var escala: Float = 1f, var giro: Float = 0f, var espejo: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("r", recurso).put("x", x.toDouble()).put("y", y.toDouble()).put("s", escala.toDouble()).put("g", giro.toDouble()).put("f", espejo)
    companion object {
        fun de(o: JSONObject) = Sello(o.optString("id"), o.optString("r"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optDouble("s", 1.0).toFloat(), o.optDouble("g").toFloat(), o.optBoolean("f"))
    }
}

// ==================================================================== el mapa

/**
 * Un mapa: lo que dibujas (capas en teselas de 256 px, las lleva [com.gonzalo.atlas.lienzo.Lienzo]) y lo que pones encima:
 * territorios, lugares, rótulos, rutas, tus dibujos sueltos y la leyenda. Finito ([ancho] × [alto] px) o infinito (0 × 0).
 */
class Mapa(val id: String, var mundo: String, var nombre: String, val ancho: Int, val alto: Int, val dir: File?) {
    val infinito get() = ancho == 0
    var kmPorPx = 1.0f
    var escalaPuesta = false
    val obras = CopyOnWriteArrayList<String>()
    var creado = System.currentTimeMillis()
    var modificado = creado
    var vista = floatArrayOf(Float.NaN, Float.NaN, 1f, 0f)
    /** El papel de fondo. */
    var papel: Int = 0xFFF1E6C8.toInt()
    var granoPapel = true
    var verTerritorios = true
    /** Cuánto se nota el color de los territorios (0..1). */
    var tinteTerritorios = 0.28f
    /** Qué nivel se colorea: 0 el más alto (imperios), 1 reinos, 2 lo pintado. */
    var colorPorNivel = 2

    val capas = CopyOnWriteArrayList<CapaInfo>()
    val territorios = CopyOnWriteArrayList<Territorio>()
    val lugares = CopyOnWriteArrayList<Lugar>()
    val rutas = CopyOnWriteArrayList<Linea>()
    val rotulos = CopyOnWriteArrayList<Rotulo>()
    val sellos = CopyOnWriteArrayList<Sello>()
    var leyenda = Leyenda()
    /** La imagen para calcar (su archivo, calco.png en la carpeta del mapa); null si no hay. */
    var calco: Calco? = null

    @Volatile var vectorVersion = 0
    val alCambiarVector = CopyOnWriteArrayList<() -> Unit>()
    fun avisaVector() { vectorVersion++; modificado = System.currentTimeMillis(); alCambiarVector.forEach { it() } }

    fun territorio(id: Int) = territorios.firstOrNull { it.id == id }
    fun idLibre(): Int = (1..255).firstOrNull { i -> territorios.none { it.id == i } } ?: 255
    fun nuevoId(prefijo: String) = prefijo + java.lang.Long.toString(System.nanoTime() xor (Math.random() * 1e15).toLong(), 36).takeLast(9)

    /** La cadena de un territorio hasta arriba (él, su padre, el padre de su padre…). */
    fun linaje(id: Int): List<Territorio> {
        val l = ArrayList<Territorio>(); var t = territorio(id); val vistos = HashSet<Int>()
        while (t != null && vistos.add(t.id)) { l += t; t = if (t.padre != 0) territorio(t.padre) else null }
        return l
    }

    /** El territorio que da el color a [id] según [colorPorNivel]. */
    fun colorDe(id: Int): Int {
        val l = linaje(id); if (l.isEmpty()) return 0
        return when (colorPorNivel) { 0 -> l.last().color; 1 -> l.getOrElse(l.size - 2) { l.last() }.color; else -> l.first().color }
    }

    /** Los hijos directos de un territorio (0: los de arriba del todo). */
    fun hijos(id: Int) = territorios.filter { it.padre == id && it.id != id }

    fun vectorJson(): JSONObject = JSONObject()
        .put("territorios", JSONArray().also { a -> territorios.forEach { a.put(it.json()) } })
        .put("lugares", JSONArray().also { a -> lugares.forEach { a.put(it.json()) } })
        .put("rutas", JSONArray().also { a -> rutas.forEach { a.put(it.json()) } })
        .put("rotulos", JSONArray().also { a -> rotulos.forEach { a.put(it.json()) } })
        .put("sellos", JSONArray().also { a -> sellos.forEach { a.put(it.json()) } })
        .put("leyenda", leyenda.json())
        .put("calco", calco?.json() ?: JSONObject.NULL)
        .put("capas", JSONArray().also { a -> capas.forEach { a.put(it.json()) } })

    fun ponVector(o: JSONObject) {
        fun <T> lista(n: String, dst: MutableList<T>, f: (JSONObject) -> T) {
            val a = o.optJSONArray(n) ?: JSONArray(); val l = ArrayList<T>(a.length())
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { l += f(it) }
            dst.clear(); dst.addAll(l)
        }
        lista("territorios", territorios, Territorio::de); lista("lugares", lugares, Lugar::de); lista("rutas", rutas, Linea::de)
        lista("rotulos", rotulos, Rotulo::de); lista("sellos", sellos, Sello::de)
        leyenda = Leyenda.de(o.optJSONObject("leyenda"))
        calco = Calco.de(o.optJSONObject("calco"))
        if (o.has("capas")) lista("capas", capas, CapaInfo::de)
    }

    fun metaJson(): JSONObject = JSONObject().put("v", 2).put("id", id).put("mundo", mundo).put("nombre", nombre).put("w", ancho).put("h", alto)
        .put("km", kmPorPx.toDouble()).put("kmPuesta", escalaPuesta).put("obras", JSONArray(obras.toList())).put("creado", creado).put("modificado", modificado)
        .put("papel", papel).put("grano", granoPapel).put("verT", verTerritorios).put("tinte", tinteTerritorios.toDouble()).put("nivel", colorPorNivel)
        .put("vista", JSONArray().also { a -> vista.forEach { a.put(if (it.isNaN()) JSONObject.NULL else it.toDouble()) } })

    fun guardaMeta() {
        val d = dir ?: return
        d.mkdirs()
        val tmp = File(d, "mapa.json.tmp"); tmp.writeText(metaJson().put("vector", vectorJson()).toString())
        val f = File(d, "mapa.json"); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        fun lee(dir: File): Mapa? = runCatching {
            val o = JSONObject(File(dir, "mapa.json").readText())
            if (o.optInt("v") < 2) return null
            val m = Mapa(o.getString("id"), o.optString("mundo"), o.optString("nombre"), o.optInt("w"), o.optInt("h"), dir)
            m.kmPorPx = o.optDouble("km", 1.0).toFloat(); m.escalaPuesta = o.optBoolean("kmPuesta")
            o.optJSONArray("obras")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { m.obras += it } }
            m.creado = o.optLong("creado", m.creado); m.modificado = o.optLong("modificado", m.modificado)
            m.papel = o.optInt("papel", m.papel); m.granoPapel = o.optBoolean("grano", true); m.verTerritorios = o.optBoolean("verT", true)
            m.tinteTerritorios = o.optDouble("tinte", 0.28).toFloat(); m.colorPorNivel = o.optInt("nivel", 2)
            o.optJSONArray("vista")?.let { a -> for (i in 0 until minOf(4, a.length())) m.vista[i] = if (a.isNull(i)) Float.NaN else a.optDouble(i).toFloat() }
            o.optJSONObject("vector")?.let { m.ponVector(it) }
            m
        }.getOrNull()
    }
}
