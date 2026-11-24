package com.gonzalo.atlas.motor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * La forma de una tierra generada: de qué tipo, cuánta parte del recuadro es tierra, cómo de recortada es la costa y cuántas
 * islas sueltas. Con la misma [semilla] sale siempre la misma.
 */
class FormaTierra(var semilla: Long = 1L, var tipo: String = "continente", var tierra: Float = 0.36f, var rugosidad: Float = 0.5f, var islas: Float = 0.35f) {
    companion object {
        val TIPOS = listOf("continente" to "Un continente", "dos" to "Dos continentes", "archipielago" to "Archipiélago", "interior" to "Mar interior", "costa" to "Costa (tierra a un lado)")
    }
}

/**
 * Genera la forma de un continente dentro de un recuadro (tú das el tamaño total; la costa se dibuja sola). Es ruido en
 * varias escalas, deformado y moldeado por la forma general del tipo elegido, y cortado a la altura del mar que deja la
 * parte de tierra pedida.
 */
object Continente {
    /** El relieve en [w] × [h] muestras: positivo, tierra; negativo, mar. */
    fun campo(p: FormaTierra, w: Int, h: Int): FloatArray {
        val r = Ruido(p.semilla * 7919L + 13L); val deforma = Ruido(p.semilla * 104729L + 5L); val fino = Ruido(p.semilla * 31L + 101L)
        val a = FloatArray(w * h)
        val lado = max(w, h).toFloat()
        // Dónde caen las masas de tierra en «dos» (un poco al azar)
        val d1y = (Ruido.hash01(p.semilla.toInt(), 1, 3) - 0.5f) * 0.5f; val d2y = (Ruido.hash01(p.semilla.toInt(), 2, 3) - 0.5f) * 0.5f
        val lado0 = Ruido.hash(p.semilla.toInt(), 5, 9) and 3
        val gan = 0.42f + 0.2f * p.rugosidad
        for (j in 0 until h) for (i in 0 until w) {
            val u = (i + 0.5f) / w; val v = (j + 0.5f) / h
            var x = (i + 0.5f - w / 2f) / lado * 2f; var y = (j + 0.5f - h / 2f) / lado * 2f
            // Deformar el espacio da penínsulas, golfos y costas que no parecen manchas redondas
            val dx = deforma.fbm(x * 1.3f + 11.3f, y * 1.3f - 7.1f, 3); val dy = deforma.fbm(x * 1.3f - 3.7f, y * 1.3f + 19.9f, 3)
            x += dx * 0.32f; y += dy * 0.32f
            val cu = (u - 0.5f) * 2f + dx * 0.25f; val cv = (v - 0.5f) * 2f + dy * 0.25f
            val base = r.fbm(x * 1.9f, y * 1.9f, 6, gan)
            val detalle = fino.fbm(x * 7.5f, y * 7.5f, 4, 0.5f)
            val forma: Float
            val peso: Float
            when (p.tipo) {
                "dos" -> { val a1 = 1f - hypot(cu + 0.5f, (cv - d1y) * 0.9f) * 2.1f; val a2 = 1f - hypot(cu - 0.5f, (cv - d2y) * 0.9f) * 2.1f; forma = max(a1, a2); peso = 0.5f }
                "archipielago" -> { forma = 0.15f - hypot(cu, cv) * 0.3f; peso = 0.9f }
                "interior" -> { forma = 1f - abs(hypot(cu, cv) - 0.6f) * 3.2f; peso = 0.42f }
                "costa" -> { val g = when (lado0) { 0 -> cu; 1 -> -cu; 2 -> cv; else -> -cv }; forma = g * 1.1f; peso = 0.5f }
                else -> { forma = 1f - hypot(cu, cv) * 1.15f; peso = 0.55f }
            }
            var val0 = forma + base * peso + detalle * (0.06f + 0.22f * p.islas) * (0.5f + p.rugosidad)
            // Agua en los bordes del recuadro (la costa no se sale), menos en «costa», donde la tierra sigue por un lado
            val borde = min(min(u, 1f - u), min(v, 1f - v))
            if (p.tipo != "costa") val0 -= suave(1f - borde / 0.07f) * 3f
            a[j * w + i] = val0
        }
        // La altura del mar que deja de tierra la parte pedida
        val orden = a.copyOf(); orden.sort()
        val nivel = orden[((1f - p.tierra.coerceIn(0.03f, 0.95f)) * (orden.size - 1)).toInt()]
        for (i in a.indices) a[i] -= nivel
        return a
    }

    private fun suave(t: Float): Float { val k = t.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }

