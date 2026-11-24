package com.gonzalo.atlas.lienzo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Una selección: la zona del lienzo en la que se pinta, se borra, se rellena o se ajusta (lo de fuera no se toca). Son
 * contornos cerrados (un rectángulo, una elipse, lo rodeado con el lazo, o varios sumados y restados).
 */
class Seleccion(val contornos: List<FloatArray>, val invertida: Boolean = false) {
    val path: Path by lazy {
        Path().apply {
            for (p in contornos) { if (p.size < 6) continue; moveTo(p[0], p[1]); var i = 2; while (i + 1 < p.size) { lineTo(p[i], p[i + 1]); i += 2 }; close() }
            fillType = if (invertida) Path.FillType.INVERSE_EVEN_ODD else Path.FillType.EVEN_ODD
        }
    }
    /** Todo lo que NO está seleccionado (para borrarlo de algo y quedarse solo con lo de dentro). */
    val fuera: Path by lazy { Path(path).apply { toggleInverseFillType() } }
    /** La caja de los contornos (si está invertida, la selección es todo lo de fuera: no tiene caja). */
    val caja: RectF by lazy { RectF().also { r -> Path(path).apply { fillType = Path.FillType.EVEN_ODD }.computeBounds(r, true) } }
    val vacia get() = contornos.none { it.size >= 6 }

    fun invertir() = Seleccion(contornos, !invertida)

    /** Suma ([quitar] false) o resta otra zona. */
    fun con(otra: Seleccion, quitar: Boolean): Seleccion {
        val a = Path(path).apply { fillType = Path.FillType.EVEN_ODD }; val b = Path(otra.path).apply { fillType = Path.FillType.EVEN_ODD }
        val op = if (invertida) (if (quitar) Path.Op.UNION else Path.Op.DIFFERENCE) else (if (quitar) Path.Op.DIFFERENCE else Path.Op.UNION)
        a.op(b, op)
        return Seleccion(contornosDe(a), invertida)
    }

    /**
     * La selección en los píxeles de un lienzo cuyo punto (0, 0) es el píxel ([desX], [desY]) del espacio a [escala] (los
     * contornos van medidos desde ([ox], [oy])). Las cuentas se hacen con doble precisión y los contornos se recortan a la
     * ventana ([x0], [y0])–([x1], [y1]) (con margen), para que a 200.000 aumentos el borde caiga donde debe. [fuera]: lo
     * que NO está seleccionado.
     */
    fun local(escala: Double, ox: Double, oy: Double, desX: Double, desY: Double, x0: Double, y0: Double, x1: Double, y1: Double, fuera: Boolean = false, margen: Double = 64.0): Path {
        val p = Path(); val m = margen
        for (c in contornos) {
            if (c.size < 6) continue
            var n = c.size / 2
            var a = DoubleArray(n * 2)
            for (i in 0 until n) { a[i * 2] = (c[i * 2] + ox) * escala - desX; a[i * 2 + 1] = (c[i * 2 + 1] + oy) * escala - desY }
            for (lado in 0 until 4) {
                val eje = lado / 2; val lim = when (lado) { 0 -> x0 - m; 1 -> x1 + m; 2 -> y0 - m; else -> y1 + m }; val mayor = lado % 2 == 0
                val b = DoubleArray(n * 4 + 4); var k = 0
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val ax = a[i * 2]; val ay = a[i * 2 + 1]; val bx = a[j * 2]; val by = a[j * 2 + 1]
                    val va = if (eje == 0) ax else ay; val vb = if (eje == 0) bx else by
                    val da = if (mayor) va >= lim else va <= lim; val db = if (mayor) vb >= lim else vb <= lim
                    if (da) { b[k++] = ax; b[k++] = ay }
                    if (da != db) { val t = (lim - va) / (vb - va); b[k++] = if (eje == 0) lim else ax + (bx - ax) * t; b[k++] = if (eje == 0) ay + (by - ay) * t else lim }
                }
                a = b; n = k / 2
                if (n < 3) break
            }
            if (n < 3) continue
            p.moveTo(a[0].toFloat(), a[1].toFloat()); for (i in 1 until n) p.lineTo(a[i * 2].toFloat(), a[i * 2 + 1].toFloat()); p.close()
        }
        p.fillType = if (invertida != fuera) Path.FillType.INVERSE_EVEN_ODD else Path.FillType.EVEN_ODD
        return p
    }

    fun movida(m: Matrix): Seleccion = Seleccion(contornos.map { p -> p.copyOf().also { m.mapPoints(it) } }, invertida)

    fun escribe(o: DataOutputStream) { o.writeBoolean(invertida); o.writeInt(contornos.size); for (p in contornos) { o.writeInt(p.size); for (v in p) o.writeFloat(v) } }

    companion object {
        fun lee(i: DataInputStream): Seleccion { val inv = i.readBoolean(); val n = i.readInt(); return Seleccion(List(n) { FloatArray(i.readInt()) { i.readFloat() } }, inv) }
        fun rectangulo(r: RectF) = Seleccion(listOf(floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)))
        fun elipse(r: RectF): Seleccion {
            val n = 96
            return Seleccion(listOf(FloatArray(n * 2) { i -> val a = (i / 2) * 2.0 * Math.PI / n; if (i % 2 == 0) r.centerX() + (Math.cos(a) * r.width() / 2).toFloat() else r.centerY() + (Math.sin(a) * r.height() / 2).toFloat() }))
        }
        /** Los contornos de un camino, como polígonos (un punto cada [paso] px en las curvas). */
        fun contornosDe(p: Path, paso: Float = 1.5f): List<FloatArray> {
            val pm = PathMeasure(p, true); val l = ArrayList<FloatArray>(); val q = FloatArray(2)
            do {
                val largo = pm.length; if (largo < 2f) continue
                val n = (largo / paso).toInt().coerceIn(8, 4000)
                l += FloatArray(n * 2).also { a -> for (i in 0 until n) { pm.getPosTan(largo * i / n, q, null); a[i * 2] = q[0]; a[i * 2 + 1] = q[1] } }
            } while (pm.nextContour())
            return l
        }
    }
}

