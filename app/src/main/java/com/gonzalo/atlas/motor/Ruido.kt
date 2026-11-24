package com.gonzalo.atlas.motor

import kotlin.math.abs
import kotlin.math.floor

/**
 * Ruido de gradiente (Perlin mejorado) con semilla, en coordenadas del mundo. Todo con enteros para el hash: el mismo
 * mapa sale igual en cualquier móvil (en la versión de Godot, el hash con decimales de la GPU daba rayas en algunos).
 */
class Ruido(semilla: Long) {
    private val p = IntArray(512)

    init {
        val perm = IntArray(256) { it }
        var s = semilla xor 0x5DEECE66DL
        for (i in 255 downTo 1) {
            s = s * 6364136223846793005L + 1442695040888963407L
            val j = ((s ushr 33) % (i + 1)).toInt()
            val t = perm[i]; perm[i] = perm[j]; perm[j] = t
        }
        for (i in 0 until 512) p[i] = perm[i and 255]
    }

    private fun fade(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun grad(h: Int, x: Float, y: Float): Float = when (h and 7) {
        0 -> x + y; 1 -> -x + y; 2 -> x - y; 3 -> -x - y
        4 -> x; 5 -> -x; 6 -> y; else -> -y
    }

    /** En −1..1 (más o menos). */
    fun ruido(x: Float, y: Float): Float {
        val fx = floor(x); val fy = floor(y)
        val xi = fx.toInt() and 255; val yi = fy.toInt() and 255
        val xf = x - fx; val yf = y - fy
        val u = fade(xf); val v = fade(yf)
        val aa = p[p[xi] + yi]; val ab = p[p[xi] + yi + 1]
        val ba = p[p[xi + 1] + yi]; val bb = p[p[xi + 1] + yi + 1]
        val r = lerp(lerp(grad(aa, xf, yf), grad(ba, xf - 1f, yf), u), lerp(grad(ab, xf, yf - 1f), grad(bb, xf - 1f, yf - 1f), u), v)
        return r * 1.41f
    }

    /** Suma de octavas (fBm), en −1..1 aproximadamente. */
    fun fbm(x: Float, y: Float, octavas: Int, ganancia: Float = 0.5f, lagunaridad: Float = 2.03f): Float {
        var a = 1f; var f = 1f; var s = 0f; var n = 0f
        for (i in 0 until octavas) {
            s += a * ruido(x * f + i * 17.13f, y * f - i * 9.71f)
            n += a; a *= ganancia; f *= lagunaridad
        }
        return s / n
    }

    /** Crestas (montañas): 0..1, con picos afilados. */
    fun crestas(x: Float, y: Float, octavas: Int): Float {
        var a = 1f; var f = 1f; var s = 0f; var n = 0f; var peso = 1f
        for (i in 0 until octavas) {
            var r = 1f - abs(ruido(x * f + i * 31.7f, y * f + i * 11.3f))
            r *= r
            r *= peso
            peso = (r * 1.6f).coerceIn(0f, 1f)
            s += a * r; n += a; a *= 0.5f; f *= 2.07f
        }
        return s / n
    }

    companion object {
        /** Un hash entero de una casilla (para lo que se reparte al azar pero fijo: casas, puntos, motas). */
        fun hash(x: Int, y: Int, semilla: Int = 0): Int {
            var h = x * 374761393 + y * 668265263 + semilla * 1274126177
            h = (h xor (h ushr 13)) * 1274126177
            return h xor (h ushr 16)
        }

        /** El mismo hash en 0..1. */
        fun hash01(x: Int, y: Int, semilla: Int = 0): Float = (hash(x, y, semilla) ushr 8) / 16777216f
    }
}

/** Una textura de ruido que se repite, precalculada: para el render (mirar una tabla es mucho más rápido que calcular ruido). */
class RuidoTabla(val lado: Int, semilla: Long, escala: Float, octavas: Int) {
    val v = FloatArray(lado * lado)

    init {
        val r = Ruido(semilla)
        // Para que se repita sin costuras: mezcla de cuatro copias desplazadas
        for (y in 0 until lado) for (x in 0 until lado) {
            val fx = x.toFloat() / lado; val fy = y.toFloat() / lado
            val s = escala / lado
            val a = r.fbm(x * s, y * s, octavas)
            val b = r.fbm((x - lado) * s, y * s, octavas)
            val c = r.fbm(x * s, (y - lado) * s, octavas)
            val d = r.fbm((x - lado) * s, (y - lado) * s, octavas)
            v[y * lado + x] = a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + d * fx * fy
        }
        // Normaliza a −1..1
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (q in v) { if (q < mn) mn = q; if (q > mx) mx = q }
        val k = if (mx > mn) 2f / (mx - mn) else 1f
        for (i in v.indices) v[i] = (v[i] - mn) * k - 1f
    }

    private val m = lado - 1

    fun en(x: Int, y: Int): Float = v[(y and m) * lado + (x and m)]

    /** Con interpolación (x, y en casillas de la tabla). */
    fun suave(x: Float, y: Float): Float {
        val fx = floor(x); val fy = floor(y)
        val ix = fx.toInt(); val iy = fy.toInt()
        val tx = x - fx; val ty = y - fy
        val a = en(ix, iy); val b = en(ix + 1, iy); val c = en(ix, iy + 1); val d = en(ix + 1, iy + 1)
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty
    }
}
