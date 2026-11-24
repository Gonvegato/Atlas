package com.gonzalo.atlas.lienzo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Formas rápidas (como QuickShape en Procreate): si al acabar un trazo dejas el lápiz quieto, lo dibujado a mano se
 * convierte en la forma limpia a la que se parece: una recta, un arco, una línea quebrada, una elipse o un polígono.
 */
object Formas {
    /** [pts]: x, y de la línea (cerrada o no). [cx], [cy]: su centro (para girarla y escalarla arrastrando). */
    class Forma(val nombre: String, val pts: FloatArray, val cerrada: Boolean) {
        val cx: Float; val cy: Float
        init { var sx = 0f; var sy = 0f; val n = pts.size / 2; for (i in 0 until n) { sx += pts[i * 2]; sy += pts[i * 2 + 1] }; cx = sx / n; cy = sy / n }

        /** La misma forma escalada [k] y girada [giro] (radianes) alrededor de su centro. */
        fun movida(k: Float, giro: Float): Forma {
            val c = cos(giro); val s = sin(giro)
            return Forma(nombre, FloatArray(pts.size) { i -> val j = i - i % 2; val dx = (pts[j] - cx) * k; val dy = (pts[j + 1] - cy) * k; if (i % 2 == 0) cx + dx * c - dy * s else cy + dx * s + dy * c }, cerrada)
        }

        /** La forma como puntos de un trazo (x, y, presión, inclinación, acimut, tiempo), uno cada [paso] px (las esquinas, exactas). */
        fun trazo(paso: Float, presion: Float = 0.8f): FloatArray {
            val out = FloatLista()
            val n = pts.size / 2
            var t = 0f
            fun pon(x: Float, y: Float) { out.pon(x); out.pon(y); out.pon(presion); out.pon(0.3f); out.pon(0f); out.pon(t); t += 4f }
            val total = if (cerrada) n else n - 1
            for (i in 0 until total) {
                val x0 = pts[i * 2]; val y0 = pts[i * 2 + 1]; val j = (i + 1) % n; val x1 = pts[j * 2]; val y1 = pts[j * 2 + 1]
                val d = hypot(x1 - x0, y1 - y0); val k = max(1, (d / paso).toInt())
                for (q in 0 until k) pon(x0 + (x1 - x0) * q / k, y0 + (y1 - y0) * q / k)
            }
            val u = if (cerrada) 0 else n - 1
            pon(pts[u * 2], pts[u * 2 + 1])
            return out.copia()
        }
    }

    /** La recta entre dos puntos (para alargarla arrastrando). */
    fun recta(x0: Float, y0: Float, x1: Float, y1: Float) = Forma("Recta", floatArrayOf(x0, y0, x1, y1), false)

