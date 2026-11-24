package com.gonzalo.atlas.lienzo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.gonzalo.atlas.motor.Ruido
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * El motor de pinceles de ATLAS. Un trazo es una fila de estampas («sellos») de la punta del pincel a lo largo del camino
 * del lápiz; cada una con su tamaño, su opacidad, su giro y su color según la presión, la inclinación, la velocidad y el
 * azar del pincel (el modelo de Procreate: forma × grano). Todo se calcula aquí, píxel a píxel y con 16 bits de
 * transparencia (los degradados suaves no hacen bandas), así que lo que se ve mientras se dibuja es exactamente lo que
 * queda al soltar, y un trazo se puede volver a pintar a cualquier tamaño (nítido por mucho que te acerques).
 */

/** Los sellos de un trazo: dónde, de qué tamaño, cuánto y de qué color estampa la punta. En píxeles del lienzo. */
class Sellos(cap: Int = 256) {
    var n = 0; private set
    /** Por sello: x, y, diámetro, opacidad (0..1), ángulo (radianes), achatado y tope (hasta dónde puede llegar la opacidad con este sello, en los pinceles de veladura). */
    var f = FloatArray(cap * P); private set
    /** El color (RGB) y unos bits al azar (volteo, sitio del grano móvil). */
    var c = IntArray(cap); private set
    var b = IntArray(cap); private set

    fun pon(x: Float, y: Float, d: Float, a: Float, ang: Float, ach: Float, color: Int, bits: Int, tope: Float = 1f) {
        if (n == c.size) { f = f.copyOf(n * 2 * P); c = c.copyOf(n * 2); b = b.copyOf(n * 2) }
        val o = n * P
        f[o] = x; f[o + 1] = y; f[o + 2] = d; f[o + 3] = a; f[o + 4] = ang; f[o + 5] = ach; f[o + 6] = tope
        c[n] = color; b[n] = bits; n++
    }

    fun limpia() { n = 0 }

    /** La caja de todos los sellos (con su tamaño), o null si no hay. */
    fun caja(): RectF? {
        if (n == 0) return null
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until n) { val o = i * P; val e = f[o + 2] * 0.8f; r.union(f[o] - e, f[o + 1] - e, f[o] + e, f[o + 1] + e) }
        return r
    }

    companion object { const val P = 7 }
}

/**
 * Recorre el camino del lápiz y va dejando los sellos. Las muestras del lápiz se unen con curvas (no con rectas), y lo que
 * depende del azar sale del número de cada sello: el mismo trazo da siempre los mismos sellos.
 *
 * [u]: por cuánto se multiplica una distancia del lienzo para tenerla en la pantalla (para que la velocidad no dependa del
 * acercamiento). [largoTotal]: lo que mide el trazo entero si ya se sabe (para afilar el final), o un número negativo.
 * [sal] null: solo mide ([recorrido]). [muestra]: el color que hay en el lienzo en un punto (para los pinceles que mezclan).
 */
