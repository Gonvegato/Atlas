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
 * uno con x, y, presión, inclinación, acimut y tiempo por punto.
 */
class TrazoDatos(
    val def: DefPincel, val color: Int, val tam: Float, val opacidad: Float, val modo: Int, val atope: Boolean, val ramas: List<FloatArray>,
    val semilla: Int, val u: Float, val lapiz: Boolean, val recorte: RectF?, val seleccion: Seleccion?,
) {
    @Volatile private var guardados: java.lang.ref.SoftReference<Sellos>? = null

    /** Los sellos del trazo entero. */
    fun sellos(muestra: ((Float, Float) -> Int)? = null): Sellos {
        if (muestra == null) guardados?.get()?.let { return it }
        val s = Sellos(512)
        ramas.forEachIndexed { k, pts ->
            val n = pts.size / Caminante.P
            val c = Caminante(def, tam, color, semilla + k * 7919, lapiz, u, Caminante.largo(def, tam, pts, n), s, muestra)
            for (i in 0 until n) { val o = i * Caminante.P; c.punto(pts[o], pts[o + 1], pts[o + 2], pts[o + 3], pts[o + 4], pts[o + 5].toLong()) }
            c.fin()
        }
        if (muestra == null) guardados = java.lang.ref.SoftReference(s)
        return s
    }

    /** Lo que ocupa (píxeles del lienzo). */
    val caja: RectF by lazy { (sellos().caja() ?: RectF()).also { r -> recorte?.let { if (!r.intersect(it)) r.setEmpty() } } }

    /** Cómo se funde con la capa: con la opacidad del pincel; borrando; o solo sobre lo que ya hay (transparencia bloqueada). */
    fun pintura(filtro: Boolean = false): Paint = Paint(if (filtro) Paint.FILTER_BITMAP_FLAG else 0).apply {
        alpha = (opacidad.coerceIn(0f, 1f) * 255).toInt()
        xfermode = when { modo == BORRAR -> PorterDuffXfermode(PorterDuff.Mode.DST_OUT); atope -> PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP); else -> null }
    }

    /** Funde la pintura de [l] (estampada a [escala]) en [c], que está en coordenadas del lienzo. [img]: la imagen de la lona. */
    fun funde(c: Canvas, l: Lona, escala: Float, img: Bitmap = l.imagen(color, def.bordes), p: Paint = pintura(escala != 1f)) = funde(c, l, escala, img, p, recorte, seleccion)

    /** Pinta este trazo en la tesela ([tx], [ty]) de 256 px a [escala] sobre [c] (en coordenadas del lienzo). Devuelve si pintó algo. */
    fun pintaTesela(c: Canvas, tx: Int, ty: Int, escala: Float): Boolean {
        val l = Lona(tx * Lienzo.L, ty * Lienzo.L, Lienzo.L, Lienzo.L, def.colorVariable)
        val s = sellos(); val r = Raster(def, escala)
        for (i in 0 until s.n) r.sella(l, s, i)
        if (!l.tocada) return false
        funde(c, l, escala)
        return true
    }

    fun escribe(o: DataOutputStream) {
        o.writeUTF(def.json().toString()); o.writeInt(color); o.writeFloat(tam); o.writeFloat(opacidad); o.writeInt(modo); o.writeBoolean(atope)
        o.writeInt(semilla); o.writeFloat(u); o.writeBoolean(lapiz)
        o.writeBoolean(recorte != null); recorte?.let { o.writeFloat(it.left); o.writeFloat(it.top); o.writeFloat(it.right); o.writeFloat(it.bottom) }
        o.writeBoolean(seleccion != null); seleccion?.escribe(o)
        o.writeInt(ramas.size); for (r in ramas) { o.writeInt(r.size); for (v in r) o.writeFloat(v) }
    }

    companion object {
        const val PINTAR = 0
        const val BORRAR = 1
        private val pFuera = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

        fun lee(i: DataInputStream): TrazoDatos {
            val def = DefPincel.de(org.json.JSONObject(i.readUTF())); val color = i.readInt(); val tam = i.readFloat(); val op = i.readFloat(); val modo = i.readInt(); val atope = i.readBoolean()
            val semilla = i.readInt(); val u = i.readFloat(); val lapiz = i.readBoolean()
            val rec = if (i.readBoolean()) RectF(i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat()) else null
            val sel = if (i.readBoolean()) Seleccion.lee(i) else null
            val ramas = List(i.readInt()) { FloatArray(i.readInt()) { i.readFloat() } }
            return TrazoDatos(def, color, tam, op, modo, atope, ramas, semilla, u, lapiz, rec, sel)
        }

        fun funde(c: Canvas, l: Lona, escala: Float, img: Bitmap, p: Paint, recorte: RectF?, seleccion: Seleccion?) {
            val g = c.save()
            recorte?.let { c.clipRect(it) }
            if (seleccion != null && !seleccion.vacia) {
                // Dentro de la selección, con su borde suavizado: se pinta aparte y se recorta con la forma
                val x = l.ox / escala; val y = l.oy / escala
                c.saveLayer(x, y, x + l.w / escala, y + l.h / escala, p)
                c.save(); c.scale(1f / escala, 1f / escala); c.drawBitmap(img, l.ox.toFloat(), l.oy.toFloat(), if (escala != 1f) FILTRO else null); c.restore()
                c.drawPath(seleccion.fuera, pFuera)
            } else {
                c.scale(1f / escala, 1f / escala)
                c.drawBitmap(img, l.ox.toFloat(), l.oy.toFloat(), p)
            }
            c.restoreToCount(g)
        }
        private val FILTRO = Paint(Paint.FILTER_BITMAP_FLAG)
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
 * pintura húmeda, que la mesa enseña fundida con la capa. Al soltar, se asienta en la capa.
 */
class TrazoVivo(
    val lz: Lienzo, val capa: CapaPx, val def: DefPincel, val color: Int, val tam: Float, val opacidad: Float, val modo: Int,
    val semilla: Int, val u: Float, val lapiz: Boolean, val nivel: Int, val recorte: RectF?, val seleccion: Seleccion?, private val espejos: List<Matrix>,
) {
    val atope = capa.info.bloqueoAlfa
    val escala: Float = run { var n = nivel; while (n < 0 && tam * Motor.escala(n) > 1200f) n++; Motor.escala(n) }
    @Volatile var humedo = Humedo(escala, def.colorVariable); private set
    @Volatile var estado = VIVO
    private val ramas = List(espejos.size) { FloatLista() }
    private val sellos = Sellos()
    private val raster = Raster(def, escala)
    /** Para fundir con la capa lo que se ve mientras se dibuja. */
    val pintura: Paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (opacidad.coerceIn(0f, 1f) * 255).toInt()
        xfermode = when { modo == TrazoDatos.BORRAR -> PorterDuffXfermode(PorterDuff.Mode.DST_OUT); atope -> PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP); else -> null }
    }
    /** El color de la capa en un punto: para los pinceles que mezclan con lo que hay. */
    val muestra: ((Float, Float) -> Int)? = if (def.mezcla > 0.001f) { x, y -> lz.colorCapa(capa, x, y) } else null
    private val tmp = FloatArray(2)
    // (Después de los sellos y de la muestra: los caminantes se quedan con ellos al crearse)
    private var caminantes = nuevos(-1f)

    private fun nuevos(largo: Float) = List(espejos.size) { k -> Caminante(def, tam, color, semilla + k * 7919, lapiz, u, largo, sellos, muestra) }

    private fun meteRama(k: Int, pts: FloatArray, n: Int) {
        val m = espejos[k]; val r = ramas[k]; val c = caminantes[k]
        for (i in 0 until n) {
            val o = i * Caminante.P
            tmp[0] = pts[o]; tmp[1] = pts[o + 1]; if (!m.isIdentity) m.mapPoints(tmp)
            r.pon(tmp[0]); r.pon(tmp[1]); r.pon(pts[o + 2]); r.pon(pts[o + 3]); r.pon(pts[o + 4]); r.pon(pts[o + 5])
            c.punto(tmp[0], tmp[1], pts[o + 2], pts[o + 3], pts[o + 4], pts[o + 5].toLong())
        }
    }

    /** Puntos nuevos del lápiz (x, y, presión, inclinación, acimut, tiempo; en píxeles del lienzo). */
    fun agrega(pts: FloatArray, n: Int = pts.size / Caminante.P) {
        if (n == 0) return
        lz.enVivo {
            if (estado != VIVO) return@enVivo
            for (k in espejos.indices) meteRama(k, pts, n)
            humedo.sella(raster, sellos); sellos.limpia()
            lz.avisaVivo()
        }
    }

    /** Sustituye todo el camino por otro (una forma limpia: una recta, una elipse…). */
    fun sustituye(pts: FloatArray) = lz.enVivo {
        if (estado != VIVO) return@enVivo
        ramas.forEach { it.limpia() }; caminantes = nuevos(-1f); sellos.limpia()
        val h = Humedo(escala, def.colorVariable)
        for (k in espejos.indices) meteRama(k, pts, pts.size / Caminante.P)
        h.sella(raster, sellos); sellos.limpia()
        humedo = h
        lz.avisaVivo()
    }

    fun datos() = TrazoDatos(def, color, tam, opacidad, modo, atope, ramas.map { it.copia() }, semilla, u, lapiz, recorte, seleccion)

    /** Se levantó el lápiz: se acaba de estampar (afilando el final si el pincel lo pide) y pasa a la capa. */
    fun termina(despues: (Edicion?, Throwable?) -> Unit) = lz.enVivo {
        if (estado != VIVO) return@enVivo
        caminantes.forEach { it.fin() }
        humedo.sella(raster, sellos); sellos.limpia()
        if (Caminante.seAfila(def, lapiz)) { val h = Humedo(escala, def.colorVariable); h.sellaTodo(raster, datos().sellos(muestra)); humedo = h }
        estado = ASENTANDO
        lz.avisaVivo()
        lz.enHilo {
            val r = runCatching { lz.asienta(this) }
            if (r.isFailure) { estado = HECHO; lz.vivos.remove(this); lz.avisaVivo() }
            despues(r.getOrNull(), r.exceptionOrNull())
        }
    }

    fun cancela() { estado = HECHO; lz.vivos.remove(this); lz.avisaVivo() }

    companion object { const val VIVO = 0; const val ASENTANDO = 1; const val ASENTADO = 2; const val HECHO = 3 }
}