    /** La forma a la que se parece la línea ([x], [y], [n] puntos), o null si es demasiado corta. */
    fun reconoce(x: FloatArray, y: FloatArray, n: Int): Forma? {
        if (n < 5) return null
        var largo = 0f
        var x0 = x[0]; var y0 = y[0]; var x1 = x[0]; var y1 = y[0]
        for (i in 1 until n) { largo += hypot(x[i] - x[i - 1], y[i] - y[i - 1]); x0 = min(x0, x[i]); y0 = min(y0, y[i]); x1 = max(x1, x[i]); y1 = max(y1, y[i]) }
        if (largo < 1e-3f) return null
        val cuerda = hypot(x[n - 1] - x[0], y[n - 1] - y[0])
        val cerrada = cuerda < largo * 0.18f
        if (!cerrada) {
            // Lo que más se separa de la recta entre los extremos
            var dev = 0f; var lejos = 0
            for (i in 1 until n - 1) { val d = distRecta(x[i], y[i], x[0], y[0], x[n - 1], y[n - 1]); if (d > dev) { dev = d; lejos = i } }
            if (dev < cuerda * 0.09f) return recta(x[0], y[0], x[n - 1], y[n - 1])
            // Un arco: la curva que pasa por los extremos y por el punto más separado
            val mx = (x[0] + x[n - 1]) / 2f; val my = (y[0] + y[n - 1]) / 2f
            val qx = 2f * x[lejos] - mx; val qy = 2f * y[lejos] - my
            val m = 48
            val arco = FloatArray((m + 1) * 2) { i -> val t = (i / 2) / m.toFloat(); val u = 1f - t; if (i % 2 == 0) u * u * x[0] + 2 * u * t * qx + t * t * x[n - 1] else u * u * y[0] + 2 * u * t * qy + t * t * y[n - 1] }
            val ax = FloatArray(m + 1) { arco[it * 2] }; val ay = FloatArray(m + 1) { arco[it * 2 + 1] }
            val errArco = errorPoligono(x, y, n, IntArray(m + 1) { it }, false, ax, ay)
            // ¿O una línea quebrada (tramos rectos con esquinas)? Solo si se ajusta claramente mejor que el arco
            val v = rdp(x, y, n, largo * 0.045f, false)
            if (v.size in 3..7) { val errQ = errorPoligono(x, y, n, v, false); if (errQ < largo * 0.014f && errQ < errArco * 0.6f) return Forma("Línea quebrada", FloatArray(v.size * 2) { i -> if (i % 2 == 0) x[v[i / 2]] else y[v[i / 2]] }, false) }
            return Forma("Arco", arco, false)
        }
        // Cerrada: elipse o polígono. Primero, puntos repartidos por igual a lo largo
        val m = 96; val rx = FloatArray(m); val ry = FloatArray(m)
        var acc = 0f; var j = 1; val paso = largo / m
        rx[0] = x[0]; ry[0] = y[0]
        var px = x[0]; var py = y[0]; var k = 1
        while (k < m && j < n) {
            val d = hypot(x[j] - px, y[j] - py)
            if (acc + d >= paso) { val f = (paso - acc) / d; px += (x[j] - px) * f; py += (y[j] - py) * f; rx[k] = px; ry[k] = py; k++; acc = 0f } else { acc += d; px = x[j]; py = y[j]; j++ }
        }
        while (k < m) { rx[k] = x[n - 1]; ry[k] = y[n - 1]; k++ }
        var cx = 0f; var cy = 0f
        for (i in 0 until m) { cx += rx[i]; cy += ry[i] }; cx /= m; cy /= m
        // Ejes principales (la elipse girada que mejor la envuelve)
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in 0 until m) { val dx = rx[i] - cx; val dy = ry[i] - cy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        sxx /= m; syy /= m; sxy /= m
        val ang = 0.5f * atan2(2f * sxy, sxx - syy)
        val co = cos(ang); val si = sin(ang)
        val l1 = sxx * co * co + 2f * sxy * co * si + syy * si * si; val l2 = sxx * si * si - 2f * sxy * co * si + syy * co * co
        var a = sqrt(max(2f * l1, 1e-3f)); var b = sqrt(max(2f * l2, 1e-3f))
        var errE = 0f
        for (i in 0 until m) { val dx = rx[i] - cx; val dy = ry[i] - cy; val u = (dx * co + dy * si) / a; val w = (-dx * si + dy * co) / b; errE += abs(sqrt(u * u + w * w) - 1f) }
        errE /= m
        val v = rdp(rx, ry, m, largo * 0.04f, true)
        val errP = if (v.size in 3..8) errorPoligono(rx, ry, m, v, true) / (largo / (2f * PI.toFloat())) else 9f
        if (v.size in 3..6 && errP < 0.075f && errP < errE * 0.9f) {
            if (v.size == 4) rectangulo(rx, ry, v)?.let { return it }
            return Forma(when (v.size) { 3 -> "Triángulo"; 4 -> "Cuadrilátero"; 5 -> "Pentágono"; else -> "Hexágono" }, FloatArray(v.size * 2) { i -> if (i % 2 == 0) rx[v[i / 2]] else ry[v[i / 2]] }, true)
        }
        if (errE > 0.2f) return null
        val circulo = abs(a - b) < max(a, b) * 0.14f
        if (circulo) { a = (a + b) / 2f; b = a }
        val q = 96
        return Forma(if (circulo) "Círculo" else "Elipse", FloatArray(q * 2) { i -> val t = (i / 2) * 2f * PI.toFloat() / q; val u = cos(t) * a; val w = sin(t) * b; if (i % 2 == 0) cx + u * co - w * si else cy + u * si + w * co }, true)
    }