class Caminante(
    private val d: DefPincel, private val tam: Float, private val color: Int, private val semilla: Int, private val lapiz: Boolean,
    private val u: Float, private val largoTotal: Float, private val sal: Sellos?, private val muestra: ((Float, Float) -> Int)? = null,
) {
    private var n = 0
    private var ax = 0f; private var ay = 0f; private var ap = 1f; private var ai = 0f; private var aa = 0f; private var at = 0L
    private var mx = 0f; private var my = 0f; private var mp = 1f; private var mi = 0f; private var ma = 0f; private var mv = 0f
    private var vel = 0f
    var recorrido = 0f; private set
    private var falta = 0f
    private var idx = 0
    private val esp = if (d.espaciado < 0.02f) 0.07f else d.espaciado
    private val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
    private val tmpHsv = FloatArray(3)
    private val afIni: Float
    private val afFin: Float
    private var lr = 0f; private var lg = 0f; private var lb = 0f; private var llevando = false

    init {
        var i = d.afiladoInicio; var f = d.afiladoFin
        // Con el dedo no hay presión: los pinceles que cambian de tamaño con ella se afilan solos al empezar y al acabar
        if (!lapiz && d.presionTamano > 0.05f) { i = max(i, d.presionTamano * 2f); f = max(f, d.presionTamano * 3f) }
        var li = i * tam; var lf = f * tam
        if (largoTotal >= 0f) { val tope = largoTotal * 0.45f; if (li > tope) li = tope; if (lf > tope) lf = tope }
        afIni = li; afFin = lf
    }

    fun punto(x: Float, y: Float, p: Float, inc: Float, aci: Float, t: Long) {
        val pr = if (lapiz) p.coerceIn(0f, 1f) else 1f
        if (n == 0) { ax = x; ay = y; ap = pr; ai = inc; aa = aci; at = t; n = 1; return }
        val dist = hypot(x - ax, y - ay)
        if (dist < tam * 0.002f) return
        val dt = max(1L, t - at).toFloat()
        val inst = dist * u / dt
        vel = if (n == 1) inst else vel * 0.65f + inst * 0.35f
        val ps = if (n == 1) (ap + pr) / 2f else ap * 0.35f + pr * 0.65f
        // El acimut, sin saltos al pasar por ±180°
        var az = aci; while (az - aa > PI) az -= (2 * PI).toFloat(); while (az - aa < -PI) az += (2 * PI).toFloat()
        val nx = (ax + x) / 2f; val ny = (ay + y) / 2f; val np = (ap + ps) / 2f; val ni = (ai + inc) / 2f; val na = (aa + az) / 2f
        if (n == 1) recta(ax, ay, ap, ai, aa, vel, nx, ny, np, ni, na, vel)
        else curva(nx, ny, np, ni, na, vel)
        mx = nx; my = ny; mp = np; mi = ni; ma = na; mv = vel
        ax = x; ay = y; ap = ps; ai = inc; aa = az; at = t; n++
    }

    /** El final del trazo: lo que queda hasta la última muestra. */
    fun fin() {
        if (n == 0) return
        if (n == 1) { if (sal != null) emite(ax, ay, 0f, ap, ai, aa, 0f, 0f); return }
        recta(mx, my, mp, mi, ma, mv, ax, ay, ap, ai, aa, vel)
        n = 0
    }

    private fun curva(x1: Float, y1: Float, p1: Float, i1: Float, a1: Float, v1: Float) {
        val x0 = mx; val y0 = my
        val aprox = hypot(ax - x0, ay - y0) + hypot(x1 - ax, y1 - ay)
        val piezas = (aprox / (tam * 0.15f).coerceAtLeast(1e-12f)).toInt().coerceIn(1, 16)
        var px = x0; var py = y0; var k0 = 0f
        for (i in 1..piezas) {
            val t = i / piezas.toFloat(); val q = 1f - t
            val bx = q * q * x0 + 2f * q * t * ax + t * t * x1; val by = q * q * y0 + 2f * q * t * ay + t * t * y1
            recta(px, py, mp + (p1 - mp) * k0, mi + (i1 - mi) * k0, ma + (a1 - ma) * k0, mv + (v1 - mv) * k0,
                bx, by, mp + (p1 - mp) * t, mi + (i1 - mi) * t, ma + (a1 - ma) * t, mv + (v1 - mv) * t)
            px = bx; py = by; k0 = t
        }
    }

    private fun recta(x0: Float, y0: Float, p0: Float, i0: Float, a0: Float, v0: Float, x1: Float, y1: Float, p1: Float, i1: Float, a1: Float, v1: Float) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < tam * 1e-5f) return
        if (sal == null) { recorrido += len; return }
        val dir = atan2(dy, dx)
        var s = falta
        while (s <= len) {
            val k = s / len
            s += emite(x0 + dx * k, y0 + dy * k, dir, p0 + (p1 - p0) * k, i0 + (i1 - i0) * k, a0 + (a1 - a0) * k, v0 + (v1 - v0) * k, recorrido + s)
        }
        falta = s - len
        recorrido += len
    }

    private fun azar(canal: Int, j: Int) = Ruido.hash01(idx * 16 + j, canal, semilla)

    /** Deja el sello (o los sellos) de este punto del camino. Devuelve cuánto hay que avanzar hasta el siguiente. */
    private fun emite(x: Float, y: Float, dir: Float, p: Float, inc: Float, aci: Float, v: Float, s: Float): Float {
        val sal = sal ?: return 1f
        idx++
        var fT = 1f; var fO = 1f
        if (lapiz) {
            if (d.presionTamano > 0.01f) fT *= (1f - d.presionTamano) + d.presionTamano * p
            if (d.presionOpacidad > 0.01f) fO *= (1f - d.presionOpacidad) + d.presionOpacidad * p
            val tn = ((inc - 0.3f) / 1.0f).coerceIn(0f, 1f)
            if (d.inclinacion > 0.01f) fT *= 1f + d.inclinacion * 2.5f * tn
            if (d.inclinacionOpacidad > 0.01f) fO *= 1f - d.inclinacionOpacidad * 0.75f * tn
        }
        val vn = (v / 2.5f).coerceIn(0f, 1f)
        if (abs(d.velocidadTamano) > 0.01f) fT *= (1f + d.velocidadTamano * vn).coerceAtLeast(0.1f)
        if (abs(d.velocidadOpacidad) > 0.01f) fO *= (1f + d.velocidadOpacidad * vn).coerceIn(0.05f, 1f)
        if (afIni > 0f && s < afIni) fT *= 0.06f + 0.94f * suave(s / afIni)
        if (afFin > 0f && largoTotal >= 0f && largoTotal - s < afFin) fT *= 0.06f + 0.94f * suave(((largoTotal - s) / afFin).coerceAtLeast(0f))
        val base = (tam * fT).coerceAtLeast(tam * 0.02f)
        val nx = -sin(dir); val ny = cos(dir)
        for (j in 0 until d.numero) {
            var dd = base
            if (d.azarTamano > 0.001f) dd *= 1f - d.azarTamano * azar(1, j)
            val azarO = if (d.azarOpacidad > 0.001f) 1f - d.azarOpacidad * azar(2, j) else 1f
            // En veladura, la presión marca hasta dónde llega la opacidad (el tope) y el flujo, lo deprisa que llega; si no, todo es la pintura del sello
            val tope = if (d.velado) fO else fO * azarO
            val al = if (d.velado) d.flujo * azarO else d.flujo * tope
            var ang = (d.angulo * (PI / 180.0)).toFloat()
            if (d.sigueDireccion) ang += dir
            if (d.acimut && lapiz) ang += aci
            if (d.giroAzar > 0.001f) ang += d.giroAzar * (azar(3, j) * 2f - 1f) * PI.toFloat()
            var sx = x; var sy = y
            if (d.dispersion > 0.001f) {
                val lat = d.dispersion * base * (azar(4, j) * 2f - 1f); val lon = d.dispersion * base * (azar(5, j) * 2f - 1f) * 0.5f
                sx += nx * lat + ny * lon; sy += ny * lat - nx * lon
            }
            var col = color and 0xFFFFFF
            if (d.azarTono > 0.001f || d.azarSat > 0.001f || d.azarBrillo > 0.001f) {
                tmpHsv[0] = ((hsv[0] + d.azarTono * 180f * (azar(6, j) * 2f - 1f)) % 360f + 360f) % 360f
                tmpHsv[1] = (hsv[1] * (1f + d.azarSat * (azar(7, j) * 2f - 1f))).coerceIn(0f, 1f)
                tmpHsv[2] = (hsv[2] * (1f + d.azarBrillo * (azar(8, j) * 2f - 1f))).coerceIn(0f, 1f)
                col = Color.HSVToColor(tmpHsv) and 0xFFFFFF
            }
            if (muestra != null && d.mezcla > 0.001f) col = mezcla(col, muestra.invoke(sx, sy))
            val bits = Ruido.hash(idx, j, semilla + 77) and (if (d.volteo) -1 else -2)
            if (dd >= tam * 0.008f && al > 0.0005f && tope > 0.0005f) sal.pon(sx, sy, dd, al.coerceAtMost(1f), ang, d.achatado.coerceIn(0.05f, 1f), col, bits, tope.coerceAtMost(1f))
        }
        return max(tam * 0.004f, esp * base)
    }

    /** Mezcla húmeda: el pincel se va cargando del color que pisa y lo arrastra. */
    private fun mezcla(col: Int, hay: Int): Int {
        val al = (hay ushr 24) / 255f
        val r = ((col shr 16) and 255).toFloat(); val g = ((col shr 8) and 255).toFloat(); val b = (col and 255).toFloat()
        if (!llevando) { if (al > 0.03f) { lr = ((hay shr 16) and 255).toFloat(); lg = ((hay shr 8) and 255).toFloat(); lb = (hay and 255).toFloat() } else { lr = r; lg = g; lb = b }; llevando = true }
        else {
            val k = 0.3f * al
            lr += (((hay shr 16) and 255) - lr) * k; lg += (((hay shr 8) and 255) - lg) * k; lb += ((hay and 255) - lb) * k
            // Y la pintura propia se va gastando poco a poco en lo que lleva
            lr += (r - lr) * 0.02f * (1f - d.mezcla); lg += (g - lg) * 0.02f * (1f - d.mezcla); lb += (b - lb) * 0.02f * (1f - d.mezcla)
        }
        val m = d.mezcla
        return ((r + (lr - r) * m).toInt().coerceIn(0, 255) shl 16) or ((g + (lg - g) * m).toInt().coerceIn(0, 255) shl 8) or (b + (lb - b) * m).toInt().coerceIn(0, 255)
    }

    private fun suave(t: Float): Float { val k = t.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }

    companion object {
        /** Los puntos de un trazo: x, y, presión, inclinación, acimut y tiempo (ms) por cada uno. */
        const val P = 6

        /** Cuánto mide el camino de esos puntos (igual que lo recorre el caminante). */
        fun largo(d: DefPincel, tam: Float, pts: FloatArray, n: Int = pts.size / P): Float {
            val c = Caminante(d, tam, 0, 0, true, 1f, -1f, null)
            for (i in 0 until n) { val o = i * P; c.punto(pts[o], pts[o + 1], pts[o + 2], pts[o + 3], pts[o + 4], pts[o + 5].toLong()) }
            c.fin()
            return c.recorrido
        }

        /** Si al soltar hay que repasar el trazo (porque se afila al final, y eso no se sabe hasta que acaba). */
        fun seAfila(d: DefPincel, lapiz: Boolean) = d.afiladoInicio > 0.01f || d.afiladoFin > 0.01f || (!lapiz && d.presionTamano > 0.05f)
    }
}

