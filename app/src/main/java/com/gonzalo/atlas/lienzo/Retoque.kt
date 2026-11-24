package com.gonzalo.atlas.lienzo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Las muestras de los pinceles (un trazo en S con la presión subiendo y bajando), para la biblioteca. */
object MuestrasPincel {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun de(d: DefPincel, w: Int = 300, h: Int = 76, color: Int = 0xFFF2F2F2.toInt()): Bitmap? {
        val clave = d.json().toString() + w + "x" + h + color
        cache[clave]?.let { return it }
        return runCatching {
            val n = 64; val pts = FloatArray(n * Caminante.P)
            for (i in 0 until n) {
                val t = i / (n - 1f); val o = i * Caminante.P
                pts[o] = 18 + t * (w - 36); pts[o + 1] = h / 2f + sin(t * 2 * PI).toFloat() * h * 0.22f
                pts[o + 2] = (0.1f + 0.9f * sin(t * PI).toFloat()).coerceIn(0f, 1f); pts[o + 3] = 0.35f; pts[o + 4] = 0f; pts[o + 5] = t * 700f
            }
            val tam = min(d.tamano, h * 0.36f).coerceAtLeast(2f)
            val datos = TrazoDatos(d, color, tam, d.opacidad.coerceAtLeast(0.35f), TrazoDatos.PINTAR, false, listOf(pts), 7, 1f, true, null, null)
            val hm = Humedo(1f, d.colorVariable); hm.sella(Raster(d, 1f), datos.sellos())
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            for (l in hm.lonas.values) if (l.tocada) datos.funde(c, l, 1f)
            b
        }.getOrNull()?.also { if (cache.size > 300) cache.clear(); cache[clave] = it }
    }
}

/**
 * Difuminar: arrastra la pintura que hay debajo (como el dedo de Procreate). Se lleva un «parche» del tamaño del pincel que
 * se mezcla con lo que encuentra a cada paso. Se hace en la CPU, sobre copias de las teselas (para deshacer).
 */
class Difumino(private val lz: Lienzo, private val capa: CapaPx, private val radio: Float, private val fuerza: Float) {
    private val antes = HashMap<Long, Bitmap?>()
    private val fondos = HashMap<Long, Fondo>()
    private val trabajo = HashMap<Long, Bitmap>()
    private var parche: FloatArray? = null
    private var px = Float.NaN; private var py = Float.NaN
    private val L = Lienzo.L

    private fun tesela(k: Long, crear: Boolean): Bitmap? {
        trabajo[k]?.let { return it }
        val v = capa.teselas[k]
        if (v == null && !crear) return null
        if (!lz.dentro(Lienzo.tx(k), Lienzo.ty(k))) return null
        antes[k] = v
        // Mientras se difumina, esta tesela se ve tal cual (sin sus trazos rehechos de cerca)
        capa.fondo.remove(k)?.let { fondos[k] = it }
        val b = v?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
        trabajo[k] = b
        return b
    }

    private fun lee(x0: Int, y0: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (ty in Math.floorDiv(y0, L)..Math.floorDiv(y0 + h - 1, L)) for (tx in Math.floorDiv(x0, L)..Math.floorDiv(x0 + w - 1, L)) {
            val b = trabajo[Lienzo.clave(tx, ty)] ?: capa.teselas[Lienzo.clave(tx, ty)] ?: continue
            val ix0 = max(x0, tx * L); val iy0 = max(y0, ty * L); val ix1 = min(x0 + w, (tx + 1) * L); val iy1 = min(y0 + h, (ty + 1) * L)
            b.getPixels(out, (iy0 - y0) * w + (ix0 - x0), w, ix0 - tx * L, iy0 - ty * L, ix1 - ix0, iy1 - iy0)
        }
        return out
    }

    private fun escribe(x0: Int, y0: Int, w: Int, h: Int, d: IntArray) {
        for (ty in Math.floorDiv(y0, L)..Math.floorDiv(y0 + h - 1, L)) for (tx in Math.floorDiv(x0, L)..Math.floorDiv(x0 + w - 1, L)) {
            val b = tesela(Lienzo.clave(tx, ty), true) ?: continue
            val ix0 = max(x0, tx * L); val iy0 = max(y0, ty * L); val ix1 = min(x0 + w, (tx + 1) * L); val iy1 = min(y0 + h, (ty + 1) * L)
            b.setPixels(d, (iy0 - y0) * w + (ix0 - x0), w, ix0 - tx * L, iy0 - ty * L, ix1 - ix0, iy1 - iy0)
        }
    }

