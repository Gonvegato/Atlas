package com.gonzalo.atlas.lienzo

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import com.gonzalo.atlas.motor.Mapa
import com.gonzalo.atlas.motor.RuidoTabla
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * El color de los territorios encima del dibujo: un velo del color de cada uno y su borde. Una imagen por tesela, rehecha
 * cuando cambian los territorios o sus colores.
 */
class VeloTerritorios(private val lz: Lienzo) {
    private class E(val bmp: Bitmap, val datos: ByteArray, val colores: Int)
    private val cache = ConcurrentHashMap<Long, E>()
    @Volatile var colores = 0

    fun tesela(k: Long): Bitmap? {
        val a = lz.terr[k] ?: return null
        val e = cache[k]
        if (e != null && e.datos === a && e.colores == colores) return e.bmp
        return construye(k, a).also { cache[k] = E(it, a, colores) }
    }

    private fun construye(k: Long, a: ByteArray): Bitmap {
        val m = lz.mapa
        val L = Lienzo.L
        val tinte = (m.tinteTerritorios.coerceIn(0f, 1f) * 255).toInt()
        val col = IntArray(256) { id -> if (id == 0) 0 else m.colorDe(id) }
        // Los vecinos de fuera de la tesela, para que el borde no se corte en las juntas
        fun v(x: Int, y: Int): Int {
            if (x in 0 until L && y in 0 until L) return a[y * L + x].toInt() and 255
            return lz.territorioEn(Lienzo.tx(k) * L + x, Lienzo.ty(k) * L + y)
        }
        val px = IntArray(L * L)
        for (y in 0 until L) for (x in 0 until L) {
            val id = a[y * L + x].toInt() and 255
            if (id == 0) continue
            val c = col[id]
            val borde = v(x + 1, y) != id || v(x - 1, y) != id || v(x, y + 1) != id || v(x, y - 1) != id ||
                v(x + 2, y) != id || v(x - 2, y) != id || v(x, y + 2) != id || v(x, y - 2) != id
            px[y * L + x] = if (borde) (0xE0 shl 24) or (oscuro(c) and 0xFFFFFF) else (tinte shl 24) or (c and 0xFFFFFF)
        }
        return Bitmap.createBitmap(px, L, L, Bitmap.Config.ARGB_8888)
    }

    fun olvida() { cache.clear(); colores++ }

    companion object {
        fun oscuro(c: Int): Int = Color.rgb((Color.red(c) * 0.6f).toInt(), (Color.green(c) * 0.6f).toInt(), (Color.blue(c) * 0.6f).toInt())
    }
}

/**
 * El dibujo visto de lejos: teselas de 256 px que resumen 2^nivel × 256 px del lienzo (todas las capas y los territorios),
 * hechas en segundo plano. Así un lienzo enorme se mueve igual de suelto que uno pequeño.
 */
