package com.gonzalo.atlas.editor

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gonzalo.atlas.lienzo.Edicion
import com.gonzalo.atlas.lienzo.Lienzo
import com.gonzalo.atlas.lienzo.Papel
import com.gonzalo.atlas.lienzo.Pinceles
import com.gonzalo.atlas.motor.Catalogo
import com.gonzalo.atlas.motor.Linea
import com.gonzalo.atlas.motor.Lugar
import com.gonzalo.atlas.motor.Mapa
import com.gonzalo.atlas.motor.Nombres
import com.gonzalo.atlas.motor.Rotulo
import com.gonzalo.atlas.motor.Sello
import com.gonzalo.atlas.motor.Territorio
import com.gonzalo.atlas.vista.Capas
import com.gonzalo.atlas.vista.Vista
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/** Qué hace el lápiz (o el dedo) sobre el mapa. */
enum class Herramienta(val nombre: String) {
    PINCEL("Pincel"), GOMA("Goma"), RELLENO("Relleno"), CUENTAGOTAS("Cuentagotas"), TERRITORIO("Territorios"),
    LUGAR("Lugares"), ROTULO("Rótulos"), RUTA("Rutas"), SELLO("Tus dibujos"), ELEGIR("Elegir"), REGLA("Regla"), MANO("Mover"), CALCO("Calco"),
}

/** Cómo se marca un territorio: tocando dentro de tus líneas, pintándolo o quitándolo. */
enum class ModoTerritorio { ZONA, PINCEL, QUITAR }

/**
 * Lo que pasa al editar un mapa: la herramienta y sus ajustes, lo vectorial (lugares, rótulos, rutas, territorios, leyenda),
 * deshacer y el guardado automático. Los píxeles los lleva el [Lienzo].
 */
class Editor(val mapa: Mapa, res: Resources, private val alGuardar: (Mapa) -> Unit = {}) {
    val lienzo = Lienzo(mapa, res).also { it.carga(enSegundoPlano = true) }

    var herramienta by mutableStateOf(Herramienta.PINCEL)
    var pincel by mutableStateOf("pluma")
    var goma by mutableStateOf("goma")
    var tamano by mutableStateOf(6f)
    var tamanoGoma by mutableStateOf(30f)
    var opacidad by mutableStateOf(1f)
    /** Estabilización del trazo (0: nada, 1: mucha), como la StreamLine de Procreate. */
    var estabilizacion by mutableStateOf(0.25f)
    /** Se llama al tocar un lugar o territorio con un mapa vinculado (con la herramienta Mover). */
    var alAbrirMapa: (String) -> Unit = {}
    var color by mutableIntStateOf(0xFF2A2118.toInt())
    val recientes = mutableStateListOf(0xFF2A2118.toInt(), 0xFF5C7894.toInt(), 0xFF6E8B3D.toInt(), 0xFFB5523B.toInt(), 0xFFD9C38A.toInt(), 0xFF8DB4BE.toInt())
    var territorio by mutableIntStateOf(0)
    var modoTerritorio by mutableStateOf(ModoTerritorio.ZONA)
    var radioTerritorio by mutableStateOf(30f)
    var tipoLugar by mutableStateOf("ciudad")
    var tipoRuta by mutableStateOf("camino")
    var recurso by mutableStateOf<String?>(null)
    var escalaSello by mutableStateOf(1f)
    var seleccion by mutableStateOf<Any?>(null)
    var capaActiva by mutableStateOf(lienzo.activa)
    var cambios by mutableIntStateOf(0)
    var puedeDeshacer by mutableStateOf(false)
    var puedeRehacer by mutableStateOf(false)
    var ocupado by mutableStateOf(false)
    var aviso by mutableStateOf("")
    /** La regla: distancia medida (km) entre los dos puntos. */
    var regla by mutableStateOf<FloatArray?>(null)

    private val principal = Handler(Looper.getMainLooper())
    @Volatile var centros: List<Capas.Centro> = emptyList(); private set
    @Volatile var superficies: LongArray = LongArray(256); private set
    var alCambiarVista: () -> Unit = {}

