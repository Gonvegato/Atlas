package com.gonzalo.atlas.motor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Un trozo cuadrado del mapa, con todas sus capas (las ráster). */
class Trozo(val cx: Int, val cy: Int) {
    /** Altura: 0 es el nivel del mar; 1, la cumbre más alta (unos 5.000 m); −1, la fosa más honda. */
    val alt = FloatArray(N)
    /** Bioma pintado (0: el que toque por la altura). */
    val bio = ByteArray(N)
    val reino = ByteArray(N)
    val region = ByteArray(N)
    val zona = ByteArray(N)

    /** Sube con cada cambio (el render y las miniaturas miran esto para saber si lo que tienen está viejo). */
    @Volatile var version = 0
    /** Cambiado desde la última vez que se guardó. */
    @Volatile var sucio = false
    /** Lo tiene guardado el disco (o el mapa entero se ha materializado): si no, se puede volver a generar. */
    @Volatile var editado = false

    fun copia(): Trozo = Trozo(cx, cy).also { t ->
        alt.copyInto(t.alt); bio.copyInto(t.bio); reino.copyInto(t.reino); region.copyInto(t.region); zona.copyInto(t.zona)
        t.editado = editado
    }

    fun copiaDe(o: Trozo) {
        o.alt.copyInto(alt); o.bio.copyInto(bio); o.reino.copyInto(reino); o.region.copyInto(region); o.zona.copyInto(zona)
    }

