package com.gonzalo.atlas.lienzo

import java.util.BitSet
import kotlin.math.abs
import kotlin.math.hypot

/**
 * De una zona marcada casilla a casilla (lo que alcanza el bote de pintura) a sus bordes como polígonos, para guardarla
 * como forma y volver a pintarla a cualquier tamaño.
 */
object Contornos {
    /**
     * Los bordes de las casillas marcadas en [m] (una rejilla de [w] × [h], fila a fila), mirando solo las columnas
     * [x0]..[x1] y las filas [y0]..[y1]. Cada borde es un polígono cerrado (x, y en casillas; la esquina de la casilla 0 es
     * 0,0); los agujeros salen como polígonos aparte (se pintan con relleno «par-impar»). Las esquinas de los escalones se
     * matan (un borde en diagonal sale recto, no en escalera) y sobran los puntos que no se separan más de [tol] casillas.
     */
    fun deMascara(m: BitSet, w: Int, h: Int, x0: Int = 0, y0: Int = 0, x1: Int = w - 1, y1: Int = h - 1, tol: Float = 0.3f): List<FloatArray> {
        fun en(x: Int, y: Int) = x >= 0 && y >= 0 && x < w && y < h && m[y * w + x]
        // Se va por las rayas entre casillas dejando lo marcado a la derecha. Direcciones: 0 → , 1 ↓ , 2 ← , 3 ↑
        fun vale(vx: Int, vy: Int, d: Int) = when (d) {
            0 -> en(vx, vy) && !en(vx, vy - 1)
            1 -> en(vx - 1, vy) && !en(vx, vy)
            2 -> en(vx - 1, vy - 1) && !en(vx - 1, vy)
            else -> en(vx, vy - 1) && !en(vx - 1, vy - 1)
        }
        val hechas = BitSet()
        val out = ArrayList<FloatArray>()
        var px = IntArray(1024); var py = IntArray(1024)
        for (y in y0..y1 + 1) for (x in x0..x1) {
            val abajo = en(x, y)
            if (abajo == en(x, y - 1) || hechas[y * w + x]) continue
            var vx = if (abajo) x else x + 1; var vy = y; var d = if (abajo) 0 else 2
            val sx = vx; val sy = vy; val sd = d
            var n = 0
            do {
                if (n == px.size) { px = px.copyOf(n * 2); py = py.copyOf(n * 2) }
                px[n] = vx; py[n] = vy; n++
                when (d) { 0 -> { hechas.set(vy * w + vx); vx++ }; 1 -> vy++; 2 -> { vx--; hechas.set(vy * w + vx) }; else -> vy-- }
                // En cada esquina: a la derecha si se puede, si no recto, si no a la izquierda
                d = when { vale(vx, vy, (d + 1) and 3) -> (d + 1) and 3; vale(vx, vy, d) -> d; else -> (d + 3) and 3 }
            } while ((vx != sx || vy != sy || d != sd) && n < 60_000_000)
            if (n < 4) continue
            // Los puntos medios de cada tramo: las escaleras se vuelven diagonales y las rectas siguen rectas
            val mx = FloatArray(n) { i -> (px[i] + px[(i + 1) % n]) * 0.5f }; val my = FloatArray(n) { i -> (py[i] + py[(i + 1) % n]) * 0.5f }
            out += simplifica(mx, my, n, tol)
        }
        return out
    }

    /** Quita de un polígono cerrado los puntos que no se separan más de [tol] de la línea entre sus vecinos (Douglas-Peucker). */
    private fun simplifica(x: FloatArray, y: FloatArray, n: Int, tol: Float): FloatArray {
        if (n <= 4) return FloatArray(n * 2) { if (it % 2 == 0) x[it / 2] else y[it / 2] }
        // Dos puntos de apoyo: el primero y el más lejano a él
        var lejos = 1; var dm = -1f
        for (i in 1 until n) { val d = hypot(x[i] - x[0], y[i] - y[0]); if (d > dm) { dm = d; lejos = i } }
        val queda = BooleanArray(n); queda[0] = true; queda[lejos] = true
        val pila = ArrayDeque<Int>(); pila.addLast(0); pila.addLast(lejos); pila.addLast(lejos); pila.addLast(n)
        while (pila.isNotEmpty()) {
            val b = pila.removeLast(); val a = pila.removeLast()
            if (b - a < 2) continue
            val ax = x[a]; val ay = y[a]; val bx = x[b % n]; val by = y[b % n]
            val dx = bx - ax; val dy = by - ay; val l = hypot(dx, dy)
            var peor = -1; var dp = tol
            for (i in a + 1 until b) {
                val d = if (l < 1e-6f) hypot(x[i] - ax, y[i] - ay) else abs((x[i] - ax) * dy - (y[i] - ay) * dx) / l
                if (d > dp) { dp = d; peor = i }
            }
            if (peor >= 0) { queda[peor] = true; pila.addLast(a); pila.addLast(peor); pila.addLast(peor); pila.addLast(b) }
        }
        val k = queda.count { it }
        val r = FloatArray(k * 2); var j = 0
        for (i in 0 until n) if (queda[i]) { r[j++] = x[i]; r[j++] = y[i] }
        return r
    }
}
