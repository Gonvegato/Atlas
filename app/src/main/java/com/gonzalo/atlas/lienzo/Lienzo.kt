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
    /** Lo que hay escrito de cada trozo del archivo de trazos (para no reescribir al guardar los que no han cambiado). */
    val trozos = HashMap<Int, Long>()

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
    /** Se llama cuando una operación de píxeles deja sin rehacer trazos dibujados de cerca (más finos que un píxel del mapa). */
    var alPerderDetalle: () -> Unit = {}
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

    /**
     * Los trazos guardados como datos y el fondo de sus teselas. Los trazos van en trozos de 256 (capas/<id>/trazos/<n>.bin):
     * al guardar solo se reescribe el trozo que cambia (el último, casi siempre), por muchos miles que haya.
     */
    private fun leeTrazos(c: CapaPx, cd: File) {
        // Los de la versión anterior (hechos con otro motor) se quedan solo como píxeles
        File(cd, "trazos.json").delete(); File(cd, "sin").deleteRecursively()
        val f = File(cd, "trazos.bin"); if (!f.exists()) return
        val l = ArrayList<TrazoV>()
        val desde = HashMap<Long, Long>()
        DataInputStream(InflaterInputStream(f.inputStream().buffered())).use { i ->
            val formato = i.readInt()
            if (formato != FORMATO && formato != FORMATO_2) return
            c.seq = i.readLong()
            repeat(i.readInt()) { desde[i.readLong()] = i.readLong() }
            // (Hasta la 2.2 iban todos aquí dentro, sin origen)
            if (formato == FORMATO_2) repeat(i.readInt()) { val s = i.readLong(); l += TrazoV(TrazoDatos.lee(i, false), s) }
        }
        File(cd, "trazos").listFiles()?.sortedBy { it.name.removeSuffix(".bin").toIntOrNull() ?: -1 }?.forEach { t ->
            val g = t.name.removeSuffix(".bin").toIntOrNull() ?: return@forEach
            runCatching {
                val suyos = ArrayList<TrazoV>()
                DataInputStream(InflaterInputStream(t.inputStream().buffered())).use { i ->
                    if (i.readInt() != FORMATO) return@use
                    repeat(i.readInt()) { val s = i.readLong(); suyos += TrazoV(TrazoDatos.lee(i), s) }
                }
                l += suyos; c.trozos[g] = firma(suyos)
            }
        }
        for ((k, d) in desde) {
            val b = BitmapFactory.decodeFile(File(cd, "fondo/${tx(k)}_${ty(k)}.png").path, opcionesBmp) ?: continue
            c.fondo[k] = Fondo(if (b.width == 1) TESELA_VACIA else b, d)
        }
        c.trazos = l.sortedBy { it.seq }
    }

    private fun firma(l: List<TrazoV>): Long = l.fold(l.size.toLong()) { a, t -> a * 1_000_003L + t.seq }

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
            if (c.trazos.size > TOPE_TRAZOS || c.trazos.sumOf { it.datos.peso.toLong() } > TOPE_PUNTOS) hornea(c, c.trazos.size / 4)
            val fd = File(cd, "fondo")
            if (c.fondo.isEmpty()) { fd.deleteRecursively(); File(cd, "trazos.bin").delete(); File(cd, "trazos").deleteRecursively(); c.trozos.clear(); c.fondoSucias.clear(); continue }
            fd.mkdirs()
            for (k in c.fondoSucias.toList()) {
                c.fondoSucias.remove(k)
                val f = File(fd, "${tx(k)}_${ty(k)}.png")
                val b = c.fondo[k]?.bmp
                if (b == null) f.delete() else f.outputStream().use { (if (b === TESELA_VACIA) Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) else b).compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            // Los trazos, en trozos de 256: solo se escriben los trozos que han cambiado
            val grupos = c.trazosVivos().groupBy { (it.seq shr 8).toInt() }
            val td = File(cd, "trazos"); td.mkdirs()
            for ((g, l) in grupos) {
                val fi = firma(l)
                if (c.trozos[g] == fi && File(td, "$g.bin").exists()) continue
                val tmp = File(td, "$g.bin.tmp")
                DataOutputStream(DeflaterOutputStream(tmp.outputStream().buffered(), Deflater(5))).use { o -> o.writeInt(FORMATO); o.writeInt(l.size); for (t in l) { o.writeLong(t.seq); t.datos.escribe(o) } }
                tmp.renameTo(File(td, "$g.bin")); c.trozos[g] = fi
            }
            td.listFiles()?.forEach { t -> if (t.name.removeSuffix(".bin").toIntOrNull() !in grupos.keys) t.delete() }
            c.trozos.keys.retainAll(grupos.keys)
            val tmp = File(cd, "trazos.bin.tmp")
            DataOutputStream(DeflaterOutputStream(tmp.outputStream().buffered(), Deflater(5))).use { o ->
                o.writeInt(FORMATO); o.writeLong(c.seq)
                val fs = c.fondo.entries.toList()
                o.writeInt(fs.size); for ((k, f) in fs) { o.writeLong(k); o.writeLong(f.desde) }
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
            val cv = Canvas(b)
            for (tr in l) if (tr.seq > f.desde && tr.datos.seVe(1f)) tr.datos.pintaTesela(cv, tx(k), ty(k), 1f)
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
                    for ((k, b) in m) { if (b === c.teselas[k]) continue; if (b == null) c.teselas.remove(k) else c.teselas[k] = b; c.sucias += k }
                    for ((k, f) in if (atrasDe) e.fondoAntes else e.fondoDespues) { if (f == null) c.fondo.remove(k) else c.fondo[k] = f; c.fondoSucias += k }
                    e.trazo?.let { tr -> c.trazos = if (atrasDe) c.trazos - tr else (c.trazos + tr).sortedBy { it.seq } }
                    c.trazosSucios = true
                }
                avisaTeselas(m.keys, c, e.trazo?.datos)
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

    private fun copia(c: CapaPx, info: CapaInfo = c.info) = CapaPx(info).also { n -> n.teselas.putAll(c.teselas); n.sucias.addAll(c.teselas.keys); n.fondo.putAll(c.fondo); n.fondoSucias.addAll(c.fondo.keys); n.trazos = c.trazos; n.seq = c.seq; n.trazosSucios = true; if (info.id == c.info.id) n.trozos.putAll(c.trozos) }

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

    /** [trazo]: si el cambio es un trazo guardado (de cerca solo hay que rehacer lo que él toca). */
    fun avisaTeselas(ks: Collection<Long>, c: CapaPx? = null, trazo: TrazoDatos? = null) {
        if (ks.isEmpty()) return
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (k in ks) { x0 = min(x0, tx(k)); y0 = min(y0, ty(k)); x1 = max(x1, tx(k)); y1 = max(y1, ty(k)) }
        version++
        if (c != null) nitidez.ensucia(c.info.id, ks, trazo)
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
                // (Un trazo dibujado muy de cerca puede no cambiar la tesela a tamaño real: se apunta igual, por su fondo)
                if (b !== viejo) { if (b == null) c.teselas.remove(k) else c.teselas[k] = b; c.sucias += k }
            }
            if (trazo != null) c.trazos = c.trazos + trazo
            c.trazosSucios = true
        }
        // Una operación de píxeles sobre teselas con trazos dibujados de muy cerca: ese detalle ya no se podrá rehacer
        if (trazo == null && fDespues.isNotEmpty() && c.trazos.any { t -> !t.datos.seVe(1f) && t.datos.cajaD.let { d -> fDespues.keys.any { k -> d[0] < (tx(k) + 1) * L && d[2] > tx(k) * L && d[1] < (ty(k) + 1) * L && d[3] > ty(k) * L } } }) alPerderDetalle()
        avisaTeselas(nuevas.keys, c, trazo?.datos)
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
            val h = Humedo(1f, d.def.colorVariable, d.ox, d.oy); h.sellaTodo(d.raster(1f), d.sellos())
            val nuevas = HashMap<Long, Bitmap?>()
            for ((k, l) in h.lonas) {
                if (!l.tocada || !dentro(tx(k), ty(k))) continue
                val viejo = c.teselas[k]
                val n = viejo?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
                d.funde(Canvas(n), l, 1f, l.ox.toDouble(), l.oy.toDouble())
                nuevas[k] = n
            }
            cambia(c, nombre, nuevas, if (d.def.repetible) TrazoV(d, ++c.seq) else null)?.let { partes += it }
        }
        return if (partes.isEmpty()) null else Edicion.Varias(nombre, partes)
    }

    // ---------------------------------------------------------------- trazos

    /**
     * Empieza un trazo en la capa activa (o null si no se puede pintar en ella). [nivel]: ver [Motor.nivel]. ([ox], [oy]): el
     * origen del trazo en el lienzo (donde se apoya el lápiz); sus puntos se dan medidos desde ahí.
     */
    fun empiezaTrazo(def: DefPincel, color: Int, tam: Float, opacidad: Float, modo: Int, u: Float, lapiz: Boolean, nivel: Int, ox: Double = 0.0, oy: Double = 0.0, espejos: List<DoubleArray> = TrazoVivo.SIN_ESPEJOS): TrazoVivo? {
        val c = capaActiva() ?: return null
        // Solo se pinta dentro de la sección activa y, si el lienzo tiene tamaño, dentro de él
        var rec = mapa.seccionActivaRect()
        if (!mapa.infinito) { val l = RectF(0f, 0f, mapa.ancho.toFloat(), mapa.alto.toFloat()); if (rec == null) rec = l else if (!rec.intersect(l)) rec.setEmpty() }
        val v = TrazoVivo(this, c, def, color, tam, opacidad, modo, (System.nanoTime() and 0x7fffffff).toInt(), u, lapiz, nivel, rec, seleccion?.takeIf { !it.vacia }, ox, oy, espejos)
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
        val h1 = if (v.humedo.escala == 1f) v.humedo else Humedo(1f, def.colorVariable, d.ox, d.oy).also { if (d.seVe(1f)) it.sellaTodo(d.raster(1f), d.sellos(v.muestra)) }
        val nuevas = HashMap<Long, Bitmap?>()
        for ((k, l) in h1.lonas) {
            if (!l.tocada || !dentro(tx(k), ty(k))) continue
            val viejo = c.teselas[k]
            if (viejo == null && (borra || d.atope)) continue
            val nuevo = viejo?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
            d.funde(Canvas(nuevo), l, 1f, l.ox.toDouble(), l.oy.toDouble())
            if (estaVacia(nuevo)) { if (viejo != null) nuevas[k] = null } else nuevas[k] = nuevo
        }
        // Dibujado muy de cerca, puede no dejar nada a tamaño real: se guarda igual, como datos, en las teselas que toca
        if (def.repetible) for (k in v.humedo.teselasBase()) {
            if (k in nuevas || !dentro(tx(k), ty(k))) continue
            val viejo = c.teselas[k]
            if (viejo == null && (borra || d.atope) && !c.fondo.containsKey(k)) continue
            nuevas[k] = viejo
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

    /** Lo que se usa como barrera para marcar territorios y seleccionar zonas: las capas de referencia que tengan dibujo (o, si no hay, todo lo visible). */
    private fun barreras(): List<CapaPx> = capas.filter { it.info.referencia && it.info.visible && (it.teselas.isNotEmpty() || it.fondo.isNotEmpty()) }.ifEmpty { capas.filter { it.info.visible } }

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

    private fun tieneAlgo(c: CapaPx) = c.teselas.isNotEmpty() || c.fondo.isNotEmpty()

    /**
     * Lo que mira el bote de pintura para saber hasta dónde llega una zona: las capas de referencia que tengan algo
     * dibujado y la capa en la que se pinta; si no hay capas de referencia con dibujo, todas las visibles.
     */
    private fun capasDelBote(activa: CapaPx): List<CapaPx> {
        val ref = capas.filter { it.info.referencia && it.info.visible && tieneAlgo(it) }
        return if (ref.isEmpty()) capas.filter { it.info.visible || it === activa } else capas.filter { it in ref || it === activa }
    }

    /**
     * Para el bote de pintura: qué píxeles son «del mismo color» que el punto tocado, mirando las [capas] fundidas, a 2^[e]
     * píxeles por píxel del lienzo (de cerca, con las teselas nítidas, que se hacen según se van tocando).
     */
    private inner class LectorColor(val capas: List<CapaPx>, val e: Int, x: Long, y: Long, flojo: Boolean = false) {
        private val cache = HashMap<Long, java.util.BitSet>()
        private val px = IntArray(N)
        private val tmp = Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
        private val cv = Canvas(tmp)
        private val pc = Paint()
        private val todo = java.util.BitSet(N).apply { set(0, N) }
        private val nada = java.util.BitSet(N)
        private var ultK = Long.MIN_VALUE; private var ult = nada
        // El color tocado (con la transparencia ya multiplicada) y cuánto puede separarse de él un píxel de la misma zona
        private val ra: Int; private val rr: Int; private val rg: Int; private val rb: Int; private val tol: Int
        init {
            val k = clave(Math.floorDiv(x, L.toLong()).toInt(), Math.floorDiv(y, L.toLong()).toInt())
            val c = if (funde(tx(k), ty(k))) px[Math.floorMod(y, L.toLong()).toInt() * L + Math.floorMod(x, L.toLong()).toInt()] else 0
            ra = c ushr 24; rr = ((c shr 16) and 255) * ra / 255; rg = ((c shr 8) and 255) * ra / 255; rb = (c and 255) * ra / 255
            // Tocando en vacío, hasta la mitad del borde suavizado de las líneas; sobre un color, solo lo que es casi igual
            // ([flojo]: para crecer bajo los bordes, también lo que está a medio camino entre ese color y otro)
            tol = if (ra < 24 || flojo) 110 else 48
        }
        /** Si se ha tocado sobre algo pintado (se va a recolorear) o en vacío. */
        val sobrePintado get() = ra >= 24

        private fun funde(tx: Int, ty: Int): Boolean {
            val s = 1 shl e; val kb = clave(Math.floorDiv(tx, s), Math.floorDiv(ty, s))
            var algo = false
            for (c in capas) {
                val b = (if (e == 0) c.teselas[kb] else if (c.teselas.containsKey(kb) || c.fondo.containsKey(kb)) nitidez.construye(c, e, tx, ty) else null) ?: continue
                if (!algo) { tmp.eraseColor(0); algo = true }
                pc.alpha = (c.info.opacidad * 255).toInt(); cv.drawBitmap(b, 0f, 0f, pc)
            }
            if (algo) tmp.getPixels(px, 0, L, 0, 0, L, L)
            return algo
        }

        private fun lee(tx: Int, ty: Int): java.util.BitSet {
            val vacioPasa = maxOf(ra, rr, rg, rb) <= tol
            if (!funde(tx, ty)) return if (vacioPasa) todo else nada
            val m = java.util.BitSet(N)
            for (i in 0 until N) {
                val c = px[i]; val a = c ushr 24
                if (a == 0) { if (vacioPasa) m.set(i); continue }
                if (kotlin.math.abs(a - ra) > tol) continue
                if (kotlin.math.abs(((c shr 16) and 255) * a / 255 - rr) > tol || kotlin.math.abs(((c shr 8) and 255) * a / 255 - rg) > tol || kotlin.math.abs((c and 255) * a / 255 - rb) > tol) continue
                m.set(i)
            }
            return m
        }

        fun igual(x: Long, y: Long): Boolean {
            val k = clave(Math.floorDiv(x, L.toLong()).toInt(), Math.floorDiv(y, L.toLong()).toInt())
            if (k != ultK) { ultK = k; ult = cache.getOrPut(k) { lee(tx(k), ty(k)) } }
            return ult[Math.floorMod(y, L.toLong()).toInt() * L + Math.floorMod(x, L.toLong()).toInt()]
        }
    }

    /** El bote de pintura a tamaño real (ver el otro [rellena]). */
    fun rellena(x: Int, y: Int, color: Int, opacidad: Float): Edicion? = rellena(x + 0.5, y + 0.5, color, opacidad, 1f, null).first

    /**
     * El bote de pintura: rellena de [color], en la capa activa, la zona que contiene (x, y): todo lo que es del mismo color
     * que ese punto hasta donde cambia (una línea, otro color). En vacío, rellena la zona cerrada por las líneas; sobre un
     * color, lo cambia por el nuevo. Mira las capas de referencia y la capa en la que se pinta ([capasDelBote]). La zona se
     * busca tal como se ve: a la escala de la pantalla ([zoom]), así que de cerca se ciñe a las líneas con toda su finura. Se guarda
     * como forma (no como píxeles): queda nítida a cualquier acercamiento y no estropea lo dibujado de cerca en esa parte
     * del mapa. De cerca, la zona tiene que caber en lo que se ve ([vis], con un margen): si se sale, no se rellena.
     * Devuelve lo hecho (para deshacer) o por qué no se ha podido.
     */
    fun rellena(x: Double, y: Double, color: Int, opacidad: Float, zoom: Float, vis: DoubleArray?): Pair<Edicion?, String?> {
        val c = capaActiva() ?: return null to "No hay capa en la que pintar"
        val e = max(0, -Motor.nivel(zoom)); val s = (1 shl e).toDouble()
        val px = floor(x * s).toLong(); val py = floor(y * s).toLong()
        // Hasta dónde puede llegar: la sección activa o el lienzo (si tiene tamaño)
        var rec = mapa.seccionActivaRect()
        if (!mapa.infinito) { val l = RectF(0f, 0f, mapa.ancho.toFloat(), mapa.alto.toFloat()); if (rec == null) rec = l else if (!rec.intersect(l)) rec.setEmpty() }
        val tope = rec?.let { longArrayOf(floor(it.left * s).toLong(), floor(it.top * s).toLong(), ceil(it.right * s).toLong(), ceil(it.bottom * s).toLong()) }
        // Dónde se busca: a tamaño real, como siempre (hasta 8.192 px alrededor); de cerca, lo que se ve y un margen
        val zona = if (e == 0) limitesRelleno(px.toInt(), py.toInt()).let { longArrayOf(it.left.toLong(), it.top.toLong(), it.right.toLong(), it.bottom.toLong()) } else {
            val v = vis ?: doubleArrayOf(x - 1536 / s, y - 1536 / s, x + 1536 / s, y + 1536 / s)
            val mx = (v[2] - v[0]) * 0.25; val my = (v[3] - v[1]) * 0.25
            val z = longArrayOf(max(floor((v[0] - mx) * s).toLong(), px - 3072), max(floor((v[1] - my) * s).toLong(), py - 3072), min(ceil((v[2] + mx) * s).toLong(), px + 3072), min(ceil((v[3] + my) * s).toLong(), py + 3072))
            if (tope != null) { z[0] = max(z[0], tope[0]); z[1] = max(z[1], tope[1]); z[2] = min(z[2], tope[2]); z[3] = min(z[3], tope[3]) }
            z
        }
        val w = (zona[2] - zona[0]).toInt(); val h = (zona[3] - zona[1]).toInt()
        if (w <= 0 || h <= 0 || px < zona[0] || py < zona[1] || px >= zona[2] || py >= zona[3]) return null to "Ahí no se puede pintar (está fuera del lienzo o de la sección)"
        val lec = LectorColor(capasDelBote(c), e, px, py)
        // Lo mismo, mirando solo la capa en la que se pinta (para saber qué es suyo y qué es una línea de otra capa)
        val suya = LectorColor(listOf(c), e, px, py, true)
        if (lec.sobrePintado && !suya.sobrePintado) return null to "Ahí hay una línea de otra capa: toca dentro de una zona"
        // Todo lo que se alcanza sin cruzar una línea
        val visto = java.util.BitSet(w * h)
        fun libre(ix: Int, iy: Int) = ix >= 0 && iy >= 0 && ix < w && iy < h && !visto[iy * w + ix] && lec.igual(zona[0] + ix, zona[1] + iy)
        var bx0 = w; var by0 = h; var bx1 = -1; var by1 = -1
        val cola = ArrayDeque<Long>()
        cola.addLast(((px - zona[0]) shl 32) or (py - zona[1]))
        while (cola.isNotEmpty()) {
            val q = cola.removeFirst(); val cx = (q shr 32).toInt(); val cy = q.toInt()
            if (!libre(cx, cy)) continue
            var l = cx; while (libre(l - 1, cy)) l--
            var r = cx; while (libre(r + 1, cy)) r++
            visto.set(cy * w + l, cy * w + r + 1)
            if (l < bx0) bx0 = l; if (r > bx1) bx1 = r; if (cy < by0) by0 = cy; if (cy > by1) by1 = cy
            for (ny in intArrayOf(cy - 1, cy + 1)) {
                var xx = l
                while (xx <= r) { if (libre(xx, ny)) { cola.addLast((xx.toLong() shl 32) or (ny.toLong() and 0xffffffffL)); while (xx <= r && libre(xx, ny)) xx++ } else xx++ }
            }
        }
        // De cerca: si llega al borde de lo que se mira (y ese borde no es el del lienzo), la zona no está cerrada o no cabe
        if (e > 0) {
            val sale = (bx0 == 0 && zona[0] != tope?.get(0)) || (by0 == 0 && zona[1] != tope?.get(1)) || (bx1 == w - 1 && zona[2] != tope?.get(2)) || (by1 == h - 1 && zona[3] != tope?.get(3))
            if (sale) return null to "La zona no está cerrada o no cabe en la pantalla: aléjate hasta verla entera y vuelve a tocar"
        }
        // Crece dos píxeles por debajo de las líneas de las otras capas (si no, queda un halo sin pintar junto a su borde
        // suavizado), pero sin comerse lo que hay pintado de otro color en esta misma capa
        repeat(2) {
            val antes = visto.clone() as java.util.BitSet
            val ax0 = max(0, bx0 - 1); val ay0 = max(0, by0 - 1); val ax1 = min(w - 1, bx1 + 1); val ay1 = min(h - 1, by1 + 1)
            for (iy in ay0..ay1) for (ix in ax0..ax1) {
                if (antes[iy * w + ix]) continue
                if (((ix > 0 && antes[iy * w + ix - 1]) || (ix < w - 1 && antes[iy * w + ix + 1]) || (iy > 0 && antes[(iy - 1) * w + ix]) || (iy < h - 1 && antes[(iy + 1) * w + ix])) && suya.igual(zona[0] + ix, zona[1] + iy)) visto.set(iy * w + ix)
            }
            bx0 = ax0; by0 = ay0; bx1 = ax1; by1 = ay1
        }
        // La zona, como forma: sus bordes medidos desde el punto tocado
        val contornos = Contornos.deMascara(visto, w, h, bx0, by0, bx1, by1).map { p -> FloatArray(p.size) { i -> (((if (i % 2 == 0) zona[0] else zona[1]) + p[i].toDouble()) / s - (if (i % 2 == 0) x else y)).toFloat() } }
        val forma = Seleccion(contornos)
        if (forma.vacia) return null to "No hay nada que rellenar ahí"
        val a = ((color ushr 24) * opacidad.coerceIn(0f, 1f) / 255f).coerceIn(0f, 1f)
        val d = TrazoDatos(DEF_RELLENO, color or (0xFF shl 24), 0f, a, TrazoDatos.PINTAR, c.info.bloqueoAlfa, emptyList(), 0, 1f, false, rec, seleccion?.takeIf { !it.vacia }, x, y, forma)
        return ponRelleno(c, d, "Relleno") to null
    }

    /** Pasa a la capa una zona rellena ([d], con su forma): sus teselas a tamaño real y, guardada como datos, para verla nítida de cerca. */
    private fun ponRelleno(c: CapaPx, d: TrazoDatos, nombre: String): Edicion? {
        val cd = d.cajaD
        if (cd[2] < cd[0] || cd[3] < cd[1]) return null
        val nuevas = HashMap<Long, Bitmap?>()
        val seVe = d.seVe(1f)
        for (ty in Math.floorDiv(floor(cd[1]).toInt(), L)..Math.floorDiv(ceil(cd[3]).toInt(), L)) for (tx in Math.floorDiv(floor(cd[0]).toInt(), L)..Math.floorDiv(ceil(cd[2]).toInt(), L)) {
            if (!dentro(tx, ty)) continue
            val k = clave(tx, ty); val viejo = c.teselas[k]
            if (viejo == null && (d.atope || d.modo == TrazoDatos.BORRAR) && !c.fondo.containsKey(k)) continue
            if (!seVe) { nuevas[k] = viejo; continue }
            val nuevo = viejo?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
            d.pintaTesela(Canvas(nuevo), tx, ty, 1f)
            // (Las teselas de la caja que la forma no llega a tocar se quedan como estaban)
            if (viejo == null) { if (!estaVacia(nuevo)) nuevas[k] = nuevo } else if (!nuevo.sameAs(viejo)) nuevas[k] = if (estaVacia(nuevo)) null else nuevo
        }
        if (nuevas.isEmpty()) return null
        return cambia(c, nombre, nuevas, TrazoV(d, ++c.seq))
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

    /**
     * Lo dibujado (las capas visibles, sin papel) como imagen recortada a lo que hay, con el lado mayor de hasta [ladoMax]
     * px: los trazos guardados se rehacen a ese tamaño, así que un dibujo pequeño (o hecho de cerca) sale con toda su finura
     * en vez de con los pocos píxeles que ocupa en el lienzo. Para guardar un dibujo del Taller en la biblioteca.
     */
    fun imagenNitida(ladoMax: Int = 1536): Bitmap? {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        contenido()?.let { r -> x0 = r.left.toDouble(); y0 = r.top.toDouble(); x1 = r.right.toDouble(); y1 = r.bottom.toDouble() }
        // (Lo dibujado muy de cerca no deja píxeles a tamaño real: se cuenta por sus trazos)
        for (c in capas) if (c.info.visible) for (t in c.trazosVivos()) {
            val d = t.datos.cajaD; if (t.datos.modo == TrazoDatos.BORRAR || d[2] < d[0] || d[3] < d[1]) continue
            x0 = min(x0, d[0]); y0 = min(y0, d[1]); x1 = max(x1, d[2]); y1 = max(y1, d[3])
        }
        if (x1 <= x0 || y1 <= y0) return null
        val lado = max(x1 - x0, y1 - y0)
        // Muy grande ya a tamaño real: tal cual
        if (lado > ladoMax) return recorta(Rect(floor(x0).toInt() - 4, floor(y0).toInt() - 4, ceil(x1).toInt() + 4, ceil(y1).toInt() + 4))
        var e = 0; while (e < Motor.MAS_CERCA && lado * (1 shl (e + 1)) <= ladoMax) e++
        val s = 1 shl e
        val px0 = floor(x0 * s).toLong() - 3; val py0 = floor(y0 * s).toLong() - 3
        val w = (ceil(x1 * s).toLong() + 3 - px0).toInt(); val h = (ceil(y1 * s).toLong() + 3 - py0).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val tx0 = Math.floorDiv(px0, L.toLong()).toInt(); val ty0 = Math.floorDiv(py0, L.toLong()).toInt(); val tx1 = Math.floorDiv(px0 + w - 1, L.toLong()).toInt(); val ty1 = Math.floorDiv(py0 + h - 1, L.toLong()).toInt()
        fun tesela(c: CapaPx, tx: Int, ty: Int): Bitmap? { val kb = clave(Math.floorDiv(tx, s), Math.floorDiv(ty, s)); return if (c.teselas.containsKey(kb) || c.fondo.containsKey(kb)) nitidez.construye(c, e, tx, ty) else null }
        val lista = capas.toList()
        val pDentro = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }; val pNada = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        for ((i, c) in lista.withIndex()) {
            if (!c.info.visible) continue
            val base = baseDe(lista, i); if (base != null && !base.info.visible) continue
            val g = cv.saveLayer(null, Paint().apply { alpha = (c.info.opacidad * 255).toInt(); blendMode = fusion(c.info.fusion) })
            for (ty in ty0..ty1) for (tx in tx0..tx1) {
                val x = (tx.toLong() * L - px0).toFloat(); val y = (ty.toLong() * L - py0).toFloat()
                tesela(c, tx, ty)?.let { cv.drawBitmap(it, x, y, null) }
                // Máscara de recorte: solo donde la capa de debajo tiene algo
                if (base != null) { val m = tesela(base, tx, ty); if (m != null) cv.drawBitmap(m, x, y, pDentro) else cv.drawRect(x, y, x + L, y + L, pNada) }
            }
            cv.restoreToCount(g)
        }
        // Recortada a lo que de verdad hay
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var ax0 = w; var ay0 = h; var ax1 = -1; var ay1 = -1
        for (y in 0 until h) { val o = y * w; for (x in 0 until w) if ((px[o + x] ushr 24) > 8) { if (x < ax0) ax0 = x; if (x > ax1) ax1 = x; if (y < ay0) ay0 = y; if (y > ay1) ay1 = y } }
        if (ax1 < 0) return null
        ax0 = max(0, ax0 - 3); ay0 = max(0, ay0 - 3); ax1 = min(w - 1, ax1 + 3); ay1 = min(h - 1, ay1 + 3)
        return Bitmap.createBitmap(b, ax0, ay0, ax1 - ax0 + 1, ay1 - ay0 + 1)
    }

    /** Si hay trazos guardados como datos en alguna capa visible (lo dibujado se puede rehacer más grande y nítido). */
    fun tieneTrazos() = capas.any { it.info.visible && it.fondo.isNotEmpty() && it.trazos.isNotEmpty() }

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
        /** El «pincel» de las zonas rellenas (no pinta: solo da nombre a la forma guardada). */
        val DEF_RELLENO: DefPincel by lazy { Catalogo2.SERIE[0].copy(id = "relleno", nombre = "Relleno", mezcla = 0f, azarTono = 0f, azarSat = 0f, azarBrillo = 0f) }
        const val FORMATO = 0x41544C33
        /** El de las versiones 2.0 a 2.2 (todos los trazos en un archivo, sin origen). */
        const val FORMATO_2 = 0x41544C32
        /** Cuántos trazos guarda una capa como datos antes de pasar los más antiguos a píxeles: 24.000, o 32 MB de puntos. */
        const val TOPE_TRAZOS = 24000
        const val TOPE_PUNTOS = 8_000_000L
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
