package com.gonzalo.atlas.motor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Qué capa toca cada herramienta. */
enum class Capa { ALT, BIO, REINO, REGION, ZONA }

/** Las herramientas de pintar el mapa (las ráster). */
enum class Herramienta(val nombre: String, val capa: Capa, val desc: String) {
    TIERRA("Tierra", Capa.ALT, "Saca tierra del mar, con costa natural"),
    MAR("Mar", Capa.ALT, "Hunde la tierra bajo el agua"),
    SUBIR("Subir", Capa.ALT, "Levanta el terreno poco a poco"),
    BAJAR("Bajar", Capa.ALT, "Rebaja el terreno"),
    MONTANAS("Montañas", Capa.ALT, "Picos y crestas"),
    COLINAS("Colinas", Capa.ALT, "Lomas suaves"),
    VALLE("Valle", Capa.ALT, "Valle en U, de laderas suaves"),
    CANON("Cañón", Capa.ALT, "Tajo hondo de paredes verticales y fondo plano"),
    ACANTILADO("Acantilado", Capa.ALT, "Un escalón vertical a lo largo del trazo (sube a la izquierda)"),
    MESETA("Meseta", Capa.ALT, "Llanura alta de bordes cortados"),
    ALLANAR("Allanar", Capa.ALT, "Iguala a la altura donde empiezas"),
    SUAVIZAR("Suavizar", Capa.ALT, "Redondea y quita aristas"),
    RUGOSIDAD("Rugosidad", Capa.ALT, "Añade detalle al relieve"),
    BIOMA("Bioma", Capa.BIO, "Pinta el suelo"),
    REINO("Reino", Capa.REINO, "Pinta el territorio de un reino"),
    REGION("Región", Capa.REGION, "Pinta una región con nombre"),
    ZONA("Zona", Capa.ZONA, "Ciudades, templos, minas… se llenan solas de edificios"),
}

class Pincel(
    var herramienta: Herramienta = Herramienta.TIERRA,
    /** Radio en píxeles del mapa. */
    var radio: Float = 40f,
    /** 0..1 */
    var fuerza: Float = 0.6f,
    /** 0 (difuminado) .. 1 (borde duro) */
    var dureza: Float = 0.3f,
    /** El bioma, reino, región o zona que se pinta (0 = borrar). */
    var valor: Int = 1,
    /** La presión del lápiz cambia el tamaño (si no, solo la fuerza). */
    var presionTamano: Boolean = true,
    /** Para BIOMA/REINO/ZONA: solo sobre tierra. */
    var soloTierra: Boolean = true,
)

/** Una copia de las capas de un trozo (para deshacer). */
class Copia(val cx: Int, val cy: Int, val alt: FloatArray?, val bio: ByteArray?, val reino: ByteArray?, val region: ByteArray?, val zona: ByteArray?) {
    val bytes: Long get() = (alt?.size?.toLong() ?: 0) * 4 + (bio?.size ?: 0) + (reino?.size ?: 0) + (region?.size ?: 0) + (zona?.size ?: 0)
    fun pon(t: Trozo) {
        alt?.copyInto(t.alt); bio?.copyInto(t.bio); reino?.copyInto(t.reino); region?.copyInto(t.region); zona?.copyInto(t.zona)
    }
    companion object {
        fun de(t: Trozo, capas: Set<Capa>) = Copia(t.cx, t.cy,
            if (Capa.ALT in capas) t.alt.copyOf() else null, if (Capa.BIO in capas) t.bio.copyOf() else null,
            if (Capa.REINO in capas) t.reino.copyOf() else null, if (Capa.REGION in capas) t.region.copyOf() else null,
            if (Capa.ZONA in capas) t.zona.copyOf() else null)
    }
}

sealed class Edicion(val nombre: String) {
    class Raster(nombre: String, val antes: List<Copia>, val despues: List<Copia>, val rect: IntArray) : Edicion(nombre)
    class Vector(nombre: String, val antes: String, val despues: String) : Edicion(nombre)
}

/** Deshacer y rehacer (con un tope de memoria: lo más viejo se olvida). */
class Historial(private val mapa: Mapa, private val tope: Long = 160L shl 20) {
    private val atras = ArrayDeque<Edicion>()
    private val alante = ArrayDeque<Edicion>()
    var alCambiar: () -> Unit = {}
    val puedeDeshacer get() = atras.isNotEmpty()
    val puedeRehacer get() = alante.isNotEmpty()
    val ultima: String get() = atras.lastOrNull()?.nombre.orEmpty()