    /** Un punto del trazo (píxeles del lienzo). Devuelve la zona cambiada. */
    fun punto(x: Float, y: Float, presion: Float): RectF? {
        val r = max(1f, radio * (0.4f + 0.6f * presion))
        if (px.isNaN()) { px = x; py = y; muestra(x, y, r); return null }
        val d = hypot(x - px, y - py)
        val paso = max(1f, r * 0.2f)
        if (d < paso) return null
        var t = paso
        val zona = RectF(x - r, y - r, x + r, y + r)
        while (t <= d) {
            val cx = px + (x - px) * t / d; val cy = py + (y - py) * t / d
            pasada(cx, cy, r, presion)
            zona.union(cx - r, cy - r, cx + r, cy + r)
            t += paso
        }
        px += (x - px) * (t - paso) / d; py += (y - py) * (t - paso) / d
        return zona
    }

    private val lado get() = ceil(radio * 2 + 3).toInt()

    private fun muestra(cx: Float, cy: Float, r: Float) {
        val n = lado; val x0 = floor(cx - n / 2f).toInt(); val y0 = floor(cy - n / 2f).toInt()
        val d = lee(x0, y0, n, n)
        // El parche en premultiplicado (para que los bordes transparentes no ensucien)
        parche = FloatArray(n * n * 4).also { p -> for (i in d.indices) premul(d[i], p, i * 4) }
    }

    private fun premul(c: Int, a: FloatArray, o: Int) {
        val al = (c ushr 24) / 255f
        a[o] = al; a[o + 1] = ((c shr 16) and 255) / 255f * al; a[o + 2] = ((c shr 8) and 255) / 255f * al; a[o + 3] = (c and 255) / 255f * al
    }

    private fun pasada(cx: Float, cy: Float, r: Float, presion: Float) {
        val p = parche ?: return
        val n = lado; val x0 = floor(cx - n / 2f).toInt(); val y0 = floor(cy - n / 2f).toInt()
        val d = lee(x0, y0, n, n)
        val c = (n - 1) / 2f
        val f0 = fuerza * (0.5f + 0.5f * presion)
        val q = FloatArray(4)
        for (j in 0 until n) for (i in 0 until n) {
            val dist = hypot(i - c, j - c) / r
            if (dist >= 1f) continue
            val f = f0 * (1f - dist * dist)
            val k = j * n + i; val o = k * 4
            premul(d[k], q, 0)
            // Lo que hay se mezcla con el parche; el parche recoge un poco de lo que hay
            val a = q[0] + (p[o] - q[0]) * f; val rr = q[1] + (p[o + 1] - q[1]) * f; val g = q[2] + (p[o + 2] - q[2]) * f; val b = q[3] + (p[o + 3] - q[3]) * f
            p[o] += (q[0] - p[o]) * 0.12f; p[o + 1] += (q[1] - p[o + 1]) * 0.12f; p[o + 2] += (q[2] - p[o + 2]) * 0.12f; p[o + 3] += (q[3] - p[o + 3]) * 0.12f
            d[k] = if (a <= 0.002f) 0 else ((a * 255).toInt().coerceIn(0, 255) shl 24) or (((rr / a) * 255).toInt().coerceIn(0, 255) shl 16) or
                (((g / a) * 255).toInt().coerceIn(0, 255) shl 8) or ((b / a) * 255).toInt().coerceIn(0, 255)
        }
        escribe(x0, y0, n, n, d)
        // Se ve ya: las copias de trabajo pasan a la capa
        for ((k, b) in trabajo) capa.teselas[k] = b
    }