/**
 * Un trazo terminado, como datos: el pincel tal como era, el color, el tamaño y por dónde pasó el lápiz. Con esto se puede
 * volver a pintar igual a cualquier tamaño. [ramas]: el camino del lápiz (y sus reflejos, si se dibujó con simetría), cada
 * uno con x, y, presión, inclinación, acimut y tiempo por punto; x e y van medidos desde el origen del trazo ([ox], [oy],
 * en píxeles del lienzo y con doble precisión), para que un trazo hecho a 2000 aumentos conserve toda su finura. [relleno]:
 * si no es un trazo de pincel sino una zona rellena de color (el bote de pintura usado de cerca), su forma.
 */
class TrazoDatos(
    val def: DefPincel, val color: Int, val tam: Float, val opacidad: Float, val modo: Int, val atope: Boolean, val ramas: List<FloatArray>,
    val semilla: Int, val u: Float, val lapiz: Boolean, val recorte: RectF?, val seleccion: Seleccion?,
    val ox: Double = 0.0, val oy: Double = 0.0, val relleno: Seleccion? = null,
) {
    @Volatile private var guardados: java.lang.ref.SoftReference<Sellos>? = null

    /** Los sellos del trazo entero (medidos desde su origen). */
    fun sellos(muestra: ((Float, Float) -> Int)? = null): Sellos {
        if (muestra == null) guardados?.get()?.let { return it }
        val s = Sellos(512)
        if (relleno == null) ramas.forEachIndexed { k, pts ->
            val n = pts.size / Caminante.P
            val c = Caminante(def, tam, color, semilla + k * 7919, lapiz, u, Caminante.largo(def, tam, pts, n), s, muestra)
            for (i in 0 until n) { val o = i * Caminante.P; c.punto(pts[o], pts[o + 1], pts[o + 2], pts[o + 3], pts[o + 4], pts[o + 5].toLong()) }
            c.fin()
        }
        if (muestra == null) guardados = java.lang.ref.SoftReference(s)
        return s
    }

    /** Lo que ocupa en el lienzo (izquierda, arriba, derecha, abajo), con doble precisión; vacío si derecha < izquierda. */
    val cajaD: DoubleArray by lazy {
        val r = relleno?.let { RectF(it.caja) } ?: sellos().caja()
        if (r == null) doubleArrayOf(0.0, 0.0, -1.0, -1.0) else {
            val d = doubleArrayOf(r.left + ox, r.top + oy, r.right + ox, r.bottom + oy)
            recorte?.let { d[0] = maxOf(d[0], it.left.toDouble()); d[1] = maxOf(d[1], it.top.toDouble()); d[2] = minOf(d[2], it.right.toDouble()); d[3] = minOf(d[3], it.bottom.toDouble()) }
            d
        }
    }

    /** Lo que ocupa (píxeles del lienzo), redondeado hacia fuera. */
    val caja: RectF by lazy {
        val d = cajaD
        if (d[2] < d[0] || d[3] < d[1]) RectF() else RectF(Math.nextDown(d[0].toFloat()), Math.nextDown(d[1].toFloat()), Math.nextUp(d[2].toFloat()), Math.nextUp(d[3].toFloat()))
    }

    /** Si toca esa zona del lienzo. */
    fun toca(x0: Double, y0: Double, x1: Double, y1: Double): Boolean { val d = cajaD; return d[0] <= x1 && d[2] >= x0 && d[1] <= y1 && d[3] >= y0 }

    /**
     * Si se llega a ver pintado a esa escala: un trazo cien veces más fino que un píxel (o una zona rellena más pequeña que
     * un tercio de píxel) no deja nada. Así, de lejos, no hay que rehacer los miles de trazos de un detalle minúsculo.
     */
    fun seVe(escala: Float): Boolean {
        if (relleno != null) { val d = cajaD; return maxOf(d[2] - d[0], d[3] - d[1]) * escala >= 0.3 }
        return tam * escala >= 0.012f
    }

    fun raster(escala: Float) = Raster(def, escala, u)

    /** Lo que ocupa en la memoria, en números. */
    val peso: Int get() = ramas.sumOf { it.size } + (relleno?.contornos?.sumOf { it.size } ?: 0) + 64

    /** Cómo se funde con la capa: con la opacidad del pincel; borrando; o solo sobre lo que ya hay (transparencia bloqueada). */
    fun pintura(): Paint = Paint().apply {
        alpha = (opacidad.coerceIn(0f, 1f) * 255).toInt()
        xfermode = when { modo == BORRAR -> PorterDuffXfermode(PorterDuff.Mode.DST_OUT); atope -> PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP); else -> null }
    }

    /**
     * Funde la pintura de [l] (estampada a [escala]) en [c]: un lienzo sin transformar cuyo punto (0, 0) es el píxel
     * ([desX], [desY]) del espacio escalado (el origen de una tesela, por ejemplo). Va píxel con píxel, sin matrices.
     */
    fun funde(c: Canvas, l: Lona, escala: Float, desX: Double, desY: Double, img: Bitmap = l.imagen(color, def.bordes)) =
        funde(c, l.ox - desX, l.oy - desY, l.w, l.h, escala, desX, desY) { cv, x, y, p -> cv.drawBitmap(img, x, y, p) }

    /** Lo común a fundir un trazo de pincel o una zona rellena: los recortes (sección, selección) y la pintura. */
    private fun funde(c: Canvas, x: Double, y: Double, w: Int, h: Int, escala: Float, desX: Double, desY: Double, pinta: (Canvas, Float, Float, Paint?) -> Unit) {
        val p = pintura()
        val g = c.save()
        recorte?.let { c.clipRect((it.left * escala.toDouble() - desX).toFloat(), (it.top * escala.toDouble() - desY).toFloat(), (it.right * escala.toDouble() - desX).toFloat(), (it.bottom * escala.toDouble() - desY).toFloat()) }
        val sel = seleccion?.takeIf { !it.vacia }
        if (sel != null) {
            // Dentro de la selección, con su borde suavizado: se pinta aparte y se le quita lo de fuera
            c.saveLayer(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p)
            pinta(c, x.toFloat(), y.toFloat(), null)
            c.drawPath(sel.local(escala.toDouble(), 0.0, 0.0, desX, desY, x, y, x + w, y + h, true), pFuera)
        } else pinta(c, x.toFloat(), y.toFloat(), p)
        c.restoreToCount(g)
    }

    /**
     * Pinta este trazo en la tesela ([tx], [ty]) de 256 px a [escala], sobre [c] (el lienzo de esa tesela, sin transformar).
     * Devuelve si pintó algo.
     */
    fun pintaTesela(c: Canvas, tx: Int, ty: Int, escala: Float): Boolean {
        val L = Lienzo.L
        val zona = relleno
        if (zona != null) {
            // Una zona rellena: su forma, del color, a este tamaño
            val pr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@TrazoDatos.color or (0xFF shl 24) }
            funde(c, 0.0, 0.0, L, L, escala, tx * L.toDouble(), ty * L.toDouble()) { cv, _, _, p ->
                val g = if (p != null) cv.saveLayer(0f, 0f, L.toFloat(), L.toFloat(), p) else -1
                cv.drawPath(zona.local(escala.toDouble(), ox, oy, tx * L.toDouble(), ty * L.toDouble(), 0.0, 0.0, L.toDouble(), L.toDouble()), pr)
                if (g >= 0) cv.restoreToCount(g)
            }
            return true
        }
        val l = Lona(tx.toLong() * L, ty.toLong() * L, L, L, def.colorVariable)
        val s = sellos(); val r = raster(escala)
        val dx = ox * escala - l.ox; val dy = oy * escala - l.oy; val gx = r.granoEn(l.ox); val gy = r.granoEn(l.oy)
        for (i in 0 until s.n) r.sella(l, s, i, dx, dy, gx, gy)
        if (!l.tocada) return false
        funde(c, l, escala, l.ox.toDouble(), l.oy.toDouble())
        return true
    }

    fun escribe(o: DataOutputStream) {
        o.writeUTF(def.json().toString()); o.writeInt(color); o.writeFloat(tam); o.writeFloat(opacidad); o.writeInt(modo); o.writeBoolean(atope)
        o.writeInt(semilla); o.writeFloat(u); o.writeBoolean(lapiz)
        o.writeBoolean(recorte != null); recorte?.let { o.writeFloat(it.left); o.writeFloat(it.top); o.writeFloat(it.right); o.writeFloat(it.bottom) }
        o.writeBoolean(seleccion != null); seleccion?.escribe(o)
        o.writeInt(ramas.size); for (r in ramas) { o.writeInt(r.size); for (v in r) o.writeFloat(v) }
        o.writeDouble(ox); o.writeDouble(oy)
        o.writeBoolean(relleno != null); relleno?.escribe(o)
    }

    companion object {
        const val PINTAR = 0
        const val BORRAR = 1
        private val pFuera = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        /** Los pinceles ya leídos (miles de trazos comparten unos pocos). */
        private val leidos = ConcurrentHashMap<String, DefPincel>()

        /** [conOrigen] false: los guardados por la 2.0–2.2 (sin origen ni rellenos). */
        fun lee(i: DataInputStream, conOrigen: Boolean = true): TrazoDatos {
            val def = i.readUTF().let { j -> if (leidos.size > 400) leidos.clear(); leidos.getOrPut(j) { DefPincel.de(org.json.JSONObject(j)) } }; val color = i.readInt(); val tam = i.readFloat(); val op = i.readFloat(); val modo = i.readInt(); val atope = i.readBoolean()
            val semilla = i.readInt(); val u = i.readFloat(); val lapiz = i.readBoolean()
            val rec = if (i.readBoolean()) RectF(i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat()) else null
            val sel = if (i.readBoolean()) Seleccion.lee(i) else null
            val ramas = List(i.readInt()) { FloatArray(i.readInt()) { i.readFloat() } }
            if (!conOrigen) return TrazoDatos(def, color, tam, op, modo, atope, ramas, semilla, u, lapiz, rec, sel)
            val ox = i.readDouble(); val oy = i.readDouble()
            val zona = if (i.readBoolean()) Seleccion.lee(i) else null
            return TrazoDatos(def, color, tam, op, modo, atope, ramas, semilla, u, lapiz, rec, sel, ox, oy, zona)
        }
    }
}