    /**
     * Las costas: las líneas donde el relieve pasa por cero, como líneas de puntos (x, y en muestras; el centro de la
     * muestra 0 es 0,0). Cerradas, salvo donde la tierra toca el borde del recuadro (ahí se cortan).
     */
    fun costas(a: FloatArray, w: Int, h: Int): List<FloatArray> {
        // Un marco de agua alrededor: así todas las líneas se cierran
        val W = w + 2; val H = h + 2
        val f = FloatArray(W * H) { -1f }
        for (j in 0 until h) for (i in 0 until w) f[(j + 1) * W + i + 1] = a[j * w + i]
        fun en(i: Int, j: Int) = f[j * W + i]
        // Cada cruce está en una arista: horizontal (i,j)-(i+1,j) → 2·(j·W+i); vertical (i,j)-(i,j+1) → 2·(j·W+i)+1
        val sig = HashMap<Int, IntArray>()
        fun une(k1: Int, k2: Int) {
            for ((p, q) in arrayOf(k1 to k2, k2 to k1)) { val v = sig[p]; if (v == null) sig[p] = intArrayOf(q, -1) else if (v[1] == -1) v[1] = q }
        }
        for (j in 0 until H - 1) for (i in 0 until W - 1) {
            val tl = en(i, j) > 0f; val tr = en(i + 1, j) > 0f; val bl = en(i, j + 1) > 0f; val br = en(i + 1, j + 1) > 0f
            val caso = (if (tl) 8 else 0) or (if (tr) 4 else 0) or (if (br) 2 else 0) or (if (bl) 1 else 0)
            if (caso == 0 || caso == 15) continue
            val arriba = 2 * (j * W + i); val abajo = 2 * ((j + 1) * W + i); val izq = 2 * (j * W + i) + 1; val der = 2 * (j * W + i + 1) + 1
            when (caso) {
                1, 14 -> une(izq, abajo)
                2, 13 -> une(abajo, der)
                3, 12 -> une(izq, der)
                4, 11 -> une(arriba, der)
                6, 9 -> une(arriba, abajo)
                7, 8 -> une(arriba, izq)
                5, 10 -> {
                    // Silla: se decide con el valor del centro
                    val centro = (en(i, j) + en(i + 1, j) + en(i, j + 1) + en(i + 1, j + 1)) / 4f > 0f
                    if ((caso == 5) == centro) { une(arriba, izq); une(abajo, der) } else { une(arriba, der); une(izq, abajo) }
                }
            }
        }
        fun punto(k: Int, out: FloatArray, o: Int) {
            val c = k shr 1; val i = c % W; val j = c / W
            if (k and 1 == 0) { val v0 = en(i, j); val v1 = en(i + 1, j); val t = v0 / (v0 - v1); out[o] = i + t - 1f; out[o + 1] = j - 1f }
            else { val v0 = en(i, j); val v1 = en(i, j + 1); val t = v0 / (v0 - v1); out[o] = i - 1f; out[o + 1] = j + t - 1f }
        }
        val visto = HashSet<Int>()
        val lineas = ArrayList<FloatArray>()
        for (k0 in sig.keys) {
            if (k0 in visto) continue
            val ks = ArrayList<Int>()
            var prev = -1; var k = k0
            while (k != -1 && k !in visto) { visto += k; ks += k; val v = sig[k] ?: break; val n = if (v[0] != prev) v[0] else v[1]; prev = k; k = n }
            if (ks.size < 4) continue
            val pts = FloatArray(ks.size * 2)
            for (q in ks.indices) punto(ks[q], pts, q * 2)
            // Donde la línea corre por el marco (la tierra se sale del recuadro), se corta
            val fuera = BooleanArray(ks.size) { q -> val x = pts[q * 2]; val y = pts[q * 2 + 1]; x < -0.01f || y < -0.01f || x > w - 0.99f || y > h - 0.99f }
            if (fuera.none { it }) { lineas += pts; continue }
            val ini = fuera.indexOfFirst { it }
            var trozo = ArrayList<Float>()
            for (s in 1..ks.size) {
                val q = (ini + s) % ks.size
                if (fuera[q]) { if (trozo.size >= 8) lineas += abierta(trozo); trozo = ArrayList() } else { trozo += pts[q * 2]; trozo += pts[q * 2 + 1] }
            }
            if (trozo.size >= 8) lineas += abierta(trozo)
        }
        return lineas
    }

    /** Una línea abierta se marca repitiendo... no: se distingue porque su primer y último punto no coinciden y se guarda con un NaN al final. */
    private fun abierta(l: List<Float>): FloatArray = FloatArray(l.size + 2) { if (it < l.size) l[it] else Float.NaN }

    fun esAbierta(p: FloatArray) = p.size >= 2 && p[p.size - 1].isNaN()
    fun puntos(p: FloatArray) = if (esAbierta(p)) (p.size - 2) / 2 else p.size / 2

    /** Redondea una línea (cortando esquinas) para que no se noten las muestras. */
    fun redondea(p: FloatArray, veces: Int = 2): FloatArray {
        val ab = esAbierta(p)
        var n = puntos(p)
        var a = p.copyOf(n * 2)
        repeat(veces) {
            val m = if (ab) n - 1 else n
            val b = FloatArray((m * 2 + (if (ab) 2 else 0)) * 2)
            var o = 0
            if (ab) { b[o++] = a[0]; b[o++] = a[1] }
            for (i in 0 until m) {
                val j = (i + 1) % n
                val x0 = a[i * 2]; val y0 = a[i * 2 + 1]; val x1 = a[j * 2]; val y1 = a[j * 2 + 1]
                b[o++] = x0 * 0.75f + x1 * 0.25f; b[o++] = y0 * 0.75f + y1 * 0.25f
                b[o++] = x0 * 0.25f + x1 * 0.75f; b[o++] = y0 * 0.25f + y1 * 0.75f
            }
            if (ab) { b[o++] = a[(n - 1) * 2]; b[o++] = a[(n - 1) * 2 + 1] }
            a = b.copyOf(o); n = o / 2
        }
        return if (ab) FloatArray(a.size + 2) { if (it < a.size) a[it] else Float.NaN } else a
    }
}
