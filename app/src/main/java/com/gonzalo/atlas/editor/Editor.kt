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
    PINCEL("Pintar"), DIFUMINAR("Difuminar"), GOMA("Borrar"), TRANSFORMAR("Transformar"), RELLENO("Relleno"), CUENTAGOTAS("Cuentagotas"), TERRITORIO("Territorios"),
    LUGAR("Lugares"), ROTULO("Rótulos"), RUTA("Rutas"), SELLO("Tus dibujos"), ELEGIR("Elegir"), REGLA("Regla"), MANO("Mover"), CALCO("Calco"), SECCION("Secciones"),
    SELECCION("Selección"), LICUAR("Licuar"),
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
    var pincel by mutableStateOf("lapiz6b")
    /** El pincel de cada herramienta (como en Procreate: pintar, difuminar y borrar eligen pincel por separado). */
    var goma by mutableStateOf("tecnico")
    var pincelDifuminar by mutableStateOf("redondo")
    var tamanoDifuminar by mutableStateOf(40f)
    var fuerzaDifuminar by mutableStateOf(0.6f)
    /** Transformando la capa activa: la matriz que se le va a aplicar (null si no se está transformando). */
    var transformacion by mutableStateOf<android.graphics.Matrix?>(null)
    var tamano by mutableStateOf(6f)
    var opacidadGoma by mutableStateOf(1f)
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
        lienzo.alPerderDetalle = { aviso("Ojo: esa operación trabaja con los píxeles del mapa y ha dejado como píxeles lo dibujado de cerca en esa zona (el detalle más fino se pierde). Deshacer lo recupera.") }
        lienzo.alCambiar += { _ -> programaGuardar(); if (lienzo.terrVersion != ultimaTerr) programaCentros() }
        mapa.alCambiarVector += { principal.post { cambios++; alCambiarVista() }; programaGuardar() }
        programaCentros(0)
    }

    fun defPincel() = Pinceles.def(when (herramienta) { Herramienta.GOMA -> goma; Herramienta.DIFUMINAR -> pincelDifuminar; else -> pincel })
    fun tamanoActual() = when (herramienta) { Herramienta.GOMA -> tamanoGoma; Herramienta.DIFUMINAR -> tamanoDifuminar; Herramienta.LICUAR -> tamanoLicuar; else -> tamano }
    fun opacidadActual() = when (herramienta) { Herramienta.GOMA -> opacidadGoma; Herramienta.DIFUMINAR -> fuerzaDifuminar; Herramienta.LICUAR -> fuerzaLicuar; else -> opacidad }
    fun ponTamano(v: Float) { when (herramienta) { Herramienta.GOMA -> tamanoGoma = v; Herramienta.DIFUMINAR -> tamanoDifuminar = v; Herramienta.LICUAR -> tamanoLicuar = v; else -> tamano = v }; muestraTam = System.currentTimeMillis() + 700; alCambiarVista() }
    fun ponOpacidad(v: Float) { when (herramienta) { Herramienta.GOMA -> opacidadGoma = v; Herramienta.DIFUMINAR -> fuerzaDifuminar = v; Herramienta.LICUAR -> fuerzaLicuar = v; else -> opacidad = v } }
    /** Hasta cuándo se enseña en el lienzo el tamaño del pincel (mientras se mueve su barra). */
    @Volatile var muestraTam = 0L

    /** Elige un pincel para la herramienta actual (pintar, difuminar o borrar), con su tamaño y su estabilización. */
    fun eligePincel(id: String) {
        val d = Pinceles.def(id)
        when (herramienta) {
            Herramienta.GOMA -> { goma = id; tamanoGoma = d.tamano * 3f }
            Herramienta.DIFUMINAR -> { pincelDifuminar = id; tamanoDifuminar = d.tamano * 2f }
            else -> { herramienta = Herramienta.PINCEL; pincel = id; tamano = d.tamano; opacidad = d.opacidad }
        }
        estabilizacion = d.estabilizacion
    }

    // ---------------------------------------------------------------- difuminar (trazo en la CPU)

    private var difumino: com.gonzalo.atlas.lienzo.Difumino? = null
    fun empiezaDifuminar() { val c = lienzo.capaActiva() ?: return; difumino = com.gonzalo.atlas.lienzo.Difumino(lienzo, c, kotlin.math.max(1f, enMapa(tamanoDifuminar) / 2f), fuerzaDifuminar) }
    fun difumina(xs: FloatArray, ys: FloatArray, ps: FloatArray, alPintar: (android.graphics.RectF) -> Unit) {
        val d = difumino ?: return
        lienzo.enHilo { var z: android.graphics.RectF? = null; for (i in xs.indices) d.punto(xs[i], ys[i], ps[i])?.let { r -> if (z == null) z = r else z!!.union(r) }; z?.let { principal.post { alPintar(it) } } }
    }
    fun terminaDifuminar() { val d = difumino ?: return; difumino = null; lienzo.enHilo { d.termina()?.let { lienzo.apunta(it) } } }

    // ---------------------------------------------------------------- licuar

    var modoLicuar by mutableStateOf("empujar")
    var tamanoLicuar by mutableStateOf(120f)
    var fuerzaLicuar by mutableStateOf(0.7f)
    private var licuar: com.gonzalo.atlas.lienzo.Licuar? = null
    fun empiezaLicuar() { val c = lienzo.capaActiva() ?: return; licuar = com.gonzalo.atlas.lienzo.Licuar(lienzo, c, modoLicuar, kotlin.math.max(2f, enMapa(tamanoLicuar) / 2f), fuerzaLicuar) }
    fun licua(xs: FloatArray, ys: FloatArray, ps: FloatArray, alPintar: (android.graphics.RectF) -> Unit) {
        val d = licuar ?: return
        lienzo.enHilo { var z: android.graphics.RectF? = null; for (i in xs.indices) d.punto(xs[i], ys[i], ps[i])?.let { r -> if (z == null) z = r else z!!.union(r) }; z?.let { principal.post { alPintar(it) } } }
    }
    fun terminaLicuar() { val d = licuar ?: return; licuar = null; lienzo.enHilo { d.termina()?.let { lienzo.apunta(it) } } }

    // ---------------------------------------------------------------- selección

    /** La selección del lienzo (la zona en la que se pinta, borra, rellena y ajusta). */
    var marco by mutableStateOf<com.gonzalo.atlas.lienzo.Seleccion?>(null); private set
    /** Cómo se selecciona: "lazo", "rect" o "elipse"; y si lo nuevo sustituye, se suma o se resta. */
    var formaSeleccion by mutableStateOf("lazo")
    var operSeleccion by mutableStateOf("nueva")
    /** Lo que se está rodeando (para verlo mientras se hace): la forma y sus puntos. */
    var marcoBorrador by mutableStateOf<Pair<String, FloatArray>?>(null)

    fun ponMarco(s: com.gonzalo.atlas.lienzo.Seleccion?) { val v = s?.takeIf { !it.vacia }; lienzo.seleccion = v; marco = v; alCambiarVista() }
    fun invierteMarco() { marco?.let { ponMarco(it.invertir()) } }
    fun borraMarco() { val c = lienzo.capaActiva() ?: return; trabajo("Borrando…") { lienzo.borraSeleccion(c)?.let { lienzo.apunta(it) } } }
    fun rellenaMarco() { val c = lienzo.capaActiva() ?: return; val m = marco ?: return; val col = color; trabajo("Rellenando…") { lienzo.rellenaForma(c, m.path, col, "Rellenar lo seleccionado")?.let { lienzo.apunta(it) } } }
    /** Lo seleccionado pasa a una capa nueva; con [cortar] se quita de la suya y, con [mover], queda lista para transformarla. */
    fun copiaMarco(cortar: Boolean, mover: Boolean = false) {
        val c = lienzo.capaActiva() ?: return
        lienzo.copiaSeleccion(c, cortar) { id -> principal.post { capaActiva = id; ponMarco(null); if (mover) herramienta = Herramienta.TRANSFORMAR } }
    }

    // ---------------------------------------------------------------- simetría

    /** Pone (o quita, con null) la guía de simetría, con su centro en ([x], [y]). */
    fun ponSimetria(tipo: String?, x: Double = 0.0, y: Double = 0.0, n: Int = 6) {
        mapa.simetria = if (tipo == null) null else com.gonzalo.atlas.motor.Simetria(tipo, x, y, n)
        mapa.avisaVector()
    }

    // ---------------------------------------------------------------- ajustes y transformar

    /** El ajuste de color que se está probando en la capa activa (se ve al momento; se aplica con [aplicaAjuste]). */
    var vistaAjuste by mutableStateOf<android.graphics.ColorMatrix?>(null)
    fun aplicaAjuste(nombre: String) { val cm = vistaAjuste ?: return; val c = lienzo.capaActiva() ?: return; trabajo("Ajustando…") { com.gonzalo.atlas.lienzo.Ajustes.matriz(lienzo, c, cm, nombre)?.let { lienzo.apunta(it) }; principal.post { vistaAjuste = null } } }
    fun ajusta(texto: String, f: (Lienzo, com.gonzalo.atlas.lienzo.CapaPx) -> Edicion?) { val c = lienzo.capaActiva() ?: return; trabajo(texto) { f(lienzo, c)?.let { lienzo.apunta(it) } } }
    fun ajustaColor(tono: Float, sat: Float, brillo: Float) = lienzo.capaActiva()?.let { c -> trabajo("Ajustando…") { com.gonzalo.atlas.lienzo.Ajustes.color(lienzo, c, tono, sat, brillo)?.let { lienzo.apunta(it) } } }
    fun desenfoca(r: Int) = lienzo.capaActiva()?.let { c -> trabajo("Desenfocando…") { com.gonzalo.atlas.lienzo.Ajustes.desenfoca(lienzo, c, r)?.let { lienzo.apunta(it) } } }
    fun aplicaTransformacion() {
        val m = transformacion ?: return; val c = lienzo.capaActiva() ?: return
        transformacion = null
        trabajo("Transformando…") { com.gonzalo.atlas.lienzo.Ajustes.transforma(lienzo, c, m)?.let { lienzo.apunta(it) } }
        herramienta = Herramienta.PINCEL
    }

    // ---------------------------------------------------------------- continentes generados

    /** La forma que se está probando, dónde iría (se ve en el lienzo antes de ponerla). */
    var vistaPrevia by mutableStateOf<Pair<Bitmap, RectF>?>(null)

    /** El relieve de una tierra generada, para mirarlo en cualquier punto del lienzo. */
    private class Relieve(val rect: RectF, val campo: FloatArray, val w: Int, val h: Int) {
        /** El relieve en (x, y) del lienzo: positivo, tierra; negativo, mar (fuera del recuadro, mar). */
        fun en(x: Float, y: Float): Float {
            if (x < rect.left || y < rect.top || x > rect.right || y > rect.bottom) return -1f
            val gx = ((x - rect.left) * w / rect.width() - 0.5f).coerceIn(0f, w - 1.001f); val gy = ((y - rect.top) * h / rect.height() - 0.5f).coerceIn(0f, h - 1.001f)
            val ix = gx.toInt(); val iy = gy.toInt(); val fx = gx - ix; val fy = gy - iy; val o = iy * w + ix
            return (campo[o] * (1 - fx) + campo[o + 1] * fx) * (1 - fy) + (campo[o + w] * (1 - fx) + campo[o + w + 1] * fx) * fy
        }
        /** La costa, como líneas de puntos del lienzo (x, y) y si cada una es cerrada. */
        fun costas(): List<Pair<FloatArray, Boolean>> {
            val kx = rect.width() / w; val ky = rect.height() / h
            return com.gonzalo.atlas.motor.Continente.costas(campo, w, h).map { com.gonzalo.atlas.motor.Continente.redondea(it, 2) }.map { l ->
                val n = com.gonzalo.atlas.motor.Continente.puntos(l)
                FloatArray(n * 2) { i -> if (i % 2 == 0) rect.left + (l[i] + 0.5f) * kx else rect.top + (l[i] + 0.5f) * ky } to !com.gonzalo.atlas.motor.Continente.esAbierta(l)
            }
        }
        val grosor get() = (max(rect.width(), rect.height()) / 700f).coerceIn(1.6f, 6f)
    }

    /** Los tramos seguidos de la línea ([p]: x, y; [cerrada]) en los que [vale] el punto, cada uno con un punto de más por cada lado ([solapa]) o de menos. */
    private fun tramos(p: FloatArray, cerrada: Boolean, solapa: Boolean, vale: (Float, Float) -> Boolean): List<FloatArray> {
        val n = p.size / 2; if (n < 2) return emptyList()
        val ok = BooleanArray(n) { vale(p[it * 2], p[it * 2 + 1]) }
        if (ok.all { it }) return listOf(if (cerrada) p + floatArrayOf(p[0], p[1]) else p)
        if (ok.none { it }) return emptyList()
        // (En una línea cerrada se empieza a contar en un punto que no vale, para no partir un tramo por la mitad)
        val ini = if (cerrada) ok.indexOfFirst { !it } else 0
        val total = if (cerrada) n else n
        val out = ArrayList<FloatArray>()
        var i = 0
        while (i < total) {
            if (!ok[(ini + i) % n]) { i++; continue }
            var j = i; while (j + 1 < total && ok[(ini + j + 1) % n]) j++
            // El tramo va de i a j; con [solapa] se alarga un punto por cada lado (para que no quede hueco donde se juntan dos costas)
            var a = i; var b = j
            if (solapa) { if (cerrada || a > 0) a--; if (cerrada || b < total - 1) b++ } else { a++; b-- }
            if (b - a >= 1) out += FloatArray((b - a + 1) * 2) { q -> val k = Math.floorMod(ini + a + q / 2, n); p[k * 2 + q % 2] }
            i = j + 1
        }
        return out
    }

    private fun trazoDe(p: FloatArray, def: com.gonzalo.atlas.lienzo.DefPincel, color: Int, tam: Float, modo: Int, semilla: Int): com.gonzalo.atlas.lienzo.TrazoDatos {
        val m = p.size / 2
        val pts = FloatArray(m * 6)
        for (i in 0 until m) { val o = i * 6; pts[o] = p[i * 2]; pts[o + 1] = p[i * 2 + 1]; pts[o + 2] = 1f; pts[o + 3] = 0.3f; pts[o + 4] = 0f; pts[o + 5] = i * 6f }
        return com.gonzalo.atlas.lienzo.TrazoDatos(def, color, tam, 1f, modo, false, listOf(pts), semilla, 1f, true, null, null)
    }

    /**
     * Genera una tierra (un continente, un archipiélago…) dentro del recuadro [rect] del lienzo: la costa, en la capa de
     * líneas; y, si se pide, la tierra y el mar de color en la de color. [rect] mide [anchoKm] de ancho: si el mapa aún
     * no tiene escala, queda fijada por el tamaño dado ([anchoKm]). [unir]: si toca a otras tierras generadas antes, se
     * funden en una sola: se borra la costa de las otras que queda tierra adentro, la nueva no se dibuja donde ya era
     * tierra y el mar no tapa la tierra que había.
     */
    fun ponContinente(p: com.gonzalo.atlas.motor.FormaTierra, rect: RectF, anchoKm: Double, tinta: Int, tierra: Int?, mar: Int?, seccion: Boolean, unir: Boolean = true) {
        vistaPrevia = null
        trabajo("Dibujando la costa…") {
            val asp = rect.width() / rect.height()
            val g = (max(rect.width(), rect.height()) / 2.5f).toInt().coerceIn(256, 900)
            val w = if (asp >= 1f) g else max(64, (g * asp).toInt()); val h = if (asp >= 1f) max(64, (g / asp).toInt()) else g
            val nueva = Relieve(RectF(rect), com.gonzalo.atlas.motor.Continente.campo(p, w, h), w, h)
            // Las tierras de antes que tocan a esta
            val viejas = if (unir) mapa.tierras.filter { RectF.intersects(it.rect, rect) && it.gw > 1 && it.gh > 1 }.map { Relieve(it.rect, com.gonzalo.atlas.motor.Continente.campo(it.forma, it.gw, it.gh), it.gw, it.gh) } else emptyList()
            fun eraTierra(x: Float, y: Float): Boolean { for (v in viejas) if (v.en(x, y) > 0f) return true; return false }
            val capaLineas = lienzo.capas.firstOrNull { it.info.referencia } ?: lienzo.capaActiva() ?: return@trabajo
            val capaColor = lienzo.capas.firstOrNull { !it.info.referencia } ?: capaLineas
            val partes = ArrayList<Edicion>()
            if (tierra != null || mar != null) pintaCampo(capaColor, rect, nueva.campo, w, h, tierra ?: 0, mar ?: 0, if (viejas.isEmpty()) null else ::eraTierra)?.let { partes += it }
            val def = Pinceles.def("costa").copy(afiladoInicio = 0f, afiladoFin = 0f, presionTamano = 0f, estabilizacion = 0f)
            val trazos = ArrayList<com.gonzalo.atlas.lienzo.TrazoDatos>()
            // De las costas de antes, se borra lo que ahora queda dentro de la tierra nueva
            if (viejas.isNotEmpty()) {
                val goma = Pinceles.def("rotulador").copy(afiladoInicio = 0f, afiladoFin = 0f, presionTamano = 0f, presionOpacidad = 0f, estabilizacion = 0f, grano = "", dureza = 1f)
                for (v in viejas) for ((l, cerrada) in v.costas()) for (t in tramos(l, cerrada, false) { x, y -> nueva.en(x, y) > 0f })
                    trazos += trazoDe(t, goma, 0, v.grosor * 2.6f + 2f, com.gonzalo.atlas.lienzo.TrazoDatos.BORRAR, 0)
            }
            // La costa nueva: entera o, uniéndose a otras, solo donde no era ya tierra
            var k = 0
            for ((l, cerrada) in nueva.costas()) {
                val ts = if (viejas.isEmpty()) listOf(if (cerrada) l + floatArrayOf(l[0], l[1]) else l) else tramos(l, cerrada, true) { x, y -> !eraTierra(x, y) }
                for (t in ts) trazos += trazoDe(t, def, tinta, nueva.grosor, com.gonzalo.atlas.lienzo.TrazoDatos.PINTAR, 1000 + k++)
            }
            lienzo.ponTrazos(capaLineas, trazos, "Costa")?.let { partes += it }
            val antes = mapa.vectorJson().toString()
            if (!mapa.escalaPuesta && anchoKm > 0.0) { mapa.kmPorPx = (anchoKm / rect.width()).toFloat(); mapa.escalaPuesta = true }
            mapa.tierras += com.gonzalo.atlas.motor.Tierra(rect.left, rect.top, rect.width(), rect.height(), w, h, com.gonzalo.atlas.motor.FormaTierra(p.semilla, p.tipo, p.tierra, p.rugosidad, p.islas))
            if (seccion) mapa.secciones += com.gonzalo.atlas.motor.Seccion(mapa.nuevoId("s"), "Continente", rect.left, rect.top, rect.width(), rect.height())
            partes += Edicion.Vector("Tierra", antes, mapa.vectorJson().toString())
            lienzo.apunta(Edicion.Varias("Continente", partes))
            mapa.avisaVector()
            aviso(if (viejas.isEmpty()) "Continente dibujado: la costa está en «${capaLineas.info.nombre}»" else "Continente dibujado y unido a la tierra que había")
        }
    }

    /** Si en ese recuadro hay tierras generadas antes (para ofrecer unirlas). */
    fun hayTierraEn(rect: RectF) = mapa.tierras.any { RectF.intersects(it.rect, rect) }

    /**
     * Pinta la tierra y el mar del relieve [campo] (de [w] × [h] muestras) en el recuadro: un color donde es tierra y otro
     * donde es mar (0: nada). [eraTierra]: donde ya había tierra de otra generada antes, el mar no se pinta.
     */
    private fun pintaCampo(c: com.gonzalo.atlas.lienzo.CapaPx, rect: RectF, campo: FloatArray, w: Int, h: Int, tierra: Int, mar: Int, eraTierra: ((Float, Float) -> Boolean)? = null): Edicion? {
        val L = Lienzo.L
        val nuevas = HashMap<Long, Bitmap?>()
        val x0 = kotlin.math.floor(rect.left).toInt(); val y0 = kotlin.math.floor(rect.top).toInt(); val x1 = kotlin.math.ceil(rect.right).toInt(); val y1 = kotlin.math.ceil(rect.bottom).toInt()
        val kx = w / rect.width(); val ky = h / rect.height()
        for (ty in Math.floorDiv(y0, L)..Math.floorDiv(y1 - 1, L)) for (tx in Math.floorDiv(x0, L)..Math.floorDiv(x1 - 1, L)) {
            if (!lienzo.dentro(tx, ty)) continue
            val px = IntArray(L * L); var algo = false
            for (j in 0 until L) {
                val Y = ty * L + j; if (Y < y0 || Y >= y1) continue
                val gy = ((Y + 0.5f - rect.top) * ky - 0.5f).coerceIn(0f, h - 1.001f); val iy = gy.toInt(); val fy = gy - iy
                for (i in 0 until L) {
                    val X = tx * L + i; if (X < x0 || X >= x1) continue
                    val gx = ((X + 0.5f - rect.left) * kx - 0.5f).coerceIn(0f, w - 1.001f); val ix = gx.toInt(); val fx = gx - ix
                    val o = iy * w + ix
                    val v = (campo[o] * (1 - fx) + campo[o + 1] * fx) * (1 - fy) + (campo[o + w] * (1 - fx) + campo[o + w + 1] * fx) * fy
                    val col = if (v > 0f) tierra else if (mar != 0 && eraTierra != null && eraTierra(X + 0.5f, Y + 0.5f)) 0 else mar
                    if (col != 0) { px[j * L + i] = col; algo = true }
                }
            }
            if (!algo) continue
            val k = Lienzo.clave(tx, ty)
            val n = c.teselas[k]?.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(L, L, Bitmap.Config.ARGB_8888)
            Canvas(n).drawBitmap(Bitmap.createBitmap(px, L, L, Bitmap.Config.ARGB_8888), 0f, 0f, null)
            nuevas[k] = n
        }
        return lienzo.cambia(c, "Tierra y mar", nuevas)
    }

    fun ponColor(c: Int) {
        color = c
        recientes.remove(c); recientes.add(0, c); while (recientes.size > 12) recientes.removeAt(recientes.size - 1)
    }

    fun ponCapa(id: String) { lienzo.activa = id; capaActiva = id }

    // ---------------------------------------------------------------- recordar cómo se estaba dibujando

    /** Lo que se recuerda de una vez para otra: pinceles, tamaños, color, historial de colores y preferencias. */
    fun estado(): org.json.JSONObject = org.json.JSONObject().put("pincel", pincel).put("goma", goma).put("difuminar", pincelDifuminar)
        .put("tam", tamano.toDouble()).put("op", opacidad.toDouble()).put("tamGoma", tamanoGoma.toDouble()).put("opGoma", opacidadGoma.toDouble())
        .put("tamDif", tamanoDifuminar.toDouble()).put("fzaDif", fuerzaDifuminar.toDouble()).put("est", estabilizacion.toDouble()).put("color", color)
        .put("recientes", org.json.JSONArray(recientes.toList())).put("formas", formasRapidas).put("afilaDedo", afilaDedo).put("tamMapa", tamanoFijoMapa)

    fun ponEstado(o: org.json.JSONObject) {
        pincel = o.optString("pincel", pincel); goma = o.optString("goma", goma); pincelDifuminar = o.optString("difuminar", pincelDifuminar)
        tamano = o.optDouble("tam", tamano.toDouble()).toFloat(); opacidad = o.optDouble("op", opacidad.toDouble()).toFloat()
        tamanoGoma = o.optDouble("tamGoma", tamanoGoma.toDouble()).toFloat(); opacidadGoma = o.optDouble("opGoma", opacidadGoma.toDouble()).toFloat()
        tamanoDifuminar = o.optDouble("tamDif", tamanoDifuminar.toDouble()).toFloat(); fuerzaDifuminar = o.optDouble("fzaDif", fuerzaDifuminar.toDouble()).toFloat()
        estabilizacion = o.optDouble("est", estabilizacion.toDouble()).toFloat(); color = o.optInt("color", color); formasRapidas = o.optBoolean("formas", true); afilaDedo = o.optBoolean("afilaDedo", true); tamanoFijoMapa = o.optBoolean("tamMapa", false)
        o.optJSONArray("recientes")?.let { a -> if (a.length() > 0) { recientes.clear(); for (i in 0 until a.length()) recientes += a.optInt(i) } }
    }

    // ---------------------------------------------------------------- trazos

    /** Formas rápidas: dejar el lápiz quieto al final de un trazo lo convierte en una recta, un arco, una elipse… */
    var formasRapidas by mutableStateOf(true)
    /** Con el dedo (sin presión), afilar el principio y el final de los trazos de los pinceles que cambian de grosor (el final se afila al levantar). */
    var afilaDedo by mutableStateOf(true)

    /** Los reflejos con los que se dibuja (solo el propio trazo si no hay simetría). */
    fun espejos(): List<DoubleArray> = mapa.simetria?.espejos() ?: com.gonzalo.atlas.lienzo.TrazoVivo.SIN_ESPEJOS

    /** El acercamiento con que se está viendo el mapa (lo pone la mesa). */
    @Volatile var zoomVista = 1f
    /**
     * Si el tamaño del pincel se mide siempre en el mapa. Si no (lo normal), al acercarse se mide en la pantalla: el pincel
     * no engorda con el acercamiento, así que cuanto más cerca, más fino sale el trazo en el mapa.
     */
    var tamanoFijoMapa by mutableStateOf(false)
    /** Un tamaño de la barra (pincel, goma, difuminar…) pasado a píxeles del mapa. */
    fun enMapa(t: Float): Float = if (tamanoFijoMapa) t else t / kotlin.math.max(1f, zoomVista)

    /** Un trazo ha pasado a su capa (o no ha podido): se apunta para deshacer. */
    fun trazoAsentado(e: Edicion?, error: Throwable?) {
        e?.let { lienzo.apunta(it) }
        if (error != null) principal.post { aviso = "El trazo no se ha podido pasar a la capa: ${error.message ?: error.javaClass.simpleName}" }
    }

    // ---------------------------------------------------------------- toques (coordenadas del lienzo)

    /** [xd], [yd]: el punto tocado con toda su precisión (de muy cerca, [x] e [y] se quedan cortos); [vis]: lo que se ve del lienzo. */
    fun toque(x: Float, y: Float, sel: Any?, xd: Double = x.toDouble(), yd: Double = y.toDouble(), vis: DoubleArray? = null) {
        when (herramienta) {
            Herramienta.RELLENO -> { val z = zoomVista; val col = color; val op = opacidad; trabajo("Rellenando…") {
                val (e, porque) = lienzo.rellena(xd, yd, col, op, z, vis)
                if (e == null) aviso(porque ?: "No se ha podido rellenar ahí") else lienzo.apunta(e)
            } }
            Herramienta.SELECCION -> if (formaSeleccion == "zona") trabajo("Seleccionando…") {
                val z = lienzo.seleccionZona(x.toInt(), y.toInt())
                if (z == null) aviso("Ahí hay una línea, o la zona es demasiado grande: toca dentro de una zona cerrada")
                else { val actual = marco; val oper = operSeleccion; principal.post { ponMarco(if (oper == "nueva" || actual == null) z else actual.con(z, oper == "restar")) } }
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
                    val l = Lugar(mapa.nuevoId("p"), Nombres.lugar(tipoLugar, Random(System.nanoTime())), tipoLugar, xd, yd, Catalogo.lugar(tipoLugar).tamano)
                    mapa.lugares += l; seleccion = l
                }
            }
            Herramienta.ROTULO -> {
                if (sel is Rotulo) { seleccion = sel; return }
                cambiaVector("Rótulo") { val r = Rotulo(mapa.nuevoId("t"), "Nombre", xd, yd, enMapa(40f)); mapa.rotulos += r; seleccion = r }
            }
            Herramienta.SELLO -> {
                if (sel is Sello) { seleccion = sel; return }
                val r = recurso ?: run { aviso("Elige uno de tus dibujos (o guarda uno desde el Taller)"); return }
                cambiaVector("Dibujo") { val s = Sello(mapa.nuevoId("s"), r, xd, yd, enMapa(escalaSello)); mapa.sellos += s; seleccion = s }
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
    /** Lo mismo con toda su precisión (para mover lugares, rótulos y dibujos también de muy cerca). */
    private var arrastreD: DoubleArray? = null
    private var antesArrastre: String? = null

    fun empieza(x: Float, y: Float, xd: Double = x.toDouble(), yd: Double = y.toDouble()) {
        bx.clear(); by.clear(); bx += x; by += y
        when (herramienta) {
            Herramienta.RUTA -> borrador = Linea("borrador", tipoRuta, floatArrayOf(x), floatArrayOf(y), null)
            Herramienta.TERRITORIO -> borrador = Linea("borrador", "territorio", floatArrayOf(x), floatArrayOf(y), null)
            Herramienta.REGLA -> regla = floatArrayOf(x, y, x, y)
            Herramienta.SECCION -> seccionBorrador = floatArrayOf(x, y, x, y)
            Herramienta.SELECCION -> marcoBorrador = formaSeleccion to floatArrayOf(x, y, x, y)
            Herramienta.ELEGIR -> { arrastre = floatArrayOf(x, y); arrastreD = doubleArrayOf(xd, yd); antesArrastre = mapa.vectorJson().toString() }
            else -> {}
        }
    }

    fun mueve(x: Float, y: Float, xd: Double = x.toDouble(), yd: Double = y.toDouble()) {
        when (herramienta) {
            Herramienta.RUTA, Herramienta.TERRITORIO -> {
                if (hypot(x - bx.last(), y - by.last()) < 1.5f) return
                bx += x; by += y
                borrador = Linea("borrador", borrador?.tipo ?: "camino", bx.toFloatArray(), by.toFloatArray(), null)
            }
            Herramienta.REGLA -> regla = floatArrayOf(bx[0], by[0], x, y)
            Herramienta.SECCION -> seccionBorrador = floatArrayOf(bx[0], by[0], x, y)
            Herramienta.SELECCION -> {
                if (formaSeleccion == "lazo") { if (hypot(x - bx.last(), y - by.last()) < 1f) return; bx += x; by += y; marcoBorrador = "lazo" to FloatArray(bx.size * 2) { if (it % 2 == 0) bx[it / 2] else by[it / 2] } }
                else marcoBorrador = formaSeleccion to floatArrayOf(bx[0], by[0], x, y)
            }
            Herramienta.ELEGIR -> {
                val a = arrastre ?: return
                val dx = x - a[0]; val dy = y - a[1]
                val ad = arrastreD ?: doubleArrayOf(a[0].toDouble(), a[1].toDouble()); val ddx = xd - ad[0]; val ddy = yd - ad[1]
                when (val s = seleccion) {
                    is Lugar -> { s.x += ddx; s.y += ddy }
                    is Rotulo -> { s.x += ddx; s.y += ddy }
                    is Sello -> { s.x += ddx; s.y += ddy }
                    is Linea -> for (i in 0 until s.n) { s.xs[i] += dx; s.ys[i] += dy }
                    is Territorio -> { if (s.lx.isNaN()) centros.firstOrNull { it.id == s.id && !it.esRegion }?.let { s.lx = it.x; s.ly = it.y }; s.lx += dx; s.ly += dy }
                    is com.gonzalo.atlas.motor.Leyenda -> { s.x += dx; s.y += dy }
                }
                arrastre = floatArrayOf(x, y); arrastreD = doubleArrayOf(xd, yd)
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
            Herramienta.SECCION -> {
                val r = seccionBorrador ?: return; seccionBorrador = null
                val x0 = minOf(r[0], r[2]); val y0 = minOf(r[1], r[3]); val w = kotlin.math.abs(r[2] - r[0]); val h = kotlin.math.abs(r[3] - r[1])
                if (w > 16f && h > 16f) nuevaSeccion(x0, y0, w, h)
            }
            Herramienta.SELECCION -> {
                val b = marcoBorrador; marcoBorrador = null
                val p = b?.second
                val nueva = when {
                    p == null -> null
                    b.first == "lazo" -> if (p.size >= 8) com.gonzalo.atlas.lienzo.Seleccion(listOf(p)) else null
                    kotlin.math.abs(p[2] - p[0]) < 3f || kotlin.math.abs(p[3] - p[1]) < 3f -> null
                    else -> RectF(minOf(p[0], p[2]), minOf(p[1], p[3]), maxOf(p[0], p[2]), maxOf(p[1], p[3])).let { if (b.first == "elipse") com.gonzalo.atlas.lienzo.Seleccion.elipse(it) else com.gonzalo.atlas.lienzo.Seleccion.rectangulo(it) }
                }
                val actual = marco
                when {
                    nueva == null -> if (operSeleccion == "nueva") ponMarco(null)
                    operSeleccion == "nueva" || actual == null -> if (operSeleccion != "restar") ponMarco(nueva)
                    else -> ponMarco(actual.con(nueva, operSeleccion == "restar"))
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
        Herramienta.RUTA, Herramienta.REGLA, Herramienta.SECCION -> true
        Herramienta.SELECCION -> formaSeleccion != "zona"
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

    /** El rectángulo que se está dibujando para una sección nueva. */
    var seccionBorrador by mutableStateOf<FloatArray?>(null)

    /** Una sección nueva (y activa: desde ya se dibuja solo dentro). */
    fun nuevaSeccion(x: Float, y: Float, w: Float, h: Float): com.gonzalo.atlas.motor.Seccion {
        val s = com.gonzalo.atlas.motor.Seccion(mapa.nuevoId("s"), "Sección ${mapa.secciones.size + 1}", x, y, w, h)
        cambiaVector("Sección") { mapa.secciones += s; mapa.seccionActiva = s.id }
        aviso = "Dibujas dentro de «${s.nombre}»"
        return s
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
    /** Un aviso en pantalla (desde cualquier hilo). */
    fun avisa(t: String) = aviso(t)

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
        /** Una imagen pequeña de la forma de tierra (para elegirla antes de ponerla). */
        fun imagenContinente(p: com.gonzalo.atlas.motor.FormaTierra, w: Int, h: Int, tierra: Int, mar: Int, tinta: Int): Bitmap {
            val a = com.gonzalo.atlas.motor.Continente.campo(p, w, h)
            val px = IntArray(w * h) { i ->
                val x = i % w; val y = i / w; val t = a[i] > 0f
                val costa = (x + 1 < w && (a[i + 1] > 0f) != t) || (y + 1 < h && (a[i + w] > 0f) != t)
                if (costa) tinta else if (t) tierra else mar
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

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