    private fun peso(e: Edicion): Long = when (e) { is Edicion.Raster -> (e.antes + e.despues).sumOf { it.bytes }; is Edicion.Vector -> (e.antes.length + e.despues.length) * 2L }

    @Synchronized fun apunta(e: Edicion) {
        atras.addLast(e); alante.clear()
        var total = atras.sumOf { peso(it) }
        while (total > tope && atras.size > 1) total -= peso(atras.removeFirst())
        alCambiar()
    }

    /** Apunta un cambio de lo vectorial: [antes] es el JSON de antes de cambiarlo. */
    fun apuntaVector(nombre: String, antes: String) = apunta(Edicion.Vector(nombre, antes, mapa.vectorJson().toString()))

    @Synchronized fun deshacer(): Boolean { val e = atras.removeLastOrNull() ?: return false; aplica(e, true); alante.addLast(e); alCambiar(); return true }
    @Synchronized fun rehacer(): Boolean { val e = alante.removeLastOrNull() ?: return false; aplica(e, false); atras.addLast(e); alCambiar(); return true }

    private fun aplica(e: Edicion, hacia_atras: Boolean) {
        when (e) {
            is Edicion.Raster -> {
                for (c in if (hacia_atras) e.antes else e.despues) { val t = mapa.trozo(c.cx, c.cy) ?: continue; synchronized(t) { c.pon(t) }; mapa.tocado(t) }
                mapa.avisa(e.rect[0], e.rect[1], e.rect[2], e.rect[3])
            }
            is Edicion.Vector -> { mapa.ponVector(org.json.JSONObject(if (hacia_atras) e.antes else e.despues)); mapa.avisaVector() }
        }
    }
}

/**
 * Un trazo con el pincel (del dedo o el lápiz abajo hasta arriba). Cada pasada guarda en cada píxel la «cobertura» máxima
 * alcanzada en este trazo y calcula el valor nuevo a partir del de antes del trazo: así, pasar dos veces por el mismo sitio
 * no hace manchas ni escalones (como la opacidad por trazo de Procreate).
 */
class Trazo(private val mapa: Mapa, private val p: Pincel, private val ruido: RuidoTabla = RUIDO) {
    private val capas: Set<Capa> = setOf(p.herramienta.capa)
    private val antes = HashMap<Long, Copia>()
    private val cobertura = HashMap<Long, FloatArray>()
    private var ux = Float.NaN; private var uy = Float.NaN
    private var dx = 1f; private var dy = 0f
    private var objetivo = Float.NaN
    private val rect = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
    private var resto = 0f
    val vacio get() = antes.isEmpty()

    /** Añade un punto del trazo (en píxeles del mapa). [presion] 0..1. Devuelve la zona cambiada (o null). */
    fun punto(x: Float, y: Float, presion: Float = 1f): IntArray? {
        val pr = presion.coerceIn(0.05f, 1f)
        val r = if (p.presionTamano) p.radio * (0.35f + 0.65f * pr) else p.radio
        if (objetivo.isNaN()) objetivo = mapa.altSuave(x, y).let { if (p.herramienta == Herramienta.MESETA) max(it, 0.02f) + 0.12f * p.fuerza + 0.04f else it }
        val cambio = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        if (ux.isNaN()) {
            pasada(x, y, r, pr, cambio)
        } else {
            val d = hypot(x - ux, y - uy)
            if (d > 0.01f) { dx = (x - ux) / d; dy = (y - uy) / d }
            val paso = max(1f, r * 0.18f)
            var t = paso - resto
            while (t <= d) {
                pasada(ux + (x - ux) * t / d, uy + (y - uy) * t / d, r, pr, cambio)
                t += paso
            }
            resto = d - (t - paso)
        }
        ux = x; uy = y
        if (cambio[0] > cambio[2]) return null
        mapa.avisa(cambio[0], cambio[1], cambio[2], cambio[3])
        return cambio
    }

    private fun copiaAntes(t: Trozo) {
        val k = Trozo.clave(t.cx, t.cy)
        if (k !in antes) antes[k] = Copia.de(t, capas)
    }