/**
 * Un trozo de lienzo donde se estampa: la transparencia acumulada con 16 bits por píxel y, si el pincel cambia de color de
 * un sello a otro, el color de cada píxel. Ocupa [w] × [h] píxeles desde ([ox], [oy]) (en píxeles ya escalados).
 */
class Lona(val ox: Long, val oy: Long, val w: Int, val h: Int, conColor: Boolean) {
    constructor(ox: Int, oy: Int, w: Int, h: Int, conColor: Boolean) : this(ox.toLong(), oy.toLong(), w, h, conColor)
    val a = CharArray(w * h)
    val rgb: IntArray? = if (conColor) IntArray(w * h) else null
    private var x0 = w; private var y0 = h; private var x1 = -1; private var y1 = -1
    @Volatile var tocada = false; private set

    fun ensucia(ax0: Int, ay0: Int, ax1: Int, ay1: Int) = synchronized(this) {
        if (ax0 < x0) x0 = ax0; if (ay0 < y0) y0 = ay0; if (ax1 > x1) x1 = ax1; if (ay1 > y1) y1 = ay1; tocada = true
    }

    /**
     * Pasa a la imagen [b] (de [w] × [h]) lo cambiado desde la última vez (o todo): el color con su transparencia.
     * [bordes]: bordes húmedos (0 nada). Devuelve si había algo que pasar.
     */
    fun vuelca(b: Bitmap, color: Int, bordes: Float = 0f, todo: Boolean = false): Boolean {
        var rx0 = 0; var ry0 = 0; var rx1 = w - 1; var ry1 = h - 1
        synchronized(this) {
            if (!todo) { rx0 = x0; ry0 = y0; rx1 = x1; ry1 = y1 }
            x0 = w; y0 = h; x1 = -1; y1 = -1
        }
        if (rx1 < rx0 || ry1 < ry0) return false
        val rw = rx1 - rx0 + 1; val rh = ry1 - ry0 + 1
        val buf = TAMPON.get()!!.let { if (it.size >= rw * rh) it else IntArray(rw * rh).also { n -> TAMPON.set(n) } }
        val c = color and 0xFFFFFF
        val tabla = if (bordes > 0.01f) tablaBordes(bordes) else null
        var o = 0
        for (y in ry0..ry1) {
            var i = y * w + rx0
            for (x in 0 until rw) {
                var av = a[i].code ushr 8
                if (tabla != null) av = tabla[av]
                buf[o++] = (av shl 24) or (rgb?.get(i) ?: c)
                i++
            }
        }
        b.setPixels(buf, 0, rw, rx0, ry0, rw, rh)
        return true
    }

