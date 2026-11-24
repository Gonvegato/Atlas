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
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.editor.Herramienta
import com.gonzalo.atlas.lienzo.FloatLista
import com.gonzalo.atlas.lienzo.Formas
import com.gonzalo.atlas.lienzo.Lienzo
import com.gonzalo.atlas.lienzo.Lona
import com.gonzalo.atlas.lienzo.Motor
import com.gonzalo.atlas.lienzo.Papel
import com.gonzalo.atlas.lienzo.Pinceles
import com.gonzalo.atlas.lienzo.Piramide
import com.gonzalo.atlas.lienzo.TrazoDatos
import com.gonzalo.atlas.lienzo.TrazoVivo
import com.gonzalo.atlas.lienzo.VeloTerritorios
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * La mesa de dibujo: el lienzo (papel, capas, territorios y lo que va encima). El trazo que se está dibujando se estampa con
 * el motor de pinceles en su pintura húmeda y se ve ya fundido con su capa, a la resolución de la pantalla: lo que ves al
 * dibujar es lo que queda al soltar.
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
    private val dens = resources.displayMetrics.density
    var lapizVisto = false
    var dedoDibuja = false
    var girar = true
    var alMoverse: () -> Unit = {}
    private val alListo: () -> Unit = { dibujo.postInvalidateOnAnimation() }

    init {
        addView(dibujo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        lz.alCambiar += { _ -> dibujo.postInvalidateOnAnimation() }
        lz.alCambiarVivo = { dibujo.postInvalidateOnAnimation() }
        lz.nitidez.alListo += alListo
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
        else if (usarGuardada && mapa.infinito && lz.capas.all { it.teselas.isEmpty() } && lz.terr.isEmpty()) {
            // Un lienzo infinito recién hecho: a tamaño real (un píxel del lienzo, un píxel de la pantalla), que es como mejor se dibuja
            vista.cx = 0f; vista.cy = 0f; vista.giro = 0f; vista.zoom = 1f
        } else {
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
        vista.zoom = vista.zoom.coerceIn(max(0.01f, zmin), 32f)
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
            val nivel = Motor.nivel(vista.zoom).let { if (editor.transformacion != null) min(0, it) else it }
            if (nivel <= 0) capasDirectas(c, papel, nivel) else desdePiramide(c, papel, nivel)
            if (mapa.calco?.encima == true) pintaCalco(c)
            pintaGuias(c, vis)
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
        // El tamaño del pincel, en el centro, mientras se mueve su barra
        val falta = editor.muestraTam - System.currentTimeMillis()
        if (falta > 0) {
            val rad = max(editor.tamanoActual() / 2f * vista.zoom, 1f)
            pGuia.pathEffect = null; pGuia.style = Paint.Style.STROKE; pGuia.strokeWidth = 3f * dens; pGuia.color = 0x99000000.toInt()
            c.drawCircle(width / 2f, height / 2f, rad, pGuia)
            pGuia.strokeWidth = 1.5f * dens; pGuia.color = 0xFFFFFFFF.toInt()
            c.drawCircle(width / 2f, height / 2f, rad, pGuia)
            dibujo.postInvalidateDelayed(falta + 20)
        }
    }

    private val pGuia = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pVelo = Paint(Paint.ANTI_ALIAS_FLAG)
    private var veloDe: com.gonzalo.atlas.lienzo.Seleccion? = null
    private var veloFuera: android.graphics.Path? = null
    private var veloBorde: android.graphics.Path? = null

    /** Lo que guía el dibujo (en coordenadas del lienzo): la forma que se va a poner, la selección y los ejes de simetría. */
    private fun pintaGuias(c: Canvas, vis: RectF) {
        val z = vista.zoom
        editor.vistaPrevia?.let { (b, r) ->
            pCalco.alpha = 215; c.drawBitmap(b, null, r, pCalco)
            pGuia.pathEffect = null; pGuia.style = Paint.Style.STROKE; pGuia.strokeWidth = 2f * dens / z; pGuia.color = 0xFF3D8BFF.toInt()
            c.drawRect(r, pGuia)
        }
        editor.marco?.let { s ->
            if (veloDe !== s) { veloDe = s; veloFuera = s.fuera; veloBorde = android.graphics.Path(s.path).apply { fillType = android.graphics.Path.FillType.EVEN_ODD } }
            // Lo de fuera, velado; el borde, en blanco y negro para que se vea sobre cualquier color
            pVelo.style = Paint.Style.FILL; pVelo.color = 0x59101418
            c.save(); c.clipRect(vis); c.drawPath(veloFuera!!, pVelo); c.restore()
            pGuia.style = Paint.Style.STROKE; pGuia.strokeWidth = 1.5f * dens / z
            pGuia.pathEffect = null; pGuia.color = 0xFF000000.toInt(); c.drawPath(veloBorde!!, pGuia)
            pGuia.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * dens / z, 6f * dens / z), 0f); pGuia.color = 0xFFFFFFFF.toInt(); c.drawPath(veloBorde!!, pGuia)
            pGuia.pathEffect = null
        }
        editor.marcoBorrador?.let { (forma, p) ->
            pGuia.style = Paint.Style.STROKE; pGuia.strokeWidth = 1.5f * dens / z; pGuia.color = 0xFF3D8BFF.toInt(); pGuia.pathEffect = null
            when (forma) {
                "rect" -> c.drawRect(min(p[0], p[2]), min(p[1], p[3]), max(p[0], p[2]), max(p[1], p[3]), pGuia)
                "elipse" -> c.drawOval(min(p[0], p[2]), min(p[1], p[3]), max(p[0], p[2]), max(p[1], p[3]), pGuia)
                else -> { val q = android.graphics.Path(); q.moveTo(p[0], p[1]); var i = 2; while (i + 1 < p.size) { q.lineTo(p[i], p[i + 1]); i += 2 }; c.drawPath(q, pGuia) }
            }
        }
        mapa.simetria?.let { g ->
            pGuia.style = Paint.Style.STROKE; pGuia.strokeWidth = 1.2f * dens / z; pGuia.color = 0xCC3D8BFF.toInt(); pGuia.pathEffect = null
            val largo = max(vis.width(), vis.height()) * 2f + hypot(g.x - vis.centerX(), g.y - vis.centerY())
            when (g.tipo) {
                "horizontal" -> c.drawLine(g.x - largo, g.y, g.x + largo, g.y, pGuia)
                "cuadrante" -> { c.drawLine(g.x - largo, g.y, g.x + largo, g.y, pGuia); c.drawLine(g.x, g.y - largo, g.x, g.y + largo, pGuia) }
                "radial" -> { val n = g.n.coerceIn(2, 24); for (k in 0 until n) { val a = k * 2.0 * Math.PI / n - Math.PI / 2; c.drawLine(g.x, g.y, g.x + (Math.cos(a) * largo).toFloat(), g.y + (Math.sin(a) * largo).toFloat(), pGuia) } }
                else -> c.drawLine(g.x, g.y - largo, g.x, g.y + largo, pGuia)
            }
            c.drawCircle(g.x, g.y, 4f * dens / z, pGuia)
        }
    }

    private fun rango(r: RectF, lado: Float): IntArray = intArrayOf(floor(r.left / lado).toInt(), floor(r.top / lado).toInt(), floor((r.right - 0.001f) / lado).toInt(), floor((r.bottom - 0.001f) / lado).toInt())

    private val rSrc = android.graphics.Rect()
    private val rDst = RectF()
    private val imagenes = java.util.WeakHashMap<Lona, Bitmap>()
    private val pFuera = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
    private val pLejos = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pDentroF = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
    private val pNada = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }

    /** Las capas tal cual (a tamaño real) o, de cerca ([nivel] < 0), con sus teselas nítidas donde hay trazos guardados. */
    private fun capasDirectas(c: Canvas, r: RectF, nivel: Int) {
        val L = Lienzo.L.toFloat()
        val t = rango(r, L)
        val tm = editor.transformacion
        val e = -nivel; val s = 1 shl e; val lado = L / s
        val th = if (e > 0) rango(r, lado) else t
        lz.vivos.removeAll { it.estado >= TrazoVivo.ASENTADO }
        synchronized(lz.cierre) {
            val lista = lz.capas.toList()
            for ((ci, capa) in lista.withIndex()) {
                val vivos = lz.vivos.filter { it.capa === capa && it.estado < TrazoVivo.ASENTADO }
                if (!capa.info.visible || (capa.teselas.isEmpty() && vivos.isEmpty())) continue
                val base = lz.baseDe(lista, ci)
                if (base != null && !base.info.visible) continue
                pCapa.alpha = (capa.info.opacidad * 255).toInt()
                pCapa.blendMode = Lienzo.fusion(capa.info.fusion)
                // El ajuste de color que se está probando en la capa activa
                pCapa.colorFilter = if (capa.info.id == lz.activa) editor.vistaAjuste?.let { android.graphics.ColorMatrixColorFilter(it) } else null
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
                val cerca = e > 0 && capa.fondo.isNotEmpty()
                for (ty in t[1]..t[3]) for (tx in t[0]..t[2]) {
                    val k = Lienzo.clave(tx, ty)
                    val b = capa.teselas[k]
                    if (cerca && capa.fondo.containsKey(k)) {
                        // De cerca: sus teselas nítidas (y, mientras se hacen, la normal ampliada)
                        for (hy in max(th[1], ty * s)..min(th[3], ty * s + s - 1)) for (hx in max(th[0], tx * s)..min(th[2], tx * s + s - 1)) {
                            rDst.set(hx * lado, hy * lado, (hx + 1) * lado, (hy + 1) * lado)
                            val hb = lz.nitidez.tesela(capa, e, hx, hy)
                            if (hb != null) c.drawBitmap(hb, null, rDst, pFiltro)
                            else if (b != null) { val px = ((hx - tx * s) * lado).toInt(); val py = ((hy - ty * s) * lado).toInt(); rSrc.set(px, py, px + lado.toInt(), py + lado.toInt()); c.drawBitmap(b, rSrc, rDst, pFiltro) }
                        }
                    } else if (b != null) c.drawBitmap(b, tx * L, ty * L, pFiltro)
                }
                for (v in vivos) pintaVivo(c, v, r)
                // Máscara de recorte: solo queda donde la capa de debajo tiene algo
                if (base != null) for (ty in t[1]..t[3]) for (tx in t[0]..t[2]) {
                    val m = base.teselas[Lienzo.clave(tx, ty)]
                    if (m != null) c.drawBitmap(m, tx * L, ty * L, pDentroF) else c.drawRect(tx * L, ty * L, (tx + 1) * L, (ty + 1) * L, pNada)
                }
                c.restoreToCount(g)
            }
            pCapa.colorFilter = null
        }
        if (tm == null) vistaTransf = null
        if (mapa.verTerritorios) for (ty in t[1]..t[3]) for (tx in t[0]..t[2]) velo.tesela(Lienzo.clave(tx, ty))?.let { c.drawBitmap(it, tx * L, ty * L, pFiltro) }
    }

    /** La imagen de una tesela de la pintura húmeda, al día. */
    private fun imagenDe(l: Lona, color: Int, bordes: Float): Bitmap {
        val b = imagenes.getOrPut(l) { Bitmap.createBitmap(l.w, l.h, Bitmap.Config.ARGB_8888) }
        l.vuelca(b, color, bordes)
        return b
    }

    /** El trazo que se está dibujando, fundido con su capa (con su opacidad; borrando; solo dentro de la selección o la sección). */
    private fun pintaVivo(c: Canvas, v: TrazoVivo, r: RectF) {
        val h = v.humedo; val s = h.escala
        val g = c.save()
        v.recorte?.let { c.clipRect(it) }
        val sel = v.seleccion
        if (sel != null) c.saveLayer(r, v.pintura)
        c.save(); c.scale(1f / s, 1f / s)
        for (l in h.lonas.values) if (l.tocada) c.drawBitmap(imagenDe(l, v.color, v.def.bordes), l.ox.toFloat(), l.oy.toFloat(), if (sel != null) pFiltro else v.pintura)
        c.restore()
        if (sel != null) c.drawPath(sel.fuera, pFuera)
        c.restoreToCount(g)
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
        // Los trazos en curso (y los recién asentados, hasta que la vista de lejos se rehace con ellos)
        val esc = Motor.escala(nivel)
        for (v in lz.vivos) {
            if (v.estado == TrazoVivo.HECHO) { lz.vivos.remove(v); continue }
            val h = v.humedo
            val asentado = v.estado == TrazoVivo.ASENTADO
            if (asentado && h.escala != esc) { lz.vivos.remove(v); continue }
            if (!v.capa.info.visible) continue
            val borra = v.modo == TrazoDatos.BORRAR
            if (borra && asentado) { lz.vivos.remove(v); continue }
            pLejos.alpha = ((if (borra) 0.75f else 1f) * v.opacidad * 255).toInt()
            var queda = false
            c.save(); v.recorte?.let { c.clipRect(it) }; v.seleccion?.let { c.clipPath(it.path) }
            c.scale(1f / h.escala, 1f / h.escala)
            for (l in h.lonas.values) {
                if (!l.tocada) continue
                if (asentado && piramide.limpia(nivel, l.ox / Lienzo.L, l.oy / Lienzo.L)) continue
                queda = true
                c.drawBitmap(imagenDe(l, if (borra) mapa.papel else v.color, v.def.bordes), l.ox.toFloat(), l.oy.toFloat(), pLejos)
            }
            c.restore()
            if (asentado && !queda) lz.vivos.remove(v)
        }
    }

    // ---------------------------------------------------------------- tocar

    private enum class Gesto { NADA, TINTA, ARRASTRE, MOVER, CALCO, DIFUMINAR, LICUAR, TRANSFORMAR }
    private var gesto = Gesto.NADA
    private var trazo: TrazoVivo? = null
    private var punteroTrazo = -1
    private var bajoX = 0f; private var bajoY = 0f; private var bajoT = 0L
    private var previo: FloatArray? = null
    private var movido = false

    private fun esLapiz(e: MotionEvent, i: Int) = e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER
    private fun puedeDibujar(e: MotionEvent, i: Int) = esLapiz(e, i) || !lapizVisto || dedoDibuja

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (esLapiz(e, 0)) lapizVisto = true
                bajoX = e.x; bajoY = e.y; bajoT = e.eventTime; movido = false; previo = null
                val h = editor.herramienta
                // La goma del lápiz (su otro extremo) o su botón apretado: se borra
                val goma = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || (esLapiz(e, 0) && (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
                maxDedos = 1; bajoMulti = e.eventTime; multiMovido = false
                if (!esLapiz(e, 0)) programaCuentagotas(e.x, e.y)
                gesto = when {
                    h == Herramienta.TRANSFORMAR -> { if (editor.transformacion == null) editor.transformacion = Matrix(); Gesto.TRANSFORMAR }
                    h == Herramienta.CALCO && mapa.calco != null -> Gesto.CALCO
                    h == Herramienta.DIFUMINAR && puedeDibujar(e, 0) -> { punteroTrazo = e.getPointerId(0); editor.empiezaDifuminar(); difumina(e); Gesto.DIFUMINAR }
                    h == Herramienta.LICUAR && puedeDibujar(e, 0) -> { punteroTrazo = e.getPointerId(0); editor.empiezaLicuar(); difumina(e, true); Gesto.LICUAR }
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
                if (gesto == Gesto.LICUAR && !esLapiz(e, i)) { editor.terminaLicuar(); editor.deshacer(); gesto = Gesto.MOVER }
                if (gesto == Gesto.TINTA && !esLapiz(e, i) && !esLapizActivo(e)) {
                    // Un segundo dedo mientras se dibuja con el dedo: era para mover; el trazo se cancela
                    cancelaTinta(); gesto = Gesto.MOVER
                } else if (gesto == Gesto.ARRASTRE && !esLapiz(e, i) && !esLapiz(e, 0)) { gesto = Gesto.MOVER }
                previo = null
            }
            MotionEvent.ACTION_MOVE -> { if (hypot(e.x - bajoX, e.y - bajoY) > 10 * dens) { cancelaCuentagotas(); if (e.pointerCount > 1) multiMovido = true }
                for (i in 0 until e.pointerCount) if (e.historySize > 0 && hypot(e.getX(i) - e.getHistoricalX(i, 0), e.getY(i) - e.getHistoricalY(i, 0)) > 3 * dens && e.pointerCount > 1) multiMovido = true
                when (gesto) {
                Gesto.TINTA -> mueveTinta(e)
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
                Gesto.LICUAR -> difumina(e, true)
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
                    Gesto.LICUAR -> editor.terminaLicuar()
                    else -> {}
                }
                gesto = Gesto.NADA; previo = null; opciones.cursor = null
                recuerdaVista(); dibujo.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelaTinta()
                if (gesto == Gesto.ARRASTRE) editor.termina()
                gesto = Gesto.NADA; previo = null; opciones.cursor = null
            }
        }
        return true
    }

    private fun esLapizActivo(e: MotionEvent): Boolean { val i = e.findPointerIndex(punteroTrazo); return i >= 0 && esLapiz(e, i) }

    // ---------------------------------------------------------------- el trazo

    private val inv = Matrix()
    private var cuerda: FloatArray? = null
    private var bajoTrazo = 0L
    private var lapizTrazo = false
    private val lote = FloatLista()
    /** Por dónde va el trazo (x, y en el lienzo), para reconocer su forma si se deja el lápiz quieto. */
    private val camino = FloatLista()
    private var forma: Formas.Forma? = null
    private var formaBase: Formas.Forma? = null
    private var anclaX = 0f; private var anclaY = 0f
    private var quietoX = 0f; private var quietoY = 0f
    private var formaPendiente = false
    private val pt = FloatArray(2)

    private fun empiezaTinta(e: MotionEvent, gomaLapiz: Boolean) {
        val goma = gomaLapiz || editor.herramienta == Herramienta.GOMA
        val def = Pinceles.def(if (goma) editor.goma else editor.pincel)
        val capa = lz.capaActiva() ?: return
        if (!capa.info.visible) { editor.aviso = "La capa «${capa.info.nombre}» está oculta"; return }
        val i = e.actionIndex
        punteroTrazo = e.getPointerId(i)
        lapizTrazo = esLapiz(e, i)
        vista.actualiza(); vista.m.invert(inv)
        val v = lz.empiezaTrazo(def, editor.color, if (goma) editor.tamanoGoma else editor.tamano, if (goma) editor.opacidadGoma else editor.opacidad,
            if (goma) TrazoDatos.BORRAR else TrazoDatos.PINTAR, vista.zoom / dens, lapizTrazo, Motor.nivel(vista.zoom), editor.espejos()) ?: return
        trazo = v; bajoTrazo = e.eventTime; camino.limpia(); forma = null; formaBase = null
        // Estabilizado: el pincel va detrás del lápiz, atado con una cuerda
        cuerda = if (editor.estabilizacion > 0.01f) floatArrayOf(e.getX(i), e.getY(i)) else null
        lote.limpia()
        punto(e.getX(i), e.getY(i), e.getPressure(i), e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getOrientation(i), e.eventTime, true)
        manda()
        quietoX = e.getX(i); quietoY = e.getY(i)
        runCatching { requestUnbufferedDispatch(e) }
        cursor(e.x, e.y)
    }

    /**
     * Un punto del lápiz (en la pantalla) pasa al trazo (en el lienzo). Con estabilización, el pincel va detrás del lápiz a una
     * distancia fija (más larga cuanto más estabilización) y solo se mueve cuando el lápiz tira de él: las líneas salen
     * limpias aunque la mano tiemble (como la StreamLine de Procreate).
     */
    private fun punto(sx: Float, sy: Float, p: Float, inc: Float, ori: Float, t: Long, forzar: Boolean = false) {
        var x = sx; var y = sy
        val cu = cuerda
        if (cu != null) {
            if (forzar) { cu[0] = sx; cu[1] = sy }
            else {
                val largo = editor.estabilizacion * editor.estabilizacion * 70f * dens
                val dx = sx - cu[0]; val dy = sy - cu[1]; val d = hypot(dx, dy)
                if (d <= largo || d < 0.01f) return
                val k = (d - largo) / d
                cu[0] += dx * k; cu[1] += dy * k; x = cu[0]; y = cu[1]
            }
        }
        pt[0] = x; pt[1] = y; inv.mapPoints(pt)
        lote.pon(pt[0]); lote.pon(pt[1])
        lote.pon(if (lapizTrazo) p.coerceIn(0f, 1f) else 1f)
        lote.pon(if (lapizTrazo) inc.coerceIn(0f, 1.5707f) else 0f)
        lote.pon(if (lapizTrazo) ori - Math.toRadians(vista.giro.toDouble()).toFloat() else 0f)
        lote.pon((t - bajoTrazo).coerceAtLeast(0L).toFloat())
        camino.pon(pt[0]); camino.pon(pt[1])
    }

    private fun manda() { if (lote.n > 0) { trazo?.agrega(lote.copia()); lote.limpia() } }

    private fun mueveTinta(e: MotionEvent) {
        if (trazo == null) return
        val i = e.findPointerIndex(punteroTrazo); if (i < 0) return
        if (forma != null) { ajustaForma(e.getX(i), e.getY(i)); return }
        for (h in 0 until e.historySize) punto(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h), e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h), e.getHistoricalOrientation(i, h), e.getHistoricalEventTime(h))
        punto(e.getX(i), e.getY(i), e.getPressure(i), e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getOrientation(i), e.eventTime)
        manda()
        // Forma rápida: si el lápiz se queda quieto sin levantarlo, lo dibujado se convierte en la forma limpia a la que se parece
        if (hypot(e.getX(i) - quietoX, e.getY(i) - quietoY) > 4 * dens) { quietoX = e.getX(i); quietoY = e.getY(i); removeCallbacks(formaRapida); if (editor.formasRapidas) postDelayed(formaRapida, 620) }
        cursor(e.getX(i), e.getY(i))
    }

    private val formaRapida = Runnable {
        val v = trazo
        if (v != null && gesto == Gesto.TINTA && forma == null) {
            val n = camino.n / 2
            val xs = FloatArray(n) { camino.v[it * 2] }; val ys = FloatArray(n) { camino.v[it * 2 + 1] }
            var largo = 0f; for (k in 1 until n) largo += hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1])
            if (largo * vista.zoom > 36 * dens) Formas.reconoce(xs, ys, n)?.let { f ->
                forma = f; formaBase = f; anclaX = xs[n - 1]; anclaY = ys[n - 1]
                v.sustituye(f.trazo(1.5f / vista.zoom))
                editor.aviso = f.nombre + " · arrastra para ajustarla"
            }
        }
    }

    /** Con la forma ya hecha y el lápiz aún apoyado: una recta se alarga hasta él; las demás se escalan y giran con él. */
    private fun ajustaForma(sx: Float, sy: Float) {
        val base = formaBase ?: return
        pt[0] = sx; pt[1] = sy; inv.mapPoints(pt)
        forma = if (!base.cerrada && base.pts.size == 4) Formas.recta(base.pts[0], base.pts[1], pt[0], pt[1]) else {
            val ax = anclaX - base.cx; val ay = anclaY - base.cy; val bx = pt[0] - base.cx; val by = pt[1] - base.cy
            val la = hypot(ax, ay)
            if (la < 1e-3f) base else base.movida(hypot(bx, by) / la, atan2(by, bx) - atan2(ay, ax))
        }
        if (!formaPendiente) { formaPendiente = true; post { formaPendiente = false; val f = forma; val v = trazo; if (f != null && v != null) v.sustituye(f.trazo(1.5f / vista.zoom)) } }
    }

    private fun terminaTinta(e: MotionEvent) {
        val v = trazo ?: return
        removeCallbacks(formaRapida)
        val f = forma
        if (f != null) v.sustituye(f.trazo(1.5f / vista.zoom))
        else {
            // El final: hasta donde se levantó el lápiz (la cuerda llega hasta él)
            val i = e.findPointerIndex(punteroTrazo).takeIf { it >= 0 } ?: e.actionIndex
            punto(e.getX(i), e.getY(i), e.getPressure(i), e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getOrientation(i), e.eventTime, true)
            manda()
        }
        trazo = null; cuerda = null; forma = null; formaBase = null
        v.termina { ed, error -> editor.trazoAsentado(ed, error) }
    }

    private fun cancelaTinta() { removeCallbacks(formaRapida); trazo?.cancela(); trazo = null; cuerda = null; forma = null; formaBase = null }

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
        if (gesto == Gesto.MOVER || gesto == Gesto.NADA) {
            val m = vista.aMapa(bajoX, bajoY)
            editor.lienzo.enHilo { val c = editor.lienzo.colorEn(m[0].toInt(), m[1].toInt()); post { editor.ponColor(c or (0xFF shl 24)); editor.aviso = "Color recogido" } }
            movido = true
        }
    }
    private fun programaCuentagotas(x: Float, y: Float) { removeCallbacks(cuentagotas); if (lapizVisto && !dedoDibuja) postDelayed(cuentagotas, 550) }
    private fun cancelaCuentagotas() = removeCallbacks(cuentagotas)

    private fun difumina(e: MotionEvent, licuar: Boolean = false) {
        val i = e.findPointerIndex(punteroTrazo).takeIf { it >= 0 } ?: 0
        val n = e.historySize + 1
        val xs = FloatArray(n); val ys = FloatArray(n); val ps = FloatArray(n)
        for (h in 0 until e.historySize) { val m = vista.aMapa(e.getHistoricalX(i, h), e.getHistoricalY(i, h)); xs[h] = m[0]; ys[h] = m[1]; ps[h] = if (esLapiz(e, i)) e.getHistoricalPressure(i, h) else 1f }
        val m = vista.aMapa(e.getX(i), e.getY(i)); xs[n - 1] = m[0]; ys[n - 1] = m[1]; ps[n - 1] = if (esLapiz(e, i)) e.getPressure(i) else 1f
        if (licuar) editor.licua(xs, ys, ps) { r -> piramide.ensucia(r); dibujo.invalidate() } else editor.difumina(xs, ys, ps) { r -> piramide.ensucia(r); dibujo.invalidate() }
        opciones.cursor = floatArrayOf(e.getX(i), e.getY(i), (if (licuar) editor.tamanoLicuar else editor.tamanoDifuminar) / 2 * vista.zoom)
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

    /**
     * El recuadro (en el lienzo) para una tierra de [anchoKm] × [altoKm]: centrado en lo que se ve; a la escala del mapa si
     * ya la tiene y, si no, ocupando buena parte de la pantalla (y entonces ese tamaño fijará la escala).
     */
    fun recuadro(anchoKm: Double, altoKm: Double): RectF {
        val asp = (anchoKm / altoKm).toFloat().coerceIn(0.2f, 5f)
        val vis = vista.visible()
        var w: Float; var h: Float
        if (mapa.escalaPuesta && mapa.kmPorPx > 0f) { w = (anchoKm / mapa.kmPorPx).toFloat(); h = w / asp }
        else { w = min(vis.width() * 0.8f, vis.height() * 0.8f * asp); h = w / asp }
        if (!mapa.infinito) { val k = min(1f, min(mapa.ancho / w, mapa.alto / h)); w *= k; h *= k }
        w = w.coerceIn(64f, 16000f); h = h.coerceIn(64f, 16000f)
        var cx = vis.centerX(); var cy = vis.centerY()
        if (!mapa.infinito) { cx = cx.coerceIn(w / 2f, mapa.ancho - w / 2f); cy = cy.coerceIn(h / 2f, mapa.alto - h / 2f) }
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    fun endereza() {
        // Si casi está derecho, a 0; si no, también (el norte arriba)
        vista.giro = 0f; vista.actualiza(); recuerdaVista(); dibujo.invalidate()
    }

    fun irA(x: Float, y: Float, zoom: Float = max(vista.zoom, 0.8f)) { vista.cx = x; vista.cy = y; vista.zoom = zoom; limita(); vista.actualiza(); recuerdaVista(); dibujo.invalidate(); alMoverse() }

    fun cierra() { recuerdaVista(); piramide.cierra(); lz.nitidez.alListo -= alListo }
}