    private fun pasada(cx: Float, cy: Float, r: Float, pr: Float, cambio: IntArray) {
        val x0 = floor(cx - r - 2).toInt(); val y0 = floor(cy - r - 2).toInt(); val x1 = floor(cx + r + 2).toInt(); val y1 = floor(cy + r + 2).toInt()
        val h = p.herramienta
        val ox = -dy; val oy = dx // a la izquierda del trazo
        for (tcy in (y0 shr Trozo.BITS)..(y1 shr Trozo.BITS)) for (tcx in (x0 shr Trozo.BITS)..(x1 shr Trozo.BITS)) {
            val t = mapa.trozo(tcx, tcy) ?: continue
            copiaAntes(t)
            val k = Trozo.clave(tcx, tcy)
            val ant = antes[k]!!
            val cov = cobertura.getOrPut(k) { FloatArray(Trozo.N) }
            val bx = tcx shl Trozo.BITS; val by = tcy shl Trozo.BITS
            val ix0 = max(x0, bx) - bx; val ix1 = min(x1, bx + Trozo.LADO - 1) - bx
            val iy0 = max(y0, by) - by; val iy1 = min(y1, by + Trozo.LADO - 1) - by
            if (ix0 > ix1 || iy0 > iy1) continue
            synchronized(t) {
                for (iy in iy0..iy1) for (ix in ix0..ix1) {
                    val px = bx + ix + 0.5f; val py = by + iy + 0.5f
                    val ddx = px - cx; val ddy = py - cy
                    val d = sqrt(ddx * ddx + ddy * ddy) / r
                    if (d >= 1f) continue
                    var f = 1f - Generador.suave(p.dureza * 0.95f, 1f, d)
                    if (h == Herramienta.ACANTILADO) {
                        // El escalón: lo de la izquierda del trazo sube; en una franja de ~2 px pasa de abajo a arriba
                        val lado = ddx * ox + ddy * oy
                        f *= Generador.suave(-1.6f, 1.6f, lado)
                    }
                    f *= pr
                    val i = iy * Trozo.LADO + ix
                    if (h == Herramienta.SUAVIZAR) { suaviza(t, ix, iy, i, f * p.fuerza); continue }
                    if (f <= cov[i]) continue
                    cov[i] = f
                    aplicaPixel(t, ant, i, px, py, f)
                }
            }
            mapa.tocado(t)
            cambio[0] = min(cambio[0], bx + ix0); cambio[1] = min(cambio[1], by + iy0); cambio[2] = max(cambio[2], bx + ix1 + 1); cambio[3] = max(cambio[3], by + iy1 + 1)
        }
        rect[0] = min(rect[0], cambio[0]); rect[1] = min(rect[1], cambio[1]); rect[2] = max(rect[2], cambio[2]); rect[3] = max(rect[3], cambio[3])
    }

    private fun suaviza(t: Trozo, ix: Int, iy: Int, i: Int, f: Float) {
        if (ix == 0 || iy == 0 || ix == Trozo.LADO - 1 || iy == Trozo.LADO - 1) return
        val a = t.alt
        val m = (a[i - 1] + a[i + 1] + a[i - Trozo.LADO] + a[i + Trozo.LADO] + a[i - Trozo.LADO - 1] + a[i - Trozo.LADO + 1] + a[i + Trozo.LADO - 1] + a[i + Trozo.LADO + 1]) / 8f
        // Sin cruzar el nivel del mar (si no, la costa se mueve sola)
        val nuevo = a[i] + (m - a[i]) * (f * 0.6f).coerceAtMost(1f)
        a[i] = if ((a[i] > 0f) != (nuevo > 0f)) a[i] else nuevo
    }

