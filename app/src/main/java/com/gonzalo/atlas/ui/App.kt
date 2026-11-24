package com.gonzalo.atlas.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.familia.Cuenta
import com.gonzalo.atlas.motor.Biblioteca
import com.gonzalo.atlas.motor.Cronica
import com.gonzalo.atlas.motor.CapaInfo
import com.gonzalo.atlas.editor.Lamina
import com.gonzalo.atlas.motor.Mapa
import com.gonzalo.atlas.vista.Mesa
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uno de tus dibujos (del Taller), para poner en el mapa. */
class Recurso(val id: String, var nombre: String, var categoria: String, val archivo: File)

/** Tu biblioteca de dibujos: `recursos/<id>.png` y `recursos/recursos.json`. */
class Recursos(private val dir: File) {
    /** Los dibujos en memoria: hasta 96 MB (los grandes pesan varios megas cada uno). */
    private val cache = object : LruCache<String, Bitmap>(96 shl 20) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val indice = File(dir, "recursos.json")
    var version by mutableIntStateOf(0)

    fun lista(): List<Recurso> {
        val a = runCatching { JSONArray(indice.readText()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Recurso(it.optString("id"), it.optString("n"), it.optString("c", "Otros"), File(dir, it.optString("id") + ".png")) }
            .filter { it.archivo.exists() }
    }

    private fun guardaIndice(l: List<Recurso>) { dir.mkdirs(); indice.writeText(JSONArray().also { a -> l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.nombre).put("c", it.categoria)) } }.toString()) }

    fun guarda(id: String?, nombre: String, categoria: String, b: Bitmap): Recurso {
        val rid = id ?: ("d" + Biblioteca.id36())
        dir.mkdirs()
        File(dir, "$rid.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val l = lista().filter { it.id != rid } + Recurso(rid, nombre, categoria, File(dir, "$rid.png"))
        guardaIndice(l)
        cache.remove(rid)
        version++
        return l.last()
    }

    fun borra(id: String) { guardaIndice(lista().filter { it.id != id }); File(dir, "$id.png").delete(); File(dir, "$id.capas").deleteRecursively(); cache.remove(id); version++ }

    fun bitmap(id: String): Bitmap? {
        cache.get(id)?.let { return it }
        val f = File(dir, "$id.png"); if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { cache.put(id, it) }
    }

    fun dirCapas(id: String) = File(dir, "$id.capas")

    companion object {
        /** El lado mayor con que se guarda un dibujo (px). */
        const val LADO = 1536
        val CATEGORIAS = listOf("Montañas", "Colinas", "Árboles y bosques", "Ciudades", "Castillos", "Templos", "Ruinas", "Barcos", "Criaturas", "Rosas de los vientos", "Adornos", "Otros")
    }
}

/** El estado de la app: la biblioteca, el mapa abierto y qué sección se ve. */
class EstadoApp(val ctx: Context) {
    val bib = Biblioteca(File(ctx.filesDir, "atlas"))
    val recursos = Recursos(bib.dirRecursos)
    val cuenta = Cuenta.de(ctx)
    private val prefs = ctx.getSharedPreferences("atlas", Context.MODE_PRIVATE)

    var seccion by mutableStateOf("portada")
    /** El mundo en el que estás dentro (null: fuera, en la portada o en la lista de mundos). */
    var mundoActual by mutableStateOf<String?>(null)
    /** La ficha de política que hay que enseñar al entrar en Política (o en la consulta mientras dibujas). */
    var potenciaPedida by mutableStateOf<String?>(null)
    /** En «Mapas»: dibujando el mapa abierto (si no, eligiendo dónde dibujar). */
    var dibujando by mutableStateOf(false)
    var editor by mutableStateOf<Editor?>(null)
        private set
    /** El dibujo suelto abierto en el Taller (y su id en la biblioteca, si ya se guardó). */
    var editorTaller by mutableStateOf<Editor?>(null)
        private set
    var recursoTaller by mutableStateOf<String?>(null)
    private val mesas = HashMap<Editor, Mesa>()
    /** Sube cuando cambian los mapas o los mundos (para repintar las listas). */
    var listas by mutableIntStateOf(0)
    var mensaje by mutableStateOf("")
    /** La ficha de la crónica que hay que enseñar al entrar en ella. */
    var fichaPedida by mutableStateOf<String?>(null)

    init {
        com.gonzalo.atlas.lienzo.Pinceles.raiz = bib.raiz
        Thread { runCatching { com.gonzalo.atlas.lienzo.Pinceles.carga(bib.raiz) } }.start()
        val ultimo = prefs.getString("ultimo", null)
        val m = ultimo?.let { bib.abre(it) } ?: bib.mapas().firstOrNull()?.let { bib.abre(it.id) }
        if (m != null) abreMapa(m, irAlMapa = false)
        mejoraDibujos()
    }

