package com.gonzalo.atlas.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gonzalo.atlas.lienzo.Lienzo
import com.gonzalo.atlas.motor.Biblioteca
import com.gonzalo.atlas.motor.Catalogo
import com.gonzalo.atlas.motor.Nombres
import java.io.File

/** La portada: el mapa en el que estás, tus mundos con sus mapas y crear uno nuevo. */
@Composable
fun Portada(e: EstadoApp) {
    @Suppress("UNUSED_VARIABLE") val l = e.listas
    val mapas = remember(e.listas) { e.bib.mapas() }
    val mundos = remember(e.listas) { e.bib.mundos() }
    var nuevo by remember { mutableStateOf<String?>(null) } // id del mundo ("" = mundo nuevo)
    var unir by remember { mutableStateOf<Biblioteca.MapaInfo?>(null) }
    var renombrar by remember { mutableStateOf<Pair<String, String>?>(null) } // ("m:id" o "w:id", nombre)
    var borrar by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Relleno).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val actual = e.mapa
        if (actual != null) {
            val info = mapas.firstOrNull { it.id == actual.id }
            BoxWithConstraints {
                val ancho = maxWidth > 700.dp
                val cartel = @Composable { mod: Modifier -> Miniatura(e.bib.miniatura(actual.id), mod.border(2.dp, T.TINTA).background(T.MESA).pulsable { e.abreMapa(actual) }, e.listas) }
                val texto = @Composable { mod: Modifier ->
                    Column(mod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Etiqueta("Estás dibujando", tam = 11.sp, color = T.ACENTO)
                        Text(actual.nombre, style = T.titular(40.sp))
                        Text(e.bib.mundo(actual.mundo)?.nombre?.let { "Mundo de $it" }.orEmpty(), style = T.nombre(20.sp, T.GRIS))
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Cifra(info?.reinos ?: 0, "territorios"); Cifra(info?.lugares ?: 0, "lugares")
                            Cifra(if (actual.infinito) 0 else actual.ancho, if (actual.infinito) "infinito" else "px de ancho")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Boton("Seguir dibujando", lleno = true) { e.abreMapa(actual) }
                            Boton("+ Mapa en este mundo") { nuevo = actual.mundo }
                        }
                    }
                }
                if (ancho) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { cartel(Modifier.weight(1.2f).aspectRatio(1.5f)); texto(Modifier.weight(1f)) }
                else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { cartel(Modifier.fillMaxWidth().aspectRatio(1.5f)); texto(Modifier.fillMaxWidth()) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Empieza tu primer mundo", style = T.titular(36.sp))
                Text("Un mundo puede tener varios mapas (un continente, una ciudad, una región…) que dibujas por separado y luego puedes unir.", style = T.texto(16.sp, T.GRIS))
                Boton("+ Mundo nuevo", lleno = true) { nuevo = "" }
            }
        }

        Seccion(1, "Tus mundos", derecha = { Enlace("+ Mundo nuevo") { nuevo = "" } })
        if (mundos.isEmpty()) Text("Aún no hay ninguno.", style = T.nombre(18.sp, T.GRIS))
        for (w in mundos) {
            val suyos = mapas.filter { it.mundo == w.id }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(w.nombre, Modifier.weight(1f).pulsable { e.entraMundo(w.id) }, style = T.nombre(26.sp))
                    Enlace("Entrar") { e.entraMundo(w.id) }
                    Enlace("+ Mapa") { nuevo = w.id }
                    Enlace("Renombrar") { renombrar = "w:${w.id}" to w.nombre }
                    Enlace("Borrar", color = T.ROJO) { borrar = "w:${w.id}" to w.nombre }
                }
                Filete(1)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (m in suyos) {
                        Column(Modifier.width(230.dp)) {
                            Miniatura(e.bib.miniatura(m.id), Modifier.fillMaxWidth().aspectRatio(1.4f).border(if (actual?.id == m.id) 3.dp else 1.dp, if (actual?.id == m.id) T.ACENTO else T.TINTA).background(T.MESA).pulsable { e.abreMapa(m.id) }, e.listas)
                            Text(m.nombre, style = T.nombre(18.sp), maxLines = 1)
                            Text((if (m.infinito) "Infinito" else "${m.ancho} × ${m.alto}") + " · ${m.reinos} territorios · ${m.lugares} lugares", style = T.texto(12.sp, T.GRIS))
                            Row {
                                Enlace("Abrir") { e.abreMapa(m.id) }
                                if (suyos.size > 1 && !m.infinito) Enlace("Unir") { unir = m }
                                Enlace("Nombre") { renombrar = "m:${m.id}" to m.nombre }
                                Enlace("Borrar", color = T.ROJO) { borrar = "m:${m.id}" to m.nombre }
                            }
                        }
                    }
                    if (suyos.isEmpty()) Text("Sin mapas.", style = T.texto(14.sp, T.GRIS))
                }
            }
        }
        if (e.mensaje.isNotBlank()) Text(e.mensaje, style = T.texto(14.sp, T.ACENTO))
        Spacer(Modifier.height(30.dp))
    }

    nuevo?.let { w -> DialogoNuevo(e, w.ifBlank { null }) { nuevo = null } }
    unir?.let { m -> DialogoUnir(e, m) { unir = null } }
    renombrar?.let { (k, n) ->
        DialogoTexto("Nuevo nombre", n, { renombrar = null }) { t ->
            if (k.startsWith("w:")) e.bib.renombraMundo(k.drop(2), t)
            else { val id = k.drop(2); val ed = e.editor?.takeIf { it.mapa.id == id }; if (ed != null) { ed.mapa.nombre = t; ed.guardaYa() } else e.bib.abre(id)?.let { it.nombre = t; it.guardaMeta() } }
            e.listas++; renombrar = null
        }
    }
    borrar?.let { (k, n) ->
        Dialog({ borrar = null }) {
            Column(Modifier.background(T.PAPEL).border(2.dp, T.TINTA).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("¿Borrar «$n»?", style = T.titular(24.sp))
                Text(if (k.startsWith("w:")) "Se borran el mundo, todos sus mapas y su crónica de este dispositivo." else "Se borra el mapa de este dispositivo (lo subido a la nube sigue allí).", style = T.texto(14.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Boton("Borrar", lleno = true, color = T.ROJO) {
                        val id = k.drop(2)
                        if (k.startsWith("w:")) { e.bib.mapas().filter { it.mundo == id }.forEach { e.cierraMapaSiEs(it.id) }; e.bib.borraMundo(id) } else { e.cierraMapaSiEs(id); e.bib.borraMapa(id) }
                        e.listas++; borrar = null
                    }
                    Boton("No") { borrar = null }
                }
            }
        }
    }
}

