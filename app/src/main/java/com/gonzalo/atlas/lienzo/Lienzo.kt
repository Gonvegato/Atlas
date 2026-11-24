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
import com.gonzalo.atlas.motor.CapaInfo
import com.gonzalo.atlas.motor.Mapa
import java.io.DataInputStream
import java.io.DataOutputStream
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

    /**
     * Los trazos guardados también como datos: de cerca se vuelven a pintar al tamaño de la pantalla (nítidos a cualquier
     * acercamiento) sobre el [fondo] de cada tesela, que es lo que había en ella antes de esos trazos. Las teselas normales
     * los llevan pintados (para ver de lejos, rellenar, exportar…). Una operación de píxeles en una tesela (rellenar,
     * difuminar, ajustes…) quita su fondo: desde ahí, esa tesela es solo píxeles hasta el siguiente trazo.
     */
    @Volatile var trazos: List<TrazoV> = emptyList()
    val fondo = ConcurrentHashMap<Long, Fondo>()
    val fondoSucias: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    @Volatile var trazosSucios = false
    @Volatile var seq = 0L

    /** Deja la capa solo como píxeles (al combinarla, vaciarla o rellenarla entera). */
    fun soloPixeles() { fondoSucias.addAll(fondo.keys); fondo.clear(); trazos = emptyList(); trazosSucios = true }

    /** Los trazos que aún hacen falta (los que alguna tesela tiene que rehacer de cerca). */
    fun trazosVivos(): List<TrazoV> {
        if (fondo.isEmpty()) return emptyList()
        return trazos.filter { tr ->
            val r = tr.caja
            var hace = false
            var ty = Math.floorDiv(floor(r.top).toInt(), Lienzo.L)
            while (!hace && ty <= Math.floorDiv(ceil(r.bottom).toInt(), Lienzo.L)) {
                for (tx in Math.floorDiv(floor(r.left).toInt(), Lienzo.L)..Math.floorDiv(ceil(r.right).toInt(), Lienzo.L)) { val f = fondo[Lienzo.clave(tx, ty)]; if (f != null && f.desde < tr.seq) { hace = true; break } }
                ty++
            }
            hace
        }
    }
}

/** Una tesela vacía (en el fondo de una tesela: «aquí no había nada antes de los trazos»). */
val TESELA_VACIA: Bitmap by lazy { Bitmap.createBitmap(Lienzo.L, Lienzo.L, Bitmap.Config.ARGB_8888) }

/** El estado entero de una capa (para deshacer añadir, borrar, combinar u ordenar capas). */
class EstadoCapa(val info: CapaInfo, val teselas: Map<Long, Bitmap>, val fondo: Map<Long, Fondo>, val trazos: List<TrazoV>, val seq: Long)

sealed class Edicion(val nombre: String) {
    class Teselas(nombre: String, val capa: String, val antes: Map<Long, Bitmap?>, val despues: Map<Long, Bitmap?>, val trazo: TrazoV? = null,
                  val fondoAntes: Map<Long, Fondo?> = emptyMap(), val fondoDespues: Map<Long, Fondo?> = emptyMap()) : Edicion(nombre)
    class Terr(nombre: String, val antes: Map<Long, ByteArray?>, val despues: Map<Long, ByteArray?>) : Edicion(nombre)
    class Vector(nombre: String, val antes: String, val despues: String) : Edicion(nombre)
    class Capas(nombre: String, val antes: List<EstadoCapa>, val despues: List<EstadoCapa>) : Edicion(nombre)
    class Varias(nombre: String, val partes: List<Edicion>) : Edicion(nombre)
}

/**
 * El dibujo de un mapa: capas en teselas, los territorios (un número por píxel) y deshacer. Todos los cambios pasan por el
 * hilo del lienzo ([enHilo]), uno detrás de otro; el trazo que se está dibujando se estampa en otro ([enVivo]).
 */
