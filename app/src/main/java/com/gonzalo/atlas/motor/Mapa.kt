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
    /** Máscara de recorte: solo se ve donde la capa de debajo tiene algo pintado. */
    var recorte: Boolean = false,
    /** El grupo en el que está ([GrupoCapas.id]; "" = suelta). Las capas de un grupo van seguidas. */
    var grupo: String = "",
    /**
     * Es la máscara de la capa de justo debajo: no se ve ella, sino que lo que tiene pintado esconde esa capa (pintar esconde,
     * borrar vuelve a enseñar; no se pierde nada de la capa). Con [visible] en falso, la máscara está apagada.
     */
    var mascara: Boolean = false,
) {
    /** Si su grupo se ve (lo pone el lienzo; no se guarda). */
    @Volatile @Transient var grupoVisible: Boolean = true
    /** Si se ve de verdad: ella y su grupo. */
    val seVe: Boolean get() = visible && grupoVisible && !mascara

    fun json() = JSONObject().put("id", id).put("n", nombre).put("v", visible).put("o", opacidad.toDouble()).put("f", fusion).put("a", bloqueoAlfa).put("r", referencia).put("k", recorte)
        .also { if (grupo.isNotEmpty()) it.put("g", grupo); if (mascara) it.put("m", true) }
    companion object { fun de(o: JSONObject) = CapaInfo(o.optString("id"), o.optString("n"), o.optBoolean("v", true), o.optDouble("o", 1.0).toFloat(), o.optString("f", "normal"), o.optBoolean("a"), o.optBoolean("r"), o.optBoolean("k"), o.optString("g"), o.optBoolean("m")) }
}

/**
 * Un cambio de un territorio desde un año: pasa a depender de otro ([padre]; conquista, cesión, herencia), se hace
 * independiente, aparece (antes no existía) o desaparece. [ocupado]: el cambio es una ocupación (se ve rayado con el color de
 * antes). La línea del tiempo enseña el mapa de cada año con los cambios que llevan hasta él.
 */
class CambioTerritorio(val id: String, var territorio: Int, var ano: Int, var tipo: String = "pasa", var padre: Int = 0, var ocupado: Boolean = false, var nota: String = "") {
    fun json() = JSONObject().put("id", id).put("t", territorio).put("a", ano).put("k", tipo).put("p", padre).put("o", ocupado).put("n", nota)
    companion object {
        fun de(o: JSONObject) = CambioTerritorio(o.optString("id"), o.optInt("t"), o.optInt("a"), o.optString("k", "pasa"), o.optInt("p"), o.optBoolean("o"), o.optString("n"))
        val TIPOS = listOf("pasa" to "Pasa a ser de", "independiente" to "Se hace independiente", "aparece" to "Aparece (antes no existía)", "desaparece" to "Desaparece")
        fun nombreTipo(k: String) = TIPOS.firstOrNull { it.first == k }?.second ?: k
    }
}

/** El acabado de la costa: líneas de agua alrededor de la tierra y sombra en el litoral, hechas solas a partir de la costa. */
class AcabadoCosta(var lineas: Int = 3, var separacionKm: Float = 0f, var apagado: Float = 0.6f, var sombra: Boolean = true, var color: Int = 0xFF567884.toInt()) {
    fun json() = JSONObject().put("l", lineas).put("s", separacionKm.toDouble()).put("a", apagado.toDouble()).put("o", sombra).put("c", color)
    companion object {
        fun de(o: JSONObject?) = o?.let { AcabadoCosta(it.optInt("l", 3), it.optDouble("s", 0.0).toFloat(), it.optDouble("a", 0.6).toFloat(), it.optBoolean("o", true), it.optInt("c", 0xFF567884.toInt())) }
    }
}

/** Un grupo de capas: se ve o se esconde entero, se pliega en la lista y se ordena junto. */
class GrupoCapas(val id: String, var nombre: String, var visible: Boolean = true, var plegado: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("n", nombre).put("v", visible).put("p", plegado)
    companion object { fun de(o: JSONObject) = GrupoCapas(o.optString("id"), o.optString("n"), o.optBoolean("v", true), o.optBoolean("p")) }
}