    private fun aplicaPixel(t: Trozo, ant: Copia, i: Int, x: Float, y: Float, f: Float) {
        val s = p.fuerza
        val n = ruido.suave(x / 9f, y / 9f)          // −1..1, detalle fino
        val n2 = ruido.suave(x / 41f + 17f, y / 41f - 5f)
        when (p.herramienta.capa) {
            Capa.ALT -> {
                val a0 = ant.alt!![i]
                t.alt[i] = when (p.herramienta) {
                    Herramienta.TIERRA -> {
                        val c = Generador.suave(0.25f, 0.75f, f + n2 * 0.25f + n * 0.08f)
                        if (a0 >= 0.01f) a0 else a0 + (max(a0, 0.012f + 0.03f * s + 0.02f * (n2 + 1f)) - a0) * c
                    }
                    Herramienta.MAR -> {
                        val c = Generador.suave(0.25f, 0.75f, f + n2 * 0.25f + n * 0.08f)
                        if (a0 <= -0.02f) a0 else a0 + (min(a0, -0.03f - 0.12f * s + 0.02f * n2) - a0) * c
                    }
                    Herramienta.SUBIR -> a0 + s * f * 0.22f
                    Herramienta.BAJAR -> {
                        val v = a0 - s * f * 0.22f
                        if (a0 > 0f && v <= 0f && p.soloTierra) 0.002f else v
                    }
                    Herramienta.MONTANAS -> {
                        val cr = MONTES.crestas(x / 85f, y / 85f, 5)
                        a0 + s * f * (0.08f + 0.62f * cr * cr) * if (a0 < 0f) 0.5f else 1f
                    }
                    Herramienta.COLINAS -> a0 + s * f * (0.035f + 0.07f * (MONTES.fbm(x / 60f, y / 60f, 3) + 1f)) * if (a0 < 0f) 0.4f else 1f
                    Herramienta.VALLE -> {
                        val v = a0 - s * f * f * 0.2f
                        if (a0 > 0f) max(v, min(a0, 0.004f)) else v
                    }
                    Herramienta.CANON -> {
                        // Paredes casi verticales: la cobertura se convierte en un escalón estrecho; fondo plano con algo de ruido
                        val c = Generador.suave(0.32f + n * 0.04f, 0.42f + n * 0.04f, f)
                        val fondo = if (a0 > 0f) max(0.003f, a0 - 0.12f - 0.25f * s) else a0 - 0.1f - 0.25f * s
                        a0 + (fondo - a0) * c
                    }
                    Herramienta.ACANTILADO -> a0 + s * 0.28f * f
                    Herramienta.MESETA -> {
                        val c = Generador.suave(0.38f, 0.5f, f + n * 0.05f)
                        val o = objetivo + n2 * 0.006f
                        if (o > a0) a0 + (o - a0) * c else a0
                    }
                    Herramienta.ALLANAR -> a0 + (objetivo - a0) * f * s.coerceAtLeast(0.2f)
                    Herramienta.RUGOSIDAD -> a0 + s * f * 0.05f * (MONTES.fbm(x / 14f, y / 14f, 3)) * if (a0 > 0f) 1f else 0.5f
                    else -> a0
                }.coerceIn(-1f, 1f)
            }
            else -> {
                // Pintar ids: borde irregular (la cobertura con ruido) para que no se vea el círculo del pincel
                val c = f + n * 0.18f + n2 * 0.12f
                if (c < 0.5f) return
                if (p.soloTierra && p.herramienta != Herramienta.BIOMA && t.alt[i] < 0f) return
                if (p.soloTierra && p.herramienta == Herramienta.BIOMA && t.alt[i] < 0f && !aguaBioma(p.valor)) return
                val v = p.valor.toByte()
                when (p.herramienta.capa) {
                    Capa.BIO -> t.bio[i] = v
                    Capa.REINO -> t.reino[i] = v
                    Capa.REGION -> t.region[i] = v
                    Capa.ZONA -> t.zona[i] = v
                    else -> {}
                }
            }
        }
    }

    private fun aguaBioma(id: Int) = id == Catalogo.B_ARRECIFE || id == Catalogo.B_HIELO || id == 0

    /** Termina el trazo: lo que hay que apuntar para deshacer (null si no ha tocado nada). */
    fun termina(nombre: String = p.herramienta.nombre): Edicion.Raster? {
        if (antes.isEmpty()) return null
        val despues = antes.values.map { c -> val t = mapa.trozo(c.cx, c.cy)!!; synchronized(t) { Copia.de(t, capas) } }
        return Edicion.Raster(nombre, antes.values.toList(), despues, rect.copyOf())
    }

    companion object {
        val RUIDO by lazy { RuidoTabla(256, 99, 16f, 3) }
        val MONTES by lazy { Ruido(4242) }
    }
}

/** Los accidentes geográficos que se ponen de un toque: modifican relieve y bioma a la vez (y se deshacen de una vez). */
class Estampa(private val mapa: Mapa) {
    private val antes = HashMap<Long, Copia>()
    private val rect = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
    private val r = Ruido(777)