    val mapa: Mapa? get() = editor?.mapa

    /** La mesa de un editor (con el contexto de la actividad: la de la pantalla). Se rehace si cambia la actividad. */
    fun mesaDe(ed: Editor, actividad: Context): Mesa {
        mesas[ed]?.let { if (it.context === actividad) return it; it.cierra() }
        return Mesa(actividad, ed) { recursos.bitmap(it) }.also { mesas[ed] = it; it.dedoDibuja = prefs.getBoolean("dedo", false); it.girar = prefs.getBoolean("girar", true) }
    }
    fun mesaAbierta(ed: Editor): Mesa? = mesas[ed]

    fun abreMapa(m: Mapa, irAlMapa: Boolean = true) {
        editor?.let { if (it.mapa.id == m.id) { if (irAlMapa) { mundoActual = m.mundo; seccion = "mapa"; dibujando = true }; return }; cierraEditor(it) }
        editor = Editor(m, ctx.resources) { listas++; cuenta.programaSubida(this) }.also { recuerda(it) }
        prefs.edit().putString("ultimo", m.id).apply()
        if (irAlMapa) { mundoActual = m.mundo; seccion = "mapa"; dibujando = true }
        listas++
    }

    private fun cierraEditor(ed: Editor) { apunta(ed); mesas.remove(ed)?.cierra(); ed.cierra() }

    /** Los pinceles, tamaños y colores con los que se estaba dibujando: se recuerdan de un mapa a otro y al volver a abrir la app. */
    private fun recuerda(ed: Editor) { prefs.getString("dibujo", null)?.let { t -> runCatching { ed.ponEstado(org.json.JSONObject(t)) } } }
    private fun apunta(ed: Editor) {
        val m = mesas[ed]
        prefs.edit().putString("dibujo", ed.estado().toString()).apply { if (m != null) { putBoolean("dedo", m.dedoDibuja); putBoolean("girar", m.girar) } }.apply()
    }

    fun abreMapa(id: String, irAlMapa: Boolean = true) { bib.abre(id)?.let { pila.clear(); abreMapa(it, irAlMapa) } }

    /** Los mapas por los que se ha ido entrando (la iglesia dentro de la ciudad dentro del reino…), para volver. */
    val pila = androidx.compose.runtime.mutableStateListOf<String>()

    fun abreVinculado(id: String) {
        val m = bib.abre(id) ?: run { mensaje = "Ese mapa ya no existe"; return }
        editor?.let { pila.add(it.mapa.id); it.guardaYa() }
        abreMapa(m)
    }

    fun vuelve() { val id = pila.removeLastOrNull() ?: return; bib.abre(id)?.let { abreMapa(it) } }

    fun nuevoMapa(mundo: String?, nombre: String, ancho: Int, alto: Int, papel: Int, tipo: String = "") {
        val w = mundo ?: bib.creaMundo("").id
        abreMapa(bib.creaMapa(w, nombre, ancho, alto, papel, tipo))
    }

    /** Entra en un mundo: su interfaz propia (mapas, política, guerras, crónica). */
    fun entraMundo(id: String, apartado: String = "mapa") { mundoActual = id; seccion = apartado; dibujando = false; listas++ }
    fun saleMundo() { editor?.guardaYa(); mundoActual = null; seccion = "mundos" }

    /** Los mapas del mundo, abiertos para leer lo que tienen (el que se está dibujando, tal como está ahora). */
    fun mapasDe(mundo: String): List<Mapa> = bib.mapas().filter { it.mundo == mundo }.mapNotNull { i -> editor?.mapa?.takeIf { it.id == i.id } ?: bib.abre(i.id) }

    fun cierraMapaSiEs(id: String) { if (editor?.mapa?.id == id) { editor?.let { cierraEditor(it) }; editor = null } }

    fun cronica(mundo: String) = Cronica(File(bib.dirCronica, "$mundo.json"))

    /**
     * Abre (o crea) la ficha ligada a algo del mapa: la de un reino, en Política; las demás, en la Crónica. Si ya hay una
     * ficha con ese nombre (el mismo reino dibujado en otro mapa del mundo), es la misma.
     */
    fun fichaDe(m: Mapa, tipo: String, id: String, cat: String, titulo: String) {
        val f = cronica(m.mundo).fichaDe(cat, titulo, "${m.id}:$tipo:$id")
        mundoActual = m.mundo
        if (cat == "reino") { potenciaPedida = f.id; seccion = "politica" } else { fichaPedida = f.id; seccion = "cronica" }
        listas++
    }