/** Un trazo guardado en una capa, con su número de orden. */
class TrazoV(val datos: TrazoDatos, val seq: Long) { val caja: RectF get() = datos.caja }

/**
 * Lo que había en una tesela antes de sus trazos guardados: de cerca, se pinta esto ampliado y encima los trazos con número
 * mayor que [desde], rehechos a ese tamaño.
 */
class Fondo(val bmp: Bitmap, val desde: Long)

/** Una lista de decimales que crece. */
class FloatLista(cap: Int = 256) {
    var v = FloatArray(cap); var n = 0
    fun pon(x: Float) { if (n == v.size) v = v.copyOf(n * 2); v[n++] = x }
    fun copia(): FloatArray = v.copyOf(n)
    fun limpia() { n = 0 }
}

/**
 * El trazo que se está dibujando: sus sellos se estampan según llegan los puntos del lápiz (en un hilo aparte) en su
 * pintura húmeda, que la mesa enseña fundida con la capa. Al soltar, se asienta en la capa. Los puntos llegan medidos desde
 * el origen del trazo ([ox], [oy]). [espejos]: los reflejos de la simetría, como transformaciones (a, b, c, d, e, f) del
 * punto del lienzo: x' = a·x + b·y + e, y' = c·x + d·y + f (la primera, el trazo tal cual).
 */