    fun termina(): Edicion? {
        if (antes.isEmpty()) return null
        // Las teselas definitivas: copias (las de trabajo se han ido cambiando). Se deja todo como estaba y se cambia de una vez
        val despues = HashMap<Long, Bitmap?>()
        for ((k, b) in trabajo) despues[k] = b.copy(Bitmap.Config.ARGB_8888, false)
        synchronized(lz.cierre) {
            for ((k, v) in antes) if (v == null) capa.teselas.remove(k) else capa.teselas[k] = v
            for ((k, f) in fondos) capa.fondo[k] = f
        }
        return lz.cambia(capa, "Difuminar", despues)
    }
}

/** Ajustes de imagen de una capa (como los de Procreate): tono, saturación y brillo; desenfoque; transformar. */
object Ajustes {
    /** La matriz de color de tono (grados, −180..180), saturación y brillo (multiplican; 1 = igual). */
    fun matrizColor(tono: Float, saturacion: Float, brillo: Float): ColorMatrix {
        val cm = ColorMatrix(); cm.setSaturation(saturacion)
        cm.postConcat(tonoMatriz(tono))
        cm.postConcat(ColorMatrix().apply { setScale(brillo, brillo, brillo, 1f) })
        return cm
    }

    /** Brillo (−1..1 suma) y contraste (0..2 multiplica alrededor del gris medio). */
    fun matrizBrilloContraste(brillo: Float, contraste: Float): ColorMatrix {
        val t = (0.5f * (1f - contraste) + brillo) * 255f
        return ColorMatrix(floatArrayOf(contraste, 0f, 0f, 0f, t, 0f, contraste, 0f, 0f, t, 0f, 0f, contraste, 0f, t, 0f, 0f, 0f, 1f, 0f))
    }

