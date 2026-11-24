package com.gonzalo.atlas.vista

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.gonzalo.atlas.R
import com.gonzalo.atlas.motor.Catalogo
import com.gonzalo.atlas.motor.Linea
import com.gonzalo.atlas.motor.Lugar
import com.gonzalo.atlas.motor.Leyenda
import com.gonzalo.atlas.motor.Mapa
import com.gonzalo.atlas.motor.Territorio
import com.gonzalo.atlas.motor.Medidas
import com.gonzalo.atlas.motor.Rotulo
import com.gonzalo.atlas.motor.Sello
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Las letras de la familia (Bricolage, Inter y Fraunces). */
object Letras {
    lateinit var serif: Typeface; lateinit var serifSemi: Typeface; lateinit var serifCursiva: Typeface
    lateinit var grotesca: Typeface; lateinit var inter: Typeface; lateinit var interSemi: Typeface; lateinit var interNegrita: Typeface
    @Volatile private var listas = false
    fun carga(ctx: Context) {
        if (listas) return
        val r = ctx.resources
        serif = r.getFont(R.font.fraunces_regular); serifSemi = r.getFont(R.font.fraunces_semibold); serifCursiva = r.getFont(R.font.fraunces_italic)
        grotesca = r.getFont(R.font.bricolage); inter = r.getFont(R.font.inter_regular); interSemi = r.getFont(R.font.inter_semibold); interNegrita = r.getFont(R.font.inter_bold)
        listas = true
    }
}

/** Dónde se mira: el centro (en píxeles del mapa), el acercamiento (px de pantalla por px del mapa) y el giro. */
class Vista(var cx: Float = 0f, var cy: Float = 0f, var zoom: Float = 0.5f, var giro: Float = 0f, var ancho: Int = 1, var alto: Int = 1) {
    val m = Matrix()
    private val inv = Matrix()
    private val p = FloatArray(2)

    fun actualiza() {
        m.reset()
        m.postTranslate(-cx, -cy)
        m.postRotate(giro)
        m.postScale(zoom, zoom)
        m.postTranslate(ancho / 2f, alto / 2f)
        m.invert(inv)
    }

    fun aMapa(sx: Float, sy: Float): FloatArray { p[0] = sx; p[1] = sy; inv.mapPoints(p); return floatArrayOf(p[0], p[1]) }
    fun aPantalla(mx: Float, my: Float): FloatArray { p[0] = mx; p[1] = my; m.mapPoints(p); return floatArrayOf(p[0], p[1]) }

    /** El rectángulo del mapa que se ve (contando el giro). */
    fun visible(): RectF {
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for ((x, y) in listOf(0f to 0f, ancho.toFloat() to 0f, 0f to alto.toFloat(), ancho.toFloat() to alto.toFloat())) {
            val q = aMapa(x, y); r.left = min(r.left, q[0]); r.top = min(r.top, q[1]); r.right = max(r.right, q[0]); r.bottom = max(r.bottom, q[1])
        }
        return r
    }
}

