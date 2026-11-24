@file:OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)

package com.gonzalo.atlas.lienzo

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.gonzalo.atlas.motor.CapaInfo
import com.gonzalo.atlas.motor.Mapa
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Una capa de dibujo: sus teselas de 256 × 256 (solo las que tienen algo). Las teselas no se cambian nunca: se sustituyen. */
class CapaPx(val info: CapaInfo) {
    val teselas = ConcurrentHashMap<Long, Bitmap>()
    /** Teselas cambiadas desde que se guardó (las que ya no están: borradas). */
    val sucias: MutableSet<Long> = ConcurrentHashMap.newKeySet()
}

sealed class Edicion(val nombre: String) {
    class Teselas(nombre: String, val capa: String, val antes: Map<Long, Bitmap?>, val despues: Map<Long, Bitmap?>) : Edicion(nombre)
    class Terr(nombre: String, val antes: Map<Long, ByteArray?>, val despues: Map<Long, ByteArray?>) : Edicion(nombre)
    class Vector(nombre: String, val antes: String, val despues: String) : Edicion(nombre)
    /** Añadir, borrar, combinar u ordenar capas: el estado entero de las capas antes y después. */
    class Capas(nombre: String, val antes: List<Pair<CapaInfo, Map<Long, Bitmap>>>, val despues: List<Pair<CapaInfo, Map<Long, Bitmap>>>) : Edicion(nombre)
    class Varias(nombre: String, val partes: List<Edicion>) : Edicion(nombre)
}

/**
 * El dibujo de un mapa: capas en teselas, los territorios (un número por píxel) y deshacer. Todos los cambios pasan por el
 * hilo del lienzo ([enHilo]), uno detrás de otro.
 */