    /** La imagen entera de esta lona (para fundirla con una capa). */
    fun imagen(color: Int, bordes: Float = 0f): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { vuelca(it, color, bordes, true) }

    fun limpia() { java.util.Arrays.fill(a, 0.toChar()); rgb?.fill(0); synchronized(this) { x0 = 0; y0 = 0; x1 = w - 1; y1 = h - 1 } }

    companion object {
        private val TAMPON = object : ThreadLocal<IntArray>() { override fun initialValue() = IntArray(256 * 256) }
        private val tablas = ConcurrentHashMap<Int, IntArray>()

        /** Bordes húmedos: el contorno del trazo (donde la transparencia va por la mitad) queda más cargado que el interior. */
        fun tablaBordes(bordes: Float): IntArray = tablas.getOrPut((bordes * 100).toInt()) {
            IntArray(256) { i ->
                val x = i / 255f
                fun ss(a: Float, b: Float, v: Float): Float { val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
                val anillo = ss(0f, 0.4f, x) * (1f - 0.55f * ss(0.4f, 1f, x))
                ((x * (1f - bordes) + anillo * bordes) * 255f).toInt().coerceIn(0, 255)
            }
        }
    }
}

/** Estampa los sellos de un pincel en una [Lona], a una escala (1: los píxeles del lienzo; 4: cuatro veces más fino). */
class Raster(val def: DefPincel, val escala: Float, uGrano: Float = 1f) {
    private val tex: Textura? = if (def.forma == "redonda" || def.forma == "plana") null else Texturas.de(def.forma)
    private val plana = def.forma == "plana"
    private val grano: Textura?
    private val pasoG: Float
    init {
        // El grano, a la reducción que toca (para que al verse pequeño no haga aguas)
        // El grano mide lo que mide en la pantalla al acercamiento al que se dibujó (de cerca, un grano más fino en el lienzo)
        var g = Texturas.de(def.grano); var paso = if (g != null) (g.lado / (def.escalaGrano.coerceAtLeast(4f) / uGrano.coerceAtLeast(1e-6f))) / escala else 0f
        while (g != null && paso > 1.3f) { val m = g.mitad ?: break; g = m; paso /= 2f }
        grano = g; pasoG = paso
    }
    private val prof = def.profGrano.coerceIn(0f, 1f)
    private val blando = (1f - def.dureza).coerceIn(0f, 1f)
    private val acumula = def.acumula
    private val velado = def.velado
    /** El grano de un pincel importado (una foto de papel) se multiplica tal cual; los de ATLAS dejan pasar la pintura según la presión. */
    private val granoTalCual = def.velado && def.grano.contains(':')