/**
 * Las capas vistas de cerca: teselas de 256 px de pantalla que cubren un trozo pequeño del lienzo, con los trazos rehechos a
 * ese tamaño (nítidos) sobre lo que había debajo ampliado. Se hacen en segundo plano y se guardan mientras se usan.
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

    /** Han cambiado esas teselas de la capa: lo que había de ellas se rehará. */
    fun ensucia(capa: String, ks: Collection<Long>) {
        if (cache.isEmpty()) return
        val set = if (ks is Set<Long>) ks else ks.toHashSet()
        for ((k, e) in cache) if (k.capa == capa) { val s = 1 shl k.e; if (Lienzo.clave(Math.floorDiv(k.tx, s), Math.floorDiv(k.ty, s)) in set) e.sucia = true }
    }

    fun olvida(capa: String? = null) { if (capa == null) cache.clear() else cache.keys.removeAll { it.capa == capa } }

    /** Hace la tesela: lo que había debajo de los trazos, ampliado, y los trazos a este tamaño. */
    fun construye(c: CapaPx, e: Int, tx: Int, ty: Int): Bitmap {
        val L = Lienzo.L; val s = 1 shl e; val lado = L / s
        val bx = Math.floorDiv(tx, s); val by = Math.floorDiv(ty, s); val k = Lienzo.clave(bx, by)
        val base: Bitmap?; val trazos: List<TrazoV>; val desde: Long
        synchronized(lz.cierre) { val f = c.fondo[k]; base = f?.bmp ?: c.teselas[k]; trazos = if (f != null) c.trazos else emptyList(); desde = f?.desde ?: Long.MAX_VALUE }
        val b = Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.scale(s.toFloat(), s.toFloat()); cv.translate(-(tx * lado).toFloat(), -(ty * lado).toFloat())
        if (base != null && base !== TESELA_VACIA) cv.drawBitmap(base, (bx * L).toFloat(), (by * L).toFloat(), pFiltro)
        val zona = RectF((tx * lado).toFloat(), (ty * lado).toFloat(), ((tx + 1) * lado).toFloat(), ((ty + 1) * lado).toFloat())
        for (tr in trazos) if (tr.seq > desde && RectF.intersects(tr.caja, zona)) tr.datos.pintaTesela(cv, tx, ty, s.toFloat())
        return b
    }

    /** Las teselas de cerca que toca un trazo recién dibujado a esa escala, ya con él encima (para que nada cambie al soltar). */
    fun conTrazo(c: CapaPx, h: Humedo, d: TrazoDatos): Map<Clave, Bitmap> {
        val e = Math.round(kotlin.math.ln(h.escala) / kotlin.math.ln(2f))
        if (e < 1 || (1 shl e).toFloat() != h.escala) return emptyMap()
        val m = HashMap<Clave, Bitmap>()
        val s = h.escala
        for ((kk, l) in h.lonas) {
            if (!l.tocada) continue
            val tx = Lienzo.tx(kk); val ty = Lienzo.ty(kk); val k = Clave(c.info.id, e, tx, ty)
            val previa = cache[k]?.takeIf { !it.sucia && !it.encolada }?.bmp
            val b = previa?.copy(Bitmap.Config.ARGB_8888, true) ?: construye(c, e, tx, ty)
            val cv = Canvas(b)
            cv.scale(s, s); cv.translate(-(tx * Lienzo.L) / s, -(ty * Lienzo.L) / s)
            d.funde(cv, l, s)
            m[k] = b
        }
        return m
    }

    fun pon(m: Map<Clave, Bitmap>) { for ((k, b) in m) cache[k] = E(b, false, false, ++reloj) }

    fun cierra() = hilos.shutdown()
}