    private inline fun recorre(cx: Float, cy: Float, rad: Float, f: (t: Trozo, i: Int, x: Float, y: Float, d: Float, ang: Float) -> Unit) {
        val x0 = floor(cx - rad - 1).toInt(); val y0 = floor(cy - rad - 1).toInt(); val x1 = floor(cx + rad + 1).toInt(); val y1 = floor(cy + rad + 1).toInt()
        for (tcy in (y0 shr Trozo.BITS)..(y1 shr Trozo.BITS)) for (tcx in (x0 shr Trozo.BITS)..(x1 shr Trozo.BITS)) {
            val t = mapa.trozo(tcx, tcy) ?: continue
            val k = Trozo.clave(tcx, tcy)
            if (k !in antes) antes[k] = Copia.de(t, setOf(Capa.ALT, Capa.BIO))
            val bx = tcx shl Trozo.BITS; val by = tcy shl Trozo.BITS
            synchronized(t) {
                for (iy in max(y0, by) - by..min(y1, by + Trozo.LADO - 1) - by) for (ix in max(x0, bx) - bx..min(x1, bx + Trozo.LADO - 1) - bx) {
                    val x = bx + ix + 0.5f; val y = by + iy + 0.5f
                    val d = hypot(x - cx, y - cy) / rad
                    if (d < 1f) f(t, iy * Trozo.LADO + ix, x, y, d, atan2(y - cy, x - cx))
                }
            }
            mapa.tocado(t)
        }
        rect[0] = min(rect[0], x0); rect[1] = min(rect[1], y0); rect[2] = max(rect[2], x1 + 1); rect[3] = max(rect[3], y1 + 1)
    }

    private fun s(a: Float, b: Float, x: Float) = Generador.suave(a, b, x)