    // ---------------------------------------------------------------- taller (tus dibujos sueltos)

    private fun dirTaller(id: String) = File(bib.dirRecursos, "$id.capas")

    fun nuevoDibujo() {
        editorTaller?.let { cierraEditor(it) }
        val id = "d" + Biblioteca.id36()
        val m = Mapa(id, "", "Dibujo", 0, 0, dirTaller(id))
        m.papel = 0; m.granoPapel = false; m.verTerritorios = false
        m.capas += CapaInfo("c" + Biblioteca.id36(), "Dibujo")
        m.guardaMeta()
        editorTaller = Editor(m, ctx.resources).also { recuerda(it) }
        recursoTaller = null
    }

    fun abreDibujo(id: String) {
        editorTaller?.let { cierraEditor(it) }
        val m = Mapa.lee(dirTaller(id)) ?: run { mensaje = "Ese dibujo no tiene sus capas guardadas: solo se puede usar."; return }
        editorTaller = Editor(m, ctx.resources).also { recuerda(it) }
        recursoTaller = id
    }

    fun cierraDibujo() { editorTaller?.let { cierraEditor(it) }; editorTaller = null; recursoTaller = null }

    /**
     * Los dibujos guardados con versiones anteriores quedaron con los pocos píxeles que ocupaban en el lienzo. Una sola vez,
     * en segundo plano, se rehacen en alta resolución los que conservan sus trazos (solo si salen más grandes que como estaban).
     */
    fun mejoraDibujos() {
        val prefs = ctx.getSharedPreferences("dibujo", Context.MODE_PRIVATE)
        if (prefs.getBoolean("dibujosHD", false)) return
        Thread {
            var hechos = 0
            for (r in recursos.lista()) runCatching {
                if (r.id == recursoTaller) return@runCatching
                val m = Mapa.lee(dirTaller(r.id)) ?: return@runCatching
                val lz = com.gonzalo.atlas.lienzo.Lienzo(m, ctx.resources)
                try {
                    lz.carga(false)
                    if (!lz.tieneTrazos()) return@runCatching
                    val viejo = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { android.graphics.BitmapFactory.decodeFile(r.archivo.path, it) }
                    val b = lz.imagenNitida(Recursos.LADO) ?: return@runCatching
                    // (Se guarda en el hilo principal, que es el que lleva la lista de dibujos)
                    if (maxOf(b.width, b.height) > maxOf(viejo.outWidth, viejo.outHeight) * 1.3f) { android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { recursos.guarda(r.id, r.nombre, r.categoria, b) } }; hechos++ }
                } finally { lz.cierra() }
            }
            prefs.edit().putBoolean("dibujosHD", true).apply()
            if (hechos > 0) android.os.Handler(android.os.Looper.getMainLooper()).post { listas++ }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY; name = "atlas-dibujos" }.start()
    }

    /** Guarda el dibujo del Taller en tu biblioteca (recortado a lo dibujado, con fondo transparente). */
    fun guardaDibujo(nombre: String, categoria: String) {
        val ed = editorTaller ?: return
        ed.lienzo.enHilo {
            ed.lienzo.guarda()
            // En alta resolución: los trazos se rehacen hasta que el dibujo mide unos 1.536 px de lado, por pequeño que se haya dibujado
            val b = ed.lienzo.imagenNitida(Recursos.LADO)
            if (b == null) { mensaje = "El dibujo está vacío"; return@enHilo }
            val id = ed.mapa.id
            android.os.Handler(android.os.Looper.getMainLooper()).post { recursos.guarda(id, nombre, categoria, b); recursoTaller = id; mensaje = "Guardado en tu biblioteca: «$nombre» (${b.width} × ${b.height} px)"; listas++ }
        }
    }

    // ---------------------------------------------------------------- exportar

    fun exporta(ed: Editor, mesa: Mesa) {
        @Suppress("UNUSED_VARIABLE") val nada = 0
        mensaje = "Preparando la imagen…"
        ed.lienzo.enHilo {
            val l = ed.lienzo.limites()
            val b = Lamina.pinta(ed, minOf(4096, maxOf(l.width(), l.height())), mesa.capas) { recursos.bitmap(it) }
            val nombre = ed.mapa.nombre.replace(Regex("[^\\p{L}\\p{N} _-]"), "") + ".png"
            val r = runCatching {
                val v = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, nombre)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS")
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { mensaje = if (r.isSuccess) "Guardada en Imágenes/ATLAS: $nombre" else "No se pudo guardar: ${r.exceptionOrNull()?.message}" }
        }
    }

    fun alSalir() { mesas.values.forEach { it.recuerdaVista() }; (editorTaller ?: editor)?.let { apunta(it) }; editor?.guardaYa(); editorTaller?.guardaYa() }
}