    /** Cuánto se sale un sello de su centro, en píxeles escalados. */
    fun alcance(s: Sellos, i: Int): Float { val r = s.f[i * Sellos.P + 2] * escala / 2f; return (if (tex != null) r * 1.4143f else r).coerceAtLeast(0.5f) + 1.5f }

    /**
     * Estampa el sello [i] en la lona (solo la parte que le cae dentro). Los sellos van medidos desde el origen de su trazo;
     * ([dx], [dy]) es dónde cae ese origen en la lona (en píxeles escalados), calculado con doble precisión por quien llama:
     * así un trazo dibujado a 2000 aumentos y lejos del centro del lienzo no pierde finura.
     */
    fun sella(l: Lona, s: Sellos, i: Int, dx0: Double, dy0: Double, gx0: Float = 0f, gy0: Float = 0f) {
        val o = i * Sellos.P
        val cx = (s.f[o].toDouble() * escala + dx0).toFloat(); val cy = (s.f[o + 1].toDouble() * escala + dy0).toFloat()
        var rad = s.f[o + 2] * escala / 2f
        var alfa = s.f[o + 3]
        // Las líneas más finas que un píxel: de un píxel, pero más transparentes
        if (rad < 0.5f) { alfa *= rad / 0.5f; rad = 0.5f }
        val ach = s.f[o + 5]
        val ry = max(rad * ach, 0.5f)
        val ext = (if (tex != null) rad * 1.4143f else rad) + 1.5f
        val xi0 = max(0, floor(cx - ext).toInt()); val yi0 = max(0, floor(cy - ext).toInt())
        val xi1 = min(l.w - 1, ceil(cx + ext).toInt()); val yi1 = min(l.h - 1, ceil(cy + ext).toInt())
        if (xi1 < xi0 || yi1 < yi0) return
        val ang = s.f[o + 4]
        val co = cos(ang); val si = sin(ang)
        val bits = s.b[i]
        val color = s.c[i]
        val a = l.a; val rgb = l.rgb; val w = l.w
        val c15 = alfa * 32768f; val c16 = alfa * 65535f
        val tope16 = (s.f[o + 6] * 65535f).toInt()
        // El grano: en coordenadas del lienzo (o movido con cada sello)
        val g = grano
        var gox = 0f; var goy = 0f
        if (g != null) { gox = gx0; goy = gy0; if (def.granoMovil) { gox += ((bits ushr 4) and 255) * g.lado / 256f; goy += ((bits ushr 12) and 255) * g.lado / 256f } }
        val gm = if (g != null) g.lado - 1 else 0
        val gSuave = pasoG < 0.99f
        val umbral = 0.3f + (1f - alfa.coerceIn(0f, 1f)) * 0.6f
        // La punta
        val t = tex?.nivel(rad * 2f)
        val tl = t?.lado ?: 0
        val irx = 1f / rad; val iry = 1f / ry
        val raa = (rad + ry) / 2f
        val lim = 1f + 0.5f / raa; val lim2 = lim * lim
        val iBlando = if (blando > 0.001f) 1f / blando else 0f
        val volX = (bits and 1) != 0
        var tocado = false
        for (py in yi0..yi1) {
            val dy = py + 0.5f - cy
            var idx = py * w + xi0
            for (px in xi0..xi1) {
                val dx = px + 0.5f - cx
                var u = (dx * co + dy * si) * irx
                val v = (dy * co - dx * si) * iry
                var cov: Float
                if (t != null) {
                    if (u <= -1f || u >= 1f || v <= -1f || v >= 1f) { idx++; continue }
                    if (volX) u = -u
                    val fx = (u * 0.5f + 0.5f) * tl - 0.5f; val fy = (v * 0.5f + 0.5f) * tl - 0.5f
                    val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
                    val tx = fx - x0; val ty = fy - y0
                    val ta = t.a
                    val xa = if (x0 < 0) 0 else x0; val xb = if (x0 + 1 >= tl) tl - 1 else x0 + 1
                    val ya = (if (y0 < 0) 0 else y0) * tl; val yb = (if (y0 + 1 >= tl) tl - 1 else y0 + 1) * tl
                    val p00 = ta[ya + xa].toInt() and 255; val p10 = ta[ya + xb].toInt() and 255; val p01 = ta[yb + xa].toInt() and 255; val p11 = ta[yb + xb].toInt() and 255
                    val arriba = p00 + (p10 - p00) * tx; val abajo = p01 + (p11 - p01) * tx
                    cov = (arriba + (abajo - arriba) * ty) * (1f / 255f)
                    if (cov <= 0.002f) { idx++; continue }
                } else if (plana) {
                    // Un rectángulo de esquinas redondeadas (la plumilla, el rotulador de punta plana)
                    val qx = abs(u) - 0.6f; val qy = abs(v) - 0.6f
                    val fuera = hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - 0.4f
                    cov = -fuera * raa + 0.5f
                    if (cov <= 0f) { idx++; continue }
                    if (cov > 1f) cov = 1f
                } else {
                    val r2 = u * u + v * v
                    if (r2 >= lim2) { idx++; continue }
                    val r = sqrt(r2)
                    cov = (1f - r) * raa + 0.5f
                    if (cov <= 0f) { idx++; continue }
                    if (cov > 1f) cov = 1f
                    if (iBlando > 0f) { val q = (1f - r) * iBlando; if (q < 1f) { if (q <= 0f) { idx++; continue }; val sv = q * q * (3f - 2f * q); if (sv < cov) cov = sv } }
                }
                if (g != null) {
                    val gx = px * pasoG + gox; val gy = py * pasoG + goy
                    val gv: Float
                    if (gSuave) {
                        val x0 = floor(gx).toInt(); val y0 = floor(gy).toInt()
                        val tx = gx - x0; val ty = gy - y0
                        val ga = g.a; val gl = g.lado
                        val xa = x0 and gm; val xb = (x0 + 1) and gm; val ya = (y0 and gm) * gl; val yb = ((y0 + 1) and gm) * gl
                        val p00 = ga[ya + xa].toInt() and 255; val p10 = ga[ya + xb].toInt() and 255; val p01 = ga[yb + xa].toInt() and 255; val p11 = ga[yb + xb].toInt() and 255
                        val arriba = p00 + (p10 - p00) * tx; val abajo = p01 + (p11 - p01) * tx
                        gv = (arriba + (abajo - arriba) * ty) * (1f / 255f)
                    } else gv = (g.a[((gy.toInt()) and gm) * g.lado + (gx.toInt() and gm)].toInt() and 255) * (1f / 255f)
                    // El papel: con poca presión el grafito solo se queda en lo que sobresale; apretando, lo llena casi todo
                    var sg = gv
                    if (!granoTalCual) { sg = (gv - umbral) * 3.3f + 0.5f; if (sg < 0f) sg = 0f else if (sg > 1f) sg = 1f }
                    cov *= (1f - prof) + prof * sg
                    if (cov <= 0.002f) { idx++; continue }
                }
                val viejo = a[idx].code
                val nuevo: Int
                if (velado) {
                    // Veladura: la pintura se acerca al tope del sello sin pasarlo (los sellos suaves se funden sin cargarse de más)
                    val c = (cov * c15).toInt()
                    if (c <= 0 || tope16 <= viejo) { idx++; continue }
                    nuevo = viejo + (((tope16 - viejo) * c) ushr 15)
                    if (nuevo <= viejo) { idx++; continue }
                    if (rgb != null) rgb[idx] = if (viejo == 0) color else mezclaColor(rgb[idx], color, c.toFloat() / 32768f, viejo / 65535f)
                } else if (acumula) {
                    val c = (cov * c15).toInt()
                    if (c <= 0) { idx++; continue }
                    nuevo = viejo + (((65535 - viejo) * c) ushr 15)
                    if (rgb != null) rgb[idx] = if (viejo == 0) color else mezclaColor(rgb[idx], color, c.toFloat() / 32768f, viejo / 65535f)
                } else {
                    val c = (cov * c16).toInt()
                    if (c <= viejo) { idx++; continue }
                    nuevo = c
                    if (rgb != null) rgb[idx] = color
                }
                a[idx] = (if (nuevo > 65535) 65535 else nuevo).toChar()
                tocado = true
                idx++
            }
        }
        if (tocado) l.ensucia(xi0, yi0, xi1, yi1)
    }