class TrazoVivo(
    val lz: Lienzo, val capa: CapaPx, val def: DefPincel, val color: Int, val tam: Float, val opacidad: Float, val modo: Int,
    val semilla: Int, val u: Float, val lapiz: Boolean, val nivel: Int, val recorte: RectF?, val seleccion: Seleccion?,
    val ox: Double, val oy: Double, private val espejos: List<DoubleArray>,
) {
    val atope = capa.info.bloqueoAlfa
    val escala: Float = run { var n = nivel; while (n < 0 && tam * Motor.escala(n) > 1200f) n++; Motor.escala(n) }
    @Volatile var humedo = Humedo(escala, def.colorVariable, ox, oy); private set
    @Volatile var estado = VIVO
    private val ramas = List(espejos.size) { FloatLista() }
    private val sellos = Sellos()
    private val raster = Raster(def, escala, u)
    /** Para fundir con la capa lo que se ve mientras se dibuja. */
    val pintura: Paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (opacidad.coerceIn(0f, 1f) * 255).toInt()
        xfermode = when { modo == TrazoDatos.BORRAR -> PorterDuffXfermode(PorterDuff.Mode.DST_OUT); atope -> PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP); else -> null }
    }
    /** El color de la capa en un punto: para los pinceles que mezclan con lo que hay. */
    val muestra: ((Float, Float) -> Int)? = if (def.mezcla > 0.001f) { x, y -> lz.colorCapa(capa, (x + ox).toFloat(), (y + oy).toFloat()) } else null
    // (Después de los sellos y de la muestra: los caminantes se quedan con ellos al crearse)
    private var caminantes = nuevos(-1f)

    private fun nuevos(largo: Float) = List(espejos.size) { k -> Caminante(def, tam, color, semilla + k * 7919, lapiz, u, largo, sellos, muestra) }

    private fun meteRama(k: Int, pts: FloatArray, n: Int) {
        val m = espejos[k]; val r = ramas[k]; val c = caminantes[k]
        for (i in 0 until n) {
            val o = i * Caminante.P
            var x = pts[o]; var y = pts[o + 1]
            if (k > 0) { val ax = x + ox; val ay = y + oy; x = (m[0] * ax + m[1] * ay + m[4] - ox).toFloat(); y = (m[2] * ax + m[3] * ay + m[5] - oy).toFloat() }
            r.pon(x); r.pon(y); r.pon(pts[o + 2]); r.pon(pts[o + 3]); r.pon(pts[o + 4]); r.pon(pts[o + 5])
            c.punto(x, y, pts[o + 2], pts[o + 3], pts[o + 4], pts[o + 5].toLong())
        }
    }

    /** Puntos nuevos del lápiz (x, y desde el origen del trazo, presión, inclinación, acimut, tiempo). */
    fun agrega(pts: FloatArray, n: Int = pts.size / Caminante.P) {
        if (n == 0) return
        lz.enVivo {
            if (estado != VIVO) return@enVivo
            val t0 = System.nanoTime()
            for (k in espejos.indices) meteRama(k, pts, n)
            humedo.sella(raster, sellos); sellos.limpia()
            Medidor.estampa(System.nanoTime() - t0)
            lz.avisaVivo()
        }
    }

    /** Sustituye todo el camino por otro (una forma limpia: una recta, una elipse…). */
    fun sustituye(pts: FloatArray) = lz.enVivo {
        if (estado != VIVO) return@enVivo
        ramas.forEach { it.limpia() }; caminantes = nuevos(-1f); sellos.limpia()
        val h = Humedo(escala, def.colorVariable, ox, oy)
        for (k in espejos.indices) meteRama(k, pts, pts.size / Caminante.P)
        h.sella(raster, sellos); sellos.limpia()
        humedo = h
        lz.avisaVivo()
    }

    fun datos() = TrazoDatos(def, color, tam, opacidad, modo, atope, ramas.map { it.copia() }, semilla, u, lapiz, recorte, seleccion, ox, oy)

    /** Se levantó el lápiz: se acaba de estampar (afilando el final si el pincel lo pide) y pasa a la capa. */
    fun termina(despues: (Edicion?, Throwable?) -> Unit) = lz.enVivo {
        if (estado != VIVO) return@enVivo
        caminantes.forEach { it.fin() }
        humedo.sella(raster, sellos); sellos.limpia()
        if (Caminante.seAfila(def, lapiz)) { val h = Humedo(escala, def.colorVariable, ox, oy); h.sellaTodo(raster, datos().sellos(muestra)); humedo = h }
        estado = ASENTANDO
        lz.avisaVivo()
        lz.enHilo {
            val r = runCatching { lz.asienta(this) }
            if (r.isFailure) { estado = HECHO; lz.vivos.remove(this); lz.avisaVivo() }
            despues(r.getOrNull(), r.exceptionOrNull())
        }
    }

    fun cancela() { estado = HECHO; lz.vivos.remove(this); lz.avisaVivo() }

    companion object {
        const val VIVO = 0; const val ASENTANDO = 1; const val ASENTADO = 2; const val HECHO = 3
        val SIN_ESPEJOS = listOf(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
    }
}

