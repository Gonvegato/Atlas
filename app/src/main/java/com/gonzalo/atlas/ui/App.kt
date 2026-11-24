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
    private val cache = LruCache<String, Bitmap>(48)
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
        Thread { runCatching { com.gonzalo.atlas.lienzo.Pinceles.carga(bib.raiz, ctx.resources) } }.start()
        val ultimo = prefs.getString("ultimo", null)
        val m = ultimo?.let { bib.abre(it) } ?: bib.mapas().firstOrNull()?.let { bib.abre(it.id) }
        if (m != null) abreMapa(m, irAlMapa = false)
    }

    val mapa: Mapa? get() = editor?.mapa

    /** La mesa de un editor (con el contexto de la actividad: la de la pantalla). Se rehace si cambia la actividad. */
    fun mesaDe(ed: Editor, actividad: Context): Mesa {
        mesas[ed]?.let { if (it.context === actividad) return it; it.cierra() }
        return Mesa(actividad, ed) { recursos.bitmap(it) }.also { mesas[ed] = it }
    }
    fun mesaAbierta(ed: Editor): Mesa? = mesas[ed]

    fun abreMapa(m: Mapa, irAlMapa: Boolean = true) {
        editor?.let { if (it.mapa.id == m.id) { if (irAlMapa) { seccion = "mapa"; dibujando = true }; return }; cierraEditor(it) }
        editor = Editor(m, ctx.resources) { listas++; cuenta.programaSubida(this) }
        prefs.edit().putString("ultimo", m.id).apply()
        if (irAlMapa) { seccion = "mapa"; dibujando = true }
        listas++
    }

    private fun cierraEditor(ed: Editor) { mesas.remove(ed)?.cierra(); ed.cierra() }

    fun abreMapa(id: String, irAlMapa: Boolean = true) { bib.abre(id)?.let { pila.clear(); abreMapa(it, irAlMapa) } }

    /** Los mapas por los que se ha ido entrando (la iglesia dentro de la ciudad dentro del reino…), para volver. */
    val pila = androidx.compose.runtime.mutableStateListOf<String>()

    fun abreVinculado(id: String) {
        val m = bib.abre(id) ?: run { mensaje = "Ese mapa ya no existe"; return }
        editor?.let { pila.add(it.mapa.id); it.guardaYa() }
        abreMapa(m)
    }

    fun vuelve() { val id = pila.removeLastOrNull() ?: return; bib.abre(id)?.let { abreMapa(it) } }

    fun nuevoMapa(mundo: String?, nombre: String, ancho: Int, alto: Int, papel: Int) {
        val w = mundo ?: bib.creaMundo("").id
        abreMapa(bib.creaMapa(w, nombre, ancho, alto, papel))
    }

    fun cierraMapaSiEs(id: String) { if (editor?.mapa?.id == id) { editor?.let { cierraEditor(it) }; editor = null } }

    fun cronica(mundo: String) = Cronica(File(bib.dirCronica, "$mundo.json"))

    /** Abre (o crea) la ficha de la crónica ligada a algo del mapa. */
    fun fichaDe(m: Mapa, tipo: String, id: String, cat: String, titulo: String) {
        val cr = cronica(m.mundo)
        val enlace = "${m.id}:$tipo:$id"
        val f = cr.deEnlace(enlace) ?: cr.nueva(cat, titulo, enlace)
        fichaPedida = f.id
        seccion = "cronica"
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
        editorTaller = Editor(m, ctx.resources)
        recursoTaller = null
    }

    fun abreDibujo(id: String) {
        editorTaller?.let { cierraEditor(it) }
        val m = Mapa.lee(dirTaller(id)) ?: run { mensaje = "Ese dibujo no tiene sus capas guardadas: solo se puede usar."; return }
        editorTaller = Editor(m, ctx.resources)
        recursoTaller = id
    }

    fun cierraDibujo() { editorTaller?.let { cierraEditor(it) }; editorTaller = null; recursoTaller = null }

    /** Guarda el dibujo del Taller en tu biblioteca (recortado a lo dibujado, con fondo transparente). */
    fun guardaDibujo(nombre: String, categoria: String) {
        val ed = editorTaller ?: return
        ed.lienzo.enHilo {
            ed.lienzo.guarda()
            val r = ed.lienzo.contenido()
            if (r == null) { mensaje = "El dibujo está vacío"; return@enHilo }
            r.inset(-4, -4)
            val b = ed.lienzo.recorta(r)
            val id = ed.mapa.id
            android.os.Handler(android.os.Looper.getMainLooper()).post { recursos.guarda(id, nombre, categoria, b); recursoTaller = id; mensaje = "Guardado en tu biblioteca: «$nombre»"; listas++ }
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

    fun alSalir() { mesas.values.forEach { it.recuerdaVista() }; editor?.guardaYa(); editorTaller?.guardaYa() }
}

val SECCIONES = listOf("portada" to "Portada", "mapa" to "Mapas", "cronica" to "Crónica", "taller" to "Taller", "mundo" to "Mundo")

@Composable
fun App(e: EstadoApp) {
    // Dibujando, el lienzo ocupa toda la pantalla (como en Procreate): «Galería» vuelve a la portada
    val dibujando = (e.seccion == "mapa" && e.editor != null && e.dibujando) || (e.seccion == "taller" && e.editorTaller != null)
    Column(Modifier.fillMaxSize().background(if (dibujando) O.MESA else T.PAPEL)) {
        if (!dibujando) { Cabecera(e); Pestanas(e) }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (e.seccion) {
                "portada" -> Portada(e)
                "mapa" -> if (dibujando) PantMapa(e) else PantMapas(e)
                "cronica" -> PantCronica(e)
                "taller" -> PantMapa(e, taller = true)
                "mundo" -> PantMundo(e)
            }
        }
    }
}

/** La cabecera negra: la fila pequeña (mundo, cuenta) y el nombre grande con el punto del acento. En el mapa, en pequeño. */
@Composable
fun Cabecera(e: EstadoApp) {
    val enMapa = false
    val mundo = e.mapa?.let { m -> e.bib.mundo(m.mundo)?.nombre }.orEmpty()
    Column(Modifier.fillMaxWidth().background(T.CABECERA).statusBarsPadding().padding(horizontal = 20.dp, vertical = if (enMapa) 6.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (enMapa) {
                Text(buildAnnotatedString { append("ATLAS"); withStyle(SpanStyle(color = T.ACENTO_NEGRO)) { append(".") } }, style = T.titular(24.sp, T.PAPEL))
                Spacer(Modifier.width(16.dp))
            }
            Etiqueta(if (mundo.isNotBlank()) "Mundo · $mundo" else "Tus mundos", Modifier.weight(1f), 11.sp, T.SOBRE_CABECERA)
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
                Box(Modifier.fillMaxHeight().background(if (sel) T.ACENTO else T.PAPEL).pulsable { e.seccion = k; if (k == "mapa") e.dibujando = false }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Etiqueta(n, tam = 13.sp, color = if (sel) T.PAPEL else T.TINTA)
                        val cuenta = androidx.compose.runtime.remember(e.listas, e.recursos.version, k) { when (k) { "portada" -> e.bib.mapas().size; "cronica" -> e.mapa?.let { m -> e.cronica(m.mundo).entradas.size } ?: 0; "taller" -> e.recursos.lista().size; else -> 0 } }
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