    init {
        lienzo.alCambiarHistorial = { principal.post { puedeDeshacer = lienzo.puedeDeshacer; puedeRehacer = lienzo.puedeRehacer; cambios++ } }
        lienzo.alCambiar += { _ -> programaGuardar(); if (lienzo.terrVersion != ultimaTerr) programaCentros() }
        mapa.alCambiarVector += { principal.post { cambios++; alCambiarVista() }; programaGuardar() }
        programaCentros(0)
    }

    fun tipoPincel() = Pinceles.de(if (herramienta == Herramienta.GOMA) goma else pincel)
    fun tamanoActual() = if (herramienta == Herramienta.GOMA) tamanoGoma else tamano

    fun ponColor(c: Int) {
        color = c
        recientes.remove(c); recientes.add(0, c); while (recientes.size > 12) recientes.removeAt(recientes.size - 1)
    }

    fun ponCapa(id: String) { lienzo.activa = id; capaActiva = id }

    // ---------------------------------------------------------------- trazos de tinta (Ink): llegan ya terminados

    fun trazoTerminado(st: androidx.ink.strokes.Stroke, tipo: com.gonzalo.atlas.lienzo.TipoPincel, op: Float, capa: String, despues: () -> Unit) {
        lienzo.enHilo {
            val e = lienzo.pintaTrazo(st, tipo, op, capa)
            e?.let { lienzo.apunta(it) }
            principal.post(despues)
        }
    }

    // ---------------------------------------------------------------- toques (coordenadas del lienzo)

    fun toque(x: Float, y: Float, sel: Any?) {
        when (herramienta) {
            Herramienta.RELLENO -> trabajo("Rellenando…") {
                val e = lienzo.rellena(x.toInt(), y.toInt(), color, opacidad)
                if (e == null) aviso("Ahí hay una línea: toca dentro de una zona") else lienzo.apunta(e)
            }
            Herramienta.CUENTAGOTAS -> lienzo.enHilo { val c = lienzo.colorEn(x.toInt(), y.toInt()); principal.post { ponColor(c or (0xFF shl 24)); herramienta = Herramienta.PINCEL } }
            Herramienta.TERRITORIO -> when (modoTerritorio) {
                ModoTerritorio.ZONA -> {
                    if (territorio == 0) { aviso("Elige o crea un territorio primero"); return }
                    val id = territorio
                    trabajo("Marcando el territorio…") {
                        val e = lienzo.territorioEnZona(x.toInt(), y.toInt(), id)
                        if (e == null) aviso("Ahí hay una línea: toca dentro de una zona") else lienzo.apunta(e)
                    }
                }
                ModoTerritorio.QUITAR -> trabajo("Quitando…") { lienzo.territorioEnZona(x.toInt(), y.toInt(), 0)?.let { lienzo.apunta(it) } }
                ModoTerritorio.PINCEL -> {}
            }
            Herramienta.LUGAR -> {
                if (sel is Lugar) { seleccion = sel; return }
                cambiaVector("Lugar") {
                    val l = Lugar(mapa.nuevoId("p"), Nombres.lugar(tipoLugar, Random(System.nanoTime())), tipoLugar, x, y, Catalogo.lugar(tipoLugar).tamano)
                    mapa.lugares += l; seleccion = l
                }
            }
            Herramienta.ROTULO -> {
                if (sel is Rotulo) { seleccion = sel; return }
                cambiaVector("Rótulo") { val r = Rotulo(mapa.nuevoId("t"), "Nombre", x, y, 40f); mapa.rotulos += r; seleccion = r }
            }
            Herramienta.SELLO -> {
                if (sel is Sello) { seleccion = sel; return }
                val r = recurso ?: run { aviso("Elige uno de tus dibujos (o guarda uno desde el Taller)"); return }
                cambiaVector("Dibujo") { val s = Sello(mapa.nuevoId("s"), r, x, y, escalaSello); mapa.sellos += s; seleccion = s }
            }
            Herramienta.ELEGIR, Herramienta.MANO -> {
                val s = sel ?: lienzo.territorioEn(x.toInt(), y.toInt()).takeIf { it != 0 }?.let { mapa.territorio(it) }
                val vinculado = when (s) { is Lugar -> s.mapa; is Territorio -> s.mapa; else -> "" }
                if (herramienta == Herramienta.MANO && vinculado.isNotBlank()) { alAbrirMapa(vinculado); return }
                seleccion = s
            }
            else -> {}
        }
        alCambiarVista()
    }