    /** Dónde cae el grano en el origen de una lona (en puntos del grano, ya reducido a una vuelta de la textura). */
    fun granoEn(origen: Long): Float { val g = grano ?: return 0f; return ((origen.toDouble() * pasoG) % g.lado).toFloat() }

    /** El color que queda al poner [nuevo] con cobertura [c] encima de [viejo] con transparencia [av]. */
    private fun mezclaColor(viejo: Int, nuevo: Int, c: Float, av: Float): Int {
        val wv = av * (1f - c); val t = c / (c + wv)
        val r = ((viejo shr 16) and 255) + (((nuevo shr 16) and 255) - ((viejo shr 16) and 255)) * t
        val g = ((viejo shr 8) and 255) + (((nuevo shr 8) and 255) - ((viejo shr 8) and 255)) * t
        val b = (viejo and 255) + ((nuevo and 255) - (viejo and 255)) * t
        return (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }
}

/**
 * La pintura «húmeda» de un trazo: se estampa aquí, aparte de la capa, y se funde con ella con la opacidad del pincel (así
 * un trazo no se oscurece donde se cruza consigo mismo). En teselas de 256, a la [escala] a la que se está mirando.
 */
class Humedo(val escala: Float, val conColor: Boolean, val ox: Double = 0.0, val oy: Double = 0.0) {
    val lonas = ConcurrentHashMap<Long, Lona>()