    companion object {
        const val LADO = 256
        const val BITS = 8
        const val N = LADO * LADO
        fun clave(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
        fun cx(clave: Long) = (clave shr 32).toInt()
        fun cy(clave: Long) = clave.toInt()
    }
}

/** Una versión reducida de un trozo (para ver el mapa de lejos): lado = 256 >> nivel. */
class Mip(val nivel: Int, val version: Int) {
    val lado = Trozo.LADO shr nivel
    val alt = FloatArray(lado * lado)
    val bio = ByteArray(lado * lado)
    val reino = ByteArray(lado * lado)
    val region = ByteArray(lado * lado)
    val zona = ByteArray(lado * lado)
}

// ==================================================================== lo que no es ráster

class Reino(
    var id: Int,
    var nombre: String,
    var color: Int,
    var tipo: String = "Reino",
    /** El reino del que depende (un imperio, un reino…); 0 si es independiente. */
    var padre: Int = 0,
    var capital: String = "",
) {
    fun json() = JSONObject().put("id", id).put("n", nombre).put("c", color).put("t", tipo).put("p", padre).put("cap", capital)
    companion object { fun de(o: JSONObject) = Reino(o.optInt("id"), o.optString("n"), o.optInt("c"), o.optString("t", "Reino"), o.optInt("p"), o.optString("cap")) }
}

/** Una región con nombre (una sierra, un bosque, una comarca): se pinta como los reinos, sin color de fondo. */
class Region(var id: Int, var nombre: String, var tipo: String = "Comarca") {
    fun json() = JSONObject().put("id", id).put("n", nombre).put("t", tipo)
    companion object { fun de(o: JSONObject) = Region(o.optInt("id"), o.optString("n"), o.optString("t", "Comarca")) }
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
) {
    fun json() = JSONObject().put("id", id).put("n", nombre).put("k", tipo).put("x", x.toDouble()).put("y", y.toDouble()).put("s", tamano).put("nota", nota)
    companion object {
        fun de(o: JSONObject) = Lugar(o.optString("id"), o.optString("n"), o.optString("k", "punto"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optInt("s", 3), o.optString("nota"))
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
 * Un mapa: capas ráster por trozos de 256 px (se generan solos a partir de la semilla hasta que los tocas) y lo vectorial.
 * Finito ([anchoTrozos] × [altoTrozos]) o infinito (0 × 0: los trozos van de −∞ a +∞).
 */
class Mapa(
    val id: String,
    var mundo: String,
    var nombre: String,
    val semilla: Long,
    val estilo: Generador.Estilo,
    val anchoTrozos: Int,
    val altoTrozos: Int,
    val tierra: Float = 0.42f,
    val dir: File?,
) {
    val infinito get() = anchoTrozos == 0
    val ancho get() = anchoTrozos * Trozo.LADO
    val alto get() = altoTrozos * Trozo.LADO
    val generador = Generador(semilla, estilo, ancho, alto, tierra)

    var kmPorPx = 1.0f
    var escalaPuesta = false
    /** Las obras (novelas de Nova) en las que sale este mapa. */
    val obras = CopyOnWriteArrayList<String>()
    var creado = System.currentTimeMillis()
    var modificado = creado
    /** Dónde se estaba mirando (para volver al mismo sitio). */
    var vista = floatArrayOf(Float.NaN, Float.NaN, 1f)

    val reinos = CopyOnWriteArrayList<Reino>()
    val regiones = CopyOnWriteArrayList<Region>()
    val lugares = CopyOnWriteArrayList<Lugar>()
    val rios = CopyOnWriteArrayList<Linea>()
    val caminos = CopyOnWriteArrayList<Linea>()
    val rotulos = CopyOnWriteArrayList<Rotulo>()
    val sellos = CopyOnWriteArrayList<Sello>()

    private val trozos = ConcurrentHashMap<Long, Trozo>()
    private val mips = ConcurrentHashMap<Long, Mip>()
    /** Trozos que están en el disco (se leen al necesitarlos). */
    private val enDisco: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Se llama al cambiar una zona del mapa (en píxeles): el render repinta eso. */
    val alCambiar = CopyOnWriteArrayList<(Int, Int, Int, Int) -> Unit>()
    /** Al cambiar lo vectorial (lugares, ríos, rótulos…). */
    val alCambiarVector = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var vectorVersion = 0

    fun dentro(cx: Int, cy: Int) = infinito || (cx in 0 until anchoTrozos && cy in 0 until altoTrozos)
    fun dentroPx(x: Int, y: Int) = infinito || (x in 0 until ancho && y in 0 until alto)

    // ---------------------------------------------------------------- trozos

    /** El trozo (lo crea, lo lee del disco o lo genera si hace falta). Null fuera de un mapa finito. */
    fun trozo(cx: Int, cy: Int): Trozo? {
        if (!dentro(cx, cy)) return null
        val k = Trozo.clave(cx, cy)
        trozos[k]?.let { return it }
        synchronized(this) {
            trozos[k]?.let { return it }
            val t = (if (k in enDisco) Formato.leeTrozo(archivoTrozo(cx, cy), cx, cy) else null) ?: generador.genera(cx, cy)
            trozos[k] = t
            return t
        }
    }

    fun trozoSiEsta(cx: Int, cy: Int): Trozo? = trozos[Trozo.clave(cx, cy)]
    fun trozosEnMemoria(): Collection<Trozo> = trozos.values
    fun clavesEnDisco(): Set<Long> = enDisco

    internal fun archivoTrozo(cx: Int, cy: Int) = File(dir, "trozos/${cx}_${cy}.bin")
    internal fun apuntaEnDisco(cx: Int, cy: Int) { enDisco += Trozo.clave(cx, cy) }

    /** Un trozo cambiado: lo marca, quita sus miniaturas y avisa al render. */
    fun tocado(t: Trozo) {
        t.version++; t.sucio = true; t.editado = true
        modificado = System.currentTimeMillis()
    }

    fun avisa(x0: Int, y0: Int, x1: Int, y1: Int) { alCambiar.forEach { it(x0, y0, x1, y1) } }
    fun avisaVector() { vectorVersion++; modificado = System.currentTimeMillis(); alCambiarVector.forEach { it() } }

    /** La versión reducida de un trozo, para el nivel [nivel] (1: la mitad … 6: 4×4). */
    fun mip(cx: Int, cy: Int, nivel: Int): Mip? {
        if (!dentro(cx, cy)) return null
        val k = Trozo.clave(cx, cy) * 8 + nivel
        val t = trozos[Trozo.clave(cx, cy)]
        val m = mips[k]
        if (t == null) {
            // Sin tocar y sin leer: si está en el disco hay que leerlo; si no, el generador lo da directamente a esta escala
            if (m != null) return m
            if (Trozo.clave(cx, cy) in enDisco) return mipDe(trozo(cx, cy)!!, nivel).also { mips[k] = it }
            return generador.mip(cx, cy, nivel).also { mips[k] = it }
        }
        if (m != null && m.version == t.version && m.version >= 0) return m
        return mipDe(t, nivel).also { mips[k] = it }
    }

    private fun mipDe(t: Trozo, nivel: Int): Mip {
        val m = Mip(nivel, t.version)
        val l = m.lado; val f = 1 shl nivel
        val inv = 1f / (f * f)
        for (y in 0 until l) for (x in 0 until l) {
            var s = 0f
            val bx = x * f; val by = y * f
            for (j in 0 until f) { val o = (by + j) * Trozo.LADO + bx; for (i in 0 until f) s += t.alt[o + i] }
            val i = y * l + x
            m.alt[i] = s * inv
            val c = (by + f / 2) * Trozo.LADO + bx + f / 2
            m.bio[i] = t.bio[c]; m.reino[i] = t.reino[c]; m.region[i] = t.region[c]; m.zona[i] = t.zona[c]
        }
        return m
    }

    /** Olvida lo que no hace falta tener en memoria (lo que está guardado y no se ve). */
    fun aligera(mantener: (Int, Int) -> Boolean) {
        for ((k, t) in trozos) if (!t.sucio && (t.editado && k in enDisco || !t.editado) && !mantener(t.cx, t.cy)) trozos.remove(k)
        if (mips.size > 6000) mips.clear()
    }

    // ---------------------------------------------------------------- lectura punto a punto

    fun alt(x: Int, y: Int): Float {
        val t = trozo(x shr Trozo.BITS, y shr Trozo.BITS) ?: return -0.3f
        return t.alt[(y and 0xff) * Trozo.LADO + (x and 0xff)]
    }

    fun bio(x: Int, y: Int): Int { val t = trozo(x shr Trozo.BITS, y shr Trozo.BITS) ?: return 0; return t.bio[(y and 0xff) * Trozo.LADO + (x and 0xff)].toInt() and 0xff }
    fun reino(x: Int, y: Int): Int { val t = trozo(x shr Trozo.BITS, y shr Trozo.BITS) ?: return 0; return t.reino[(y and 0xff) * Trozo.LADO + (x and 0xff)].toInt() and 0xff }
    fun region(x: Int, y: Int): Int { val t = trozo(x shr Trozo.BITS, y shr Trozo.BITS) ?: return 0; return t.region[(y and 0xff) * Trozo.LADO + (x and 0xff)].toInt() and 0xff }
    fun zona(x: Int, y: Int): Int { val t = trozo(x shr Trozo.BITS, y shr Trozo.BITS) ?: return 0; return t.zona[(y and 0xff) * Trozo.LADO + (x and 0xff)].toInt() and 0xff }

    /** Altura con interpolación (x, y en píxeles, con decimales). */
    fun altSuave(x: Float, y: Float): Float {
        val fx = floor(x - 0.5f); val fy = floor(y - 0.5f)
        val ix = fx.toInt(); val iy = fy.toInt(); val tx = x - 0.5f - fx; val ty = y - 0.5f - fy
        val a = alt(ix, iy); val b = alt(ix + 1, iy); val c = alt(ix, iy + 1); val d = alt(ix + 1, iy + 1)
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    }

    // ---------------------------------------------------------------- ids y nombres

    fun reino(id: Int) = reinos.firstOrNull { it.id == id }
    fun region(id: Int) = regiones.firstOrNull { it.id == id }
    fun idLibreReino(): Int = (1..255).firstOrNull { i -> reinos.none { it.id == i } } ?: 255
    fun idLibreRegion(): Int = (1..255).firstOrNull { i -> regiones.none { it.id == i } } ?: 255
    fun nuevoId(prefijo: String) = prefijo + java.lang.Long.toString(System.nanoTime() xor (Math.random() * 1e15).toLong(), 36).takeLast(9)

    /** Los límites de lo que hay que mirar (para un mapa infinito, los trozos tocados más un margen). */
    fun limites(): IntArray {
        if (!infinito) return intArrayOf(0, 0, ancho, alto)
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        val todas = HashSet<Long>(enDisco); trozos.values.filter { it.editado }.forEach { todas += Trozo.clave(it.cx, it.cy) }
        for (k in todas) { val cx = Trozo.cx(k); val cy = Trozo.cy(k); x0 = min(x0, cx); y0 = min(y0, cy); x1 = max(x1, cx + 1); y1 = max(y1, cy + 1) }
        lugares.forEach { x0 = min(x0, floor(it.x / Trozo.LADO).toInt()); y0 = min(y0, floor(it.y / Trozo.LADO).toInt()); x1 = max(x1, floor(it.x / Trozo.LADO).toInt() + 1); y1 = max(y1, floor(it.y / Trozo.LADO).toInt() + 1) }
        if (x0 > x1) return intArrayOf(-4 * Trozo.LADO, -3 * Trozo.LADO, 4 * Trozo.LADO, 3 * Trozo.LADO)
        return intArrayOf(x0 * Trozo.LADO, y0 * Trozo.LADO, x1 * Trozo.LADO, y1 * Trozo.LADO)
    }

    // ---------------------------------------------------------------- lo vectorial en JSON (para guardar y para deshacer)

    fun vectorJson(): JSONObject = JSONObject()
        .put("reinos", JSONArray().also { a -> reinos.forEach { a.put(it.json()) } })
        .put("regiones", JSONArray().also { a -> regiones.forEach { a.put(it.json()) } })
        .put("lugares", JSONArray().also { a -> lugares.forEach { a.put(it.json()) } })
        .put("rios", JSONArray().also { a -> rios.forEach { a.put(it.json()) } })
        .put("caminos", JSONArray().also { a -> caminos.forEach { a.put(it.json()) } })
        .put("rotulos", JSONArray().also { a -> rotulos.forEach { a.put(it.json()) } })
        .put("sellos", JSONArray().also { a -> sellos.forEach { a.put(it.json()) } })

    fun ponVector(o: JSONObject) {
        fun <T> lista(n: String, dst: MutableList<T>, f: (JSONObject) -> T) {
            val a = o.optJSONArray(n) ?: JSONArray(); val l = ArrayList<T>(a.length())
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { l += f(it) }
            dst.clear(); dst.addAll(l)
        }
        lista("reinos", reinos, Reino::de); lista("regiones", regiones, Region::de); lista("lugares", lugares, Lugar::de)
        lista("rios", rios, Linea::de); lista("caminos", caminos, Linea::de); lista("rotulos", rotulos, Rotulo::de); lista("sellos", sellos, Sello::de)
    }

    fun metaJson(): JSONObject = JSONObject().put("v", 1).put("id", id).put("mundo", mundo).put("nombre", nombre).put("semilla", semilla)
        .put("estilo", estilo.name).put("w", anchoTrozos).put("h", altoTrozos).put("tierra", tierra.toDouble()).put("gen", Generador.VERSION)
        .put("km", kmPorPx.toDouble()).put("kmPuesta", escalaPuesta).put("obras", JSONArray(obras.toList())).put("creado", creado).put("modificado", modificado)
        .put("vista", JSONArray().also { a -> vista.forEach { a.put(if (it.isNaN()) JSONObject.NULL else it.toDouble()) } })

    companion object {
        /** Lee un mapa de su carpeta (solo lo de arriba; los trozos se leen al mirarlos). */
        fun lee(dir: File): Mapa? = Formato.leeMapa(dir)
    }
}