    // ---------------------------------------------------------------- arrastres que no son tinta: rutas, territorio a pincel, regla, mover

    private val bx = ArrayList<Float>(); private val by = ArrayList<Float>()
    var borrador: Linea? = null; private set
    private var arrastre: FloatArray? = null
    private var antesArrastre: String? = null

    fun empieza(x: Float, y: Float) {
        bx.clear(); by.clear(); bx += x; by += y
        when (herramienta) {
            Herramienta.RUTA -> borrador = Linea("borrador", tipoRuta, floatArrayOf(x), floatArrayOf(y), null)
            Herramienta.TERRITORIO -> borrador = Linea("borrador", "territorio", floatArrayOf(x), floatArrayOf(y), null)
            Herramienta.REGLA -> regla = floatArrayOf(x, y, x, y)
            Herramienta.ELEGIR -> { arrastre = floatArrayOf(x, y); antesArrastre = mapa.vectorJson().toString() }
            else -> {}
        }
    }

    fun mueve(x: Float, y: Float) {
        when (herramienta) {
            Herramienta.RUTA, Herramienta.TERRITORIO -> {
                if (hypot(x - bx.last(), y - by.last()) < 1.5f) return
                bx += x; by += y
                borrador = Linea("borrador", borrador?.tipo ?: "camino", bx.toFloatArray(), by.toFloatArray(), null)
            }
            Herramienta.REGLA -> regla = floatArrayOf(bx[0], by[0], x, y)
            Herramienta.ELEGIR -> {
                val a = arrastre ?: return
                val dx = x - a[0]; val dy = y - a[1]
                when (val s = seleccion) {
                    is Lugar -> { s.x += dx; s.y += dy }
                    is Rotulo -> { s.x += dx; s.y += dy }
                    is Sello -> { s.x += dx; s.y += dy }
                    is Linea -> for (i in 0 until s.n) { s.xs[i] += dx; s.ys[i] += dy }
                    is Territorio -> { if (s.lx.isNaN()) centros.firstOrNull { it.id == s.id && !it.esRegion }?.let { s.lx = it.x; s.ly = it.y }; s.lx += dx; s.ly += dy }
                    is com.gonzalo.atlas.motor.Leyenda -> { s.x += dx; s.y += dy }
                }
                arrastre = floatArrayOf(x, y)
            }
            else -> {}
        }
        alCambiarVista()
    }

    fun termina() {
        when (herramienta) {
            Herramienta.RUTA -> {
                val b = borrador ?: return; borrador = null
                if (b.n >= 2) cambiaVector("Ruta") { val l = simplifica(b, 0.8f); val r = Linea(mapa.nuevoId("r"), tipoRuta, l.xs, l.ys, null); mapa.rutas += r; seleccion = r }
            }
            Herramienta.TERRITORIO -> {
                val b = borrador ?: return; borrador = null
                if (modoTerritorio == ModoTerritorio.PINCEL || modoTerritorio == ModoTerritorio.QUITAR) {
                    val id = if (modoTerritorio == ModoTerritorio.QUITAR) 0 else territorio
                    if (id == 0 && modoTerritorio == ModoTerritorio.PINCEL) { aviso("Elige o crea un territorio primero"); return }
                    val r = radioTerritorio
                    trabajo("Pintando el territorio…") { lienzo.territorioPincel(b.xs, b.ys, r, id)?.let { lienzo.apunta(it) } }
                }
            }
            Herramienta.ELEGIR -> {
                if (arrastre != null && seleccion != null) antesArrastre?.let { lienzo.apunta(Edicion.Vector("Mover", it, mapa.vectorJson().toString())); mapa.avisaVector() }
                arrastre = null
            }
            else -> {}
        }
        alCambiarVista()
    }