/** Lo que va encima del mapa pintado: ríos, caminos, lugares, rótulos, nombres de reinos, tus dibujos, cuadrícula y escala. */
class Capas(private val ctx: Context) {
    private val dens = ctx.resources.displayMetrics.density
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val pHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = PAPEL }
    private val pLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    /** Los nombres de los reinos: dónde ponerlos (el centro de su territorio) y su tamaño. */
    /** [nivel]: 0 los de arriba del todo (imperios), 1 sus reinos… */
    class Centro(val id: Int, val x: Float, val y: Float, val pixeles: Long, val nivel: Int = 0, val esRegion: Boolean = false)

    class Opciones(
        var cuadricula: Boolean = false,
        var nombresReinos: Boolean = true,
        var borrador: Linea? = null,
        var seleccion: Any? = null,
        var regla: FloatArray? = null,
        var seccionBorrador: FloatArray? = null,
        var radioBorrador: Float = 0f,
        var cursor: FloatArray? = null, // x, y (pantalla), radio (pantalla)
        var impresion: Boolean = false,
    )

    init { Letras.carga(ctx) }
    private var cuadriculaVisible = false

    fun dibuja(c: Canvas, mapa: Mapa, v: Vista, centros: List<Centro>, superficies: LongArray, recurso: (String) -> Bitmap?, op: Opciones) {
        val z = v.zoom
        c.save()
        c.concat(v.m)
        cuadriculaVisible = op.cuadricula
        if (op.cuadricula) cuadricula(c, mapa, v)
        secciones(c, mapa, v, op)
        for (k in mapa.rutas) camino(c, k, z, op.seleccion === k)
        op.borrador?.let { b ->
            if (b.tipo == "territorio") {
                pLinea.pathEffect = null; pLinea.color = 0x66000000; pLinea.strokeWidth = op.radioBorrador * 2
                path.reset(); path.moveTo(b.xs[0], b.ys[0]); for (i in 1 until b.n) path.lineTo(b.xs[i], b.ys[i]); c.drawPath(path, pLinea)
            } else camino(c, b, z, false)
        }
        for (s in mapa.sellos) sello(c, s, recurso(s.recurso), op.seleccion === s)
        c.restore()
        // Lo que se escribe va derecho en pantalla (aunque el mapa esté girado)
        if (op.nombresReinos && mapa.verTerritorios) {
            // Si el nombre de un imperio cae encima del de uno de sus reinos, el del imperio sube un poco
            val ajustados = centros.map { ce ->
                val hijo = centros.firstOrNull { o -> o.nivel > ce.nivel && mapa.linaje(o.id).any { it.id == ce.id } && kotlin.math.abs(o.x - ce.x) * v.zoom < 160 * dens && kotlin.math.abs(o.y - ce.y) * v.zoom < 40 * dens }
                if (hijo == null) ce else Centro(ce.id, ce.x, ce.y - 46 * dens / v.zoom, ce.pixeles, ce.nivel)
            }
            for (ce in ajustados) nombreTerritorio(c, mapa, v, ce, op.seleccion)
        }
        for (r in mapa.rotulos) rotulo(c, v, r, op.seleccion === r)
        lugares(c, mapa, v, op.seleccion)
        if (mapa.leyenda.visible) leyenda(c, mapa, v, superficies, op.seleccion === mapa.leyenda)
        op.regla?.let { regla(c, mapa, v, it) }
        if (!op.impresion) {
            escala(c, mapa, v)
            norte(c, v)
            op.cursor?.let { cu ->
                pLinea.strokeWidth = 1.5f * dens; pLinea.pathEffect = null; pLinea.color = Color.WHITE
                c.drawCircle(cu[0], cu[1], cu[2], pLinea)
                pLinea.color = TINTA; pLinea.pathEffect = DashPathEffect(floatArrayOf(5 * dens, 4 * dens), 0f)
                c.drawCircle(cu[0], cu[1], cu[2], pLinea)
                pLinea.pathEffect = null
            }
        }
    }

    // ---------------------------------------------------------------- líneas

    private fun camino(c: Canvas, k: Linea, z: Float, sel: Boolean) {
        if (k.n < 2) return
        val tipo = com.gonzalo.atlas.motor.Catalogo.ruta(k.tipo)
        path.reset(); path.moveTo(k.xs[0], k.ys[0])
        for (i in 1 until k.n) path.lineTo(k.xs[i], k.ys[i])
        val u = dens / z
        val w = max(tipo.ancho, 1.3f * u)
        pLinea.strokeCap = Paint.Cap.ROUND
        if (sel) { pLinea.pathEffect = null; pLinea.color = (ACENTO and 0xFFFFFF) or 0x88000000.toInt(); pLinea.strokeWidth = w + 8 * u; c.drawPath(path, pLinea) }
        when (k.tipo) {
            "sendero" -> { pLinea.color = CAMINO_TIERRA; pLinea.strokeWidth = w; pLinea.pathEffect = DashPathEffect(floatArrayOf(0.1f, w * 2.2f), 0f) }
            "camino" -> { pLinea.color = CAMINO_TIERRA; pLinea.strokeWidth = w; pLinea.pathEffect = DashPathEffect(floatArrayOf(w * 3f, w * 2f), 0f) }
            "maritima" -> { pLinea.color = 0xFF3F6474.toInt(); pLinea.strokeWidth = w; pLinea.pathEffect = DashPathEffect(floatArrayOf(0.1f, w * 2.6f), 0f) }
            "frontera" -> { pLinea.color = 0xFF7A2E22.toInt(); pLinea.strokeWidth = w; pLinea.pathEffect = DashPathEffect(floatArrayOf(w * 4f, w * 1.5f, w * 0.6f, w * 1.5f), 0f) }
            "viaje" -> { pLinea.color = 0xFFA23B2A.toInt(); pLinea.strokeWidth = w; pLinea.pathEffect = DashPathEffect(floatArrayOf(w * 3f, w * 2f), 0f) }
            else -> { pLinea.pathEffect = null; pLinea.color = TINTA; pLinea.strokeWidth = w + 1.2f * u; c.drawPath(path, pLinea); pLinea.color = CAMINO_PIEDRA; pLinea.strokeWidth = w }
        }
        c.drawPath(path, pLinea)
        pLinea.pathEffect = null
        if (k.tipo == "viaje") {
            // Punta de flecha al final
            val n = k.n - 1; val dx = k.xs[n] - k.xs[n - 1]; val dy = k.ys[n] - k.ys[n - 1]; val d = max(0.01f, hypot(dx, dy))
            val ux = dx / d; val uy = dy / d; val a = w * 4f
            path.reset(); path.moveTo(k.xs[n] + ux * a * 0.4f, k.ys[n] + uy * a * 0.4f)
            path.lineTo(k.xs[n] - ux * a - uy * a * 0.6f, k.ys[n] - uy * a + ux * a * 0.6f); path.lineTo(k.xs[n] - ux * a + uy * a * 0.6f, k.ys[n] - uy * a - ux * a * 0.6f); path.close()
            pRelleno.color = 0xFFA23B2A.toInt(); c.drawPath(path, pRelleno)
        }
    }

    private fun sello(c: Canvas, s: Sello, b: Bitmap?, sel: Boolean) {
        if (b == null) return
        val lado = 64f * s.escala
        val k = lado / max(b.width, b.height)
        c.save()
        c.translate(s.x, s.y); c.rotate(s.giro); c.scale(if (s.espejo) -k else k, k)
        c.drawBitmap(b, -b.width / 2f, -b.height / 2f, pBmp)
        if (sel) { pLinea.color = ACENTO; pLinea.strokeWidth = 2f / k; pLinea.pathEffect = DashPathEffect(floatArrayOf(6f / k, 4f / k), 0f); c.drawRect(-b.width / 2f, -b.height / 2f, b.width / 2f, b.height / 2f, pLinea); pLinea.pathEffect = null }
        c.restore()
    }

    // ---------------------------------------------------------------- textos

    private fun texto(c: Canvas, t: String, x: Float, y: Float, tam: Float, tf: Typeface, color: Int, espaciado: Float = 0f, halo: Float = 3f) {
        pTexto.typeface = tf; pTexto.textSize = tam; pTexto.letterSpacing = espaciado; pTexto.textAlign = Paint.Align.CENTER
        pHalo.typeface = tf; pHalo.textSize = tam; pHalo.letterSpacing = espaciado; pHalo.textAlign = Paint.Align.CENTER
        if (halo > 0f) { pHalo.strokeWidth = halo * dens; pHalo.color = (PAPEL and 0xFFFFFF) or (0xC8 shl 24); c.drawText(t, x, y, pHalo) }
        pTexto.color = color
        c.drawText(t, x, y, pTexto)
    }

    private fun nombreTerritorio(c: Canvas, mapa: Mapa, v: Vista, ce: Centro, sel: Any?) {
        val t = mapa.territorio(ce.id) ?: return
        if (!t.verNombre) return
        val lado = sqrt(ce.pixeles.toFloat())
        if (lado * v.zoom < 50f * dens && t !== sel) return
        val auto = (lado * v.zoom * when (ce.nivel) { 0 -> 0.11f; 1 -> 0.085f; else -> 0.07f }).coerceIn(10f * dens, when (ce.nivel) { 0 -> 46f; 1 -> 32f; else -> 22f } * dens)
        val tam = if (t.tam > 0f) t.tam * v.zoom else auto
        val x = if (t.lx.isNaN()) ce.x else t.lx; val y = if (t.ly.isNaN()) ce.y else t.ly
        val p = v.aPantalla(x, y)
        val col = VeloOscuro.de(t.color)
        c.save(); c.rotate(t.giro, p[0], p[1])
        val texto = if (ce.nivel <= 1) t.nombre.uppercase() else t.nombre
        val tf = if (ce.nivel == 0) Letras.serifSemi else if (ce.nivel == 1) Letras.serif else Letras.serifCursiva
        val esp = if (ce.nivel == 0) 0.32f else if (ce.nivel == 1) 0.2f else 0.05f
        if (abs(t.curva) > 0.01f) {
            pTexto.typeface = tf; pTexto.textSize = tam; pTexto.letterSpacing = esp; pTexto.textAlign = Paint.Align.CENTER; pTexto.color = col
            val ancho = pTexto.measureText(texto); val rad = ancho / (t.curva * 2f)
            path.reset(); val oy = p[1] + rad
            path.addArc(RectF(p[0] - abs(rad), oy - abs(rad), p[0] + abs(rad), oy + abs(rad)), if (rad > 0) 180f else 0f, if (rad > 0) 180f else -180f)
            pHalo.typeface = tf; pHalo.textSize = tam; pHalo.letterSpacing = esp; pHalo.textAlign = Paint.Align.CENTER; pHalo.strokeWidth = 3 * dens; pHalo.color = (PAPEL and 0xFFFFFF) or (0xB0 shl 24)
            c.drawTextOnPath(texto, path, 0f, tam * 0.35f, pHalo); c.drawTextOnPath(texto, path, 0f, tam * 0.35f, pTexto)
        } else texto(c, texto, p[0], p[1] + tam * 0.35f, tam, tf, col, esp, 3f)
        if (t === sel) { pLinea.color = ACENTO; pLinea.strokeWidth = 2 * dens; pTexto.textSize = tam; val w = pTexto.measureText(texto) / 2 + 8 * dens; c.drawRect(p[0] - w, p[1] - tam * 0.75f, p[0] + w, p[1] + tam * 0.6f, pLinea) }
        c.restore()
    }

    // ---------------------------------------------------------------- secciones del terreno

    private fun secciones(c: Canvas, mapa: Mapa, v: Vista, op: Opciones) {
        val u = dens / v.zoom
        val activa = mapa.secciones.firstOrNull { it.id == mapa.seccionActiva }
        if (activa != null && !op.impresion) {
            // Fuera de la sección activa, un velo: se ve dónde se puede dibujar
            val vis = v.visible(); vis.inset(-10f, -10f)
            c.save(); c.clipOutRect(activa.x, activa.y, activa.x + activa.w, activa.y + activa.h)
            pRelleno.color = 0x66000000; c.drawRect(vis, pRelleno); c.restore()
        }
        for (s in mapa.secciones) marco(c, mapa, s.x, s.y, s.w, s.h, s.nombre, s === activa, u, v)
        op.seccionBorrador?.let { r -> marco(c, mapa, minOf(r[0], r[2]), minOf(r[1], r[3]), kotlin.math.abs(r[2] - r[0]), kotlin.math.abs(r[3] - r[1]), "Nueva sección", true, u, v) }
    }

    private fun marco(c: Canvas, mapa: Mapa, x: Float, y: Float, w: Float, h: Float, nombre: String, activa: Boolean, u: Float, v: Vista) {
        pLinea.pathEffect = if (activa) null else DashPathEffect(floatArrayOf(8 * u, 5 * u), 0f)
        pLinea.color = if (activa) 0xFF3D8BFF.toInt() else 0xAA3D8BFF.toInt(); pLinea.strokeWidth = (if (activa) 2.5f else 1.5f) * u
        c.drawRect(x, y, x + w, y + h, pLinea); pLinea.pathEffect = null
        val km = Medidas.formatoKm(w.toDouble() * mapa.kmPorPx) + " × " + Medidas.formatoKm(h.toDouble() * mapa.kmPorPx) + " · " + Medidas.formatoKm2(w.toDouble() * h * mapa.kmPorPx * mapa.kmPorPx)
        c.save(); c.translate(x, y); c.scale(u, u)
        pTexto.typeface = Letras.interSemi; pTexto.textSize = 12f; pTexto.letterSpacing = 0.04f; pTexto.textAlign = Paint.Align.LEFT; pTexto.color = 0xFF3D8BFF.toInt()
        pHalo.typeface = Letras.interSemi; pHalo.textSize = 12f; pHalo.letterSpacing = 0.04f; pHalo.textAlign = Paint.Align.LEFT; pHalo.strokeWidth = 3f; pHalo.color = PAPEL
        val t = "${nombre.uppercase()} · $km"
        c.drawText(t, 4f, -6f, pHalo); c.drawText(t, 4f, -6f, pTexto)
        c.restore()
        @Suppress("UNUSED_VARIABLE") val z = v.zoom
    }

    // ---------------------------------------------------------------- leyenda

    /** Dónde está la leyenda (en el lienzo) y su tamaño, para tocarla. */
    fun rectLeyenda(mapa: Mapa, v: Vista, superficies: LongArray): RectF {
        val l = mapa.leyenda
        if (l.x.isNaN()) { val q = v.aMapa(24 * dens, v.alto - 240 * dens); l.x = q[0]; l.y = q[1] }
        val filas = entradasLeyenda(mapa, superficies).size
        val k = l.escala * 1.6f
        return RectF(l.x, l.y, l.x + 220f * k, l.y + (54f + filas * 26f + (if (l.escalaKm) 34f else 0f)) * k)
    }

    private fun entradasLeyenda(mapa: Mapa, superficies: LongArray): List<Pair<String, String>> {
        val l = mapa.leyenda
        val out = ArrayList<Pair<String, String>>()
        if (l.territorios) for (t in mapa.territorios.filter { it.padre == 0 && superficies.getOrElse(it.id) { 0L } > 0 }) out += ("color:" + t.color) to t.nombre
        if (l.lugares) for (tipo in mapa.lugares.map { it.tipo }.distinct()) out += tipo to com.gonzalo.atlas.motor.Catalogo.lugar(tipo).nombre
        for (e in l.extra) { val p = e.split('|', limit = 2); if (p.size == 2) out += p[0] to p[1] }
        return out
    }

    private fun leyenda(c: Canvas, mapa: Mapa, v: Vista, superficies: LongArray, sel: Boolean) {
        val r = rectLeyenda(mapa, v, superficies)
        val l = mapa.leyenda
        val k = l.escala * 1.6f
        c.save(); c.concat(v.m)
        pRelleno.color = (PAPEL and 0xFFFFFF) or (0xEE shl 24); c.drawRect(r, pRelleno)
        pLinea.pathEffect = null; pLinea.color = TINTA; pLinea.strokeWidth = 2f * k; c.drawRect(r, pLinea)
        pLinea.strokeWidth = 0.8f * k; c.drawRect(r.left + 4 * k, r.top + 4 * k, r.right - 4 * k, r.bottom - 4 * k, pLinea)
        texto(c, l.titulo.uppercase(), r.centerX(), r.top + 30f * k, 15f * k, Letras.serifSemi, TINTA_TEXTO, 0.25f, 0f)
        var y = r.top + 58f * k
        for ((sim, nombre) in entradasLeyenda(mapa, superficies)) {
            val sx = r.left + 24f * k
            if (sim.startsWith("color:")) {
                pRelleno.color = (sim.removePrefix("color:").toIntOrNull() ?: 0) and 0xFFFFFF or (0xAA shl 24)
                c.drawRect(sx - 9 * k, y - 9 * k, sx + 9 * k, y + 5 * k, pRelleno); pLinea.strokeWidth = 1f * k; c.drawRect(sx - 9 * k, y - 9 * k, sx + 9 * k, y + 5 * k, pLinea)
            } else simbolo(c, sim, sx, y - 2 * k, 6f * k)
            pTexto.typeface = Letras.serif; pTexto.textSize = 13f * k; pTexto.letterSpacing = 0f; pTexto.textAlign = Paint.Align.LEFT; pTexto.color = TINTA_TEXTO
            c.drawText(nombre, sx + 20 * k, y + 3 * k, pTexto)
            y += 26f * k
        }
        if (l.escalaKm) {
            val km = Medidas.bonito(120.0 * k / 1.6 * mapa.kmPorPx)
            val largo = (km / mapa.kmPorPx).toFloat()
            val x0 = r.left + 16 * k; val y0 = y + 2 * k
            for (i in 0 until 4) { pRelleno.color = if (i % 2 == 0) TINTA else PAPEL; c.drawRect(x0 + largo * i / 4, y0, x0 + largo * (i + 1) / 4, y0 + 5 * k, pRelleno) }
            pLinea.strokeWidth = 1f * k; c.drawRect(x0, y0, x0 + largo, y0 + 5 * k, pLinea)
            pTexto.typeface = Letras.interSemi; pTexto.textSize = 10f * k; pTexto.textAlign = Paint.Align.LEFT; c.drawText(Medidas.formatoKm(km), x0 + largo + 6 * k, y0 + 6 * k, pTexto)
        }
        if (sel) { pLinea.color = ACENTO; pLinea.strokeWidth = 3f / v.zoom; pLinea.pathEffect = DashPathEffect(floatArrayOf(8f / v.zoom, 5f / v.zoom), 0f); c.drawRect(r.left - 6 / v.zoom, r.top - 6 / v.zoom, r.right + 6 / v.zoom, r.bottom + 6 / v.zoom, pLinea); pLinea.pathEffect = null }
        c.restore()
    }

    private fun regla(c: Canvas, mapa: Mapa, v: Vista, r: FloatArray) {
        val a = v.aPantalla(r[0], r[1]); val b = v.aPantalla(r[2], r[3])
        pLinea.pathEffect = null; pLinea.color = Color.WHITE; pLinea.strokeWidth = 4 * dens; c.drawLine(a[0], a[1], b[0], b[1], pLinea)
        pLinea.color = 0xFFA23B2A.toInt(); pLinea.strokeWidth = 2 * dens; c.drawLine(a[0], a[1], b[0], b[1], pLinea)
        pRelleno.color = 0xFFA23B2A.toInt(); c.drawCircle(a[0], a[1], 4 * dens, pRelleno); c.drawCircle(b[0], b[1], 4 * dens, pRelleno)
        val km = hypot(r[2] - r[0], r[3] - r[1]).toDouble() * mapa.kmPorPx
        texto(c, Medidas.formatoKm(km), (a[0] + b[0]) / 2, (a[1] + b[1]) / 2 - 10 * dens, 14 * dens, Letras.interNegrita, 0xFFA23B2A.toInt(), 0.02f, 3.5f)
    }

    private fun rotulo(c: Canvas, v: Vista, r: Rotulo, sel: Boolean) {
        val p = v.aPantalla(r.x, r.y)
        val tam = r.tam * v.zoom
        if (tam < 5f * dens) return
        val tf = when (r.estilo) { "cursiva" -> Letras.serifCursiva; "grotesca" -> Letras.grotesca; "negrita" -> Letras.serifSemi; else -> Letras.serif }
        c.save()
        c.rotate(r.giro + v.giro, p[0], p[1])
        if (abs(r.curva) > 0.01f) {
            pTexto.typeface = tf; pTexto.textSize = tam; pTexto.letterSpacing = r.espaciado; pTexto.textAlign = Paint.Align.CENTER; pTexto.color = TINTA_TEXTO
            val ancho = pTexto.measureText(r.texto)
            val rad = ancho / (r.curva * 2f)
            path.reset()
            val ox = p[0]; val oy = p[1] + rad
            path.addArc(RectF(ox - abs(rad), oy - abs(rad), ox + abs(rad), oy + abs(rad)), if (rad > 0) 180f else 0f, if (rad > 0) 180f else -180f)
            pHalo.typeface = tf; pHalo.textSize = tam; pHalo.letterSpacing = r.espaciado; pHalo.textAlign = Paint.Align.CENTER; pHalo.strokeWidth = 3 * dens
            c.drawTextOnPath(r.texto, path, 0f, tam * 0.35f, pHalo)
            c.drawTextOnPath(r.texto, path, 0f, tam * 0.35f, pTexto)
        } else texto(c, r.texto, p[0], p[1] + tam * 0.35f, tam, tf, TINTA_TEXTO, r.espaciado)
        if (sel) { pLinea.color = ACENTO; pLinea.strokeWidth = 2 * dens; pTexto.textSize = tam; val w = pTexto.measureText(r.texto) / 2 + 6 * dens; c.drawRect(p[0] - w, p[1] - tam * 0.7f, p[0] + w, p[1] + tam * 0.6f, pLinea) }
        c.restore()
    }

    // ---------------------------------------------------------------- lugares

    private fun lugares(c: Canvas, mapa: Mapa, v: Vista, sel: Any?) {
        val vis = v.visible()
        // Los pequeños se esconden de lejos (los grandes siempre se ven)
        val umbral = when { v.zoom > 0.8f -> 0; v.zoom > 0.35f -> 2; v.zoom > 0.15f -> 3; v.zoom > 0.07f -> 4; else -> 5 }
        val orden = mapa.lugares.filter { it.tamano > umbral || it === sel }.sortedBy { it.tamano }
        for (l in orden) {
            if (l.x < vis.left - 50 || l.x > vis.right + 50 || l.y < vis.top - 50 || l.y > vis.bottom + 50) continue
            val p = v.aPantalla(l.x, l.y)
            val s = (5f + l.tamano * 0.9f) * dens * (0.75f + min(v.zoom, 2f) * 0.25f)
            simbolo(c, l.tipo, p[0], p[1], s)
            val tam = (if (l.tipo == "capital") 15f else if (l.tamano >= 5) 13.5f else 11.5f) * dens * (0.85f + min(v.zoom, 2f) * 0.15f)
            texto(c, l.nombre, p[0], p[1] - s - 4 * dens, tam, if (l.tipo == "capital" || l.tamano >= 6) Letras.serifSemi else Letras.serif, TINTA_TEXTO)
            if (l === sel) { pLinea.color = ACENTO; pLinea.strokeWidth = 2 * dens; c.drawCircle(p[0], p[1], s + 6 * dens, pLinea) }
        }
    }

    fun simbolo(c: Canvas, tipo: String, x: Float, y: Float, s: Float) {
        pRelleno.style = Paint.Style.FILL
        pLinea.pathEffect = null; pLinea.strokeWidth = max(1.2f, s * 0.16f); pLinea.color = TINTA
        fun circulo(r: Float, relleno: Int) { pRelleno.color = relleno; c.drawCircle(x, y, r, pRelleno); c.drawCircle(x, y, r, pLinea) }
        when (tipo) {
            "capital" -> { circulo(s, PAPEL); pRelleno.color = TINTA; c.drawCircle(x, y, s * 0.55f, pRelleno); estrella(c, x, y, s * 0.42f, PAPEL) }
            "ciudad" -> { circulo(s * 0.85f, PAPEL); pRelleno.color = TINTA; c.drawCircle(x, y, s * 0.45f, pRelleno) }
            "villa" -> { pRelleno.color = TINTA; c.drawCircle(x, y, s * 0.55f, pRelleno) }
            "aldea" -> circulo(s * 0.45f, PAPEL)
            "puerto" -> { circulo(s * 0.8f, PAPEL); ancla(c, x, y, s * 0.55f) }
            "castillo", "torre", "faro" -> torre(c, x, y, s, tipo)
            "fortaleza" -> { estrella(c, x, y, s, PAPEL, true); pRelleno.color = TINTA; c.drawCircle(x, y, s * 0.25f, pRelleno) }
            "templo", "monasterio" -> templo(c, x, y, s, tipo == "monasterio")
            "mina" -> { circulo(s * 0.8f, PAPEL); pLinea.strokeWidth = s * 0.18f; c.drawLine(x - s * 0.45f, y - s * 0.45f, x + s * 0.45f, y + s * 0.45f, pLinea); c.drawLine(x + s * 0.45f, y - s * 0.45f, x - s * 0.45f, y + s * 0.45f, pLinea) }
            "ruina" -> { pLinea.strokeWidth = s * 0.2f; c.drawArc(x - s * 0.7f, y - s * 0.7f, x + s * 0.7f, y + s * 0.7f, 200f, 110f, false, pLinea); c.drawArc(x - s * 0.7f, y - s * 0.7f, x + s * 0.7f, y + s * 0.7f, 330f, 60f, false, pLinea); c.drawLine(x - s * 0.7f, y + s * 0.6f, x + s * 0.7f, y + s * 0.6f, pLinea) }
            "cueva" -> { pRelleno.color = TINTA; c.drawArc(x - s * 0.75f, y - s * 0.6f, x + s * 0.75f, y + s * 0.9f, 180f, 180f, true, pRelleno) }
            "campamento" -> { path.reset(); path.moveTo(x, y - s * 0.8f); path.lineTo(x + s * 0.8f, y + s * 0.6f); path.lineTo(x - s * 0.8f, y + s * 0.6f); path.close(); pRelleno.color = PAPEL; c.drawPath(path, pRelleno); c.drawPath(path, pLinea) }
            else -> { path.reset(); path.moveTo(x, y - s * 0.6f); path.lineTo(x + s * 0.6f, y); path.lineTo(x, y + s * 0.6f); path.lineTo(x - s * 0.6f, y); path.close(); pRelleno.color = TINTA; c.drawPath(path, pRelleno) }
        }
    }

    private fun estrella(c: Canvas, x: Float, y: Float, r: Float, color: Int, conBorde: Boolean = false) {
        path.reset()
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            val px = x + (cos(a) * rr).toFloat(); val py = y + (sin(a) * rr).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        pRelleno.color = color; c.drawPath(path, pRelleno)
        if (conBorde) c.drawPath(path, pLinea)
    }

    private fun ancla(c: Canvas, x: Float, y: Float, s: Float) {
        pLinea.strokeWidth = s * 0.22f
        c.drawLine(x, y - s * 0.8f, x, y + s * 0.8f, pLinea)
        c.drawLine(x - s * 0.45f, y - s * 0.35f, x + s * 0.45f, y - s * 0.35f, pLinea)
        c.drawArc(x - s * 0.8f, y - s * 0.2f, x + s * 0.8f, y + s * 0.8f, 20f, 140f, false, pLinea)
    }

    private fun torre(c: Canvas, x: Float, y: Float, s: Float, tipo: String) {
        val w = if (tipo == "castillo") s * 1.4f else s * 0.8f; val h = if (tipo == "castillo") s * 1.1f else s * 1.6f
        val l = x - w / 2; val t = y - h / 2
        path.reset(); path.moveTo(l, y + h / 2); path.lineTo(l, t)
        val almenas = if (tipo == "castillo") 5 else 3
        val aw = w / almenas
        for (i in 0 until almenas) { val ax = l + i * aw; if (i % 2 == 0) { path.lineTo(ax, t - h * 0.18f); path.lineTo(ax + aw, t - h * 0.18f); path.lineTo(ax + aw, t) } else path.lineTo(ax + aw, t) }
        path.lineTo(l + w, y + h / 2); path.close()
        pRelleno.color = PAPEL; c.drawPath(path, pRelleno); c.drawPath(path, pLinea)
        if (tipo == "faro") { pLinea.strokeWidth = s * 0.1f; for (a in listOf(-30f, 0f, 30f)) { val r = Math.toRadians((a - 90).toDouble()); c.drawLine(x + (cos(r) * s).toFloat(), t - h * 0.2f + (sin(r) * s * 0.4f).toFloat(), x + (cos(r) * s * 1.6f).toFloat(), t - h * 0.2f + (sin(r) * s * 1.2f).toFloat(), pLinea) } }
        else { pRelleno.color = TINTA; c.drawRect(x - w * 0.12f, y + h * 0.1f, x + w * 0.12f, y + h / 2, pRelleno) }
    }

    private fun templo(c: Canvas, x: Float, y: Float, s: Float, cruz: Boolean) {
        if (cruz) { pLinea.strokeWidth = s * 0.25f; c.drawLine(x, y - s, x, y + s, pLinea); c.drawLine(x - s * 0.6f, y - s * 0.35f, x + s * 0.6f, y - s * 0.35f, pLinea); return }
        path.reset(); path.moveTo(x - s, y - s * 0.3f); path.lineTo(x, y - s); path.lineTo(x + s, y - s * 0.3f); path.close()
        pRelleno.color = TINTA; c.drawPath(path, pRelleno)
        pLinea.strokeWidth = s * 0.16f
        for (i in -1..1) c.drawLine(x + i * s * 0.6f, y - s * 0.2f, x + i * s * 0.6f, y + s * 0.6f, pLinea)
        c.drawLine(x - s, y + s * 0.75f, x + s, y + s * 0.75f, pLinea)
    }

    // ---------------------------------------------------------------- cuadrícula, escala y norte

    /** El lado de cada casilla de la cuadrícula en km. */
    fun casillaKm(mapa: Mapa, v: Vista): Double = Medidas.bonito(110.0 * dens / v.zoom * mapa.kmPorPx)

    private fun cuadricula(c: Canvas, mapa: Mapa, v: Vista) {
        val km = casillaKm(mapa, v)
        val px = (km / mapa.kmPorPx).toFloat()
        val r = v.visible()
        if (!mapa.infinito && !r.intersect(0f, 0f, mapa.ancho.toFloat(), mapa.alto.toFloat())) return
        pLinea.color = if (android.graphics.Color.luminance(mapa.papel) < 0.4f && android.graphics.Color.alpha(mapa.papel) > 0) 0x44FFFFFF else 0x38000000; pLinea.strokeWidth = 1.2f * dens / v.zoom; pLinea.pathEffect = null
        var x = floor(r.left / px) * px
        while (x <= r.right) { c.drawLine(x, r.top, x, r.bottom, pLinea); x += px }
        var y = floor(r.top / px) * px
        while (y <= r.bottom) { c.drawLine(r.left, y, r.right, y, pLinea); y += px }
    }

    private fun escala(c: Canvas, mapa: Mapa, v: Vista) {
        val km = Medidas.bonito(140.0 * dens / v.zoom * mapa.kmPorPx)
        val largo = (km / mapa.kmPorPx * v.zoom).toFloat()
        val x0 = 18 * dens; val y0 = v.alto - 22 * dens; val h = 5 * dens
        pRelleno.style = Paint.Style.FILL
        for (i in 0 until 4) {
            pRelleno.color = if (i % 2 == 0) TINTA else PAPEL
            c.drawRect(x0 + largo * i / 4, y0, x0 + largo * (i + 1) / 4, y0 + h, pRelleno)
        }
        pLinea.color = TINTA; pLinea.strokeWidth = 1.2f * dens; pLinea.pathEffect = null
        c.drawRect(x0, y0, x0 + largo, y0 + h, pLinea)
        pTexto.textAlign = Paint.Align.LEFT; pTexto.typeface = Letras.interSemi; pTexto.textSize = 11 * dens; pTexto.letterSpacing = 0.05f; pTexto.color = TINTA
        pHalo.textAlign = Paint.Align.LEFT; pHalo.typeface = Letras.interSemi; pHalo.textSize = 11 * dens; pHalo.strokeWidth = 3 * dens; pHalo.letterSpacing = 0.05f; pHalo.color = PAPEL
        val t = Medidas.formatoKm(km).uppercase() + if (!mapa.escalaPuesta) " (SIN FIJAR)" else ""
        if (cuadriculaVisible) {
            val ck = casillaKm(mapa, v)
            val tc = "CASILLA " + Medidas.formatoKm(ck).uppercase() + " · " + Medidas.formatoKm2(ck * ck).uppercase()
            c.drawText(tc, x0, y0 - 22 * dens, pHalo); c.drawText(tc, x0, y0 - 22 * dens, pTexto)
        }
        c.drawText(t, x0, y0 - 5 * dens, pHalo); c.drawText(t, x0, y0 - 5 * dens, pTexto)
    }

    private fun norte(c: Canvas, v: Vista) {
        val x = v.ancho - 30 * dens; val y = 34 * dens; val s = 13 * dens
        c.save(); c.rotate(v.giro, x, y)
        path.reset(); path.moveTo(x, y - s); path.lineTo(x + s * 0.45f, y + s * 0.6f); path.lineTo(x, y + s * 0.3f); path.lineTo(x - s * 0.45f, y + s * 0.6f); path.close()
        pRelleno.color = TINTA; c.drawPath(path, pRelleno)
        texto(c, "N", x, y - s - 4 * dens, 11 * dens, Letras.interNegrita, TINTA, 0f, 2.5f)
        c.restore()
    }

    // ---------------------------------------------------------------- tocar cosas

    /** Lo que hay bajo un toque (en pantalla): un lugar, un rótulo, un sello, un camino o un río. */
    fun queHay(mapa: Mapa, v: Vista, sx: Float, sy: Float, centros: List<Centro> = emptyList(), superficies: LongArray = LongArray(256)): Any? {
        val tol = 22 * dens
        mapa.lugares.minByOrNull { val p = v.aPantalla(it.x, it.y); hypot(p[0] - sx, p[1] - sy) }?.let { l -> val p = v.aPantalla(l.x, l.y); if (hypot(p[0] - sx, p[1] - sy) < tol) return l }
        for (r in mapa.rotulos.reversed()) {
            val p = v.aPantalla(r.x, r.y); pTexto.textSize = r.tam * v.zoom
            val w = pTexto.measureText(r.texto) / 2 + 8 * dens
            if (abs(sx - p[0]) < w && abs(sy - p[1]) < r.tam * v.zoom) return r
        }
        val m = v.aMapa(sx, sy)
        if (mapa.leyenda.visible && rectLeyenda(mapa, v, superficies).contains(m[0], m[1])) return mapa.leyenda
        for (ce in centros) {
            val t = mapa.territorio(ce.id) ?: continue
            val p = v.aPantalla(if (t.lx.isNaN()) ce.x else t.lx, if (t.ly.isNaN()) ce.y else t.ly)
            if (abs(sx - p[0]) < 60 * dens && abs(sy - p[1]) < 14 * dens) return t
        }
        for (s in mapa.sellos.reversed()) if (hypot(s.x - m[0], s.y - m[1]) < 32f * s.escala + tol / v.zoom * 0.5f) return s
        val tm = tol / v.zoom
        for (l in mapa.rutas) for (i in 1 until l.n) if (distSeg(m[0], m[1], l.xs[i - 1], l.ys[i - 1], l.xs[i], l.ys[i]) < tm) return l
        return null
    }

    companion object {
        val PAPEL = 0xFFF8F0D4.toInt()
        val TINTA = 0xFF1B1712.toInt()
        val TINTA_TEXTO = 0xFF231D16.toInt()
        val ACENTO = 0xFF5C7894.toInt()
        val RIO = 0xFF8DB4BE.toInt()
        val RIO_BORDE = 0xFF3D5F6E.toInt()
        val CAMINO_TIERRA = 0xFF7A5A3A.toInt()
        val CAMINO_PIEDRA = 0xFFD9CDB0.toInt()

        fun distSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }

        @Suppress("unused") private fun nada() = ceil(0.0)
    }
}

/** Un color de tinta para el nombre de un territorio (su color, oscurecido). */
object VeloOscuro {
    fun de(c: Int): Int = Color.rgb((Color.red(c) * 0.55f).toInt(), (Color.green(c) * 0.55f).toInt(), (Color.blue(c) * 0.55f).toInt())
}
