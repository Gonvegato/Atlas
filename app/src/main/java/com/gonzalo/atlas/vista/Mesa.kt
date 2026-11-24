package com.gonzalo.atlas.vista

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.brush.InputToolType
import androidx.input.motionprediction.MotionEventPredictor
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.editor.Herramienta
import com.gonzalo.atlas.lienzo.Lienzo
import com.gonzalo.atlas.lienzo.Papel
import com.gonzalo.atlas.lienzo.Pinceles
import com.gonzalo.atlas.lienzo.Piramide
import com.gonzalo.atlas.lienzo.TipoPincel
import com.gonzalo.atlas.lienzo.VeloTerritorios
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * La mesa de dibujo: el lienzo (papel, capas, territorios y lo que va encima) y, delante, la capa de tinta de Ink, que pinta
 * el trazo en curso en el búfer delantero de la pantalla (lo más inmediato que deja Android). Al levantar el lápiz el trazo
 * pasa a la capa activa.
 *
 * Con el lápiz: el lápiz dibuja y los dedos mueven, acercan y giran (la palma no pinta). Sin lápiz: un dedo dibuja y dos mueven.
 */
@SuppressLint("ViewConstructor")
class Mesa(ctx: Context, val editor: Editor, private val recurso: (String) -> Bitmap?) : FrameLayout(ctx) {
    val lz: Lienzo = editor.lienzo
    val mapa = editor.mapa
    val vista = Vista()
    val capas = Capas(ctx)
    val opciones = Capas.Opciones()
    val velo = VeloTerritorios(lz)
    private val dibujo = object : View(ctx) { override fun onDraw(c: Canvas) = pinta(c) }
    val piramide = Piramide(lz, velo) { dibujo.postInvalidateOnAnimation() }
    private val tinta = InProgressStrokesView(ctx)
    private val dens = resources.displayMetrics.density
    /** Predice por dónde va a ir el lápiz (para restar latencia). Si el sistema no puede, sin predicción. */
    private val predictor: MotionEventPredictor? = runCatching { MotionEventPredictor.newInstance(this) }.getOrNull()
    var lapizVisto = false
    var dedoDibuja = false
    var girar = true
    var alMoverse: () -> Unit = {}

    private class Meta(val tipo: TipoPincel, val opacidad: Float, val capa: String, val color: Int, val tam: Float)
    private val metas = HashMap<InProgressStrokeId, Meta>()