    fun arrastra(): Boolean = when (herramienta) {
        Herramienta.RUTA, Herramienta.REGLA -> true
        Herramienta.TERRITORIO -> modoTerritorio != ModoTerritorio.ZONA
        Herramienta.ELEGIR -> seleccion != null
        else -> false
    }

    // ---------------------------------------------------------------- lo vectorial con deshacer

    fun cambiaVector(nombre: String, f: () -> Unit) {
        val antes = mapa.vectorJson().toString()
        f()
        lienzo.apunta(Edicion.Vector(nombre, antes, mapa.vectorJson().toString()))
        mapa.avisaVector()
    }

    fun borraSeleccion() {
        val s = seleccion ?: return
        if (s is Territorio) { borraTerritorio(s); return }
        cambiaVector("Borrar") {
            when (s) { is Lugar -> mapa.lugares.remove(s); is Rotulo -> mapa.rotulos.remove(s); is Sello -> mapa.sellos.remove(s); is Linea -> mapa.rutas.remove(s); is com.gonzalo.atlas.motor.Leyenda -> s.visible = false }
            seleccion = null
        }
    }

    fun nuevoTerritorio(tipo: String, padre: Int): Territorio? {
        val id = mapa.idLibre()
        if (mapa.territorio(id) != null) { aviso("Ya hay 255 territorios"); return null }
        var t: Territorio? = null
        cambiaVector("Nuevo territorio") {
            t = Territorio(id, Nombres.reino(Random(System.nanoTime())), tipo, Catalogo.COLORES[(id - 1) % Catalogo.COLORES.size], padre)
            mapa.territorios += t!!
        }
        territorio = id
        lienzo.alCambiar.forEach { it(RectF(-1e9f, -1e9f, 1e9f, 1e9f)) }
        return t
    }

    fun borraTerritorio(t: Territorio) {
        val antes = mapa.vectorJson().toString()
        lienzo.enHilo {
            val e = lienzo.borraTerritorio(t.id)
            mapa.territorios.remove(t)
            mapa.territorios.filter { it.padre == t.id }.forEach { it.padre = t.padre }
            val v = Edicion.Vector("Borrar territorio", antes, mapa.vectorJson().toString())
            lienzo.apunta(if (e != null) Edicion.Varias("Borrar territorio", listOf(e, v)) else v)
            principal.post { if (territorio == t.id) territorio = 0; seleccion = null; mapa.avisaVector() }
        }
    }

    fun deshacer() { seleccion = null; lienzo.deshacer() }
    fun rehacer() { seleccion = null; lienzo.rehacer() }

    private fun aviso(t: String) { principal.post { aviso = t } }

    private fun trabajo(t: String, f: () -> Unit) {
        ocupado = true
        lienzo.enHilo { try { f() } finally { principal.post { ocupado = false } } }
    }

    // ---------------------------------------------------------------- nombres de los territorios en su sitio

    @Volatile private var ultimaTerr = -1
    private val calcCentros = Runnable {
        lienzo.enHilo {
            ultimaTerr = lienzo.terrVersion
            val c = lienzo.cuentaTerritorios()
            // Lo que ocupa cada territorio, con lo de sus hijos sumado (un imperio = sus reinos)
            val total = c.pixeles.copyOf(); val sx = c.sx.copyOf(); val sy = c.sy.copyOf()
            for (t in mapa.territorios) for (a in mapa.linaje(t.id).drop(1)) { total[a.id] += c.pixeles[t.id]; sx[a.id] += c.sx[t.id]; sy[a.id] += c.sy[t.id] }
            superficies = total
            centros = mapa.territorios.filter { total[it.id] > 0 }.map { t ->
                val nivel = mapa.linaje(t.id).size - 1
                Capas.Centro(t.id, (sx[t.id] / total[t.id]).toFloat(), (sy[t.id] / total[t.id]).toFloat(), total[t.id], nivel)
            }
            principal.post { cambios++; alCambiarVista() }
        }
    }
    fun programaCentros(espera: Long = 600) { principal.removeCallbacks(calcCentros); principal.postDelayed(calcCentros, espera) }