@Composable
private fun Cifra(n: Int, t: String) {
    Column { Text(if (n == 0 && t == "infinito") "∞" else "$n", style = T.titular(30.sp, T.ACENTO)); Etiqueta(t, tam = 10.sp, color = T.GRIS) }
}

@Composable
fun Miniatura(f: File, mod: Modifier, clave: Any? = null) {
    val b = remember(f.path, clave, f.lastModified()) { if (f.exists()) BitmapFactory.decodeFile(f.path) else null }
    Box(mod, contentAlignment = Alignment.Center) {
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text("Sin dibujar", style = T.nombre(16.sp, T.GRIS))
    }
}

/** El tamaño del lienzo de un mapa nuevo (siempre en múltiplos de 256). */
private val TAMANOS = listOf(
    Triple("Infinito", 0, 0), Triple("Grande", 4096, 4096), Triple("Apaisado", 6144, 4096), Triple("Vertical", 4096, 6144),
    Triple("Enorme", 8192, 8192), Triple("Pequeño", 2048, 2048),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DialogoNuevo(e: EstadoApp, mundo: String?, cerrar: () -> Unit) {
    var nombre by remember { mutableStateOf(Nombres.raiz()) }
    var tipo by remember { mutableStateOf("continente") }
    var nombreMundo by remember { mutableStateOf(Nombres.mundo()) }
    var tam by remember { mutableStateOf(TAMANOS[0]) }
    var papel by remember { mutableStateOf(Catalogo.PAPELES[0].second) }
    Dialog(cerrar) {
        Column(Modifier.background(T.PAPEL).border(2.dp, T.TINTA).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (mundo == null) "Mundo nuevo" else "Mapa nuevo", style = T.titular(28.sp))
            if (mundo == null) { Etiqueta("El mundo", tam = 10.sp, color = T.GRIS); Campo(nombreMundo, { nombreMundo = it }, Modifier.fillMaxWidth()) }
            Etiqueta("¿Mapa de qué?", tam = 10.sp, color = T.GRIS)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((k, n, _) in Catalogo.TIPOS_MAPA) Ficha(n, tipo == k) { tipo = k }
            }
            Etiqueta("Su nombre", tam = 10.sp, color = T.GRIS)
            Campo(nombre, { nombre = it }, Modifier.fillMaxWidth())
            Etiqueta("Tamaño del lienzo", tam = 10.sp, color = T.GRIS)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in TAMANOS) Ficha(if (t.second == 0) t.first else "${t.first} ${t.second}×${t.third}", tam == t) { tam = t }
            }
            Text(if (tam.second == 0) "Sin bordes: crece hacia donde dibujes." else "Se puede unir luego con otros mapas del mismo mundo.", style = T.texto(12.sp, T.GRIS))
            Etiqueta("Papel", tam = 10.sp, color = T.GRIS)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((n, c) in Catalogo.PAPELES) Row(Modifier.border(if (papel == c) 2.5.dp else 1.dp, if (papel == c) T.ACENTO else T.TINTA).pulsable { papel = c }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).background(Color(c)).border(1.dp, T.TINTA)); Spacer(Modifier.width(6.dp)); Text(n, style = T.texto(13.sp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Boton("Crear", lleno = true) {
                    val w = mundo ?: e.bib.creaMundo(nombreMundo).id
                    e.nuevoMapa(w, nombre, tam.second, tam.third, papel, tipo); cerrar()
                }
                Boton("Cancelar") { cerrar() }
            }
        }
    }
}