/** La imagen para calcar: detrás (o encima) del dibujo, que no sale al exportar. Mide [ancho] px del lienzo. */
class Calco(var visible: Boolean = true, var x: Float = 0f, var y: Float = 0f, var ancho: Float = 1000f, var giro: Float = 0f, var opacidad: Float = 0.5f, var encima: Boolean = false) {
    fun json() = JSONObject().put("v", visible).put("x", x.toDouble()).put("y", y.toDouble()).put("w", ancho.toDouble()).put("g", giro.toDouble()).put("o", opacidad.toDouble()).put("e", encima)
    companion object {
        fun de(o: JSONObject?) = o?.let { Calco(it.optBoolean("v", true), it.optDouble("x").toFloat(), it.optDouble("y").toFloat(), it.optDouble("w", 1000.0).toFloat(), it.optDouble("g").toFloat(), it.optDouble("o", 0.5).toFloat(), it.optBoolean("e")) }
    }
}

/**
 * Una sección del terreno: un marco con su tamaño real (por ejemplo, 500 × 300 km). Con ella activa, lo que dibujas no se
 * sale de ella: así sabes que lo de dentro mide eso como mucho.
 */
class Seccion(val id: String, var nombre: String, var x: Float, var y: Float, var w: Float, var h: Float) {
    fun json() = JSONObject().put("id", id).put("n", nombre).put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
    companion object { fun de(o: JSONObject) = Seccion(o.optString("id"), o.optString("n"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optDouble("w").toFloat(), o.optDouble("h").toFloat()) }
}

/**
 * Guía de simetría: lo que dibujas se repite reflejado. [tipo]: "vertical" (un eje vertical: izquierda y derecha),
 * "horizontal", "cuadrante" (los dos ejes) o "radial" ([n] gajos girados alrededor del centro).
 */
class Simetria(var tipo: String = "vertical", var x: Double = 0.0, var y: Double = 0.0, var n: Int = 6) {
    fun json() = JSONObject().put("t", tipo).put("x", x).put("y", y).put("n", n)
    /**
     * Las transformaciones de cada copia (la primera, el trazo tal cual), como (a, b, c, d, e, f):
     * x' = a·x + b·y + e, y' = c·x + d·y + f. Con doble precisión, para que el reflejo caiga en su sitio también de muy cerca.
     */
    fun espejos(): List<DoubleArray> {
        val igual = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        val h = doubleArrayOf(-1.0, 0.0, 0.0, 1.0, 2 * x, 0.0); val v = doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 2 * y)
        return when (tipo) {
            "horizontal" -> listOf(igual, v)
            "cuadrante" -> listOf(igual, h, v, doubleArrayOf(-1.0, 0.0, 0.0, -1.0, 2 * x, 2 * y))
            "radial" -> { val k = n.coerceIn(2, 24); List(k) { i -> val a = i * 2.0 * Math.PI / k; val c = Math.cos(a); val s = Math.sin(a); doubleArrayOf(c, -s, s, c, x - c * x + s * y, y - s * x - c * y) } }
            else -> listOf(igual, h)
        }
    }
    companion object { fun de(o: JSONObject?) = o?.let { Simetria(it.optString("t", "vertical"), it.optDouble("x"), it.optDouble("y"), it.optInt("n", 6)) } }
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
    /** Dónde está, en píxeles del mapa (con doble precisión: se puede colocar al metro en un mapa de miles de km). */
    var x: Double,
    var y: Double,
    /** 1 (un caserío) a 10 (una metrópoli). */
    var tamano: Int = Catalogo.lugar(tipo).tamano,
    var nota: String = "",
    /** El mapa que se abre al tocarlo (el plano de esa ciudad, de esa iglesia…). */
    var mapa: String = "",
) {
    fun json() = JSONObject().put("id", id).put("n", nombre).put("k", tipo).put("x", x).put("y", y).put("s", tamano).put("nota", nota).put("mp", mapa)
    companion object {
        fun de(o: JSONObject) = Lugar(o.optString("id"), o.optString("n"), o.optString("k", "punto"), o.optDouble("x"), o.optDouble("y"), o.optInt("s", 3), o.optString("nota"), o.optString("mp"))
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
class Rotulo(val id: String, var texto: String, var x: Double, var y: Double, var tam: Float = 28f, var giro: Float = 0f, var estilo: String = "serif", var espaciado: Float = 0.15f, var curva: Float = 0f) {
    fun json() = JSONObject().put("id", id).put("t", texto).put("x", x).put("y", y).put("s", tam.toDouble()).put("g", giro.toDouble())
        .put("e", estilo).put("sp", espaciado.toDouble()).put("cv", curva.toDouble())
    companion object {
        fun de(o: JSONObject) = Rotulo(o.optString("id"), o.optString("t"), o.optDouble("x"), o.optDouble("y"), o.optDouble("s", 28.0).toFloat(),
            o.optDouble("g").toFloat(), o.optString("e", "serif"), o.optDouble("sp", 0.15).toFloat(), o.optDouble("cv").toFloat())
    }
}

/** Uno de tus dibujos puesto en el mapa. [recurso] es el id del dibujo en la biblioteca. */
class Sello(val id: String, var recurso: String, var x: Double, var y: Double, var escala: Float = 1f, var giro: Float = 0f, var espejo: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("r", recurso).put("x", x).put("y", y).put("s", escala.toDouble()).put("g", giro.toDouble()).put("f", espejo)
    companion object {
        fun de(o: JSONObject) = Sello(o.optString("id"), o.optString("r"), o.optDouble("x"), o.optDouble("y"), o.optDouble("s", 1.0).toFloat(), o.optDouble("g").toFloat(), o.optBoolean("f"))
    }
}

/**
 * Una tierra generada (un continente, un archipiélago…) y dónde está: el recuadro en el lienzo, con cuántas muestras se
 * calculó ([gw] × [gh]), su forma y sus colores (0: sin pintar). Las tierras [movible]s se dibujan solas en sus dos capas
 * (la costa y el color) a partir de esta lista: por eso se pueden mover, borrar y fundir unas con otras. (Las generadas
 * con versiones anteriores quedaron dibujadas en las capas del mapa: esas no se mueven.)
 */
class Tierra(var x: Float, var y: Float, val w: Float, val h: Float, val gw: Int, val gh: Int, val forma: FormaTierra,
             val id: String = "", val tinta: Int = 0xFF2A2118.toInt(), val colTierra: Int = 0, val colMar: Int = 0, val movible: Boolean = false) {
    val rect get() = android.graphics.RectF(x, y, x + w, y + h)
    fun con(forma: FormaTierra) = Tierra(x, y, w, h, gw, gh, forma, id, tinta, colTierra, colMar, movible)
    fun json() = JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble()).put("gw", gw).put("gh", gh)
        .put("s", forma.semilla).put("t", forma.tipo).put("ti", forma.tierra.toDouble()).put("r", forma.rugosidad.toDouble()).put("i", forma.islas.toDouble())
        .put("id", id).put("ink", tinta).put("ct", colTierra).put("cm", colMar).put("mv", movible)
    companion object {
        fun de(o: JSONObject) = Tierra(o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optDouble("w").toFloat(), o.optDouble("h").toFloat(), o.optInt("gw"), o.optInt("gh"),
            FormaTierra(o.optLong("s"), o.optString("t", "continente"), o.optDouble("ti", 0.36).toFloat(), o.optDouble("r", 0.5).toFloat(), o.optDouble("i", 0.35).toFloat()),
            o.optString("id"), o.optInt("ink", 0xFF2A2118.toInt()), o.optInt("ct"), o.optInt("cm"), o.optBoolean("mv"))
    }
}

// ==================================================================== el mapa

/**
 * Un mapa: lo que dibujas (capas en teselas de 256 px, las lleva [com.gonzalo.atlas.lienzo.Lienzo]) y lo que pones encima:
 * territorios, lugares, rótulos, rutas, tus dibujos sueltos y la leyenda. Finito ([ancho] × [alto] px) o infinito (0 × 0).
 */
class Mapa(val id: String, var mundo: String, var nombre: String, val ancho: Int, val alto: Int, val dir: File?) {
    val infinito get() = ancho == 0
    /** De qué es el mapa: "mundo", "continente", "region", "ciudad", "lugar" u "otro" ("" en los de antes). */
    var tipo = ""
    var kmPorPx = 1.0f
    var escalaPuesta = false
    val obras = CopyOnWriteArrayList<String>()
    var creado = System.currentTimeMillis()
    var modificado = creado
    /** Dónde se quedó mirando: centro (x, y), acercamiento y giro. */
    var vista = doubleArrayOf(Double.NaN, Double.NaN, 1.0, 0.0)
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
    val secciones = CopyOnWriteArrayList<Seccion>()
    /** Los grupos de capas (las capas dicen en cuál están). */
    val grupos = CopyOnWriteArrayList<GrupoCapas>()
    /** Los cambios de los territorios con los años (la línea del tiempo). */
    val cambios = CopyOnWriteArrayList<CambioTerritorio>()
    /** El año que se está viendo en la línea del tiempo; null: el mapa tal como está dibujado (sin cambios). No se guarda. */
    @Volatile var anoVista: Int? = null
    /** El acabado de la costa puesto (null: ninguno). */
    var acabadoCosta: AcabadoCosta? = null

    /** Los cambios de [id] hasta el año [a], por orden. */
    private fun cambiosHasta(id: Int, a: Int?): List<CambioTerritorio> { if (a == null) return emptyList(); return cambios.filter { it.territorio == id && it.ano <= a }.sortedBy { it.ano } }

    /** De quién depende [t] en el año [a] (por omisión, el que se ve). */
    fun padreDe(t: Territorio, a: Int? = anoVista): Int {
        var p = t.padre
        for (c in cambiosHasta(t.id, a)) when (c.tipo) { "pasa" -> p = c.padre; "independiente" -> p = 0 }
        return p
    }

    /** Si [id] existe en el año [a] (los que tienen «aparece» no existen antes de ese año). */
    fun existe(id: Int, a: Int? = anoVista): Boolean {
        if (a == null) return true
        var vive = cambios.none { it.territorio == id && it.tipo == "aparece" }
        for (c in cambiosHasta(id, a)) when (c.tipo) { "aparece" -> vive = true; "desaparece" -> vive = false }
        return vive
    }

    /** Si [id], en el año [a], está ocupado (el último cambio de manos fue una ocupación). */
    fun ocupado(id: Int, a: Int? = anoVista): Boolean = cambiosHasta(id, a).lastOrNull { it.tipo == "pasa" || it.tipo == "independiente" }?.ocupado == true

    /** El color que tenía [id] antes de su ocupación (para rayarlo), en el año [a]. */
    fun colorAntesDe(id: Int, a: Int? = anoVista): Int {
        val ult = cambiosHasta(id, a).lastOrNull { it.tipo == "pasa" || it.tipo == "independiente" } ?: return colorDe(id, a)
        return colorDe(id, ult.ano - 1)
    }
    /** Las tierras generadas en este mapa (para poder unirlas con las siguientes). */
    val tierras = CopyOnWriteArrayList<Tierra>()
    /** La sección en la que se dibuja (lo de fuera no se pinta); "" ninguna. */
    var seccionActiva = ""
    fun seccionActivaRect(): android.graphics.RectF? = secciones.firstOrNull { it.id == seccionActiva }?.let { android.graphics.RectF(it.x, it.y, it.x + it.w, it.y + it.h) }
    /** La imagen para calcar (su archivo, calco.png en la carpeta del mapa); null si no hay. */
    var calco: Calco? = null
    /** La guía de simetría con la que se dibuja (null: sin ella). */
    var simetria: Simetria? = null

    @Volatile var vectorVersion = 0
    val alCambiarVector = CopyOnWriteArrayList<() -> Unit>()
    fun avisaVector() { vectorVersion++; modificado = System.currentTimeMillis(); alCambiarVector.forEach { it() } }

    fun territorio(id: Int) = territorios.firstOrNull { it.id == id }
    fun idLibre(): Int = (1..255).firstOrNull { i -> territorios.none { it.id == i } } ?: 255
    fun nuevoId(prefijo: String) = prefijo + java.lang.Long.toString(System.nanoTime() xor (Math.random() * 1e15).toLong(), 36).takeLast(9)

    /** La cadena de un territorio hasta arriba (él, su padre, el padre de su padre…). */
    fun linaje(id: Int, a: Int? = anoVista): List<Territorio> {
        val l = ArrayList<Territorio>(); var t = territorio(id); val vistos = HashSet<Int>()
        while (t != null && vistos.add(t.id)) { l += t; val p = padreDe(t, a); t = if (p != 0) territorio(p) else null }
        return l
    }

    /** El territorio que da el color a [id] según [colorPorNivel]. */
    fun colorDe(id: Int, a: Int? = anoVista): Int {
        if (!existe(id, a)) return 0
        val l = linaje(id, a); if (l.isEmpty()) return 0
        return when (colorPorNivel) { 0 -> l.last().color; 1 -> l.getOrElse(l.size - 2) { l.last() }.color; else -> l.first().color }
    }

    /** Los hijos directos de un territorio (0: los de arriba del todo). */
    fun hijos(id: Int) = territorios.filter { padreDe(it) == id && it.id != id && existe(it.id) }

    fun vectorJson(): JSONObject = JSONObject()
        .put("territorios", JSONArray().also { a -> territorios.forEach { a.put(it.json()) } })
        .put("lugares", JSONArray().also { a -> lugares.forEach { a.put(it.json()) } })
        .put("rutas", JSONArray().also { a -> rutas.forEach { a.put(it.json()) } })
        .put("rotulos", JSONArray().also { a -> rotulos.forEach { a.put(it.json()) } })
        .put("sellos", JSONArray().also { a -> sellos.forEach { a.put(it.json()) } })
        .put("leyenda", leyenda.json())
        .put("calco", calco?.json() ?: JSONObject.NULL).put("simetria", simetria?.json() ?: JSONObject.NULL)
        .put("secciones", JSONArray().also { a -> secciones.forEach { a.put(it.json()) } }).put("seccionActiva", seccionActiva)
        .put("capas", JSONArray().also { a -> capas.forEach { a.put(it.json()) } })
        .put("grupos", JSONArray().also { a -> grupos.forEach { a.put(it.json()) } })
        .put("cambios", JSONArray().also { a -> cambios.forEach { a.put(it.json()) } })
        .put("costa", acabadoCosta?.json() ?: JSONObject.NULL)
        .put("tierras", JSONArray().also { a -> tierras.forEach { a.put(it.json()) } })

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
        simetria = Simetria.de(o.optJSONObject("simetria"))
        lista("secciones", secciones, Seccion::de)
        seccionActiva = o.optString("seccionActiva")
        if (o.has("capas")) lista("capas", capas, CapaInfo::de)
        lista("grupos", grupos, GrupoCapas::de)
        lista("cambios", cambios, CambioTerritorio::de)
        acabadoCosta = AcabadoCosta.de(o.optJSONObject("costa"))
        lista("tierras", tierras, Tierra::de)
    }

    fun metaJson(): JSONObject = JSONObject().put("v", 2).put("id", id).put("mundo", mundo).put("nombre", nombre).put("tipo", tipo).put("w", ancho).put("h", alto)
        .put("km", kmPorPx.toDouble()).put("kmPuesta", escalaPuesta).put("obras", JSONArray(obras.toList())).put("creado", creado).put("modificado", modificado)
        .put("papel", papel).put("grano", granoPapel).put("verT", verTerritorios).put("tinte", tinteTerritorios.toDouble()).put("nivel", colorPorNivel)
        .put("vista", JSONArray().also { a -> vista.forEach { a.put(if (it.isNaN()) JSONObject.NULL else it) } })

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
            m.tipo = o.optString("tipo")
            m.kmPorPx = o.optDouble("km", 1.0).toFloat(); m.escalaPuesta = o.optBoolean("kmPuesta")
            o.optJSONArray("obras")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { m.obras += it } }
            m.creado = o.optLong("creado", m.creado); m.modificado = o.optLong("modificado", m.modificado)
            m.papel = o.optInt("papel", m.papel); m.granoPapel = o.optBoolean("grano", true); m.verTerritorios = o.optBoolean("verT", true)
            m.tinteTerritorios = o.optDouble("tinte", 0.28).toFloat(); m.colorPorNivel = o.optInt("nivel", 2)
            o.optJSONArray("vista")?.let { a -> for (i in 0 until minOf(4, a.length())) m.vista[i] = if (a.isNull(i)) Double.NaN else a.optDouble(i) }
            o.optJSONObject("vector")?.let { m.ponVector(it) }
            m
        }.getOrNull()
    }
}