    // ---------------------------------------------------------------- guardar

    private val guardar = Runnable { guardaYa() }
    private fun programaGuardar() { principal.removeCallbacks(guardar); principal.postDelayed(guardar, 3000) }

    fun guardaYa(despues: () -> Unit = {}) {
        principal.removeCallbacks(guardar)
        lienzo.enHilo {
            runCatching { lienzo.guarda() }
            runCatching { miniatura() }
            principal.post { alGuardar(mapa); despues() }
        }
    }

    private var miniaturaVersion = -1
    private fun miniatura() {
        val dir = mapa.dir ?: return
        val v = lienzo.version + mapa.vectorVersion * 7919
        if (v == miniaturaVersion && File(dir, "miniatura.png").exists()) return
        miniaturaVersion = v
        val b = Lamina.pinta(this, 512, null)
        File(dir, "miniatura.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun cierra() { guardaYa { lienzo.cierra() } }

    companion object {
        /** Quita puntos que casi no cambian la línea (Ramer-Douglas-Peucker). */
        fun simplifica(l: Linea, tol: Float): Linea {
            if (l.n < 3) return l
            val keep = BooleanArray(l.n); keep[0] = true; keep[l.n - 1] = true
            fun rdp(a: Int, b: Int) {
                var md = 0f; var mi = -1
                for (i in a + 1 until b) { val d = Capas.distSeg(l.xs[i], l.ys[i], l.xs[a], l.ys[a], l.xs[b], l.ys[b]); if (d > md) { md = d; mi = i } }
                if (md > tol && mi > 0) { keep[mi] = true; rdp(a, mi); rdp(mi, b) }
            }
            rdp(0, l.n - 1)
            val idx = (0 until l.n).filter { keep[it] }
            return Linea(l.id, l.tipo, FloatArray(idx.size) { l.xs[idx[it]] }, FloatArray(idx.size) { l.ys[idx[it]] }, null, l.nombre)
        }
    }
}

/** El mapa entero en una imagen: miniatura, lámina para los escritos, exportar. */
object Lamina {
    /** [capas] null: sin lo vectorial (para la miniatura basta el dibujo con los territorios). */
    fun pinta(ed: Editor, ladoMayor: Int, capas: Capas?, recurso: (String) -> Bitmap? = { null }): Bitmap {
        val lz = ed.lienzo; val m = ed.mapa
        val l = lz.limites()
        val w = l.width().toFloat(); val h = l.height().toFloat()
        val k = ladoMayor / max(w, h)
        val bw = max(1, (w * k).toInt()); val bh = max(1, (h * k).toInt())
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.save()
        c.scale(k, k); c.translate(-l.left.toFloat(), -l.top.toFloat())
        Papel.pinta(c, m, RectF(l))
        lz.componeZona(c, Rect(l))
        if (m.verTerritorios) {
            val velo = com.gonzalo.atlas.lienzo.VeloTerritorios(lz)
            val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            for (key in lz.terr.keys) velo.tesela(key)?.let { c.drawBitmap(it, Lienzo.tx(key) * Lienzo.L.toFloat(), Lienzo.ty(key) * Lienzo.L.toFloat(), p) }
        }
        c.restore()
        if (capas != null) {
            val v = Vista(l.exactCenterX(), l.exactCenterY(), k, 0f, bw, bh); v.actualiza()
            capas.dibuja(c, m, v, ed.centros, ed.superficies, recurso, Capas.Opciones(impresion = true))
        }
        return b
    }
}