    fun lona(tx: Int, ty: Int): Lona = lonas.getOrPut(Lienzo.clave(tx, ty)) { Lona(tx.toLong() * L, ty.toLong() * L, L, L, conColor) }

    private fun estampa(r: Raster, l: Lona, s: Sellos, i: Int) = r.sella(l, s, i, ox * escala - l.ox, oy * escala - l.oy, r.granoEn(l.ox), r.granoEn(l.oy))

    /** Estampa los sellos [desde]..[hasta] (sin llegar a él). */
    fun sella(r: Raster, s: Sellos, desde: Int = 0, hasta: Int = s.n) {
        for (i in desde until hasta) {
            val o = i * Sellos.P
            val cx = (s.f[o] + ox) * escala; val cy = (s.f[o + 1] + oy) * escala; val e = r.alcance(s, i)
            for (ty in Math.floorDiv(floor(cy - e).toLong(), L.toLong()).toInt()..Math.floorDiv(ceil(cy + e).toLong(), L.toLong()).toInt()) for (tx in Math.floorDiv(floor(cx - e).toLong(), L.toLong()).toInt()..Math.floorDiv(ceil(cx + e).toLong(), L.toLong()).toInt())
                estampa(r, lona(tx, ty), s, i)
        }
    }

    /** Estampa todos los sellos repartiendo las teselas entre los núcleos (para pasar un trazo entero de una vez). */
    fun sellaTodo(r: Raster, s: Sellos) {
        if (s.n < 64) { sella(r, s); return }
        val porLona = HashMap<Long, IntLista>()
        for (i in 0 until s.n) {
            val o = i * Sellos.P
            val cx = (s.f[o] + ox) * escala; val cy = (s.f[o + 1] + oy) * escala; val e = r.alcance(s, i)
            for (ty in Math.floorDiv(floor(cy - e).toLong(), L.toLong()).toInt()..Math.floorDiv(ceil(cy + e).toLong(), L.toLong()).toInt()) for (tx in Math.floorDiv(floor(cx - e).toLong(), L.toLong()).toInt()..Math.floorDiv(ceil(cx + e).toLong(), L.toLong()).toInt())
                porLona.getOrPut(Lienzo.clave(tx, ty)) { IntLista() }.pon(i)
        }
        val tareas = porLona.map { (k, lista) -> Callable { val l = lona(Lienzo.tx(k), Lienzo.ty(k)); for (j in 0 until lista.n) estampa(r, l, s, lista.v[j]) } }
        if (tareas.size < 3) tareas.forEach { it.call() } else Motor.reparto.invokeAll(tareas).forEach { it.get() }
    }