/** Los apartados de fuera (la app) y los de dentro de un mundo. */
val SECCIONES = listOf("portada" to "Portada", "mundos" to "Mundos", "taller" to "Taller", "mundo" to "Ajustes")
val SECCIONES_MUNDO = listOf("mapa" to "Mapas", "politica" to "Política", "guerras" to "Guerras", "cronica" to "Crónica")

@Composable
fun App(e: EstadoApp) {
    val w = e.mundoActual
    val dentro = w != null && SECCIONES_MUNDO.any { it.first == e.seccion }
    // Dibujando, el lienzo ocupa la pantalla (como en Procreate), con la barra del mundo encima para ir a Política, Guerras…
    val dibujando = (dentro && e.seccion == "mapa" && e.editor?.mapa?.mundo == w && e.dibujando) || (e.seccion == "taller" && e.editorTaller != null)
    Column(Modifier.fillMaxSize().background(if (dibujando) O.MESA else T.PAPEL)) {
        if (!dibujando) { if (dentro) { CabeceraMundo(e); PestanasMundo(e) } else { Cabecera(e); Pestanas(e) } }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                dentro && e.seccion == "mapa" -> if (dibujando) PantMapa(e) else PantMapas(e)
                dentro && e.seccion == "politica" -> PantPolitica(e)
                dentro && e.seccion == "guerras" -> PantGuerras(e)
                dentro && e.seccion == "cronica" -> PantCronica(e)
                e.seccion == "taller" -> PantMapa(e, taller = true)
                e.seccion == "mundo" -> PantMundo(e)
                e.seccion == "portada" -> Portada(e)
                else -> PantMundos(e)
            }
        }
    }
}

/** La cabecera negra: la fila pequeña (mundo, cuenta) y el nombre grande con el punto del acento. En el mapa, en pequeño. */
@Composable
fun Cabecera(e: EstadoApp) {
    val enMapa = false
    val mundo = ""
    Column(Modifier.fillMaxWidth().background(T.CABECERA).statusBarsPadding().padding(horizontal = 20.dp, vertical = if (enMapa) 6.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (enMapa) {
                Text(buildAnnotatedString { append("ATLAS"); withStyle(SpanStyle(color = T.ACENTO_NEGRO)) { append(".") } }, style = T.titular(24.sp, T.PAPEL))
                Spacer(Modifier.width(16.dp))
            }
            Etiqueta(if (mundo.isNotBlank()) "Mundo · $mundo" else "Tus mundos de fantasía", Modifier.weight(1f), 11.sp, T.SOBRE_CABECERA)
            Etiqueta(if (e.cuenta.dentro) "Familia · " + e.cuenta.usuario else "Sin cuenta", Modifier.pulsable { e.seccion = "mundo" }, 11.sp, T.SOBRE_CABECERA)
        }
        if (!enMapa) {
            Spacer(Modifier.height(2.dp))
            Text(buildAnnotatedString { append("ATLAS"); withStyle(SpanStyle(color = T.ACENTO_NEGRO)) { append(".") } }, style = T.titular(52.sp, T.PAPEL))
        }
    }
}

@Composable
fun Pestanas(e: EstadoApp) {
    @Suppress("UNUSED_VARIABLE") val l = e.listas
    Column(Modifier.fillMaxWidth().background(T.PAPEL)) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((k, n) in SECCIONES) {
                val sel = e.seccion == k
                Box(Modifier.fillMaxHeight().background(if (sel) T.ACENTO else T.PAPEL).pulsable { e.seccion = k }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Etiqueta(n, tam = 13.sp, color = if (sel) T.PAPEL else T.TINTA)
                        val cuenta = androidx.compose.runtime.remember(e.listas, e.recursos.version, k) { when (k) { "mundos" -> e.bib.mundos().size; "taller" -> e.recursos.lista().size; else -> 0 } }
                        if (cuenta > 0) { Spacer(Modifier.width(6.dp)); Text("$cuenta", style = T.titular(14.sp, if (sel) T.PAPEL else T.ACENTO)) }
                    }
                }
            }
        }
        Filete(2)
    }
}

@Composable
fun Vacio(t: String, mod: Modifier = Modifier) = Box(mod.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) { Text(t, style = T.nombre(18.sp, T.GRIS)) }