class Lienzo(val mapa: Mapa, private val res: Resources) {
    val capas = CopyOnWriteArrayList<CapaPx>()
    /** Territorios: un byte por píxel (0 = ninguno), en teselas de 256. */
    val terr = ConcurrentHashMap<Long, ByteArray>()
    private val terrSucias: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    @Volatile var terrVersion = 0
    @Volatile var version = 0
    var activa: String = ""
    /** Se llama (en el hilo del lienzo) con la zona cambiada, en píxeles del lienzo. */
    val alCambiar = CopyOnWriteArrayList<(RectF) -> Unit>()
    private val hilo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "atlas-lienzo").apply { isDaemon = true } }
    private val hiloVivo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "atlas-trazo").apply { isDaemon = true; priority = Thread.MAX_PRIORITY } }
    /** Para que la pantalla no pinte a medias mientras un trazo pasa a la capa. */
    val cierre = Any()
    /** Los trazos que se están dibujando o asentando (la mesa los pinta encima de su capa). */
    val vivos = CopyOnWriteArrayList<TrazoVivo>()
    var alCambiarVivo: () -> Unit = {}
    val nitidez = Nitidez(this)
    /** La selección: si la hay, solo se pinta, borra, rellena y ajusta dentro. */
    @Volatile var seleccion: Seleccion? = null

    private val atras = ArrayDeque<Edicion>()
    private val alante = ArrayDeque<Edicion>()
    var alCambiarHistorial: () -> Unit = {}
    val puedeDeshacer get() = atras.isNotEmpty()
    val puedeRehacer get() = alante.isNotEmpty()

    fun enHilo(f: () -> Unit) { runCatching { hilo.execute { runCatching(f).onFailure { it.printStackTrace() } } } }
    fun enVivo(f: () -> Unit) { runCatching { hiloVivo.execute { runCatching(f).onFailure { it.printStackTrace() } } } }
    fun avisaVivo() = alCambiarVivo()
    fun cierra() { hilo.shutdown(); hiloVivo.shutdown(); nitidez.cierra() }

    // ---------------------------------------------------------------- capas

    fun capa(id: String) = capas.firstOrNull { it.info.id == id }
    fun capaActiva(): CapaPx? = capa(activa) ?: capas.lastOrNull()

    private fun sincronizaInfo() { mapa.capas.clear(); mapa.capas.addAll(capas.map { it.info }) }

    // ---------------------------------------------------------------- cargar y guardar

    /** Lee el mapa: las capas al momento y sus teselas en el hilo del lienzo si [enSegundoPlano] (avisa al terminar). */
    fun carga(enSegundoPlano: Boolean = false) {
        capas.clear()
        for (ci in mapa.capas) capas += CapaPx(ci)
        if (capas.isEmpty()) { capas += CapaPx(CapaInfo("c" + System.currentTimeMillis().toString(36), "Dibujo")); sincronizaInfo() }
        activa = capas.lastOrNull { !it.info.referencia }?.info?.id ?: capas.last().info.id
        if (enSegundoPlano) enHilo { leeTeselas(); avisaTodo() } else leeTeselas()
    }

    private fun leeTeselas() {
        val dir = mapa.dir ?: return
        for (c in capas) File(dir, "capas/${c.info.id}").listFiles()?.forEach { f -> clave(f.name, ".png")?.let { k -> if (!c.teselas.containsKey(k)) BitmapFactory.decodeFile(f.path, opcionesBmp)?.let { c.teselas.putIfAbsent(k, it) } } }
        for (c in capas) runCatching { leeTrazos(c, File(dir, "capas/${c.info.id}")) }.onFailure { c.trazos = emptyList(); c.fondo.clear() }
        File(dir, "terr").listFiles()?.forEach { f -> clave(f.name, ".bin")?.let { k -> runCatching { InflaterInputStream(f.inputStream()).use { it.readBytes() } }.getOrNull()?.takeIf { it.size == N }?.let { terr.putIfAbsent(k, it) } } }
        terrVersion++
    }

    /** Los trazos guardados como datos y el fondo de sus teselas. */
    private fun leeTrazos(c: CapaPx, cd: File) {
        // Los de la versión anterior (hechos con otro motor) se quedan solo como píxeles
        File(cd, "trazos.json").delete(); File(cd, "sin").deleteRecursively()
        val f = File(cd, "trazos.bin"); if (!f.exists()) return
        DataInputStream(InflaterInputStream(f.inputStream().buffered())).use { i ->
            if (i.readInt() != FORMATO) return
            c.seq = i.readLong()
            val desde = HashMap<Long, Long>()
            repeat(i.readInt()) { desde[i.readLong()] = i.readLong() }
            val l = ArrayList<TrazoV>()
            repeat(i.readInt()) { val s = i.readLong(); l += TrazoV(TrazoDatos.lee(i), s) }
            for ((k, d) in desde) {
                val b = BitmapFactory.decodeFile(File(cd, "fondo/${tx(k)}_${ty(k)}.png").path, opcionesBmp) ?: continue
                c.fondo[k] = Fondo(if (b.width == 1) TESELA_VACIA else b, d)
            }
            c.trazos = l
        }
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
        // Los trazos como datos y el fondo de sus teselas
        for (c in capas) if (c.trazosSucios) {
            c.trazosSucios = false
            val cd = File(d, "capas/${c.info.id}")
            if (c.trazos.size > TOPE_TRAZOS) hornea(c, c.trazos.size - TOPE_TRAZOS * 3 / 4)
            val fd = File(cd, "fondo")
            if (c.fondo.isEmpty()) { fd.deleteRecursively(); File(cd, "trazos.bin").delete(); c.fondoSucias.clear(); continue }
            fd.mkdirs()
            for (k in c.fondoSucias.toList()) {
                c.fondoSucias.remove(k)
                val f = File(fd, "${tx(k)}_${ty(k)}.png")
                val b = c.fondo[k]?.bmp
                if (b == null) f.delete() else f.outputStream().use { (if (b === TESELA_VACIA) Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) else b).compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val vivos = c.trazosVivos()
            val tmp = File(cd, "trazos.bin.tmp")
            DataOutputStream(DeflaterOutputStream(tmp.outputStream().buffered(), Deflater(5))).use { o ->
                o.writeInt(FORMATO); o.writeLong(c.seq)
                val fs = c.fondo.entries.toList()
                o.writeInt(fs.size); for ((k, f) in fs) { o.writeLong(k); o.writeLong(f.desde) }
                o.writeInt(vivos.size); for (t in vivos) { o.writeLong(t.seq); t.datos.escribe(o) }
            }
            tmp.renameTo(File(cd, "trazos.bin"))
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

    /**
     * Cuando una capa junta demasiados trazos guardados, los [n] más antiguos pasan a formar parte del fondo de sus teselas
     * (se quedan como píxeles: de cerca ya no se rehacen). El orden se respeta: son siempre los de más abajo.
     */
    private fun hornea(c: CapaPx, n: Int) {
        val viejos = c.trazos.sortedBy { it.seq }.take(n); if (viejos.isEmpty()) return
        val tope = viejos.last().seq
        val porTesela = HashMap<Long, ArrayList<TrazoV>>()
        for (tr in viejos) { val r = tr.caja; for (ty in Math.floorDiv(floor(r.top).toInt(), L)..Math.floorDiv(ceil(r.bottom).toInt(), L)) for (tx in Math.floorDiv(floor(r.left).toInt(), L)..Math.floorDiv(ceil(r.right).toInt(), L)) porTesela.getOrPut(clave(tx, ty)) { ArrayList() } += tr }
        val nuevos = HashMap<Long, Fondo>()
        for ((k, l) in porTesela) {
            val f = c.fondo[k] ?: continue
            val b = f.bmp.copy(Bitmap.Config.ARGB_8888, true)
            val cv = Canvas(b); cv.translate(-tx(k) * L.toFloat(), -ty(k) * L.toFloat())
            for (tr in l) if (tr.seq > f.desde) tr.datos.pintaTesela(cv, tx(k), ty(k), 1f)
            nuevos[k] = Fondo(b, max(f.desde, tope))
        }
        synchronized(cierre) {
            for ((k, f) in nuevos) { c.fondo[k] = f; c.fondoSucias += k }
            for ((k, f) in c.fondo) if (f.desde < tope && k !in nuevos) { c.fondo[k] = Fondo(f.bmp, tope) }
            c.trazos = c.trazos.filter { it.seq > tope }
        }
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
        is Edicion.Teselas -> (e.antes.size + e.despues.size + e.fondoAntes.size) * (N * 4L)
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
                synchronized(cierre) {
                    for ((k, b) in m) { if (b == null) c.teselas.remove(k) else c.teselas[k] = b; c.sucias += k }
                    for ((k, f) in if (atrasDe) e.fondoAntes else e.fondoDespues) { if (f == null) c.fondo.remove(k) else c.fondo[k] = f; c.fondoSucias += k }
                    e.trazo?.let { tr -> c.trazos = if (atrasDe) c.trazos - tr else (c.trazos + tr).sortedBy { it.seq } }
                    c.trazosSucios = true
                }
                avisaTeselas(m.keys, c)
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

    private fun copia(c: CapaPx, info: CapaInfo = c.info) = CapaPx(info).also { n -> n.teselas.putAll(c.teselas); n.sucias.addAll(c.teselas.keys); n.fondo.putAll(c.fondo); n.fondoSucias.addAll(c.fondo.keys); n.trazos = c.trazos; n.seq = c.seq; n.trazosSucios = true }

    /** Si el JSON trae otro orden o datos de capas, se respetan (sin tocar los píxeles). */
    private fun reordenaPorInfo() {
        val nuevas = mapa.capas.mapNotNull { ci -> capas.firstOrNull { it.info.id == ci.id }?.let { copia(it, ci) } }
        if (nuevas.size == capas.size) synchronized(cierre) { capas.clear(); capas.addAll(nuevas) }
    }

    fun estadoCapas(): List<EstadoCapa> = capas.map { EstadoCapa(CapaInfo.de(it.info.json()), HashMap(it.teselas), HashMap(it.fondo), it.trazos, it.seq) }

    fun ponEstadoCapas(e: List<EstadoCapa>) {
        synchronized(cierre) {
            capas.clear()
            for (s in e) { val c = CapaPx(CapaInfo.de(s.info.json())); c.teselas.putAll(s.teselas); c.sucias.addAll(s.teselas.keys); c.fondo.putAll(s.fondo); c.fondoSucias.addAll(s.fondo.keys); c.trazos = s.trazos; c.seq = s.seq; c.trazosSucios = true; capas += c }
            if (capa(activa) == null) activa = capas.lastOrNull()?.info?.id.orEmpty()
        }
        nitidez.olvida()
        sincronizaInfo(); version++; mapa.avisaVector(); avisaTodo()
    }

    /** Cambia la estructura de capas con [f] y lo apunta para deshacer. */
    fun cambiaCapas(nombre: String, f: () -> Unit) = enHilo {
        val antes = estadoCapas()
        synchronized(cierre) { f() }
        sincronizaInfo()
        // Las carpetas de las capas: se marcan sucias todas las teselas de las capas que quedan (se reescriben al guardar)
        capas.forEach { it.sucias.addAll(it.teselas.keys); it.fondoSucias.addAll(it.fondo.keys); it.trazosSucios = true }
        apunta(Edicion.Capas(nombre, antes, estadoCapas()))
        nitidez.olvida()
        version++; mapa.avisaVector(); avisaTodo()
    }

    // ---------------------------------------------------------------- avisos

    fun avisaTeselas(ks: Collection<Long>, c: CapaPx? = null) {
        if (ks.isEmpty()) return
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (k in ks) { x0 = min(x0, tx(k)); y0 = min(y0, ty(k)); x1 = max(x1, tx(k)); y1 = max(y1, ty(k)) }
        version++
        if (c != null) nitidez.ensucia(c.info.id, ks)
        val r = RectF(x0 * L.toFloat(), y0 * L.toFloat(), (x1 + 1) * L.toFloat(), (y1 + 1) * L.toFloat())
        alCambiar.forEach { it(r) }
    }

    fun avisaTodo() { version++; val r = RectF(-1e9f, -1e9f, 1e9f, 1e9f); alCambiar.forEach { it(r) } }

    // ---------------------------------------------------------------- cambiar píxeles

    /**
     * Cambia teselas de una capa ([nuevas]: la tesela nueva, o null si queda vacía) y devuelve cómo deshacerlo. Si el cambio
     * es un trazo guardado ([trazo]), las teselas que aún no tenían fondo se lo apuntan (lo que había); si es una operación
     * de píxeles, lo pierden (desde ahí son solo píxeles).
     */
    fun cambia(c: CapaPx, nombre: String, nuevas: Map<Long, Bitmap?>, trazo: TrazoV? = null): Edicion.Teselas? {
        if (nuevas.isEmpty()) return null
        val antes = HashMap<Long, Bitmap?>(); val fAntes = HashMap<Long, Fondo?>(); val fDespues = HashMap<Long, Fondo?>()
        synchronized(cierre) {
            for ((k, b) in nuevas) {
                val viejo = c.teselas[k]; antes[k] = viejo
                val f0 = c.fondo[k]
                if (trazo != null) { if (f0 == null) { fAntes[k] = null; val f = Fondo(viejo ?: TESELA_VACIA, trazo.seq - 1); c.fondo[k] = f; fDespues[k] = f; c.fondoSucias += k } }
                else if (f0 != null) { fAntes[k] = f0; fDespues[k] = null; c.fondo.remove(k); c.fondoSucias += k }
                if (b == null) c.teselas.remove(k) else c.teselas[k] = b
                c.sucias += k
            }
            if (trazo != null) c.trazos = c.trazos + trazo
            c.trazosSucios = true
        }
        avisaTeselas(nuevas.keys, c)
        return Edicion.Teselas(nombre, c.info.id, antes, HashMap(nuevas), trazo, fAntes, fDespues)
    }

    /** [nuevo] solo dentro de la selección (fuera, lo que había en [viejo]); sin selección, [nuevo] tal cual. */
    fun enSeleccion(k: Long, viejo: Bitmap?, nuevo: Bitmap): Bitmap {
        val sel = seleccion?.takeIf { !it.vacia } ?: return nuevo
        val dentro = nuevo.copy(Bitmap.Config.ARGB_8888, true)
        val cd = Canvas(dentro); cd.translate(-tx(k) * L.toFloat(), -ty(k) * L.toFloat())
        cd.drawPath(sel.fuera, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        val r = viejo?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
        val cr = Canvas(r); cr.save(); cr.translate(-tx(k) * L.toFloat(), -ty(k) * L.toFloat())
        cr.drawPath(sel.path, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        cr.restore(); cr.drawBitmap(dentro, 0f, 0f, null)
        return r
    }

    /** Aplica [f] a cada tesela de la capa (solo dentro de la selección, si la hay): los ajustes de imagen. */
    fun cadaTesela(c: CapaPx, nombre: String, f: (Bitmap) -> Bitmap): Edicion? {
        val nuevas = HashMap<Long, Bitmap?>()
        for ((k, v) in c.teselas.toMap()) nuevas[k] = enSeleccion(k, v, f(v))
        return cambia(c, nombre, nuevas)
    }

    // ---------------------------------------------------------------- selección

    /** Las teselas de la capa que toca la selección (todas, si está invertida). */
    private fun teselasEnSeleccion(c: CapaPx, sel: Seleccion): List<Long> {
        if (sel.invertida) return c.teselas.keys.toList()
        val r = sel.caja
        return c.teselas.keys.filter { k -> tx(k) * L < r.right && (tx(k) + 1) * L > r.left && ty(k) * L < r.bottom && (ty(k) + 1) * L > r.top }
    }

    /** La tesela solo con lo de dentro de la selección ([dentro]) o solo con lo de fuera. */
    private fun conForma(b: Bitmap, k: Long, sel: Seleccion, dentro: Boolean): Bitmap {
        val n = b.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(n); cv.translate(-tx(k) * L.toFloat(), -ty(k) * L.toFloat())
        cv.drawPath(if (dentro) sel.fuera else sel.path, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        return n
    }

    /**
     * Selección automática: la zona cerrada por tus líneas que contiene (x, y), como selección (para pintarla por dentro con
     * cualquier pincel sin salirse). Null si ahí hay una línea o la zona es demasiado grande.
     */
    fun seleccionZona(x: Int, y: Int): Seleccion? {
        val m = zona(x, y, 110, limitesRelleno(x, y), 2) ?: return null
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (k in m.keys) { x0 = min(x0, tx(k)); y0 = min(y0, ty(k)); x1 = max(x1, tx(k)); y1 = max(y1, ty(k)) }
        val w = (x1 - x0 + 1) * L; val h = (y1 - y0 + 1) * L
        if (w.toLong() * h > 6000L * 6000L) return null
        val campo = FloatArray(w * h) { -1f }
        for ((k, mask) in m) { val ox = (tx(k) - x0) * L; val oy = (ty(k) - y0) * L; for (i in mask.indices) if (mask[i]) campo[(oy + i / L) * w + ox + i % L] = 1f }
        val contornos = com.gonzalo.atlas.motor.Continente.costas(campo, w, h).map { c ->
            val n = com.gonzalo.atlas.motor.Continente.puntos(c)
            FloatArray(n * 2) { i -> c[i] + 0.5f + (if (i % 2 == 0) x0 * L else y0 * L) }
        }
        return Seleccion(contornos).takeIf { !it.vacia }
    }

    /** Borra de la capa lo que cae dentro de la selección. */
    fun borraSeleccion(c: CapaPx): Edicion? {
        val sel = seleccion?.takeIf { !it.vacia } ?: return null
        val nuevas = HashMap<Long, Bitmap?>()
        for (k in teselasEnSeleccion(c, sel)) { val n = conForma(c.teselas[k] ?: continue, k, sel, false); nuevas[k] = if (estaVacia(n)) null else n }
        return cambia(c, "Borrar lo seleccionado", nuevas)
    }

    /** Rellena de [color] la forma [forma] en la capa (dentro del lienzo). Para rellenar la selección o una forma hecha. */
    fun rellenaForma(c: CapaPx, forma: android.graphics.Path, color: Int, nombre: String = "Rellenar"): Edicion? {
        val r = RectF(); forma.computeBounds(r, true)
        if (forma.isInverseFillType) r.set(RectF(limites()))
        if (r.isEmpty || r.width() * r.height() > 16384f * 16384f) return null
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; if (c.info.bloqueoAlfa) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
        val nuevas = HashMap<Long, Bitmap?>()
        for (ty in Math.floorDiv(floor(r.top).toInt(), L)..Math.floorDiv(ceil(r.bottom).toInt(), L)) for (tx in Math.floorDiv(floor(r.left).toInt(), L)..Math.floorDiv(ceil(r.right).toInt(), L)) {
            if (!dentro(tx, ty)) continue
            val k = clave(tx, ty); val viejo = c.teselas[k]
            if (viejo == null && c.info.bloqueoAlfa) continue
            val n = viejo?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
            val cv = Canvas(n); cv.translate(-tx * L.toFloat(), -ty * L.toFloat()); cv.drawPath(forma, p)
            if (viejo != null || !estaVacia(n)) nuevas[k] = n
        }
        return cambia(c, nombre, nuevas)
    }

    /** Lo seleccionado de la capa pasa a una capa nueva encima (y, si [cortar], se quita de la suya). */
    fun copiaSeleccion(c: CapaPx, cortar: Boolean, alAcabar: (String) -> Unit = {}) {
        val sel = seleccion?.takeIf { !it.vacia } ?: return
        cambiaCapas(if (cortar) "Cortar a una capa" else "Copiar a una capa") {
            val n = CapaPx(CapaInfo("c" + java.lang.Long.toString(System.nanoTime(), 36), if (cortar) "Recorte" else "Copia"))
            for (k in teselasEnSeleccion(c, sel)) {
                val b = c.teselas[k] ?: continue
                val d = conForma(b, k, sel, true)
                if (!estaVacia(d)) n.teselas[k] = d
                if (cortar) { val f = conForma(b, k, sel, false); if (estaVacia(f)) c.teselas.remove(k) else c.teselas[k] = f; c.fondo.remove(k); c.fondoSucias += k }
            }
            capas.add(capas.indexOf(c) + 1, n); activa = n.info.id
            alAcabar(n.info.id)
        }
    }

    /** Pone trazos ya hechos (una costa generada, una forma) en la capa, guardados como datos (nítidos de cerca). */
    fun ponTrazos(c: CapaPx, trazos: List<TrazoDatos>, nombre: String): Edicion? {
        val partes = ArrayList<Edicion>()
        for (d in trazos) {
            val h = Humedo(1f, d.def.colorVariable); h.sellaTodo(Raster(d.def, 1f), d.sellos())
            val nuevas = HashMap<Long, Bitmap?>()
            for ((k, l) in h.lonas) {
                if (!l.tocada || !dentro(tx(k), ty(k))) continue
                val viejo = c.teselas[k]
                val n = viejo?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
                val cv = Canvas(n); cv.translate(-tx(k) * L.toFloat(), -ty(k) * L.toFloat()); d.funde(cv, l, 1f)
                nuevas[k] = n
            }
            cambia(c, nombre, nuevas, if (d.def.repetible) TrazoV(d, ++c.seq) else null)?.let { partes += it }
        }
        return if (partes.isEmpty()) null else Edicion.Varias(nombre, partes)
    }

    // ---------------------------------------------------------------- trazos

    /** Empieza un trazo en la capa activa (o null si no se puede pintar en ella). [nivel]: ver [Motor.nivel]. */
    fun empiezaTrazo(def: DefPincel, color: Int, tam: Float, opacidad: Float, modo: Int, u: Float, lapiz: Boolean, nivel: Int, espejos: List<Matrix> = listOf(Matrix())): TrazoVivo? {
        val c = capaActiva() ?: return null
        // Solo se pinta dentro de la sección activa y, si el lienzo tiene tamaño, dentro de él
        var rec = mapa.seccionActivaRect()
        if (!mapa.infinito) { val l = RectF(0f, 0f, mapa.ancho.toFloat(), mapa.alto.toFloat()); if (rec == null) rec = l else if (!rec.intersect(l)) rec.setEmpty() }
        val v = TrazoVivo(this, c, def, color, tam, opacidad, modo, (System.nanoTime() and 0x7fffffff).toInt(), u, lapiz, nivel, rec, seleccion?.takeIf { !it.vacia }, espejos)
        vivos += v
        return v
    }

    /**
     * Pasa a la capa un trazo recién dibujado: sus teselas a tamaño real (rehechas ahí si se dibujó a otra escala) y, si se
     * dibujó de cerca, las teselas nítidas con él ya encima; todo de una vez, para que en la pantalla no cambie nada.
     */
    fun asienta(v: TrazoVivo): Edicion? {
        val c = v.capa; val d = v.datos(); val def = d.def
        val borra = d.modo == TrazoDatos.BORRAR
        val h1 = if (v.humedo.escala == 1f) v.humedo else Humedo(1f, def.colorVariable).also { it.sellaTodo(Raster(def, 1f), d.sellos(v.muestra)) }
        val nuevas = HashMap<Long, Bitmap?>()
        for ((k, l) in h1.lonas) {
            if (!l.tocada || !dentro(tx(k), ty(k))) continue
            val viejo = c.teselas[k]
            if (viejo == null && (borra || d.atope)) continue
            val nuevo = viejo?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
            val cv = Canvas(nuevo); cv.translate(-tx(k) * L.toFloat(), -ty(k) * L.toFloat())
            d.funde(cv, l, 1f)
            if (estaVacia(nuevo)) { if (viejo != null) nuevas[k] = null } else nuevas[k] = nuevo
        }
        val guardado = def.repetible && nuevas.isNotEmpty()
        val cerca = if (guardado) nitidez.conTrazo(c, v.humedo, d) else emptyMap()
        var e: Edicion? = null
        synchronized(cierre) {
            e = cambia(c, def.nombre, nuevas, if (guardado) TrazoV(d, ++c.seq) else null)
            nitidez.pon(cerca)
            v.estado = TrazoVivo.ASENTADO
            if (v.nivel <= 0) vivos.remove(v)
        }
        avisaVivo()
        return e
    }

    /** El color de una capa en un punto (sin premultiplicar; 0 si no hay nada). */
    fun colorCapa(c: CapaPx, x: Float, y: Float): Int {
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        return c.teselas[clave(Math.floorDiv(ix, L), Math.floorDiv(iy, L))]?.getPixel(Math.floorMod(ix, L), Math.floorMod(iy, L)) ?: 0
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

    /** La capa a la que se recorta la capa [i] (la primera de debajo que no es máscara de recorte), o null si no se recorta. */
    fun baseDe(l: List<CapaPx>, i: Int): CapaPx? {
        if (!l[i].info.recorte) return null
        var j = i - 1; while (j >= 0 && l[j].info.recorte) j--
        return l.getOrNull(j)
    }

    /** Dibuja las capas visibles de la zona [r] (píxeles del lienzo) en [c] (ya colocado en coordenadas del lienzo). */
    fun componeZona(c: Canvas, r: Rect, incluir: (CapaPx) -> Boolean = { it.info.visible }) {
        val x0 = Math.floorDiv(r.left, L); val y0 = Math.floorDiv(r.top, L); val x1 = Math.floorDiv(r.right - 1, L); val y1 = Math.floorDiv(r.bottom - 1, L)
        val pCapa = Paint(Paint.FILTER_BITMAP_FLAG) // una por llamada: la usan varios hilos a la vez
        val pDentro = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        val pNada = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val l = capas.toList()
        for ((i, capa) in l.withIndex()) {
            if (!incluir(capa)) continue
            val base = baseDe(l, i)
            if (base != null && !base.info.visible) continue
            pCapa.alpha = (capa.info.opacidad * 255).toInt()
            pCapa.blendMode = fusion(capa.info.fusion)
            val g = c.saveLayer(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), pCapa)
            for (ty in y0..y1) for (tx in x0..x1) {
                val b = capa.teselas[clave(tx, ty)] ?: continue
                c.drawBitmap(b, tx * L.toFloat(), ty * L.toFloat(), null)
                // Máscara de recorte: solo donde la capa de debajo tiene algo
                if (base != null) { val m = base.teselas[clave(tx, ty)]; if (m != null) c.drawBitmap(m, tx * L.toFloat(), ty * L.toFloat(), pDentro) else c.drawRect(tx * L.toFloat(), ty * L.toFloat(), (tx + 1) * L.toFloat(), (ty + 1) * L.toFloat(), pNada) }
            }
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
        val nuevas = HashMap<Long, Bitmap?>()
        val a = ((color ushr 24) * opacidad.coerceIn(0f, 1f)).toInt()
        val col = (a shl 24) or (color and 0xFFFFFF)
        val p = Paint().apply { xfermode = if (c.info.bloqueoAlfa) PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) else null }
        val sel = seleccion?.takeIf { !it.vacia }
        for ((k, mask) in m) {
            if (!dentro(tx(k), ty(k))) continue
            val viejo = c.teselas[k]
            if (viejo == null && c.info.bloqueoAlfa) continue
            // La máscara pintada del color y fundida encima (o solo sobre lo que ya había, con la transparencia bloqueada)
            val px = IntArray(L * L) { if (mask[it]) -1 else 0 }
            val relleno = Bitmap.createBitmap(px, L, L, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
            val cr = Canvas(relleno)
            cr.drawRect(0f, 0f, L.toFloat(), L.toFloat(), Paint().apply { this.color = col; xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
            if (sel != null) { cr.translate(-tx(k) * L.toFloat(), -ty(k) * L.toFloat()); cr.drawPath(sel.fuera, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }) }
            val nuevo = viejo?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
            Canvas(nuevo).drawBitmap(relleno, 0f, 0f, p)
            nuevas[k] = nuevo
        }
        return cambia(c, "Relleno", nuevas)
    }

    private fun limitesRelleno(x: Int, y: Int): Rect {
        mapa.seccionActivaRect()?.let { r -> return Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()) }
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
        const val FORMATO = 0x41544C32
        const val TOPE_TRAZOS = 4000
        const val L = 256
        const val N = L * L
        fun clave(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)
        fun tx(k: Long) = (k shr 32).toInt()
        fun ty(k: Long) = k.toInt()
        private val VECINOS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        fun fusion(f: String): BlendMode = when (f) {
            "multiplicar" -> BlendMode.MULTIPLY; "pantalla" -> BlendMode.SCREEN; "superponer" -> BlendMode.OVERLAY
            "aclarar" -> BlendMode.LIGHTEN; "oscurecer" -> BlendMode.DARKEN; "anadir" -> BlendMode.PLUS; "color" -> BlendMode.COLOR
            "luz_suave" -> BlendMode.SOFT_LIGHT; "luz_fuerte" -> BlendMode.HARD_LIGHT; "subexponer" -> BlendMode.COLOR_BURN; "sobreexponer" -> BlendMode.COLOR_DODGE
            "diferencia" -> BlendMode.DIFFERENCE; "exclusion" -> BlendMode.EXCLUSION; "tono" -> BlendMode.HUE; "saturacion" -> BlendMode.SATURATION; "luminosidad" -> BlendMode.LUMINOSITY
            else -> BlendMode.SRC_OVER
        }
        val FUSIONES = listOf("normal" to "Normal", "multiplicar" to "Multiplicar", "oscurecer" to "Oscurecer", "subexponer" to "Subexponer color",
            "aclarar" to "Aclarar", "pantalla" to "Pantalla", "sobreexponer" to "Sobreexponer color", "anadir" to "Añadir",
            "superponer" to "Superponer", "luz_suave" to "Luz suave", "luz_fuerte" to "Luz fuerte", "diferencia" to "Diferencia", "exclusion" to "Exclusión",
            "tono" to "Tono", "saturacion" to "Saturación", "color" to "Color", "luminosidad" to "Luminosidad")
    }
}