class Lienzo(val mapa: Mapa, private val res: Resources) {
    val capas = CopyOnWriteArrayList<CapaPx>()
    /** Territorios: un byte por píxel (0 = ninguno), en teselas de 256. */
    val terr = ConcurrentHashMap<Long, ByteArray>()
    private val terrSucias: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    @Volatile var terrVersion = 0
    @Volatile var version = 0
    /** El que pasa los trazos de Ink a píxeles (se crea al primer trazo: carga código nativo). */
    val renderer: CanvasStrokeRenderer by lazy { CanvasStrokeRenderer.create(Pinceles.almacen(res)) }
    var activa: String = ""
    /** Se llama (en el hilo del lienzo) con la zona cambiada, en píxeles del lienzo. */
    val alCambiar = CopyOnWriteArrayList<(RectF) -> Unit>()
    private val hilo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "atlas-lienzo").apply { isDaemon = true } }

    private val atras = ArrayDeque<Edicion>()
    private val alante = ArrayDeque<Edicion>()
    var alCambiarHistorial: () -> Unit = {}
    val puedeDeshacer get() = atras.isNotEmpty()
    val puedeRehacer get() = alante.isNotEmpty()

    fun enHilo(f: () -> Unit) { hilo.execute { runCatching(f) } }
    fun cierra() { hilo.shutdown() }

    // ---------------------------------------------------------------- capas

    fun capa(id: String) = capas.firstOrNull { it.info.id == id }
    fun capaActiva(): CapaPx? = capa(activa) ?: capas.lastOrNull()

    private fun sincronizaInfo() { mapa.capas.clear(); mapa.capas.addAll(capas.map { it.info }) }

    // ---------------------------------------------------------------- cargar y guardar

    fun carga() {
        capas.clear()
        val dir = mapa.dir
        for (ci in mapa.capas) {
            val c = CapaPx(ci)
            dir?.let { d -> File(d, "capas/${ci.id}").listFiles()?.forEach { f -> clave(f.name, ".png")?.let { k -> BitmapFactory.decodeFile(f.path, opcionesBmp)?.let { c.teselas[k] = it } } } }
            capas += c
        }
        if (capas.isEmpty()) { capas += CapaPx(CapaInfo("c" + System.currentTimeMillis().toString(36), "Dibujo")); sincronizaInfo() }
        dir?.let { d -> File(d, "terr").listFiles()?.forEach { f -> clave(f.name, ".bin")?.let { k -> runCatching { InflaterInputStream(f.inputStream()).use { it.readBytes() } }.getOrNull()?.takeIf { it.size == N }?.let { terr[k] = it } } } }
        activa = capas.lastOrNull { !it.info.referencia }?.info?.id ?: capas.last().info.id
    }

    private val opcionesBmp = BitmapFactory.Options().apply { inMutable = false; inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = true }

    private fun clave(n: String, ext: String): Long? {
        if (!n.endsWith(ext)) return null
        val p = n.removeSuffix(ext).split('_'); if (p.size != 2) return null
        return clave(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null)
    }

    /** Escribe lo cambiado (bloquea: se llama desde el hilo del lienzo). */
    fun guarda() {
        val d = mapa.dir ?: return
        sincronizaInfo()
        for (c in capas) {
            val cd = File(d, "capas/${c.info.id}"); cd.mkdirs()
            for (k in c.sucias.toList()) {
                c.sucias.remove(k)
                val f = File(cd, "${tx(k)}_${ty(k)}.png")
                val b = c.teselas[k]
                if (b == null) f.delete() else { val tmp = File(cd, f.name + ".tmp"); tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; tmp.renameTo(f) }
            }
        }
        // Las carpetas de capas que ya no existen
        File(d, "capas").listFiles()?.forEach { f -> if (capas.none { it.info.id == f.name }) f.deleteRecursively() }
        val td = File(d, "terr"); td.mkdirs()
        for (k in terrSucias.toList()) {
            terrSucias.remove(k)
            val f = File(td, "${tx(k)}_${ty(k)}.bin")
            val a = terr[k]
            if (a == null || a.all { it.toInt() == 0 }) f.delete()
            else DeflaterOutputStream(File(td, f.name + ".tmp").outputStream(), Deflater(6)).use { it.write(a) }.also { File(td, f.name + ".tmp").renameTo(f) }
        }
        mapa.guardaMeta()
    }

    // ---------------------------------------------------------------- deshacer

    @Synchronized fun apunta(e: Edicion) {
        atras.addLast(e); alante.clear()
        // Tope: 60 pasos y unos 300 MB de teselas
        var peso = atras.sumOf { pesa(it) }
        while ((atras.size > 60 || peso > 300L shl 20) && atras.size > 1) peso -= pesa(atras.removeFirst())
        alCambiarHistorial()
    }

    private fun pesa(e: Edicion): Long = when (e) {
        is Edicion.Teselas -> (e.antes.size + e.despues.size) * (N * 4L)
        is Edicion.Terr -> (e.antes.size + e.despues.size) * N.toLong()
        is Edicion.Vector -> (e.antes.length + e.despues.length) * 2L
        is Edicion.Capas -> 0L
        is Edicion.Varias -> e.partes.sumOf { pesa(it) }
    }

    fun deshacer() = enHilo { val e = synchronized(this) { atras.removeLastOrNull() } ?: return@enHilo; aplica(e, true); synchronized(this) { alante.addLast(e) }; alCambiarHistorial() }
    fun rehacer() = enHilo { val e = synchronized(this) { alante.removeLastOrNull() } ?: return@enHilo; aplica(e, false); synchronized(this) { atras.addLast(e) }; alCambiarHistorial() }

    private fun aplica(e: Edicion, atrasDe: Boolean) {
        when (e) {
            is Edicion.Teselas -> {
                val c = capa(e.capa) ?: return
                val m = if (atrasDe) e.antes else e.despues
                for ((k, b) in m) { if (b == null) c.teselas.remove(k) else c.teselas[k] = b; c.sucias += k }
                avisaTeselas(m.keys)
            }
            is Edicion.Terr -> {
                val m = if (atrasDe) e.antes else e.despues
                for ((k, a) in m) { if (a == null) terr.remove(k) else terr[k] = a; terrSucias += k }
                terrVersion++; avisaTeselas(m.keys)
            }
            is Edicion.Vector -> { mapa.ponVector(org.json.JSONObject(if (atrasDe) e.antes else e.despues)); reordenaPorInfo(); mapa.avisaVector(); avisaTodo() }
            is Edicion.Capas -> ponEstadoCapas(if (atrasDe) e.antes else e.despues)
            is Edicion.Varias -> (if (atrasDe) e.partes.reversed() else e.partes).forEach { aplica(it, atrasDe) }
        }
    }

    /** Si el JSON trae otro orden o datos de capas, se respetan (sin tocar los píxeles). */
    private fun reordenaPorInfo() {
        val nuevas = mapa.capas.mapNotNull { ci -> capas.firstOrNull { it.info.id == ci.id }?.let { CapaPx(ci).also { n -> n.teselas.putAll(it.teselas); n.sucias.addAll(it.sucias) } } }
        if (nuevas.size == capas.size) { capas.clear(); capas.addAll(nuevas) }
    }

    fun estadoCapas(): List<Pair<CapaInfo, Map<Long, Bitmap>>> = capas.map { CapaInfo.de(it.info.json()) to HashMap(it.teselas) }

    fun ponEstadoCapas(e: List<Pair<CapaInfo, Map<Long, Bitmap>>>) {
        capas.clear()
        for ((ci, t) in e) { val c = CapaPx(CapaInfo.de(ci.json())); c.teselas.putAll(t); c.sucias.addAll(t.keys); capas += c }
        if (capa(activa) == null) activa = capas.lastOrNull()?.info?.id.orEmpty()
        sincronizaInfo(); version++; mapa.avisaVector(); avisaTodo()
    }

    /** Cambia la estructura de capas con [f] y lo apunta para deshacer. */
    fun cambiaCapas(nombre: String, f: () -> Unit) = enHilo {
        val antes = estadoCapas()
        // Las carpetas de las capas: se marcan sucias todas las teselas de las capas que quedan (se reescriben al guardar)
        f()
        sincronizaInfo()
        capas.forEach { it.sucias.addAll(it.teselas.keys) }
        apunta(Edicion.Capas(nombre, antes, estadoCapas()))
        version++; mapa.avisaVector(); avisaTodo()
    }

    // ---------------------------------------------------------------- avisos

    fun avisaTeselas(ks: Collection<Long>) {
        if (ks.isEmpty()) return
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (k in ks) { x0 = min(x0, tx(k)); y0 = min(y0, ty(k)); x1 = max(x1, tx(k)); y1 = max(y1, ty(k)) }
        version++
        val r = RectF(x0 * L.toFloat(), y0 * L.toFloat(), (x1 + 1) * L.toFloat(), (y1 + 1) * L.toFloat())
        alCambiar.forEach { it(r) }
    }

    fun avisaTodo() { version++; val r = RectF(-1e9f, -1e9f, 1e9f, 1e9f); alCambiar.forEach { it(r) } }

    // ---------------------------------------------------------------- dibujar trazos

    private val tmp: Bitmap by lazy { Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888) }
    private val tmpC by lazy { Canvas(tmp) }
    private val pComp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val mTrazo = Matrix()

    /**
     * Pasa un trazo terminado (en píxeles del lienzo) a la capa activa: se pinta opaco en una tesela aparte y se funde con la
     * opacidad del pincel (así un trazo no se oscurece donde se cruza consigo mismo), borrando si es una goma y respetando el
     * bloqueo de transparencia de la capa.
     */
    fun pintaTrazo(st: Stroke, tipo: TipoPincel, opacidad: Float, capaId: String = activa): Edicion? {
        val c = capa(capaId) ?: return null
        val box = st.shape.computeBoundingBox() ?: return null
        val margen = 2f
        val x0 = floor((box.xMin - margen) / L).toInt(); val y0 = floor((box.yMin - margen) / L).toInt()
        val x1 = floor((box.xMax + margen) / L).toInt(); val y1 = floor((box.yMax + margen) / L).toInt()
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > 4096) return null
        val antes = HashMap<Long, Bitmap?>(); val despues = HashMap<Long, Bitmap?>()
        pComp.alpha = (opacidad.coerceIn(0f, 1f) * 255).toInt()
        pComp.xfermode = when { tipo.goma -> PorterDuffXfermode(PorterDuff.Mode.DST_OUT); c.info.bloqueoAlfa -> PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP); else -> null }
        for (ty in y0..y1) for (tx in x0..x1) {
            if (!dentro(tx, ty)) continue
            val k = clave(tx, ty)
            val viejo = c.teselas[k]
            if (viejo == null && (tipo.goma || c.info.bloqueoAlfa)) continue
            tmp.eraseColor(Color.TRANSPARENT)
            mTrazo.setTranslate(-tx * L.toFloat(), -ty * L.toFloat())
            renderer.draw(tmpC, st, mTrazo, 0f)
            val nuevo = viejo?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
            Canvas(nuevo).drawBitmap(tmp, 0f, 0f, pComp)
            antes[k] = viejo
            val vacia = tipo.goma && estaVacia(nuevo)
            despues[k] = if (vacia) null else nuevo
            if (vacia) c.teselas.remove(k) else c.teselas[k] = nuevo
            c.sucias += k
        }
        if (antes.isEmpty()) return null
        avisaTeselas(antes.keys)
        return Edicion.Teselas(tipo.nombre, c.info.id, antes, despues)
    }

    private fun estaVacia(b: Bitmap): Boolean {
        val px = IntArray(L * L); b.getPixels(px, 0, L, 0, 0, L, L)
        return px.all { (it ushr 24) == 0 }
    }

    fun dentro(tx: Int, ty: Int): Boolean = mapa.infinito || (tx >= 0 && ty >= 0 && tx * L < mapa.ancho && ty * L < mapa.alto)

    // ---------------------------------------------------------------- leer píxeles

    private fun pixeles(b: Bitmap): IntArray { val px = IntArray(L * L); b.getPixels(px, 0, L, 0, 0, L, L); return px }

    /** El color visible (todas las capas visibles fundidas, sin el papel) en un punto: el cuentagotas. */
    fun colorEn(x: Int, y: Int): Int {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(mapa.papel)
        cv.translate(-x.toFloat(), -y.toFloat())
        componeZona(cv, Rect(x, y, x + 1, y + 1))
        return bmp.getPixel(0, 0)
    }

    private val pCapa = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Dibuja las capas visibles de la zona [r] (píxeles del lienzo) en [c] (ya colocado en coordenadas del lienzo). */
    fun componeZona(c: Canvas, r: Rect, incluir: (CapaPx) -> Boolean = { it.info.visible }) {
        val x0 = Math.floorDiv(r.left, L); val y0 = Math.floorDiv(r.top, L); val x1 = Math.floorDiv(r.right - 1, L); val y1 = Math.floorDiv(r.bottom - 1, L)
        for (capa in capas) {
            if (!incluir(capa)) continue
            pCapa.alpha = (capa.info.opacidad * 255).toInt()
            pCapa.blendMode = fusion(capa.info.fusion)
            val g = c.saveLayer(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), pCapa)
            for (ty in y0..y1) for (tx in x0..x1) capa.teselas[clave(tx, ty)]?.let { c.drawBitmap(it, tx * L.toFloat(), ty * L.toFloat(), null) }
            c.restoreToCount(g)
        }
    }

    /** Lo que se usa como barrera para rellenar: las capas de referencia (o, si no hay, todo lo visible). */
    private fun barreras(): List<CapaPx> = capas.filter { it.info.referencia && it.info.visible }.ifEmpty { capas.filter { it.info.visible } }

    /** Alfa de las barreras en (x, y), con caché por tesela. */
    private class Lector(val capas: List<CapaPx>) {
        private val cache = HashMap<Long, IntArray?>()
        fun alfa(x: Int, y: Int): Int {
            val k = clave(Math.floorDiv(x, L), Math.floorDiv(y, L))
            val a = cache.getOrPut(k) {
                var acc: IntArray? = null
                for (c in capas) {
                    val b = c.teselas[k] ?: continue
                    val px = IntArray(L * L); b.getPixels(px, 0, L, 0, 0, L, L)
                    if (acc == null) acc = IntArray(L * L)
                    for (i in px.indices) { val al = px[i] ushr 24; if (al > acc[i]) acc[i] = al }
                }
                acc
            } ?: return 0
            return a[Math.floorMod(y, L) * L + Math.floorMod(x, L)]
        }
    }

    /** La zona en la que se busca al rellenar (el lienzo finito, o lo dibujado más un margen en uno infinito). */
    fun limites(): Rect {
        if (!mapa.infinito) return Rect(0, 0, mapa.ancho, mapa.alto)
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (c in capas) for (k in c.teselas.keys) { x0 = min(x0, tx(k)); y0 = min(y0, ty(k)); x1 = max(x1, tx(k)); y1 = max(y1, ty(k)) }
        for (k in terr.keys) { x0 = min(x0, tx(k)); y0 = min(y0, ty(k)); x1 = max(x1, tx(k)); y1 = max(y1, ty(k)) }
        if (x0 > x1) return Rect(-2048, -1536, 2048, 1536)
        return Rect(x0 * L, y0 * L, (x1 + 1) * L, (y1 + 1) * L)
    }

    /**
     * Relleno por zonas cerradas: desde (x, y), todo lo que se alcanza sin cruzar una línea (alfa de las barreras > [umbral]).
     * Devuelve los píxeles rellenados como máscara por tesela, o null si (x, y) cae sobre una línea.
     */
    private fun zona(x: Int, y: Int, umbral: Int, limite: Rect, crece: Int): Map<Long, BooleanArray>? {
        val lec = Lector(barreras())
        if (lec.alfa(x, y) > umbral) return null
        val w = limite.width(); val h = limite.height()
        if (x < limite.left || y < limite.top || x >= limite.right || y >= limite.bottom) return null
        val visto = java.util.BitSet(w * h)
        val mascara = HashMap<Long, BooleanArray>()
        fun pon(px: Int, py: Int) {
            val k = clave(Math.floorDiv(px, L), Math.floorDiv(py, L))
            mascara.getOrPut(k) { BooleanArray(L * L) }[Math.floorMod(py, L) * L + Math.floorMod(px, L)] = true
        }
        fun libre(px: Int, py: Int): Boolean {
            if (px < limite.left || py < limite.top || px >= limite.right || py >= limite.bottom) return false
            val i = (py - limite.top) * w + (px - limite.left)
            return !visto[i] && lec.alfa(px, py) <= umbral
        }
        val cola = ArrayDeque<Long>()
        cola.addLast((x.toLong() shl 32) or (y.toLong() and 0xffffffffL))
        while (cola.isNotEmpty()) {
            val c = cola.removeFirst()
            val cx = (c shr 32).toInt(); val cy = c.toInt()
            if (!libre(cx, cy)) continue
            var l = cx; while (libre(l - 1, cy)) l--
            var r = cx; while (libre(r + 1, cy)) r++
            for (xx in l..r) { visto[(cy - limite.top) * w + (xx - limite.left)] = true; pon(xx, cy) }
            for (ny in intArrayOf(cy - 1, cy + 1)) {
                var xx = l
                while (xx <= r) { if (libre(xx, ny)) { cola.addLast((xx.toLong() shl 32) or (ny.toLong() and 0xffffffffL)); while (xx <= r && libre(xx, ny)) xx++ } else xx++ }
            }
        }
        // Crece unos píxeles por debajo de las líneas (si no, queda un halo sin pintar junto al borde suavizado)
        repeat(crece) {
            val nuevos = ArrayList<Long>()
            for ((k, m) in mascara) {
                val bx = tx(k) * L; val by = ty(k) * L
                for (i in m.indices) if (m[i]) {
                    val px = bx + i % L; val py = by + i / L
                    for ((dx, dy) in VECINOS) {
                        val nx = px + dx; val ny = py + dy
                        if (nx < limite.left || ny < limite.top || nx >= limite.right || ny >= limite.bottom) continue
                        if (!visto[(ny - limite.top) * w + (nx - limite.left)]) nuevos += (nx.toLong() shl 32) or (ny.toLong() and 0xffffffffL)
                    }
                }
            }
            for (n in nuevos) { val nx = (n shr 32).toInt(); val ny = n.toInt(); val i = (ny - limite.top) * w + (nx - limite.left); if (!visto[i]) { visto[i] = true; pon(nx, ny) } }
        }
        return mascara
    }

    /** El bote de pintura: rellena de [color] la zona cerrada en la capa activa. */
    fun rellena(x: Int, y: Int, color: Int, opacidad: Float): Edicion? {
        val c = capaActiva() ?: return null
        val m = zona(x, y, 110, limitesRelleno(x, y), 2) ?: return null
        val antes = HashMap<Long, Bitmap?>(); val despues = HashMap<Long, Bitmap?>()
        val a = ((color ushr 24) * opacidad.coerceIn(0f, 1f)).toInt()
        val col = (a shl 24) or (color and 0xFFFFFF)
        val p = Paint().apply { this.color = col; xfermode = if (c.info.bloqueoAlfa) PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) else null }
        for ((k, mask) in m) {
            if (!dentro(tx(k), ty(k))) continue
            val viejo = c.teselas[k]
            if (viejo == null && c.info.bloqueoAlfa) continue
            // La máscara pintada del color y fundida encima (o solo sobre lo que ya había, con la transparencia bloqueada)
            val px = IntArray(L * L) { if (mask[it]) -1 else 0 }
            val relleno = Bitmap.createBitmap(px, L, L, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
            Canvas(relleno).drawRect(0f, 0f, L.toFloat(), L.toFloat(), Paint().apply { this.color = col; xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
            val nuevo = viejo?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
            Canvas(nuevo).drawBitmap(relleno, 0f, 0f, p)
            antes[k] = viejo; despues[k] = nuevo; c.teselas[k] = nuevo; c.sucias += k
        }
        if (antes.isEmpty()) return null
        avisaTeselas(antes.keys)
        return Edicion.Teselas("Relleno", c.info.id, antes, despues)
    }

    private fun limitesRelleno(x: Int, y: Int): Rect {
        val l = limites()
        if (!mapa.infinito) return l
        // En un lienzo infinito se rellena como mucho en un cuadrado de 8.192 px alrededor (y dentro de lo dibujado + margen)
        l.inset(-1024, -1024)
        l.intersect(Rect(x - 4096, y - 4096, x + 4096, y + 4096))
        return l
    }

    // ---------------------------------------------------------------- territorios

    /** Marca como territorio [id] la zona cerrada por tus líneas que contiene (x, y). 0 = quitar. */
    fun territorioEnZona(x: Int, y: Int, id: Int): Edicion? {
        val m = zona(x, y, 110, limitesRelleno(x, y), 3) ?: return null
        return escribeTerr(m, id, "Territorio")
    }

    /** Pinta territorio con un pincel redondo a lo largo de los puntos (x, y). */
    fun territorioPincel(xs: FloatArray, ys: FloatArray, radio: Float, id: Int): Edicion? {
        val m = HashMap<Long, BooleanArray>()
        val r2 = radio * radio
        fun disco(cx: Float, cy: Float) {
            for (py in floor(cy - radio).toInt()..ceil(cy + radio).toInt()) for (px in floor(cx - radio).toInt()..ceil(cx + radio).toInt()) {
                val dx = px + 0.5f - cx; val dy = py + 0.5f - cy
                if (dx * dx + dy * dy > r2) continue
                if (!mapa.infinito && (px < 0 || py < 0 || px >= mapa.ancho || py >= mapa.alto)) continue
                val k = clave(Math.floorDiv(px, L), Math.floorDiv(py, L))
                m.getOrPut(k) { BooleanArray(L * L) }[Math.floorMod(py, L) * L + Math.floorMod(px, L)] = true
            }
        }
        val paso = max(1f, radio * 0.3f)
        for (i in xs.indices) {
            disco(xs[i], ys[i])
            if (i > 0) {
                val d = kotlin.math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]); var t = paso
                while (t < d) { disco(xs[i - 1] + (xs[i] - xs[i - 1]) * t / d, ys[i - 1] + (ys[i] - ys[i - 1]) * t / d); t += paso }
            }
        }
        return escribeTerr(m, id, if (id == 0) "Quitar territorio" else "Territorio")
    }

    private fun escribeTerr(m: Map<Long, BooleanArray>, id: Int, nombre: String): Edicion? {
        val antes = HashMap<Long, ByteArray?>(); val despues = HashMap<Long, ByteArray?>()
        val v = id.toByte()
        for ((k, mask) in m) {
            val viejo = terr[k]
            if (viejo == null && id == 0) continue
            val nuevo = viejo?.copyOf() ?: ByteArray(N)
            for (i in mask.indices) if (mask[i]) nuevo[i] = v
            antes[k] = viejo
            val vacio = nuevo.all { it.toInt() == 0 }
            despues[k] = if (vacio) null else nuevo
            if (vacio) terr.remove(k) else terr[k] = nuevo
            terrSucias += k
        }
        if (antes.isEmpty()) return null
        terrVersion++
        avisaTeselas(antes.keys)
        return Edicion.Terr(nombre, antes, despues)
    }

    fun territorioEn(x: Int, y: Int): Int = terr[clave(Math.floorDiv(x, L), Math.floorDiv(y, L))]?.let { it[Math.floorMod(y, L) * L + Math.floorMod(x, L)].toInt() and 255 } ?: 0

    /** Quita un territorio de todo el mapa (al borrarlo de la lista). */
    fun borraTerritorio(id: Int): Edicion? {
        val antes = HashMap<Long, ByteArray?>(); val despues = HashMap<Long, ByteArray?>()
        for ((k, a) in terr) {
            if (a.none { (it.toInt() and 255) == id }) continue
            val n = a.copyOf(); for (i in n.indices) if ((n[i].toInt() and 255) == id) n[i] = 0
            antes[k] = a; val vacio = n.all { it.toInt() == 0 }; despues[k] = if (vacio) null else n
            if (vacio) terr.remove(k) else terr[k] = n; terrSucias += k
        }
        if (antes.isEmpty()) return null
        terrVersion++; avisaTeselas(antes.keys)
        return Edicion.Terr("Borrar territorio", antes, despues)
    }

    class Cuenta(val pixeles: LongArray, val sx: DoubleArray, val sy: DoubleArray)

    /** Píxeles y centro de cada territorio (para su superficie y para poner su nombre). */
    fun cuentaTerritorios(): Cuenta {
        val n = LongArray(256); val sx = DoubleArray(256); val sy = DoubleArray(256)
        for ((k, a) in terr) {
            val bx = tx(k) * L; val by = ty(k) * L
            for (i in a.indices) { val v = a[i].toInt() and 255; if (v != 0) { n[v]++; sx[v] += (bx + i % L).toDouble(); sy[v] += (by + i / L).toDouble() } }
        }
        return Cuenta(n, sx, sy)
    }

    // ---------------------------------------------------------------- imágenes

    /** Pone una imagen (una foto, un boceto, un mapa exportado de otra app) en una capa nueva, encajada en lo visible. */
    fun importaImagen(b: Bitmap, nombre: String, x: Float, y: Float, ancho: Float) {
        val id = "c" + java.lang.Long.toString(System.nanoTime(), 36)
        val c = CapaPx(CapaInfo(id, nombre, opacidad = 1f))
        val k = ancho / b.width
        val r = RectF(x, y, x + b.width * k, y + b.height * k)
        val pt = Paint(Paint.FILTER_BITMAP_FLAG)
        for (ty in floor(r.top / L).toInt()..floor((r.bottom - 1) / L).toInt()) for (tx in floor(r.left / L).toInt()..floor((r.right - 1) / L).toInt()) {
            if (!dentro(tx, ty)) continue
            val t = Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
            val cv = Canvas(t); cv.translate(-tx * L.toFloat(), -ty * L.toFloat())
            cv.drawBitmap(b, null, r, pt)
            c.teselas[clave(tx, ty)] = t
        }
        cambiaCapas("Importar imagen") { capas += c; activa = id }
    }

    /** Lo dibujado en la zona [r] en un mapa de bits (sin papel): para guardar un dibujo en tu biblioteca. */
    fun recorta(r: Rect, capa: CapaPx? = null): Bitmap {
        val b = Bitmap.createBitmap(max(1, r.width()), max(1, r.height()), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.translate(-r.left.toFloat(), -r.top.toFloat())
        componeZona(c, r) { if (capa != null) it === capa else it.info.visible }
        return b
    }

    /** Los límites de lo que hay dibujado (en las capas visibles). */
    fun contenido(): Rect? {
        var r: Rect? = null
        for (c in capas) if (c.info.visible) for ((k, b) in c.teselas) {
            val px = pixeles(b)
            var x0 = L; var y0 = L; var x1 = -1; var y1 = -1
            for (i in px.indices) if ((px[i] ushr 24) > 8) { val x = i % L; val y = i / L; if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y }
            if (x1 < 0) continue
            val rr = Rect(tx(k) * L + x0, ty(k) * L + y0, tx(k) * L + x1 + 1, ty(k) * L + y1 + 1)
            if (r == null) r = rr else r.union(rr)
        }
        return r
    }

    companion object {
        const val L = 256
        const val N = L * L
        fun clave(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)
        fun tx(k: Long) = (k shr 32).toInt()
        fun ty(k: Long) = k.toInt()
        private val VECINOS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        fun fusion(f: String): BlendMode = when (f) {
            "multiplicar" -> BlendMode.MULTIPLY; "pantalla" -> BlendMode.SCREEN; "superponer" -> BlendMode.OVERLAY
            "aclarar" -> BlendMode.LIGHTEN; "oscurecer" -> BlendMode.DARKEN; "anadir" -> BlendMode.PLUS; "color" -> BlendMode.COLOR
            "luz_suave" -> BlendMode.SOFT_LIGHT
            else -> BlendMode.SRC_OVER
        }
        val FUSIONES = listOf("normal" to "Normal", "multiplicar" to "Multiplicar", "pantalla" to "Pantalla", "superponer" to "Superponer",
            "luz_suave" to "Luz suave", "aclarar" to "Aclarar", "oscurecer" to "Oscurecer", "anadir" to "Añadir", "color" to "Color")
    }
}