/**
 * Las capas vistas de cerca: teselas de 256 px de pantalla que cubren un trozo pequeño del lienzo (a 2.048 aumentos, un
 * octavo de píxel), con los trazos rehechos a ese tamaño (nítidos) sobre lo que había debajo ampliado. Se hacen en segundo
 * plano y se guardan mientras se usan.
 */
class Nitidez(private val lz: Lienzo) {
    data class Clave(val capa: String, val e: Int, val tx: Int, val ty: Int)
    private class E(@Volatile var bmp: Bitmap?, @Volatile var sucia: Boolean, @Volatile var encolada: Boolean, @Volatile var uso: Long)
    private val cache = ConcurrentHashMap<Clave, E>()
    private val hilos = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r, "atlas-nitidez").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    val alListo = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var reloj = 0L
    private val pFiltro = Paint(Paint.FILTER_BITMAP_FLAG)

    /** La tesela ([tx], [ty]) de la capa a escala 2^[e] (o null si aún no está: se pide). */
    fun tesela(c: CapaPx, e: Int, tx: Int, ty: Int): Bitmap? {
        val k = Clave(c.info.id, e, tx, ty)
        val en = cache.getOrPut(k) { E(null, true, false, 0) }
        en.uso = ++reloj
        if (en.sucia && !en.encolada) {
            en.encolada = true
            // (Si el mapa ya se ha cerrado, los hilos no aceptan más trabajo: no pasa nada)
            runCatching { hilos.execute {
                en.sucia = false
                val b = runCatching { construye(c, e, tx, ty) }.getOrNull()
                if (b != null) en.bmp = b
                en.encolada = false
                alListo.forEach { it() }
            } }.onFailure { en.encolada = false }
        }
        if (cache.size > 520) recorta()
        return en.bmp
    }

    private fun recorta() {
        val l = cache.entries.sortedBy { it.value.uso }
        for (i in 0 until (l.size - 380).coerceAtLeast(0)) cache.remove(l[i].key)
    }

    /** Han cambiado esas teselas de la capa: lo que había de ellas se rehará (si el cambio es un [trazo], solo lo que él toca). */
    fun ensucia(capa: String, ks: Collection<Long>, trazo: TrazoDatos? = null) {
        if (cache.isEmpty()) return
        val set = if (ks is Set<Long>) ks else ks.toHashSet()
        for ((k, e) in cache) if (k.capa == capa) {
            val s = 1 shl k.e
            if (Lienzo.clave(Math.floorDiv(k.tx, s), Math.floorDiv(k.ty, s)) !in set) continue
            if (trazo != null) { val lado = Lienzo.L.toDouble() / s; val x0 = k.tx * lado; val y0 = k.ty * lado; if (!trazo.toca(x0 - lado * 0.01, y0 - lado * 0.01, x0 + lado * 1.01, y0 + lado * 1.01)) continue }
            e.sucia = true
        }
    }

    fun olvida(capa: String? = null) { if (capa == null) cache.clear() else cache.keys.removeAll { it.capa == capa } }

    /** Hace la tesela: lo que había debajo de los trazos, ampliado, y los trazos a este tamaño. */
    fun construye(c: CapaPx, e: Int, tx: Int, ty: Int): Bitmap {
        val L = Lienzo.L; val s = 1 shl e
        val bx = Math.floorDiv(tx, s); val by = Math.floorDiv(ty, s); val k = Lienzo.clave(bx, by)
        val base: Bitmap?; val trazos: List<TrazoV>; val desde: Long
        synchronized(lz.cierre) { val f = c.fondo[k]; base = f?.bmp ?: c.teselas[k]; trazos = if (f != null) c.trazos else emptyList(); desde = f?.desde ?: Long.MAX_VALUE }
        val b = Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        // Dónde empieza esta tesela en el lienzo (con doble precisión: a 2.048 aumentos las cuentas con decimales cortos fallan)
        val x0 = tx * L.toDouble() / s; val y0 = ty * L.toDouble() / s; val lado = L.toDouble() / s
        if (base != null && base !== TESELA_VACIA) {
            // El trozo de la tesela normal que cae aquí (con un píxel de margen para que el suavizado no haga bordes), ampliado
            val lx = x0 - bx * L.toDouble(); val ly = y0 - by * L.toDouble()
            val o = android.graphics.Rect(maxOf(0, floor(lx).toInt() - 1), maxOf(0, floor(ly).toInt() - 1), minOf(L, ceil(lx + lado).toInt() + 1), minOf(L, ceil(ly + lado).toInt() + 1))
            val d = RectF(((o.left - lx) * s).toFloat(), ((o.top - ly) * s).toFloat(), ((o.right - lx) * s).toFloat(), ((o.bottom - ly) * s).toFloat())
            cv.drawBitmap(base, o, d, pFiltro)
        }
        val m = lado * 0.01
        for (tr in trazos) if (tr.seq > desde && tr.datos.seVe(s.toFloat()) && tr.datos.toca(x0 - m, y0 - m, x0 + lado + m, y0 + lado + m)) tr.datos.pintaTesela(cv, tx, ty, s.toFloat())
        return b
    }

    /** Las teselas de cerca que toca un trazo recién dibujado a esa escala, ya con él encima (para que nada cambie al soltar). */
    fun conTrazo(c: CapaPx, h: Humedo, d: TrazoDatos): Map<Clave, Bitmap> {
        val e = Math.round(kotlin.math.ln(h.escala) / kotlin.math.ln(2f))
        if (e < 1 || (1 shl e).toFloat() != h.escala) return emptyMap()
        val m = HashMap<Clave, Bitmap>()
        for ((kk, l) in h.lonas) {
            if (!l.tocada) continue
            val tx = Lienzo.tx(kk); val ty = Lienzo.ty(kk); val k = Clave(c.info.id, e, tx, ty)
            val previa = cache[k]?.takeIf { !it.sucia && !it.encolada }?.bmp
            val b = previa?.copy(Bitmap.Config.ARGB_8888, true) ?: construye(c, e, tx, ty)
            d.funde(Canvas(b), l, h.escala, l.ox.toDouble(), l.oy.toDouble())
            m[k] = b
        }
        return m
    }

    /** La tesela si ya está hecha (sin pedirla). */
    fun hecha(c: CapaPx, e: Int, tx: Int, ty: Int): Bitmap? = cache[Clave(c.info.id, e, tx, ty)]?.bmp

    fun pon(m: Map<Clave, Bitmap>) { for ((k, b) in m) cache[k] = E(b, false, false, ++reloj) }

    fun cierra() = hilos.shutdown()
}