@Composable
private fun DialogoUnir(e: EstadoApp, a: Biblioteca.MapaInfo, cerrar: () -> Unit) {
    val otros = remember { e.bib.mapas().filter { it.mundo == a.mundo && it.id != a.id && !it.infinito } }
    var b by remember { mutableStateOf(otros.firstOrNull()) }
    var lado by remember { mutableStateOf("E") }
    var trabajando by remember { mutableStateOf(false) }
    Dialog(cerrar) {
        Column(Modifier.background(T.PAPEL).border(2.dp, T.TINTA).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Unir «${a.nombre}» con…", style = T.titular(24.sp))
            for (o in otros) Ficha(o.nombre, b == o) { b = o }
            Etiqueta("Dónde va el segundo", tam = 10.sp, color = T.GRIS)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for ((k, n) in listOf("E" to "A la derecha", "O" to "A la izquierda", "S" to "Debajo", "N" to "Encima")) Ficha(n, lado == k) { lado = k } }
            Text("Se crea un mapa nuevo con los dos (capas, territorios, lugares y rótulos). Los dos de antes no cambian.", style = T.texto(12.sp, T.GRIS))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Boton(if (trabajando) "Uniendo…" else "Unir", lleno = true, activo = b != null && !trabajando) {
                    trabajando = true
                    val bb = b!!
                    e.editor?.guardaYa()
                    Thread {
                        val r = runCatching { Uniones.une(e.bib, a.id, bb.id, lado) }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            trabajando = false
                            r.onSuccess { e.abreMapa(it, irAlMapa = false); e.listas++; cerrar() }.onFailure { e.mensaje = "No se pudieron unir: ${it.message}"; cerrar() }
                        }
                    }.start()
                }
                Boton("Cancelar") { cerrar() }
            }
        }
    }
}