    /** Si los cuatro vértices hacen ángulos casi rectos, el rectángulo (girado como esté) que los ajusta. */
    private fun rectangulo(x: FloatArray, y: FloatArray, v: IntArray): Forma? {
        val px = FloatArray(4) { x[v[it]] }; val py = FloatArray(4) { y[v[it]] }
        for (i in 0 until 4) {
            val ax = px[(i + 3) % 4] - px[i]; val ay = py[(i + 3) % 4] - py[i]; val bx = px[(i + 1) % 4] - px[i]; val by = py[(i + 1) % 4] - py[i]
            val c = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by) + 1e-6f)
            if (abs(c) > 0.3f) return null
        }
        // Dirección media de los lados opuestos y sus largos medios
        var dx = (px[1] - px[0]) + (px[2] - px[3]); var dy = (py[1] - py[0]) + (py[2] - py[3])
        val dl = hypot(dx, dy); dx /= dl; dy /= dl
        val w = (hypot(px[1] - px[0], py[1] - py[0]) + hypot(px[2] - px[3], py[2] - py[3])) / 4f
        val h = (hypot(px[3] - px[0], py[3] - py[0]) + hypot(px[2] - px[1], py[2] - py[1])) / 4f
        // Si está casi derecho, derecho del todo
        if (abs(dy) < 0.09f) { dx = if (dx > 0) 1f else -1f; dy = 0f } else if (abs(dx) < 0.09f) { dy = if (dy > 0) 1f else -1f; dx = 0f }
        val cx = px.sum() / 4f; val cy = py.sum() / 4f
        var hx = -dy; var hy = dx
        if ((px[3] - px[0]) * hx + (py[3] - py[0]) * hy < 0) { hx = -hx; hy = -hy }
        val cuadrado = abs(w - h) < max(w, h) * 0.1f
        val ww = if (cuadrado) (w + h) / 2f else w; val hh = if (cuadrado) (w + h) / 2f else h
        return Forma(if (cuadrado) "Cuadrado" else "Rectángulo", floatArrayOf(
            cx - dx * ww - hx * hh, cy - dy * ww - hy * hh, cx + dx * ww - hx * hh, cy + dy * ww - hy * hh,
            cx + dx * ww + hx * hh, cy + dy * ww + hy * hh, cx - dx * ww + hx * hh, cy - dy * ww + hy * hh), true)
    }

    private fun distRecta(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        if (l2 < 1e-9f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    /** Los vértices que quedan al quitar los puntos que casi no cambian la línea (índices). En las cerradas, sin repetir el primero. */
    private fun rdp(x: FloatArray, y: FloatArray, n: Int, tol: Float, cerrada: Boolean): IntArray {
        val keep = BooleanArray(n)
        fun rec(a: Int, b: Int) {
            var md = 0f; var mi = -1
            for (i in a + 1 until b) { val d = distRecta(x[i], y[i], x[a], y[a], x[b], y[b]); if (d > md) { md = d; mi = i } }
            if (md > tol && mi > 0) { keep[mi] = true; rec(a, mi); rec(mi, b) }
        }
        if (!cerrada) { keep[0] = true; keep[n - 1] = true; rec(0, n - 1); return (0 until n).filter { keep[it] }.toIntArray() }
        // Cerrada: se parte por el punto más lejano del primero
        var lejos = 0; var md = 0f
        for (i in 1 until n) { val d = hypot(x[i] - x[0], y[i] - y[0]); if (d > md) { md = d; lejos = i } }
        keep[0] = true; keep[lejos] = true
        rec(0, lejos)
        // la vuelta: de lejos al final y al principio
        val ex = FloatArray(n - lejos + 1) { if (it < n - lejos) x[lejos + it] else x[0] }; val ey = FloatArray(n - lejos + 1) { if (it < n - lejos) y[lejos + it] else y[0] }
        val k2 = BooleanArray(ex.size)
        fun rec2(a: Int, b: Int) {
            var m2 = 0f; var mi = -1
            for (i in a + 1 until b) { val d = distRecta(ex[i], ey[i], ex[a], ey[a], ex[b], ey[b]); if (d > m2) { m2 = d; mi = i } }
            if (m2 > tol && mi > 0) { k2[mi] = true; rec2(a, mi); rec2(mi, b) }
        }
        rec2(0, ex.size - 1)
        for (i in 1 until ex.size - 1) if (k2[i]) keep[lejos + i] = true
        // El primero puede no ser una esquina de verdad: se quita si queda alineado con sus vecinos
        val idx = (0 until n).filter { keep[it] }.toMutableList()
        if (idx.size > 3) { val a = idx.last(); val b = idx[1]; if (distRecta(x[0], y[0], x[a], y[a], x[b], y[b]) < tol) idx.removeAt(0) }
        return idx.toIntArray()
    }

    /** La distancia media de los puntos al polígono (o a la línea quebrada) de vértices [v]. */
    private fun errorPoligono(x: FloatArray, y: FloatArray, n: Int, v: IntArray, cerrada: Boolean, vx: FloatArray = x, vy: FloatArray = y): Float {
        var s = 0f
        val lados = if (cerrada) v.size else v.size - 1
        for (i in 0 until n) {
            var md = Float.MAX_VALUE
            for (k in 0 until lados) { val a = v[k]; val b = v[(k + 1) % v.size]; val d = distRecta(x[i], y[i], vx[a], vy[a], vx[b], vy[b]); if (d < md) md = d }
            s += md
        }
        return s / n
    }
}