class Piramide(private val lz: Lienzo, private val velo: VeloTerritorios, private val alListo: () -> Unit) {
    private class E(@Volatile var bmp: Bitmap?, @Volatile var sucia: Boolean, @Volatile var encolada: Boolean)
    private val cache = ConcurrentHashMap<Long, E>()
    private val hilo = Executors.newFixedThreadPool(2) { r -> Thread(r, "atlas-piramide").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val principal = Handler(Looper.getMainLooper())
    @Volatile var conTerritorios = true

    private fun clave(n: Int, tx: Int, ty: Int) = (n.toLong() shl 58) or ((tx.toLong() and 0x1fffffffL) shl 29) or (ty.toLong() and 0x1fffffffL)

    init {
        lz.alCambiar += { r -> ensucia(r) }
    }

    fun ensucia(r: RectF) {
        for ((k, e) in cache) {
            val n = (k ushr 58).toInt(); val lado = Lienzo.L.toFloat() * (1 shl n)
            var tx = ((k shr 29) and 0x1fffffffL).toInt(); var ty = (k and 0x1fffffffL).toInt()
            if (tx and 0x10000000 != 0) tx = tx or 0xE0000000.toInt(); if (ty and 0x10000000 != 0) ty = ty or 0xE0000000.toInt()
            if (tx * lado < r.right && (tx + 1) * lado > r.left && ty * lado < r.bottom && (ty + 1) * lado > r.top) e.sucia = true
        }
    }

    fun todoSucio() { cache.values.forEach { it.sucia = true } }

    /** La tesela del nivel [n] (o null si aún no está: se pide). */
    fun tesela(n: Int, tx: Int, ty: Int): Bitmap? {
        val k = clave(n, tx, ty)
        val e = cache.getOrPut(k) { E(null, true, false) }
        if ((e.sucia || e.bmp == null) && !e.encolada) {
            e.encolada = true
            hilo.execute {
                e.sucia = false
                val b = runCatching { construye(n, tx, ty) }.getOrNull()
                e.bmp = b; e.encolada = false
                principal.post(alListo)
            }
        }
        if (cache.size > 900) recorta()
        return e.bmp
    }

    /** Si la tesela está hecha y al día. */
    fun limpia(n: Int, tx: Int, ty: Int): Boolean { val e = cache[clave(n, tx, ty)] ?: return false; return e.bmp != null && !e.sucia && !e.encolada }

    private fun recorta() { val it = cache.entries.iterator(); var q = cache.size - 700; while (it.hasNext() && q-- > 0) { it.next(); it.remove() } }

    private val pFiltro = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun construye(n: Int, tx: Int, ty: Int): Bitmap {
        val L = Lienzo.L
        val b = Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val lado = L * (1 shl n)
        val k = 1f / (1 shl n)
        c.scale(k, k)
        c.translate(-tx * lado.toFloat(), -ty * lado.toFloat())
        val r = Rect(tx * lado, ty * lado, (tx + 1) * lado, (ty + 1) * lado)
        lz.componeZona(c, r)
        if (conTerritorios && lz.mapa.verTerritorios) {
            val t0x = Math.floorDiv(r.left, L); val t0y = Math.floorDiv(r.top, L)
            for (yy in t0y until t0y + (1 shl n)) for (xx in t0x until t0x + (1 shl n))
                velo.tesela(Lienzo.clave(xx, yy))?.let { c.drawBitmap(it, xx * L.toFloat(), yy * L.toFloat(), pFiltro) }
        }
        return b
    }

    fun cierra() = hilo.shutdown()
}

/** El papel de fondo (con un grano suave que se repite). */
object Papel {
    private var grano: Bitmap? = null
    fun pintura(m: Mapa): Paint {
        val g = grano ?: run {
            val t = RuidoTabla(256, 91, 40f, 3)
            val px = IntArray(256 * 256) { i -> val v = t.v[i]; if (v >= 0) Color.argb((v * 22).toInt(), 255, 255, 255) else Color.argb((-v * 26).toInt(), 60, 40, 20) }
            Bitmap.createBitmap(px, 256, 256, Bitmap.Config.ARGB_8888).also { grano = it }
        }
        return Paint().apply { shader = if (m.granoPapel) BitmapShader(g, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) else null }
    }

    /** El papel en la zona [r] del lienzo. */
    fun pinta(c: Canvas, m: Mapa, r: RectF) {
        if (Color.alpha(m.papel) == 0) { cuadros(c, r); return }
        c.drawRect(r, Paint().apply { color = m.papel })
        if (m.granoPapel) c.drawRect(r, pintura(m))
    }

    /** Fondo transparente: cuadros grises (como en los programas de dibujo). */
    private fun cuadros(c: Canvas, r: RectF) {
        val p = Paint(); val lado = 32f
        c.drawRect(r, Paint().apply { color = 0xFFFFFFFF.toInt() })
        p.color = 0xFFE6E6E6.toInt()
        var y = floor(r.top / lado) * lado
        while (y < r.bottom) { var x = floor(r.left / lado) * lado; while (x < r.right) { if (((x / lado).toInt() + (y / lado).toInt()) and 1 == 0) c.drawRect(x, y, min(x + lado, r.right), min(y + lado, r.bottom), p); x += lado }; y += lado }
        @Suppress("UNUSED_VARIABLE") val n = max(0, 0)
    }
}
