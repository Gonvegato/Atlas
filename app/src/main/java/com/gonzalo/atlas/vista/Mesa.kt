@file:OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)

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
    val opciones = Capas.Opciones(cuadricula = mapa.mundo.isNotBlank())
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

    /** Lo que hace falta para pasar el trazo a la capa: el pincel, y la vista que había (de pantalla a lienzo) y su acercamiento. */
    private class Meta(val tipo: TipoPincel, val opacidad: Float, val capa: String, val color: Int, val tam: Float, val aLienzo: Matrix, val zoom: Float)
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
                    // El trazo de verdad: sus puntos pasados de la pantalla al lienzo (con la vista que había al dibujarlo), el
                    // tamaño del pincel en píxeles del lienzo y el color opaco (la opacidad se aplica al fundirlo con la capa)
                    val opaco = runCatching { aLienzo(st, m) }.getOrElse { st.copy(st.brush.copyWithColorIntArgb(m.color or (0xFF shl 24))) }
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

    /** Rehace el trazo (dibujado en coordenadas de pantalla) en coordenadas del lienzo. */
    private fun aLienzo(st: Stroke, m: Meta): Stroke {
        val src = st.inputs
        val lote = MutableStrokeInputBatch()
        val p = FloatArray(2)
        val unidad = if (src.hasStrokeUnitLength()) src.getStrokeUnitLengthCm() * m.zoom else StrokeInput.NO_STROKE_UNIT_LENGTH
        for (i in 0 until src.size) {
            val s = src.get(i)
            p[0] = s.x; p[1] = s.y; m.aLienzo.mapPoints(p)
            lote.add(s.toolType, p[0], p[1], s.elapsedTimeMillis, unidad,
                if (src.hasPressure()) s.pressure else StrokeInput.NO_PRESSURE,
                if (src.hasTilt()) s.tiltRadians else StrokeInput.NO_TILT, StrokeInput.NO_ORIENTATION)
        }
        val b = st.brush.copy(size = m.tam.coerceAtLeast(0.5f), epsilon = minOf(0.1f, m.tam / 4f)).copyWithColorIntArgb(m.color or (0xFF shl 24))
        return Stroke(b, lote)
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
            val nivel = if (vista.zoom >= 0.7f || editor.transformacion != null) 0 else floor(ln(1f / vista.zoom) / ln(2f)).toInt().coerceIn(1, 7)
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
        opciones.seccionBorrador = editor.seccionBorrador
        capas.dibuja(c, mapa, vista, editor.centros, editor.superficies, recurso, opciones)
    }

    private fun rango(r: RectF, lado: Float): IntArray = intArrayOf(floor(r.left / lado).toInt(), floor(r.top / lado).toInt(), floor((r.right - 0.001f) / lado).toInt(), floor((r.bottom - 0.001f) / lado).toInt())

    private fun capasDirectas(c: Canvas, r: RectF) {
        val L = Lienzo.L.toFloat()
        val t = rango(r, L)
        val tm = editor.transformacion
        for (capa in lz.capas) {
            if (!capa.info.visible || capa.teselas.isEmpty()) continue
            pCapa.alpha = (capa.info.opacidad * 255).toInt()
            pCapa.blendMode = Lienzo.fusion(capa.info.fusion)
            if (tm != null && capa.info.id == lz.activa) {
                // La capa que se transforma: una imagen suya movida con la matriz
                if (vistaTransfCapa != capa.info.id || vistaTransf == null) { vistaTransf = com.gonzalo.atlas.lienzo.Ajustes.vistaCapa(capa); vistaTransfCapa = capa.info.id }
                vistaTransf?.let { (b, caja) ->
                    val g = c.saveLayer(null, pCapa); c.save(); c.concat(tm); c.drawBitmap(b, null, caja, pFiltro)
                    val borde = Paint().apply { style = Paint.Style.STROKE; color = 0xFF3D8BFF.toInt(); strokeWidth = 2f / vista.zoom }
                    c.drawRect(caja, borde); c.restore(); c.restoreToCount(g)
                }
                continue
            }
            val g = c.saveLayer(r, pCapa)
            // De cerca, los trazos se pintan como vectores (nítidos) sobre lo que había debajo de ellos
            val hd = vista.zoom > 1.1f && capa.trazos.isNotEmpty() && (android.os.Build.VERSION.SDK_INT >= 34 || capa.trazos.none { it.texturado })
            for (ty in t[1]..t[3]) for (tx in t[0]..t[2]) {
                val k = Lienzo.clave(tx, ty)
                val b = (if (hd) capa.sinTrazos[k] ?: capa.teselas[k] else capa.teselas[k]) ?: continue
                if (b !== com.gonzalo.atlas.lienzo.TESELA_VACIA) c.drawBitmap(b, tx * L, ty * L, pFiltro)
            }
            if (hd) dibujaTrazos(c, capa, r)
            c.restoreToCount(g)
        }
        if (tm == null) vistaTransf = null
        if (mapa.verTerritorios) for (ty in t[1]..t[3]) for (tx in t[0]..t[2]) velo.tesela(Lienzo.clave(tx, ty))?.let { c.drawBitmap(it, tx * L, ty * L, pFiltro) }
    }

    private val rendererVista by lazy { androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create(Pinceles.almacen(resources)) }
    private val identidad = Matrix()
    private val pTrazo = Paint()

    private fun dibujaTrazos(c: Canvas, capa: com.gonzalo.atlas.lienzo.CapaPx, r: RectF) {
        for (tr in capa.trazos) {
            if (!RectF.intersects(tr.caja, r)) continue
            val capaPropia = tr.goma || tr.opacidad < 0.999f
            var g = -1
            if (capaPropia) {
                pTrazo.alpha = (tr.opacidad * 255).toInt().coerceIn(0, 255)
                pTrazo.xfermode = if (tr.goma) android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) else null
                g = c.saveLayer(tr.caja, pTrazo)
            }
            runCatching { rendererVista.draw(c, tr.stroke, identidad, 0f) }
            if (g >= 0) c.restoreToCount(g)
        }
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

    private enum class Gesto { NADA, TINTA, ARRASTRE, MOVER, CALCO, DIFUMINAR, TRANSFORMAR }
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
                maxDedos = 1; bajoMulti = e.eventTime; multiMovido = false
                if (!esLapiz(e, 0)) programaCuentagotas(e.x, e.y)
                gesto = when {
                    h == Herramienta.TRANSFORMAR -> { if (editor.transformacion == null) editor.transformacion = Matrix(); Gesto.TRANSFORMAR }
                    h == Herramienta.CALCO && mapa.calco != null -> Gesto.CALCO
                    h == Herramienta.DIFUMINAR && puedeDibujar(e, 0) -> { punteroTrazo = e.getPointerId(0); editor.empiezaDifuminar(); difumina(e); Gesto.DIFUMINAR }
                    !puedeDibujar(e, 0) -> Gesto.MOVER
                    h == Herramienta.PINCEL || h == Herramienta.GOMA || goma -> { empiezaTinta(e, goma); Gesto.TINTA }
                    h == Herramienta.ELEGIR && editor.seleccion != null && capas.queHay(mapa, vista, e.x, e.y, editor.centros, editor.superficies) === editor.seleccion -> { empiezaArrastre(e); Gesto.ARRASTRE }
                    h != Herramienta.ELEGIR && editor.arrastra() -> { empiezaArrastre(e); Gesto.ARRASTRE }
                    else -> Gesto.MOVER
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelaCuentagotas()
                maxDedos = maxOf(maxDedos, e.pointerCount)
                val i = e.actionIndex
                if (gesto == Gesto.DIFUMINAR && !esLapiz(e, i)) { editor.terminaDifuminar(); editor.deshacer(); gesto = Gesto.MOVER }
                if (gesto == Gesto.TINTA && !esLapiz(e, i) && !esLapizActivo(e)) {
                    // Un segundo dedo mientras se dibuja con el dedo: era para mover; el trazo se cancela
                    trazo?.let { tinta.cancelStroke(it, e); metas.remove(it) }; trazo = null; gesto = Gesto.MOVER
                } else if (gesto == Gesto.ARRASTRE && !esLapiz(e, i) && !esLapiz(e, 0)) { gesto = Gesto.MOVER }
                previo = null
            }
            MotionEvent.ACTION_MOVE -> { if (hypot(e.x - bajoX, e.y - bajoY) > 10 * dens) { cancelaCuentagotas(); if (e.pointerCount > 1) multiMovido = true }
                for (i in 0 until e.pointerCount) if (e.historySize > 0 && hypot(e.getX(i) - e.getHistoricalX(i, 0), e.getY(i) - e.getHistoricalY(i, 0)) > 3 * dens && e.pointerCount > 1) multiMovido = true
                when (gesto) {
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
                Gesto.TRANSFORMAR -> mueveTransformacion(e)
                Gesto.DIFUMINAR -> difumina(e)
                else -> {}
            } }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesto == Gesto.TINTA && e.getPointerId(e.actionIndex) == punteroTrazo) terminaTinta(e)
                previo = null
            }
            MotionEvent.ACTION_UP -> {
                cancelaCuentagotas()
                // Toque con dos dedos: deshacer; con tres: rehacer (como en Procreate)
                if (maxDedos >= 2 && !multiMovido && e.eventTime - bajoMulti < 350) { if (maxDedos == 2) editor.deshacer() else editor.rehacer(); gesto = Gesto.NADA; previo = null; return true }
                when (gesto) {
                    Gesto.TINTA -> terminaTinta(e)
                    Gesto.ARRASTRE -> { editor.termina(); if (e.eventTime - bajoT < 250 && hypot(e.x - bajoX, e.y - bajoY) < 8 * dens) toque(e.x, e.y) }
                    Gesto.MOVER -> if (!movido && e.eventTime - bajoT < 350) toque(e.x, e.y)
                    Gesto.CALCO -> mapa.avisaVector()
                    Gesto.DIFUMINAR -> editor.terminaDifuminar()
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
        val tipo = if (goma) Pinceles.de(editor.goma).comoGoma() else Pinceles.de(editor.pincel)
        val capa = lz.capaActiva() ?: return
        if (!capa.info.visible) { editor.aviso = "La capa «${capa.info.nombre}» está oculta"; return }
        val tam = if (goma) editor.tamanoGoma else editor.tamano
        val op = if (goma) editor.opacidadGoma else editor.opacidad
        // Lo que se ve mientras se dibuja: el color con su opacidad (la goma, un velo del papel)
        val verColor = if (goma) (0xB0 shl 24) or (mapa.papel and 0xFFFFFF) else ((op * 255).toInt().coerceIn(10, 255) shl 24) or (editor.color and 0xFFFFFF)
        vista.m.invert(inv)
        punteroTrazo = e.getPointerId(e.actionIndex)
        // Ink pinta en coordenadas de pantalla (sin transformaciones): el pincel, a su tamaño en pantalla
        val tamPantalla = (tam * vista.zoom).coerceAtLeast(0.5f)
        val est = editor.estabilizacion
        val id = if (est > 0.01f) {
            // Estabilizado: los puntos se suavizan aquí (cuerda) antes de dárselos a Ink
            val i = e.actionIndex
            cuerda = floatArrayOf(e.getX(i), e.getY(i)); bajoTrazo = e.eventTime; herramientaLapiz = esLapiz(e, i)
            runCatching { tinta.startStroke(entrada(e.getX(i), e.getY(i), e, i, e.eventTime), tipo.brush(verColor, tamPantalla)) }.getOrNull()
        } else { cuerda = null; runCatching { tinta.startStroke(e, punteroTrazo, tipo.brush(verColor, tamPantalla)) }.getOrNull() } ?: return
        trazo = id
        metas[id] = Meta(tipo, op, capa.info.id, editor.color, tam, Matrix(inv), vista.zoom)
        cursor(e.x, e.y)
    }

    private fun terminaTinta(e: MotionEvent) {
        val id = trazo ?: return
        val cu = cuerda
        if (cu != null) {
            // El final: la cuerda llega hasta donde levantaste el lápiz
            val i = e.findPointerIndex(punteroTrazo).takeIf { it >= 0 } ?: e.actionIndex
            runCatching { tinta.finishStroke(entrada(e.getX(i), e.getY(i), e, i, e.eventTime), id) }
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
        val largo = editor.estabilizacion * editor.estabilizacion * 70f * dens
        lote.clear()
        fun punto(sx: Float, sy: Float, t: Long, p: Float) {
            val dx = sx - cu[0]; val dy = sy - cu[1]; val d = hypot(dx, dy)
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

    // ---------------------------------------------------------------- toques de varios dedos y cuentagotas

    private var maxDedos = 1
    private var bajoMulti = 0L
    private var multiMovido = false
    private val cuentagotas = Runnable {
        if (gesto == Gesto.MOVER || gesto == Gesto.NADA || (gesto == Gesto.TINTA && trazo == null)) {
            val m = vista.aMapa(bajoX, bajoY)
            editor.lienzo.enHilo { val c = editor.lienzo.colorEn(m[0].toInt(), m[1].toInt()); post { editor.ponColor(c or (0xFF shl 24)); editor.aviso = "Color recogido" } }
            movido = true
        }
    }
    private fun programaCuentagotas(x: Float, y: Float) { removeCallbacks(cuentagotas); if (lapizVisto && !dedoDibuja) postDelayed(cuentagotas, 550) }
    private fun cancelaCuentagotas() = removeCallbacks(cuentagotas)

    private fun difumina(e: MotionEvent) {
        val i = e.findPointerIndex(punteroTrazo).takeIf { it >= 0 } ?: 0
        val n = e.historySize + 1
        val xs = FloatArray(n); val ys = FloatArray(n); val ps = FloatArray(n)
        for (h in 0 until e.historySize) { val m = vista.aMapa(e.getHistoricalX(i, h), e.getHistoricalY(i, h)); xs[h] = m[0]; ys[h] = m[1]; ps[h] = if (esLapiz(e, i)) e.getHistoricalPressure(i, h) else 1f }
        val m = vista.aMapa(e.getX(i), e.getY(i)); xs[n - 1] = m[0]; ys[n - 1] = m[1]; ps[n - 1] = if (esLapiz(e, i)) e.getPressure(i) else 1f
        editor.difumina(xs, ys, ps) { r -> piramide.ensucia(r); dibujo.invalidate() }
        opciones.cursor = floatArrayOf(e.getX(i), e.getY(i), editor.tamanoDifuminar / 2 * vista.zoom)
    }

    /** Transformar la capa activa: un dedo la mueve; dos la escalan y giran. */
    private fun mueveTransformacion(e: MotionEvent) {
        val m = editor.transformacion ?: return
        val n = min(e.pointerCount, 2)
        var mx = 0f; var my = 0f
        for (i in 0 until n) { mx += e.getX(i); my += e.getY(i) }
        mx /= n; my /= n
        val d = if (n == 2) hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)) else 0f
        val a = if (n == 2) Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat() else 0f
        val p = previo
        if (p != null && p[3] == n.toFloat()) {
            val antes = vista.aMapa(p[0], p[1]); val ahora = vista.aMapa(mx, my)
            m.postTranslate(ahora[0] - antes[0], ahora[1] - antes[1])
            if (n == 2 && p[2] > 0f) {
                var dg = a - p[4]; if (dg > 180) dg -= 360; if (dg < -180) dg += 360
                m.postScale(d / p[2], d / p[2], ahora[0], ahora[1]); m.postRotate(dg, ahora[0], ahora[1])
            }
            editor.transformacion = Matrix(m)
            dibujo.invalidate()
        }
        previo = floatArrayOf(mx, my, d, n.toFloat(), a)
    }

    private var vistaTransf: Pair<Bitmap, RectF>? = null
    private var vistaTransfCapa: String? = null

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