    /** Las teselas del lienzo (de 256 px a tamaño real) que toca lo pintado. */
    fun teselasBase(): Set<Long> {
        val r = HashSet<Long>()
        for (l in lonas.values) if (l.tocada) {
            val x0 = Math.floorDiv(floor(l.ox / escala.toDouble()).toLong(), L.toLong()).toInt(); val x1 = Math.floorDiv(ceil((l.ox + l.w) / escala.toDouble()).toLong() - 1, L.toLong()).toInt()
            val y0 = Math.floorDiv(floor(l.oy / escala.toDouble()).toLong(), L.toLong()).toInt(); val y1 = Math.floorDiv(ceil((l.oy + l.h) / escala.toDouble()).toLong() - 1, L.toLong()).toInt()
            for (ty in y0..max(y0, y1)) for (tx in x0..max(x0, x1)) r += Lienzo.clave(tx, ty)
        }
        return r
    }

    companion object { const val L = Lienzo.L }
}

/** Una lista de enteros que crece (sin cajas). */
class IntLista { var v = IntArray(16); var n = 0; fun pon(x: Int) { if (n == v.size) v = v.copyOf(n * 2); v[n++] = x } }

object Motor {
    /** Los hilos que se reparten las teselas al pasar un trazo entero. */
    val reparto: java.util.concurrent.ExecutorService = Executors.newFixedThreadPool(max(2, Runtime.getRuntime().availableProcessors() - 1)) { r -> Thread(r, "atlas-reparto").apply { isDaemon = true } }

    /**
     * A qué escala se pinta según el acercamiento (píxeles de pantalla por píxel del lienzo): de lejos, a la de las vistas
     * resumidas (½, ¼…); a tamaño real, 1; de cerca, 2, 4, 8… para que el trazo salga nítido. [nivel] > 0: de lejos.
     */
    fun nivel(zoom: Float): Int = if (zoom >= 0.7f) -Math.round(kotlin.math.ln(zoom) / kotlin.math.ln(2f)).coerceIn(0, MAS_CERCA) else floor(kotlin.math.ln(1f / zoom) / kotlin.math.ln(2f)).toInt().coerceIn(1, 7)
    fun escala(nivel: Int): Float = if (nivel >= 0) 1f / (1 shl nivel) else (1 shl -nivel).toFloat()
    /**
     * Hasta dónde se puede acercar: 2^18 = 262.144 aumentos. Con un píxel del lienzo de 1 km, un metro mide entonces 262
     * píxeles de la pantalla; con uno de 5 km, 52.
     */
    const val MAS_CERCA = 18
    const val ZOOM_MAX = 262144f

    /** El acercamiento máximo para un mapa con esa escala: hasta que un metro mida unos 400 píxeles (y nunca menos de 64 aumentos). */
    fun zoomMaximo(kmPorPx: Float): Float = (400_000f * kmPorPx).coerceIn(64f, ZOOM_MAX)
}