@Composable
fun DialogoTexto(titulo: String, inicial: String, cerrar: () -> Unit, aceptar: (String) -> Unit) {
    var t by remember { mutableStateOf(inicial) }
    Dialog(cerrar) {
        Column(Modifier.background(T.PAPEL).border(2.dp, T.TINTA).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(titulo, style = T.titular(24.sp))
            Campo(t, { t = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Boton("Aceptar", lleno = true) { aceptar(t.trim()) }; Boton("Cancelar") { cerrar() } }
        }
    }
}

/** Unir dos mapas dibujados: las teselas de B se copian desplazadas (los tamaños son múltiplos de 256). */
object Uniones {
    fun une(bib: Biblioteca, idA: String, idB: String, lado: String): String {
        val a = bib.abre(idA)!!; val b = bib.abre(idB)!!
        val ew = lado == "E" || lado == "O"
        val w = if (ew) a.ancho + b.ancho else maxOf(a.ancho, b.ancho)
        val h = if (ew) maxOf(a.alto, b.alto) else a.alto + b.alto
        val (ax, ay, bx, by) = when (lado) { "E" -> listOf(0, 0, a.ancho, 0); "O" -> listOf(b.ancho, 0, 0, 0); "S" -> listOf(0, 0, 0, a.alto); else -> listOf(0, b.alto, 0, 0) }
        val id = "m" + Biblioteca.id36()
        val dir = bib.dirMapa(id)
        val m = com.gonzalo.atlas.motor.Mapa(id, a.mundo, "${a.nombre} + ${b.nombre}", w, h, dir)
        m.kmPorPx = a.kmPorPx; m.escalaPuesta = a.escalaPuesta; m.papel = a.papel; m.granoPapel = a.granoPapel
        m.obras.addAll((a.obras + b.obras).distinct())
        // Capas: las de A y, para cada una de B, la de A con el mismo nombre (o una nueva)
        val capaDeB = HashMap<String, String>()
        a.capas.forEach { m.capas += com.gonzalo.atlas.motor.CapaInfo.de(it.json()) }
        for (cb in b.capas) {
            val igual = m.capas.firstOrNull { it.nombre.equals(cb.nombre, true) && it.id !in capaDeB.values }
            if (igual != null) capaDeB[cb.id] = igual.id else { val n = com.gonzalo.atlas.motor.CapaInfo.de(cb.json().put("id", "c" + Biblioteca.id36() + "b")); m.capas += n; capaDeB[cb.id] = n.id }
        }
        fun copiaTeselas(src: File, dst: File, dx: Int, dy: Int, ext: String, cambia: ((ByteArray) -> ByteArray)?) {
            dst.mkdirs()
            src.listFiles()?.forEach { f ->
                val p = f.name.removeSuffix(ext).split('_'); if (!f.name.endsWith(ext) || p.size != 2) return@forEach
                val tx = (p[0].toIntOrNull() ?: return@forEach) + dx / Lienzo.L; val ty = (p[1].toIntOrNull() ?: return@forEach) + dy / Lienzo.L
                val out = File(dst, "${tx}_${ty}$ext")
                if (cambia == null) f.copyTo(out, true)
                else { val d = java.util.zip.InflaterInputStream(f.inputStream()).use { it.readBytes() }; java.util.zip.DeflaterOutputStream(out.outputStream()).use { it.write(cambia(d)) } }
            }
        }
        for (c in a.capas) copiaTeselas(File(a.dir, "capas/${c.id}"), File(dir, "capas/${c.id}"), ax, ay, ".png", null)
        // Si una capa de B va a una de A, se funden las teselas que coincidan (si no coinciden, basta copiarlas)
        for (c in b.capas) {
            val destino = File(dir, "capas/${capaDeB[c.id]}")
            File(b.dir, "capas/${c.id}").listFiles()?.forEach { f ->
                val p = f.name.removeSuffix(".png").split('_'); if (!f.name.endsWith(".png") || p.size != 2) return@forEach
                val tx = p[0].toInt() + bx / Lienzo.L; val ty = p[1].toInt() + by / Lienzo.L
                destino.mkdirs(); val out = File(destino, "${tx}_${ty}.png")
                if (!out.exists()) f.copyTo(out) else {
                    val base = BitmapFactory.decodeFile(out.path).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                    android.graphics.Canvas(base).drawBitmap(BitmapFactory.decodeFile(f.path), 0f, 0f, null)
                    out.outputStream().use { base.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
        // Territorios: los de B cambian de número si chocan
        val rem = IntArray(256) { it }
        val usados = a.territorios.map { it.id }.toMutableSet()
        for (t in b.territorios) if (t.id in usados) { val n = (1..255).first { it !in usados && b.territorios.none { o -> o.id == it } }; rem[t.id] = n; usados += n } else usados += t.id
        copiaTeselas(File(a.dir, "terr"), File(dir, "terr"), ax, ay, ".bin", null)
        copiaTeselas(File(b.dir, "terr"), File(dir, "terr"), bx, by, ".bin") { d -> ByteArray(d.size) { i -> rem[d[i].toInt() and 255].toByte() } }
        a.territorios.forEach { m.territorios += com.gonzalo.atlas.motor.Territorio.de(it.json()).also { t -> if (!t.lx.isNaN()) { t.lx += ax; t.ly += ay } } }
        b.territorios.forEach { m.territorios += com.gonzalo.atlas.motor.Territorio.de(it.json()).also { t -> t.id = rem[t.id]; if (t.padre != 0) t.padre = rem[t.padre]; if (!t.lx.isNaN()) { t.lx += bx; t.ly += by } } }
        for ((src, dx, dy) in listOf(Triple(a, ax.toFloat(), ay.toFloat()), Triple(b, bx.toFloat(), by.toFloat()))) {
            src.lugares.forEach { m.lugares += com.gonzalo.atlas.motor.Lugar(it.id, it.nombre, it.tipo, it.x + dx, it.y + dy, it.tamano, it.nota, it.mapa) }
            src.rutas.forEach { m.rutas += it.desplazada(dx, dy) }
            src.rotulos.forEach { m.rotulos += com.gonzalo.atlas.motor.Rotulo(it.id, it.texto, it.x + dx, it.y + dy, it.tam, it.giro, it.estilo, it.espaciado, it.curva) }
            src.sellos.forEach { m.sellos += com.gonzalo.atlas.motor.Sello(it.id, it.recurso, it.x + dx, it.y + dy, it.escala, it.giro, it.espejo) }
        }
        m.guardaMeta()
        return id
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("unused") @Composable private fun nada() = FlowRow {}

/**
 * Los mapas del mundo en el que estás, por lo que son (el mundo entero, continentes, regiones y reinos, ciudades, lugares):
 * seguir con el que estabas, abrir otro o hacer uno nuevo diciendo de qué es.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantMapas(e: EstadoApp) {
    @Suppress("UNUSED_VARIABLE") val l = e.listas
    val w = e.mundoActual ?: run { Vacio("Entra en un mundo"); return }
    val mapas = remember(e.listas, w) { e.bib.mapas().filter { it.mundo == w } }
    var nuevo by remember { mutableStateOf(false) }
    var unir by remember { mutableStateOf<Biblioteca.MapaInfo?>(null) }
    var renombrar by remember { mutableStateOf<Pair<String, String>?>(null) }
    var tipoDe by remember { mutableStateOf<Biblioteca.MapaInfo?>(null) }
    var borrar by remember { mutableStateOf<Pair<String, String>?>(null) }
    val abierto = e.editor?.mapa?.takeIf { it.mundo == w }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Relleno).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Mapas", style = T.titular(36.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            abierto?.let { m -> Boton("Seguir con «${m.nombre}»", lleno = true) { e.abreMapa(m) } }
            Boton("+ Mapa nuevo", lleno = abierto == null) { nuevo = true }
        }
        if (mapas.isEmpty()) Text("Este mundo aún no tiene mapas. Haz el primero: te pregunta de qué es (el mundo entero, un continente, una región, una ciudad…).", style = T.texto(15.sp, T.GRIS))
        var n = 1
        for ((clave, _, grupo) in Catalogo.TIPOS_MAPA) {
            val suyos = mapas.filter { (it.tipo.ifBlank { "otro" }.takeIf { t -> Catalogo.TIPOS_MAPA.any { x -> x.first == t } } ?: "otro") == clave }
            if (suyos.isEmpty()) continue
            Seccion(n++, grupo)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (m in suyos) Column(Modifier.width(250.dp)) {
                    Miniatura(e.bib.miniatura(m.id), Modifier.fillMaxWidth().aspectRatio(1.4f).border(if (abierto?.id == m.id) 3.dp else 1.dp, if (abierto?.id == m.id) T.BLOQUE2 else T.TINTA).background(T.MESA).pulsable { e.abreMapa(m.id) }, e.listas)
                    Text(m.nombre, style = T.nombre(18.sp), maxLines = 1)
                    Text((if (m.infinito) "Infinito" else "${m.ancho} × ${m.alto}") + " · ${m.reinos} territorios · ${m.lugares} lugares", style = T.texto(12.sp, T.GRIS))
                    FlowRow {
                        Enlace("Abrir") { e.abreMapa(m.id) }
                        Enlace("Nombre") { renombrar = m.id to m.nombre }
                        Enlace("Tipo") { tipoDe = m }
                        if (mapas.count { !it.infinito } > 1 && !m.infinito) Enlace("Unir") { unir = m }
                        Enlace("Borrar", color = T.ROJO) { borrar = m.id to m.nombre }
                    }
                }
            }
        }
        if (e.mensaje.isNotBlank()) Text(e.mensaje, style = T.texto(14.sp, T.ACENTO))
        Spacer(Modifier.height(30.dp))
    }
    if (nuevo) DialogoNuevo(e, w) { nuevo = false }
    unir?.let { m -> DialogoUnir(e, m) { unir = null } }
    fun cambia(id: String, f: (com.gonzalo.atlas.motor.Mapa) -> Unit) { val ed = e.editor?.takeIf { it.mapa.id == id }; if (ed != null) { f(ed.mapa); ed.guardaYa() } else e.bib.abre(id)?.let { f(it); it.guardaMeta() }; e.listas++ }
    renombrar?.let { (id, nombre) -> DialogoTexto("Nuevo nombre", nombre, { renombrar = null }) { t -> cambia(id) { it.nombre = t }; renombrar = null } }
    tipoDe?.let { m ->
        Dialog({ tipoDe = null }) {
            Column(Modifier.background(T.PAPEL).border(2.dp, T.TINTA).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("«${m.nombre}» es un mapa de…", style = T.titular(22.sp))
                for ((k, nombre, _) in Catalogo.TIPOS_MAPA) Ficha(nombre, m.tipo == k) { cambia(m.id) { it.tipo = k }; tipoDe = null }
            }
        }
    }
    borrar?.let { (id, nombre) ->
        Dialog({ borrar = null }) {
            Column(Modifier.background(T.PAPEL).border(2.dp, T.TINTA).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("¿Borrar «$nombre»?", style = T.titular(24.sp))
                Text("Se borra el mapa de este dispositivo (lo subido a la nube sigue allí).", style = T.texto(14.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Boton("Borrar", lleno = true, color = T.ROJO) { e.cierraMapaSiEs(id); e.bib.borraMapa(id); e.listas++; borrar = null }
                    Boton("No") { borrar = null }
                }
            }
        }
    }
}