    /** Pone [clave] (de [Catalogo.ACCIDENTES]) en (x, y) con radio [rad]. */
    fun pon(clave: String, x: Float, y: Float, rad: Float, semilla: Int = (x * 7 + y * 13).toInt()): Edicion.Raster? {
        val base = mapa.altSuave(x, y)
        val sem = semilla.toFloat() * 0.013f
        when (clave) {
            "volcan" -> recorre(x, y, rad) { t, i, px, py, d, ang ->
                val irreg = r.fbm(ang * 1.3f + sem, d * 2f, 3) * 0.12f
                val dd = d * (1f + irreg)
                val cono = (1f - dd).coerceAtLeast(0f).let { it * it * (3 - 2 * it) } * 0.55f
                val crater = s(0.2f, 0.08f, dd) * 0.22f
                val a = max(t.alt[i], max(base, 0.01f) * (1f - cono * 1.2f).coerceAtLeast(0f) + cono - crater)
                t.alt[i] = a.coerceAtMost(1f)
                val surco = abs(r.ruido(ang * 3f + sem * 5f, 0.5f))
                t.bio[i] = when {
                    dd < 0.12f -> Catalogo.B_LAVA
                    dd < 0.75f && surco < 0.06f && Ruido.hash01((ang * 40).toInt(), 3) > 0.3f -> Catalogo.B_LAVA
                    dd < 0.55f -> Catalogo.B_CENIZA
                    dd < 0.9f -> Catalogo.B_BASALTO
                    else -> t.bio[i].toInt()
                }.toByte()
            }
            "caldera" -> recorre(x, y, rad) { t, i, _, _, d, ang ->
                val dd = d * (1f + r.fbm(ang + sem, 0f, 2) * 0.08f)
                val anillo = s(1f, 0.7f, dd) * s(0.4f, 0.62f, dd) * 0.3f
                val dentro = s(0.55f, 0.45f, dd)
                t.alt[i] = (max(t.alt[i], max(base, 0.02f) + anillo) * (1 - dentro) + (max(base, 0.02f) + 0.05f) * dentro).coerceAtMost(1f)
                t.bio[i] = (if (dd < 0.45f) Catalogo.B_AGUA else if (dd < 0.85f) Catalogo.B_BASALTO else t.bio[i].toInt()).toByte()
            }
            "crater" -> recorre(x, y, rad) { t, i, _, _, d, ang ->
                val dd = d * (1f + r.fbm(ang * 2f + sem, 0f, 2) * 0.06f)
                val borde = s(1f, 0.75f, dd) * s(0.55f, 0.75f, dd) * 0.12f
                val hoyo = s(0.7f, 0.2f, dd) * 0.14f
                t.alt[i] = t.alt[i] + borde - hoyo
                if (dd < 0.7f && t.alt[i] > 0f) t.bio[i] = Catalogo.B_ROCA.toByte()
            }
            "isla", "archipielago", "atolon" -> {
                val centros = if (clave == "archipielago") List(9) { k ->
                    val a = k * 2.4f + sem; val dist = Ruido.hash01(k, semilla) * 0.75f
                    floatArrayOf(x + cos(a) * rad * dist, y + sin(a) * rad * dist, rad * (0.12f + Ruido.hash01(k, semilla, 3) * 0.2f))
                } else listOf(floatArrayOf(x, y, rad))
                for (c in centros) recorre(c[0], c[1], c[2]) { t, i, px, py, d, ang ->
                    val dd = d * (1f + r.fbm(px / 40f + sem, py / 40f, 4) * (if (clave == "archipielago") 0.8f else 0.45f))
                    if (clave == "atolon") {
                        val anillo = s(0.62f, 0.75f, dd) * s(0.95f, 0.85f, dd)
                        if (anillo > 0.5f) { t.alt[i] = max(t.alt[i], 0.006f); t.bio[i] = Catalogo.B_PLAYA.toByte() }
                        else if (dd < 0.95f) { t.alt[i] = max(min(t.alt[i], -0.01f), -0.04f); t.bio[i] = (if (dd < 0.62f) 0 else Catalogo.B_ARRECIFE).toByte() }
                    } else {
                        val h = (1f - dd).coerceAtLeast(0f)
                        if (h > 0f) {
                            val a = 0.01f + h * h * 0.35f + r.crestas(px / 50f, py / 50f, 3) * h * 0.2f
                            t.alt[i] = max(t.alt[i], a - 0.01f * (1 - h) * 4)
                            if (t.alt[i] > 0f && t.alt[i] < 0.02f) t.bio[i] = Catalogo.B_PLAYA.toByte()
                            else if (t.alt[i] >= 0.02f && (t.bio[i].toInt() == 0 || t.bio[i].toInt() == Catalogo.B_PLAYA)) t.bio[i] = Catalogo.B_PRADERA.toByte()
                        }
                    }
                }
            }
            "arrecife" -> recorre(x, y, rad) { t, i, px, py, d, _ ->
                if (t.alt[i] < 0.002f && d + r.fbm(px / 15f, py / 15f, 3) * 0.3f < 0.8f) {
                    t.alt[i] = max(t.alt[i], -0.035f); t.bio[i] = Catalogo.B_ARRECIFE.toByte()
                }
            }
            "lago", "oasis" -> recorre(x, y, rad) { t, i, px, py, d, _ ->
                val dd = d * (1f + r.fbm(px / 30f + sem, py / 30f, 3) * 0.4f)
                if (dd < 0.55f) { t.bio[i] = Catalogo.B_AGUA.toByte(); t.alt[i] = max(0.003f, min(t.alt[i], base) - 0.004f * (1 - dd)) }
                else if (clave == "oasis" && dd < 1f) t.bio[i] = (if (dd < 0.8f) Catalogo.B_SELVA else Catalogo.B_SABANA).toByte()
            }
            "geiser" -> recorre(x, y, rad) { t, i, px, py, d, _ ->
                val dd = d + r.fbm(px / 25f + sem, py / 25f, 3) * 0.3f
                if (dd > 0.9f) return@recorre
                // Costra de sal y azufre, con pozas termales
                val cel = Ruido.hash01((px / 9).toInt(), (py / 9).toInt(), semilla)
                val fx = (px / 9f) % 1f; val fy = (py / 9f) % 1f
                val poza = cel > 0.82f && (fx - 0.5f) * (fx - 0.5f) + (fy - 0.5f) * (fy - 0.5f) < 0.06f
                t.bio[i] = (if (poza) Catalogo.B_AGUA else if (r.ruido(px / 14f, py / 14f) > 0.35f) Catalogo.B_YERMO else Catalogo.B_SALAR).toByte()
            }
            "meseta" -> recorre(x, y, rad) { t, i, px, py, d, _ ->
                val dd = d * (1f + r.fbm(px / 45f + sem, py / 45f, 4) * 0.35f)
                val c = s(0.82f, 0.7f, dd)
                val o = max(base, 0.02f) + 0.16f + r.fbm(px / 120f, py / 120f, 2) * 0.01f
                if (o > t.alt[i]) t.alt[i] = t.alt[i] + (o - t.alt[i]) * c
            }
            "sima" -> recorre(x, y, rad) { t, i, px, py, d, _ ->
                val dd = d * (1f + r.fbm(px / 20f + sem, py / 20f, 3) * 0.3f)
                val c = s(0.5f, 0.35f, dd)
                t.alt[i] = t.alt[i] - c * 0.3f
                if (dd < 0.6f) t.bio[i] = Catalogo.B_ROCA.toByte()
            }
            "delta" -> recorre(x, y, rad) { t, i, px, py, d, ang ->
                // Abanico de islotes bajos, brazos de agua y marismas
                val canal = abs(r.fbm(px / 22f + sem, py / 22f, 3))
                val ancho = 0.035f * (0.4f + d)
                if (d < 0.95f) {
                    if (canal < ancho) { t.alt[i] = min(t.alt[i], -0.006f); t.bio[i] = 0 }
                    else if (t.alt[i] > -0.03f) { t.alt[i] = max(0.002f, min(max(t.alt[i], 0.003f), 0.012f + (1 - d) * 0.01f)); t.bio[i] = (if (canal < ancho * 3f) Catalogo.B_PANTANO else Catalogo.B_PRADERA).toByte() }
                }
            }
            "dunas" -> recorre(x, y, rad) { t, i, px, py, d, _ ->
                if (t.alt[i] > 0f && d + r.fbm(px / 30f, py / 30f, 3) * 0.3f < 0.9f) t.bio[i] = Catalogo.B_DUNAS.toByte()
            }
            "salar" -> recorre(x, y, rad) { t, i, px, py, d, _ ->
                val dd = d * (1f + r.fbm(px / 40f + sem, py / 40f, 3) * 0.35f)
                if (t.alt[i] > 0f && dd < 0.9f) { t.bio[i] = Catalogo.B_SALAR.toByte(); t.alt[i] = t.alt[i] + (max(base, 0.01f) - t.alt[i]) * s(0.9f, 0.6f, dd) }
            }
            else -> return null
        }
        if (antes.isEmpty()) return null
        mapa.avisa(rect[0], rect[1], rect[2], rect[3])
        val despues = antes.values.map { c -> Copia.de(mapa.trozo(c.cx, c.cy)!!, setOf(Capa.ALT, Capa.BIO)) }
        return Edicion.Raster(Catalogo.accidente(clave).nombre, antes.values.toList(), despues, rect.copyOf())
    }