    /** Balance de color: más o menos rojo, verde y azul (−1..1). */
    fun matrizBalance(r: Float, g: Float, b: Float) = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, r * 80f, 0f, 1f, 0f, 0f, g * 80f, 0f, 0f, 1f, 0f, b * 80f, 0f, 0f, 0f, 1f, 0f))

    fun matrizInvertir() = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))

    /** Aplica una matriz de color a la capa (dentro de la selección, si la hay). */
    fun matriz(lz: Lienzo, capa: CapaPx, cm: ColorMatrix, nombre: String): Edicion? {
        if (capa.teselas.isEmpty()) return null
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        return lz.cadaTesela(capa, nombre) { v -> Bitmap.createBitmap(Lienzo.L, Lienzo.L, Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(v, 0f, 0f, p) } }
    }

    fun color(lz: Lienzo, capa: CapaPx, tono: Float, saturacion: Float, brillo: Float): Edicion? = matriz(lz, capa, matrizColor(tono, saturacion, brillo), "Ajuste de color")

    /** Cambia los píxeles de cada tesela con [f] (colores sin premultiplicar). */
    fun pixeles(lz: Lienzo, capa: CapaPx, nombre: String, f: (IntArray, Long) -> Unit): Edicion? {
        if (capa.teselas.isEmpty()) return null
        val L = Lienzo.L
        return lz.cadaTesela(capa, nombre) { v -> val px = IntArray(L * L); v.getPixels(px, 0, L, 0, 0, L, L); f(px, 0L); Bitmap.createBitmap(px, L, L, Bitmap.Config.ARGB_8888) }
    }

    /** Mapa de degradado: cada píxel toma el color de la rampa [colores] según lo claro que sea. */
    fun degradado(lz: Lienzo, capa: CapaPx, colores: IntArray): Edicion? {
        val tabla = IntArray(256) { i ->
            val t = i / 255f * (colores.size - 1); val k = t.toInt().coerceAtMost(colores.size - 2); val f = t - k
            val a = colores[k]; val b = colores[k + 1]
            fun m(sh: Int) = (((a shr sh) and 255) + (((b shr sh) and 255) - ((a shr sh) and 255)) * f).toInt()
            (m(16) shl 16) or (m(8) shl 8) or m(0)
        }
        return pixeles(lz, capa, "Mapa de degradado") { px, _ ->
            for (i in px.indices) { val c = px[i]; val al = c ushr 24; if (al == 0) continue; val l = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000; px[i] = (al shl 24) or tabla[l] }
        }
    }

    /** Ruido: granula los colores (cantidad 0..1). */
    fun ruido(lz: Lienzo, capa: CapaPx, cantidad: Float): Edicion? {
        val k = (cantidad * 90f).toInt()
        var semilla = 12345
        return pixeles(lz, capa, "Ruido") { px, _ ->
            for (i in px.indices) {
                val c = px[i]; val al = c ushr 24; if (al == 0) continue
                semilla = semilla * 1103515245 + 12345
                val d = ((semilla ushr 16) and 255) * k / 128 - k
                px[i] = (al shl 24) or ((((c shr 16) and 255) + d).coerceIn(0, 255) shl 16) or ((((c shr 8) and 255) + d).coerceIn(0, 255) shl 8) or ((c and 255) + d).coerceIn(0, 255)
            }
        }
    }

    private fun tonoMatriz(grados: Float): ColorMatrix {
        val a = Math.toRadians(grados.toDouble()); val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        return ColorMatrix(floatArrayOf(
            lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0f, 0f,
            lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.14f, lb + c * (-lb) + s * (-0.283f), 0f, 0f,
            lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
    }

    /**
     * Para los ajustes que miran a los vecinos (desenfocar, enfocar): da a [f] cada tesela con [pad] píxeles de margen
     * (los de las teselas de al lado), en cuatro canales premultiplicados (transparencia, rojo, verde, azul), y [f] los cambia.
     */
    private fun conMargen(lz: Lienzo, capa: CapaPx, pad: Int, nombre: String, f: (Array<FloatArray>, Int) -> Unit): Edicion? {
        if (capa.teselas.isEmpty()) return null
        val L = Lienzo.L
        val origen = capa.teselas.toMap()
        val claves = HashSet<Long>()
        val m = (pad + L - 1) / L
        for (k in origen.keys) for (dy in -m..m) for (dx in -m..m) { val t = Lienzo.clave(Lienzo.tx(k) + dx, Lienzo.ty(k) + dy); if (lz.dentro(Lienzo.tx(t), Lienzo.ty(t))) claves += t }
        val despues = java.util.concurrent.ConcurrentHashMap<Long, Bitmap>()
        val w = L + pad * 2
        val tareas = claves.map { k -> java.util.concurrent.Callable {
            val x0 = Lienzo.tx(k) * L - pad; val y0 = Lienzo.ty(k) * L - pad
            val px = IntArray(w * w)
            for (ty in Math.floorDiv(y0, L)..Math.floorDiv(y0 + w - 1, L)) for (tx in Math.floorDiv(x0, L)..Math.floorDiv(x0 + w - 1, L)) {
                val b = origen[Lienzo.clave(tx, ty)] ?: continue
                val ix0 = max(x0, tx * L); val iy0 = max(y0, ty * L); val ix1 = min(x0 + w, (tx + 1) * L); val iy1 = min(y0 + w, (ty + 1) * L)
                b.getPixels(px, (iy0 - y0) * w + (ix0 - x0), w, ix0 - tx * L, iy0 - ty * L, ix1 - ix0, iy1 - iy0)
            }
            if (px.all { (it ushr 24) == 0 }) return@Callable
            val ch = Array(4) { FloatArray(w * w) }
            for (i in px.indices) { val a = (px[i] ushr 24) / 255f; ch[0][i] = a; ch[1][i] = ((px[i] shr 16) and 255) / 255f * a; ch[2][i] = ((px[i] shr 8) and 255) / 255f * a; ch[3][i] = (px[i] and 255) / 255f * a }
            f(ch, w)
            val out = IntArray(L * L)
            for (y in 0 until L) for (x in 0 until L) {
                val i = (y + pad) * w + x + pad; val a = ch[0][i].coerceIn(0f, 1f)
                out[y * L + x] = if (a <= 0.002f) 0 else ((a * 255).toInt().coerceIn(0, 255) shl 24) or (((ch[1][i] / a) * 255).toInt().coerceIn(0, 255) shl 16) or
                    (((ch[2][i] / a) * 255).toInt().coerceIn(0, 255) shl 8) or ((ch[3][i] / a) * 255).toInt().coerceIn(0, 255)
            }
            despues[k] = lz.enSeleccion(k, origen[k], Bitmap.createBitmap(out, L, L, Bitmap.Config.ARGB_8888))
        } }
        Motor.reparto.invokeAll(tareas).forEach { it.get() }
        return lz.cambia(capa, nombre, HashMap<Long, Bitmap?>(despues))
    }

    /** Desenfoque gaussiano (tres cajas) de [radio] px, sin cortes entre teselas. */
    fun desenfoca(lz: Lienzo, capa: CapaPx, radio: Int): Edicion? {
        if (radio < 1) return null
        return conMargen(lz, capa, radio * 3, "Desenfoque") { ch, w -> for (c in ch) repeat(3) { caja(c, w, w, radio) } }
    }

    /** Desenfoque de movimiento: arrastra la imagen [largo] px en la dirección [grados]. */
    fun movimiento(lz: Lienzo, capa: CapaPx, largo: Int, grados: Float): Edicion? {
        if (largo < 1) return null
        val dx = Math.cos(Math.toRadians(grados.toDouble())).toFloat(); val dy = Math.sin(Math.toRadians(grados.toDouble())).toFloat()
        return conMargen(lz, capa, largo, "Desenfoque de movimiento") { ch, w ->
            for (c in ch) {
                val o = c.copyOf()
                for (y in 0 until w) for (x in 0 until w) {
                    var s = 0f; var n = 0
                    for (t in -largo..largo) { val sx = (x + dx * t).toInt(); val sy = (y + dy * t).toInt(); if (sx in 0 until w && sy in 0 until w) { s += o[sy * w + sx]; n++ } }
                    c[y * w + x] = s / n
                }
            }
        }
    }

    /** Enfocar: realza los bordes (cantidad 0..2). */
    fun enfoca(lz: Lienzo, capa: CapaPx, cantidad: Float): Edicion? {
        if (cantidad <= 0.01f) return null
        return conMargen(lz, capa, 4, "Enfocar") { ch, w ->
            val a = ch[0]
            for (ci in 1..3) {
                val c = ch[ci]; val b = c.copyOf(); caja(b, w, w, 1)
                for (i in c.indices) c[i] = (c[i] + (c[i] - b[i]) * cantidad * 1.5f).coerceIn(0f, a[i])
            }
        }
    }

    private fun caja(a: FloatArray, w: Int, h: Int, r: Int) {
        val t = FloatArray(a.size)
        val k = 1f / (2 * r + 1)
        for (y in 0 until h) { var s = 0f; val o = y * w; for (x in -r..r) s += a[o + x.coerceIn(0, w - 1)]; for (x in 0 until w) { t[o + x] = s * k; s += a[o + (x + r + 1).coerceAtMost(w - 1)] - a[o + (x - r).coerceAtLeast(0)] } }
        for (x in 0 until w) { var s = 0f; for (y in -r..r) s += t[y.coerceIn(0, h - 1) * w + x]; for (y in 0 until h) { a[y * w + x] = s * k; s += t[(y + r + 1).coerceAtMost(h - 1) * w + x] - t[(y - r).coerceAtLeast(0) * w + x] } }
    }

    /** Aplica una transformación (mover, escalar, girar, voltear) a toda la capa: sus teselas se rehacen con la matriz [m]. */
    fun transforma(lz: Lienzo, capa: CapaPx, m: Matrix): Edicion? {
        val L = Lienzo.L
        val origen = capa.teselas.toMap()
        if (origen.isEmpty()) return null
        val caja = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (k in origen.keys) caja.union(Lienzo.tx(k) * L.toFloat(), Lienzo.ty(k) * L.toFloat(), (Lienzo.tx(k) + 1) * L.toFloat(), (Lienzo.ty(k) + 1) * L.toFloat())
        val dest = RectF(); m.mapRect(dest, caja)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val nuevas = HashMap<Long, Bitmap>()
        for (ty in floor(dest.top / L).toInt()..floor(dest.bottom / L).toInt()) for (tx in floor(dest.left / L).toInt()..floor(dest.right / L).toInt()) {
            if (!lz.dentro(tx, ty)) continue
            val b = Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.translate(-tx * L.toFloat(), -ty * L.toFloat()); c.concat(m)
            val vis = RectF(tx * L.toFloat(), ty * L.toFloat(), (tx + 1) * L.toFloat(), (ty + 1) * L.toFloat())
            val inv = Matrix(); m.invert(inv); val src = RectF(); inv.mapRect(src, vis); src.inset(-2f, -2f)
            var algo = false
            for ((k, s) in origen) {
                val x = Lienzo.tx(k) * L.toFloat(); val y = Lienzo.ty(k) * L.toFloat()
                if (x > src.right || x + L < src.left || y > src.bottom || y + L < src.top) continue
                c.drawBitmap(s, x, y, p); algo = true
            }
            if (algo) nuevas[Lienzo.clave(tx, ty)] = b
        }
        val despues = HashMap<Long, Bitmap?>()
        for (k in origen.keys) despues[k] = null
        for ((k, b) in nuevas) despues[k] = b
        return lz.cambia(capa, "Transformar", despues)
    }

    /** Lo dibujado en la capa como un solo mapa de bits (para ver la transformación mientras se hace) y dónde va. */
    fun vistaCapa(capa: CapaPx, maxLado: Int = 2048): Pair<Bitmap, RectF>? {
        val L = Lienzo.L
        if (capa.teselas.isEmpty()) return null
        val caja = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (k in capa.teselas.keys) caja.union(Lienzo.tx(k) * L.toFloat(), Lienzo.ty(k) * L.toFloat(), (Lienzo.tx(k) + 1) * L.toFloat(), (Lienzo.ty(k) + 1) * L.toFloat())
        val k = min(1f, maxLado / max(caja.width(), caja.height()))
        val b = Bitmap.createBitmap(max(1, (caja.width() * k).toInt()), max(1, (caja.height() * k).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.scale(k, k); c.translate(-caja.left, -caja.top)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((t, s) in capa.teselas) c.drawBitmap(s, Lienzo.tx(t) * L.toFloat(), Lienzo.ty(t) * L.toFloat(), p)
        return b to caja
    }
}

/**
 * Licuar: empuja, arremolina, pellizca o hincha la pintura de la capa bajo el dedo. Trabaja sobre copias de las teselas
 * (para deshacer) y solo dentro de la selección, si la hay.
 */
class Licuar(private val lz: Lienzo, private val capa: CapaPx, private val modo: String, private val radio: Float, private val fuerza: Float) {
    private val antes = HashMap<Long, Bitmap?>()
    private val fondos = HashMap<Long, Fondo>()
    private val trabajo = HashMap<Long, Bitmap>()
    private var px = Float.NaN; private var py = Float.NaN
    private val L = Lienzo.L

    private fun tesela(k: Long): Bitmap? {
        trabajo[k]?.let { return it }
        if (!lz.dentro(Lienzo.tx(k), Lienzo.ty(k))) return null
        val v = capa.teselas[k]
        antes[k] = v
        capa.fondo.remove(k)?.let { fondos[k] = it }
        val b = v?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
        trabajo[k] = b
        return b
    }

    private fun lee(x0: Int, y0: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (ty in Math.floorDiv(y0, L)..Math.floorDiv(y0 + h - 1, L)) for (tx in Math.floorDiv(x0, L)..Math.floorDiv(x0 + w - 1, L)) {
            val b = trabajo[Lienzo.clave(tx, ty)] ?: capa.teselas[Lienzo.clave(tx, ty)] ?: continue
            val ix0 = max(x0, tx * L); val iy0 = max(y0, ty * L); val ix1 = min(x0 + w, (tx + 1) * L); val iy1 = min(y0 + h, (ty + 1) * L)
            b.getPixels(out, (iy0 - y0) * w + (ix0 - x0), w, ix0 - tx * L, iy0 - ty * L, ix1 - ix0, iy1 - iy0)
        }
        return out
    }

    private fun escribe(x0: Int, y0: Int, w: Int, h: Int, d: IntArray) {
        for (ty in Math.floorDiv(y0, L)..Math.floorDiv(y0 + h - 1, L)) for (tx in Math.floorDiv(x0, L)..Math.floorDiv(x0 + w - 1, L)) {
            val b = tesela(Lienzo.clave(tx, ty)) ?: continue
            val ix0 = max(x0, tx * L); val iy0 = max(y0, ty * L); val ix1 = min(x0 + w, (tx + 1) * L); val iy1 = min(y0 + h, (ty + 1) * L)
            b.setPixels(d, (iy0 - y0) * w + (ix0 - x0), w, ix0 - tx * L, iy0 - ty * L, ix1 - ix0, iy1 - iy0)
        }
        for ((k, b) in trabajo) capa.teselas[k] = b
    }

    /** Un punto del gesto (píxeles del lienzo). Devuelve la zona cambiada. */
    fun punto(x: Float, y: Float, presion: Float): RectF? {
        if (px.isNaN()) { px = x; py = y; if (modo == "empujar") return null }
        val dx = x - px; val dy = y - py
        val d = hypot(dx, dy)
        if (modo == "empujar" && d < 1f) return null
        val r = radio
        val pad = (if (modo == "empujar") d else 0f) + 2f
        val x0 = floor(x - r - pad).toInt(); val y0 = floor(y - r - pad).toInt(); val w = ceil((r + pad) * 2).toInt() + 1
        val src = lee(x0, y0, w, w)
        val out = src.copyOf()
        val f = fuerza * (0.5f + 0.5f * presion)
        val cx = x - x0; val cy = y - y0
        for (j in 0 until w) for (i in 0 until w) {
            val ox = i + 0.5f - cx; val oy = j + 0.5f - cy
            val dist = hypot(ox, oy) / r
            if (dist >= 1f) continue
            val peso = (1f - dist * dist).let { it * it }
            var sx = i + 0.5f; var sy = j + 0.5f
            when (modo) {
                "empujar" -> { sx -= dx * peso * f; sy -= dy * peso * f }
                "remolino", "remolino2" -> { val a = (if (modo == "remolino") 0.12f else -0.12f) * peso * f; val c = Math.cos(a.toDouble()).toFloat(); val s = Math.sin(a.toDouble()).toFloat(); sx = cx + ox * c - oy * s; sy = cy + ox * s + oy * c }
                "pellizcar" -> { val k = 1f + 0.1f * peso * f; sx = cx + ox * k; sy = cy + oy * k }
                else -> { val k = 1f - 0.1f * peso * f; sx = cx + ox * k; sy = cy + oy * k }
            }
            out[j * w + i] = muestra(src, w, sx - 0.5f, sy - 0.5f)
        }
        // Solo dentro de la selección
        escribe(x0, y0, w, w, out)
        px = x; py = y
        return RectF(x0.toFloat(), y0.toFloat(), (x0 + w).toFloat(), (y0 + w).toFloat())
    }

    /** El color en un punto entre píxeles (mezcla de los cuatro de alrededor, contando su transparencia). */
    private fun muestra(src: IntArray, w: Int, x: Float, y: Float): Int {
        val xa = floor(x).toInt(); val ya = floor(y).toInt(); val tx = x - xa; val ty = y - ya
        var a = 0f; var r = 0f; var g = 0f; var b = 0f
        for (q in 0..3) {
            val xx = xa + (q and 1); val yy = ya + (q shr 1)
            if (xx < 0 || yy < 0 || xx >= w || yy >= w) continue
            val c = src[yy * w + xx]; val al = (c ushr 24) / 255f
            val peso = (if (q and 1 == 0) 1f - tx else tx) * (if (q shr 1 == 0) 1f - ty else ty) * al
            a += peso; r += ((c shr 16) and 255) * peso; g += ((c shr 8) and 255) * peso; b += (c and 255) * peso
        }
        if (a <= 0.003f) return 0
        return ((a * 255).toInt().coerceIn(0, 255) shl 24) or ((r / a).toInt().coerceIn(0, 255) shl 16) or ((g / a).toInt().coerceIn(0, 255) shl 8) or (b / a).toInt().coerceIn(0, 255)
    }

    fun termina(): Edicion? {
        if (antes.isEmpty()) return null
        val despues = HashMap<Long, Bitmap?>()
        for ((k, b) in trabajo) despues[k] = lz.enSeleccion(k, antes[k], b.copy(Bitmap.Config.ARGB_8888, false))
        synchronized(lz.cierre) {
            for ((k, v) in antes) if (v == null) capa.teselas.remove(k) else capa.teselas[k] = v
            for ((k, f) in fondos) capa.fondo[k] = f
        }
        return lz.cambia(capa, "Licuar", despues)
    }
}