    init {
        addView(dibujo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(tinta, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tinta.textureBitmapStore = Pinceles.almacen(resources)
        tinta.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                for ((id, st) in strokes) {
                    val m = metas.remove(id)
                    if (m == null) { tinta.removeFinishedStrokes(setOf(id)); continue }
                    // El trazo de verdad, con el color opaco (la opacidad se aplica al fundirlo con la capa)
                    val opaco = st.copy(st.brush.copyWithColorIntArgb(m.color or (0xFF shl 24)))
                    editor.trazoTerminado(opaco, m.tipo, m.opacidad, m.capa) {
                        dibujo.invalidate()
                        dibujo.postOnAnimation { tinta.removeFinishedStrokes(setOf(id)) }
                    }
                }
            }
        })
        runCatching { tinta.eagerInit() }
        lz.alCambiar += { _ -> post { dibujo.postInvalidateOnAnimation() } }
        editor.alCambiarVista = { dibujo.postInvalidateOnAnimation() }
        mapa.alCambiarVector += { post { velo.olvida(); piramide.todoSucio(); dibujo.postInvalidateOnAnimation() } }
    }

    private var colocada = false
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        vista.ancho = w; vista.alto = h
        if (!colocada) { encuadra(); colocada = true }
        vista.actualiza()
    }

    fun encuadra(usarGuardada: Boolean = true) {
        val g = mapa.vista
        if (usarGuardada && !g[0].isNaN()) { vista.cx = g[0]; vista.cy = g[1]; vista.zoom = g[2]; vista.giro = if (g[3].isNaN()) 0f else g[3] }
        else {
            val l = lz.limites()
            vista.cx = l.exactCenterX(); vista.cy = l.exactCenterY(); vista.giro = 0f
            vista.zoom = min(width / l.width().toFloat(), height / l.height().toFloat()) * 0.9f
        }
        limita(); vista.actualiza(); dibujo.invalidate(); alMoverse()
    }

    fun recuerdaVista() { mapa.vista[0] = vista.cx; mapa.vista[1] = vista.cy; mapa.vista[2] = vista.zoom; mapa.vista[3] = vista.giro }

    private fun limita() {
        val l = lz.limites()
        val zmin = if (mapa.infinito) 0.03f else min(width / l.width().toFloat(), height / l.height().toFloat()) * 0.4f
        vista.zoom = vista.zoom.coerceIn(max(0.01f, zmin), 16f)
        if (!mapa.infinito) { vista.cx = vista.cx.coerceIn(0f, mapa.ancho.toFloat()); vista.cy = vista.cy.coerceIn(0f, mapa.alto.toFloat()) }
    }

    fun refresca() { dibujo.invalidate() }

    // ---------------------------------------------------------------- dibujar

    private val pCapa = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pFiltro = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pCalco = Paint(Paint.FILTER_BITMAP_FLAG)
    private var calcoBmp: Bitmap? = null
    private var calcoLeido = 0L

    /** La imagen de calcar (se vuelve a leer si cambia el archivo). */
    fun calco(): Bitmap? {
        val f = mapa.dir?.let { java.io.File(it, "calco.png") } ?: return null
        if (mapa.calco == null || !f.exists()) return null
        if (calcoBmp == null || f.lastModified() != calcoLeido) { calcoBmp = android.graphics.BitmapFactory.decodeFile(f.path); calcoLeido = f.lastModified() }
        return calcoBmp
    }

    private fun pintaCalco(c: Canvas) {
        val k = mapa.calco ?: return
        if (!k.visible) return
        val b = calco() ?: return
        pCalco.alpha = (k.opacidad * 255).toInt()
        c.save()
        c.translate(k.x, k.y); c.rotate(k.giro)
        val s = k.ancho / b.width
        c.scale(s, abs(s))
        c.drawBitmap(b, -b.width / 2f, -b.height / 2f, pCalco)
        if (editor.herramienta == Herramienta.CALCO) {
            val p = Paint().apply { style = Paint.Style.STROKE; color = 0xFF5C7894.toInt(); strokeWidth = 2f / (abs(s) * vista.zoom) }
            c.drawRect(-b.width / 2f, -b.height / 2f, b.width / 2f, b.height / 2f, p)
        }
        c.restore()
    }

    private fun pinta(c: Canvas) {
        c.drawColor(0xFFE4DABB.toInt())
        vista.actualiza()
        val vis = vista.visible()
        val papel = if (mapa.infinito) RectF(vis) else RectF(0f, 0f, mapa.ancho.toFloat(), mapa.alto.toFloat()).also { if (!it.intersect(vis)) it.setEmpty() }
        c.save()
        c.concat(vista.m)
        if (!papel.isEmpty) {
            Papel.pinta(c, mapa, papel)
            if (mapa.calco?.encima != true) pintaCalco(c)
            val nivel = if (vista.zoom >= 0.7f) 0 else floor(ln(1f / vista.zoom) / ln(2f)).toInt().coerceIn(1, 7)
            if (nivel == 0) capasDirectas(c, papel) else desdePiramide(c, papel, nivel)
            if (mapa.calco?.encima == true) pintaCalco(c)
            // Borde del lienzo
            if (!mapa.infinito) { pCapa.alpha = 255; pCapa.blendMode = null; val p = Paint().apply { style = Paint.Style.STROKE; color = 0x55000000; strokeWidth = 1f / vista.zoom }; c.drawRect(0f, 0f, mapa.ancho.toFloat(), mapa.alto.toFloat(), p) }
        }
        c.restore()
        opciones.borrador = editor.borrador
        opciones.radioBorrador = editor.radioTerritorio
        opciones.seleccion = editor.seleccion
        opciones.regla = editor.regla
        capas.dibuja(c, mapa, vista, editor.centros, editor.superficies, recurso, opciones)
    }

    private fun rango(r: RectF, lado: Float): IntArray = intArrayOf(floor(r.left / lado).toInt(), floor(r.top / lado).toInt(), floor((r.right - 0.001f) / lado).toInt(), floor((r.bottom - 0.001f) / lado).toInt())

    private fun capasDirectas(c: Canvas, r: RectF) {
        val L = Lienzo.L.toFloat()
        val t = rango(r, L)
        for (capa in lz.capas) {
            if (!capa.info.visible || capa.teselas.isEmpty()) continue
            pCapa.alpha = (capa.info.opacidad * 255).toInt()
            pCapa.blendMode = Lienzo.fusion(capa.info.fusion)
            val g = c.saveLayer(r, pCapa)
            for (ty in t[1]..t[3]) for (tx in t[0]..t[2]) capa.teselas[Lienzo.clave(tx, ty)]?.let { c.drawBitmap(it, tx * L, ty * L, pFiltro) }
            c.restoreToCount(g)
        }
        if (mapa.verTerritorios) for (ty in t[1]..t[3]) for (tx in t[0]..t[2]) velo.tesela(Lienzo.clave(tx, ty))?.let { c.drawBitmap(it, tx * L, ty * L, pFiltro) }
    }

    private val rr = RectF()
    private fun desdePiramide(c: Canvas, r: RectF, nivel: Int) {
        val lado = Lienzo.L.toFloat() * (1 shl nivel)
        val t = rango(r, lado)
        if ((t[2] - t[0] + 1) * (t[3] - t[1] + 1) > 600) return
        for (ty in t[1]..t[3]) for (tx in t[0]..t[2]) {
            var b = piramide.tesela(nivel, tx, ty)
            rr.set(tx * lado, ty * lado, (tx + 1) * lado, (ty + 1) * lado)
            if (b == null) {
                // Mientras se hace: la del nivel de arriba (más basta), recortada
                val n2 = nivel + 1; val l2 = Lienzo.L.toFloat() * (1 shl n2)
                val px = floor(tx * lado / l2).toInt(); val py = floor(ty * lado / l2).toInt()
                val b2 = piramide.tesela(n2, px, py) ?: continue
                val sx = ((tx * lado - px * l2) / l2 * Lienzo.L).toInt(); val sy = ((ty * lado - py * l2) / l2 * Lienzo.L).toInt()
                c.drawBitmap(b2, android.graphics.Rect(sx, sy, sx + Lienzo.L / 2, sy + Lienzo.L / 2), rr, pFiltro)
                continue
            }
            c.drawBitmap(b, null, rr, pFiltro)
        }
    }

    // ---------------------------------------------------------------- tocar

    private enum class Gesto { NADA, TINTA, ARRASTRE, MOVER, CALCO }
    private var gesto = Gesto.NADA
    private var trazo: InProgressStrokeId? = null
    private var punteroTrazo = -1
    private var bajoX = 0f; private var bajoY = 0f; private var bajoT = 0L
    private var previo: FloatArray? = null
    private var movido = false

    private fun esLapiz(e: MotionEvent, i: Int) = e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER
    private fun puedeDibujar(e: MotionEvent, i: Int) = esLapiz(e, i) || !lapizVisto || dedoDibuja

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        predictor?.record(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (esLapiz(e, 0)) lapizVisto = true
                bajoX = e.x; bajoY = e.y; bajoT = e.eventTime; movido = false; previo = null
                val h = editor.herramienta
                val goma = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                gesto = when {
                    h == Herramienta.CALCO && mapa.calco != null -> Gesto.CALCO
                    !puedeDibujar(e, 0) -> Gesto.MOVER
                    h == Herramienta.PINCEL || h == Herramienta.GOMA || goma -> { empiezaTinta(e, goma); Gesto.TINTA }
                    h == Herramienta.ELEGIR && editor.seleccion != null && capas.queHay(mapa, vista, e.x, e.y, editor.centros, editor.superficies) === editor.seleccion -> { empiezaArrastre(e); Gesto.ARRASTRE }
                    h != Herramienta.ELEGIR && editor.arrastra() -> { empiezaArrastre(e); Gesto.ARRASTRE }
                    else -> Gesto.MOVER
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (gesto == Gesto.TINTA && !esLapiz(e, i) && !esLapizActivo(e)) {
                    // Un segundo dedo mientras se dibuja con el dedo: era para mover; el trazo se cancela
                    trazo?.let { tinta.cancelStroke(it, e); metas.remove(it) }; trazo = null; gesto = Gesto.MOVER
                } else if (gesto == Gesto.ARRASTRE && !esLapiz(e, i) && !esLapiz(e, 0)) { gesto = Gesto.MOVER }
                previo = null
            }
            MotionEvent.ACTION_MOVE -> when (gesto) {
                Gesto.TINTA -> trazo?.let { id ->
                    val i = e.findPointerIndex(punteroTrazo); if (i < 0) return true
                    if (cuerda != null) { estabiliza(e, i, id); cursor(e.getX(i), e.getY(i)); return true }
                    val pred = predictor?.predict()
                    runCatching { tinta.addToStroke(e, punteroTrazo, id, pred) }
                    pred?.recycle()
                    cursor(e.getX(i), e.getY(i))
                }
                Gesto.ARRASTRE -> {
                    val i = e.findPointerIndex(punteroTrazo).takeIf { it >= 0 } ?: 0
                    for (hh in 0 until e.historySize) { val m = vista.aMapa(e.getHistoricalX(i, hh), e.getHistoricalY(i, hh)); editor.mueve(m[0], m[1]) }
                    val m = vista.aMapa(e.getX(i), e.getY(i)); editor.mueve(m[0], m[1])
                    dibujo.invalidate()
                }
                Gesto.MOVER -> { if (hypot(e.x - bajoX, e.y - bajoY) > 8 * dens || e.pointerCount > 1) movido = true; mueveVista(e) }
                Gesto.CALCO -> mueveCalco(e)
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesto == Gesto.TINTA && e.getPointerId(e.actionIndex) == punteroTrazo) terminaTinta(e)
                previo = null
            }
            MotionEvent.ACTION_UP -> {
                when (gesto) {
                    Gesto.TINTA -> terminaTinta(e)
                    Gesto.ARRASTRE -> { editor.termina(); if (e.eventTime - bajoT < 250 && hypot(e.x - bajoX, e.y - bajoY) < 8 * dens) toque(e.x, e.y) }
                    Gesto.MOVER -> if (!movido && e.eventTime - bajoT < 350) toque(e.x, e.y)
                    Gesto.CALCO -> mapa.avisaVector()
                    else -> {}
                }
                gesto = Gesto.NADA; previo = null; opciones.cursor = null
                recuerdaVista(); dibujo.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                trazo?.let { tinta.cancelStroke(it, e); metas.remove(it) }; trazo = null
                if (gesto == Gesto.ARRASTRE) editor.termina()
                gesto = Gesto.NADA; previo = null; opciones.cursor = null
            }
        }
        return true
    }

    private fun esLapizActivo(e: MotionEvent): Boolean { val i = e.findPointerIndex(punteroTrazo); return i >= 0 && esLapiz(e, i) }

    private val inv = Matrix()
    private fun empiezaTinta(e: MotionEvent, gomaLapiz: Boolean) {
        val goma = gomaLapiz || editor.herramienta == Herramienta.GOMA
        val tipo = Pinceles.de(if (goma) editor.goma else editor.pincel)
        val capa = lz.capaActiva() ?: return
        if (!capa.info.visible) { editor.aviso = "La capa «${capa.info.nombre}» está oculta"; return }
        val tam = if (goma) editor.tamanoGoma else editor.tamano
        val op = if (goma) tipo.opacidad else editor.opacidad
        // Lo que se ve mientras se dibuja: el color con su opacidad (la goma, un velo del papel)
        val verColor = if (goma) (0xB0 shl 24) or (mapa.papel and 0xFFFFFF) else ((op * 255).toInt().coerceIn(10, 255) shl 24) or (editor.color and 0xFFFFFF)
        vista.m.invert(inv)
        punteroTrazo = e.getPointerId(e.actionIndex)
        val est = editor.estabilizacion
        val id = if (est > 0.01f) {
            // Estabilizado: los puntos se suavizan aquí (cuerda) y se le dan a Ink ya en píxeles del lienzo
            val i = e.actionIndex
            val m = vista.aMapa(e.getX(i), e.getY(i))
            cuerda = floatArrayOf(m[0], m[1]); bajoTrazo = e.eventTime; herramientaLapiz = esLapiz(e, i)
            runCatching { tinta.startStroke(entrada(m[0], m[1], e, i, e.eventTime), tipo.brush(verColor, tam), Matrix(vista.m)) }.getOrNull()
        } else { cuerda = null; runCatching { tinta.startStroke(e, punteroTrazo, tipo.brush(verColor, tam), inv, Matrix()) }.getOrNull() } ?: return
        trazo = id
        metas[id] = Meta(tipo, op, capa.info.id, editor.color, tam)
        cursor(e.x, e.y)
    }

    private fun terminaTinta(e: MotionEvent) {
        val id = trazo ?: return
        val cu = cuerda
        if (cu != null) {
            // El final: la cuerda llega hasta donde levantaste el lápiz
            val i = e.findPointerIndex(punteroTrazo).takeIf { it >= 0 } ?: e.actionIndex
            val m = vista.aMapa(e.getX(i), e.getY(i))
            runCatching { tinta.finishStroke(entrada(m[0], m[1], e, i, e.eventTime), id) }
        } else runCatching { tinta.finishStroke(e, punteroTrazo, id) }
        trazo = null; cuerda = null
    }

    // ---------------------------------------------------------------- estabilización (como la StreamLine de Procreate)

    private var cuerda: FloatArray? = null
    private var bajoTrazo = 0L
    private var herramientaLapiz = false
    private val lote by lazy { MutableStrokeInputBatch() }

    private fun entrada(x: Float, y: Float, e: MotionEvent, i: Int, t: Long, p: Float = e.getPressure(i)): StrokeInput {
        val lapiz = esLapiz(e, i)
        return StrokeInput.create(x, y, (t - bajoTrazo).coerceAtLeast(0L), if (lapiz) InputToolType.STYLUS else InputToolType.TOUCH,
            StrokeInput.NO_STROKE_UNIT_LENGTH, if (lapiz) p.coerceIn(0f, 1f) else StrokeInput.NO_PRESSURE,
            if (lapiz) e.getAxisValue(MotionEvent.AXIS_TILT, i).coerceIn(0f, 1.5707f) else StrokeInput.NO_TILT, StrokeInput.NO_ORIENTATION)
    }

    /**
     * La «cuerda»: el pincel va detrás del lápiz a una distancia fija (más larga cuanto más estabilización) y solo se mueve
     * cuando el lápiz tira de él. Las líneas salen limpias aunque la mano tiemble.
     */
    private fun estabiliza(e: MotionEvent, i: Int, id: InProgressStrokeId) {
        val cu = cuerda ?: return
        val largo = editor.estabilizacion * editor.estabilizacion * 70f * dens / vista.zoom
        lote.clear()
        fun punto(sx: Float, sy: Float, t: Long, p: Float) {
            val m = vista.aMapa(sx, sy)
            val dx = m[0] - cu[0]; val dy = m[1] - cu[1]; val d = hypot(dx, dy)
            if (d <= largo || d < 0.01f) return
            val k = (d - largo) / d
            cu[0] += dx * k; cu[1] += dy * k
            lote.add(entrada(cu[0], cu[1], e, i, t, p))
        }
        for (hh in 0 until e.historySize) punto(e.getHistoricalX(i, hh), e.getHistoricalY(i, hh), e.getHistoricalEventTime(hh), e.getHistoricalPressure(i, hh))
        punto(e.getX(i), e.getY(i), e.eventTime, e.getPressure(i))
        if (lote.size > 0) runCatching { tinta.addToStroke(lote, id) }
    }

    private fun empiezaArrastre(e: MotionEvent) {
        punteroTrazo = e.getPointerId(0)
        val m = vista.aMapa(e.x, e.y)
        editor.empieza(m[0], m[1])
    }

    private fun toque(sx: Float, sy: Float) {
        val m = vista.aMapa(sx, sy)
        editor.toque(m[0], m[1], capas.queHay(mapa, vista, sx, sy, editor.centros, editor.superficies))
        dibujo.invalidate()
    }

    private fun cursor(sx: Float, sy: Float) {
        opciones.cursor = if (editor.herramienta == Herramienta.GOMA) floatArrayOf(sx, sy, editor.tamanoGoma / 2 * vista.zoom) else null
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        // El lápiz sin tocar la pantalla: se enseña el tamaño del pincel
        if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE && esLapiz(e, 0)) {
            lapizVisto = true
            val r = editor.tamanoActual() / 2 * vista.zoom
            opciones.cursor = if (editor.herramienta == Herramienta.PINCEL || editor.herramienta == Herramienta.GOMA) floatArrayOf(e.x, e.y, max(r, 2 * dens)) else null
            dibujo.invalidate(); return true
        }
        if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) { opciones.cursor = null; dibujo.invalidate() }
        return super.onGenericMotionEvent(e)
    }

    private fun mueveVista(e: MotionEvent) {
        val n = min(e.pointerCount, 2)
        var mx = 0f; var my = 0f
        for (i in 0 until n) { mx += e.getX(i); my += e.getY(i) }
        mx /= n; my /= n
        val d = if (n == 2) hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)) else 0f
        val a = if (n == 2) Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat() else 0f
        val p = previo
        if (p != null && p[3] == n.toFloat()) {
            val antes = vista.aMapa(p[0], p[1])
            if (n == 2 && p[2] > 0f) {
                vista.zoom *= d / p[2]
                if (girar) { var dg = a - p[4]; if (dg > 180) dg -= 360; if (dg < -180) dg += 360; vista.giro += dg }
            }
            limita(); vista.actualiza()
            val ahora = vista.aMapa(mx, my)
            vista.cx += antes[0] - ahora[0]; vista.cy += antes[1] - ahora[1]
            limita(); vista.actualiza()
            alMoverse(); dibujo.invalidate()
        }
        previo = floatArrayOf(mx, my, d, n.toFloat(), a)
    }

    /** Mover (un dedo), escalar y girar (dos) la imagen de calcar. */
    private fun mueveCalco(e: MotionEvent) {
        val k = mapa.calco ?: return
        val n = min(e.pointerCount, 2)
        var mx = 0f; var my = 0f
        for (i in 0 until n) { mx += e.getX(i); my += e.getY(i) }
        mx /= n; my /= n
        val d = if (n == 2) hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)) else 0f
        val a = if (n == 2) Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat() else 0f
        val p = previo
        if (p != null && p[3] == n.toFloat()) {
            val antes = vista.aMapa(p[0], p[1]); val ahora = vista.aMapa(mx, my)
            k.x += ahora[0] - antes[0]; k.y += ahora[1] - antes[1]
            if (n == 2 && p[2] > 0f) {
                val f = d / p[2]
                var dg = a - p[4]; if (dg > 180) dg -= 360; if (dg < -180) dg += 360
                // Escalar y girar alrededor del punto entre los dedos
                val dx = k.x - ahora[0]; val dy = k.y - ahora[1]
                val r = Math.toRadians(dg.toDouble())
                k.x = ahora[0] + ((dx * Math.cos(r) - dy * Math.sin(r)) * f).toFloat()
                k.y = ahora[1] + ((dx * Math.sin(r) + dy * Math.cos(r)) * f).toFloat()
                k.ancho = (k.ancho * f).let { if (it >= 0f) it.coerceAtLeast(16f) else it.coerceAtMost(-16f) }; k.giro += dg
            }
            dibujo.invalidate()
        }
        previo = floatArrayOf(mx, my, d, n.toFloat(), a)
    }

    fun endereza() {
        // Si casi está derecho, a 0; si no, también (el norte arriba)
        vista.giro = 0f; vista.actualiza(); recuerdaVista(); dibujo.invalidate()
    }

    fun irA(x: Float, y: Float, zoom: Float = max(vista.zoom, 0.8f)) { vista.cx = x; vista.cy = y; vista.zoom = zoom; limita(); vista.actualiza(); recuerdaVista(); dibujo.invalidate(); alMoverse() }

    fun cierra() { recuerdaVista(); piramide.cierra() }
}