    /** Los accidentes que se dibujan con un trazo: glaciar, cordillera, fiordo y arrecife (a lo largo de la línea). */
    fun alLargo(clave: String, xs: FloatArray, ys: FloatArray, rad: Float): Edicion.Raster? {
        if (xs.isEmpty()) return null
        // Se recorre la línea a pasos regulares (no solo sus puntos)
        val paso = max(1f, rad * 0.3f)
        val px = ArrayList<Float>(); val py = ArrayList<Float>()
        px += xs[0]; py += ys[0]
        var resto = 0f
        for (k in 1 until xs.size) {
            val d = hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1])
            var t = paso - resto
            while (t <= d) { px += xs[k - 1] + (xs[k] - xs[k - 1]) * t / d; py += ys[k - 1] + (ys[k] - ys[k - 1]) * t / d; t += paso }
            resto = d - (t - paso)
        }
        for (k in px.indices) {
            val x = px[k]; val y = py[k]
            when (clave) {
                "cordillera" -> recorre(x, y, rad) { t, i, px, py, d, _ ->
                    val cr = Trazo.MONTES.crestas(px / 70f, py / 70f, 5)
                    val f = (1f - d).let { it * it }
                    t.alt[i] = max(t.alt[i], max(t.alt[i], 0.02f) + f * (0.25f + cr * 0.5f))
                    if (t.alt[i] > 0.8f) t.bio[i] = Catalogo.B_NIEVE.toByte() else if (t.alt[i] > 0.5f) t.bio[i] = Catalogo.B_ROCA.toByte()
                }
                "glaciar" -> recorre(x, y, rad) { t, i, px, py, d, _ -> if (d + r.fbm(px / 20f, py / 20f, 3) * 0.25f < 0.8f) t.bio[i] = Catalogo.B_NIEVE.toByte() }
                "fiordo" -> recorre(x, y, rad) { t, i, px, py, d, _ ->
                    val c = s(0.62f, 0.45f, d + r.fbm(px / 25f, py / 25f, 3) * 0.12f)
                    t.alt[i] = t.alt[i] + (min(t.alt[i], -0.08f) - t.alt[i]) * c
                    val pared = s(1f, 0.6f, d) * (1 - c)
                    if (t.alt[i] > 0f) t.alt[i] = t.alt[i] + pared * 0.08f
                    if (t.alt[i] < 0f) t.bio[i] = 0
                }
                "arrecife" -> recorre(x, y, rad) { t, i, px, py, d, _ ->
                    if (t.alt[i] < 0.002f && d + r.fbm(px / 15f, py / 15f, 3) * 0.3f < 0.8f) { t.alt[i] = max(t.alt[i], -0.035f); t.bio[i] = Catalogo.B_ARRECIFE.toByte() }
                }
            }
        }
        if (antes.isEmpty()) return null
        mapa.avisa(rect[0], rect[1], rect[2], rect[3])
        val despues = antes.values.map { c -> Copia.de(mapa.trozo(c.cx, c.cy)!!, setOf(Capa.ALT, Capa.BIO)) }
        return Edicion.Raster(Catalogo.accidente(clave).nombre, antes.values.toList(), despues, rect.copyOf())
    }
}

/** El bote de pintura: rellena de un reino, una región o una zona lo que está unido (la tierra, o el agua, del mismo color). */
object Relleno {
    /** Devuelve la edición, o null si no había nada que rellenar. [tope]: píxeles como mucho. */
    fun rellena(mapa: Mapa, x0: Int, y0: Int, capa: Capa, valor: Int, tope: Int = 24_000_000): Edicion.Raster? {
        if (!mapa.dentroPx(x0, y0)) return null
        fun lee(t: Trozo, i: Int): Int = when (capa) { Capa.BIO -> t.bio[i]; Capa.REINO -> t.reino[i]; Capa.REGION -> t.region[i]; Capa.ZONA -> t.zona[i]; Capa.ALT -> 0 }.toInt() and 0xff
        val t0 = mapa.trozo(x0 shr Trozo.BITS, y0 shr Trozo.BITS) ?: return null
        val i0 = (y0 and 0xff) * Trozo.LADO + (x0 and 0xff)
        val tierra = t0.alt[i0] >= 0f
        // Los reinos y las zonas solo van en tierra (las regiones sí pueden ser un mar: «Mar de Olmar»)
        if (!tierra && (capa == Capa.REINO || capa == Capa.ZONA)) return null
        val de = lee(t0, i0)
        if (de == valor) return null
        val lim = if (mapa.infinito) intArrayOf(x0 - 6000, y0 - 6000, x0 + 6000, y0 + 6000) else intArrayOf(0, 0, mapa.ancho, mapa.alto)
        val antes = HashMap<Long, Copia>()
        // Por filas (scanline): mucho menos trabajo que píxel a píxel
        val cola = ArrayDeque<Long>()
        cola.addLast((x0.toLong() shl 32) or (y0.toLong() and 0xffffffffL))
        var n = 0
        val rect = intArrayOf(x0, y0, x0 + 1, y0 + 1)
        fun ok(x: Int, y: Int): Boolean {
            if (x < lim[0] || y < lim[1] || x >= lim[2] || y >= lim[3]) return false
            val t = mapa.trozo(x shr Trozo.BITS, y shr Trozo.BITS) ?: return false
            val i = (y and 0xff) * Trozo.LADO + (x and 0xff)
            return (t.alt[i] >= 0f) == tierra && lee(t, i) == de
        }
        fun pon(x: Int, y: Int) {
            val t = mapa.trozo(x shr Trozo.BITS, y shr Trozo.BITS)!!
            val k = Trozo.clave(t.cx, t.cy)
            if (k !in antes) antes[k] = Copia.de(t, setOf(capa))
            val i = (y and 0xff) * Trozo.LADO + (x and 0xff)
            val v = valor.toByte()
            when (capa) { Capa.BIO -> t.bio[i] = v; Capa.REINO -> t.reino[i] = v; Capa.REGION -> t.region[i] = v; Capa.ZONA -> t.zona[i] = v; else -> {} }
        }
        while (cola.isNotEmpty() && n < tope) {
            val c = cola.removeFirst()
            val x = (c shr 32).toInt(); val y = c.toInt()
            if (!ok(x, y)) continue
            var l = x; while (ok(l - 1, y)) l--
            var r = x; while (ok(r + 1, y)) r++
            for (xx in l..r) pon(xx, y)
            n += r - l + 1
            rect[0] = min(rect[0], l); rect[2] = max(rect[2], r + 1); rect[1] = min(rect[1], y); rect[3] = max(rect[3], y + 1)
            for (ny in intArrayOf(y - 1, y + 1)) {
                var xx = l
                while (xx <= r) {
                    if (ok(xx, ny)) { cola.addLast((xx.toLong() shl 32) or (ny.toLong() and 0xffffffffL)); while (xx <= r && ok(xx, ny)) xx++ } else xx++
                }
            }
        }
        if (antes.isEmpty()) return null
        for (c in antes.values) mapa.tocado(mapa.trozo(c.cx, c.cy)!!)
        mapa.avisa(rect[0], rect[1], rect[2], rect[3])
        val despues = antes.values.map { c -> Copia.de(mapa.trozo(c.cx, c.cy)!!, setOf(capa)) }
        return Edicion.Raster("Relleno", antes.values.toList(), despues, rect)
    }
}
