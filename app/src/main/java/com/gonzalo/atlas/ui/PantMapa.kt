package com.gonzalo.atlas.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.editor.Herramienta
import com.gonzalo.atlas.editor.ModoTerritorio
import com.gonzalo.atlas.lienzo.CapaPx
import com.gonzalo.atlas.lienzo.Lienzo
import com.gonzalo.atlas.lienzo.Pinceles
import com.gonzalo.atlas.motor.CapaInfo
import com.gonzalo.atlas.motor.Catalogo
import com.gonzalo.atlas.motor.Leyenda
import com.gonzalo.atlas.motor.Linea
import com.gonzalo.atlas.motor.Lugar
import com.gonzalo.atlas.motor.Medidas
import com.gonzalo.atlas.motor.Rotulo
import com.gonzalo.atlas.motor.Sello
import com.gonzalo.atlas.motor.Territorio
import com.gonzalo.atlas.vista.Mesa
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** El mapa: la mesa de dibujo y, alrededor, las herramientas. [taller]: solo dibujo (para tus dibujos sueltos). */
@Composable
fun PantMapa(e: EstadoApp, taller: Boolean = false) {
    val ed = (if (taller) e.editorTaller else e.editor) ?: run {
        if (taller) { TallerSinDibujo(e); return }
        Vacio("Crea un mapa en la Portada"); return
    }
    @Suppress("UNUSED_VARIABLE") val cambios = ed.cambios
    var panel by remember(ed) { mutableStateOf<String?>(null) }
    val actividad = LocalContext.current
    val mesa = remember(ed, actividad) { e.mesaDe(ed, actividad) }
    LaunchedEffect(ed.aviso) { if (ed.aviso.isNotBlank()) { kotlinx.coroutines.delay(2600); ed.aviso = "" } }
    // Al elegir algo, se abre su panel
    LaunchedEffect(ed.seleccion) { if (ed.seleccion != null) panel = "sel" }

    Column(Modifier.fillMaxSize()) {
        if (taller) CabezaTaller(e)
        BarraHerramientas(e, ed, mesa, taller, panel) { panel = if (panel == it) null else it }
        Filete(1)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView({ mesa.also { (it.parent as? android.view.ViewGroup)?.removeView(it) } }, Modifier.fillMaxSize())
            if (!taller) ed.alAbrirMapa = { id -> e.abreVinculado(id) }
            if (!taller && e.pila.isNotEmpty()) {
                val anterior = remember(e.pila.size) { e.bib.abre(e.pila.last())?.nombre ?: "el mapa anterior" }
                Box(Modifier.align(Alignment.TopStart).padding(start = 70.dp, top = 10.dp).background(T.CABECERA).pulsable { e.vuelve() }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Etiqueta("← Volver a $anterior", tam = 12.sp, color = T.PAPEL)
                }
            }
            // La barra de la izquierda: tamaño, opacidad, deshacer
            Column(Modifier.align(Alignment.CenterStart).padding(start = 10.dp).background(T.PAPEL.copy(alpha = 0.92f)).border(1.dp, T.TINTA).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val goma = ed.herramienta == Herramienta.GOMA
                val tam = if (goma) ed.tamanoGoma else ed.tamano
                // Escala logarítmica: 1 a 400 px
                BarraVertical((ln(tam) / ln(400f)).coerceIn(0f, 1f), "${tam.roundToInt()}") { v -> val t = 400f.pow(v).coerceAtLeast(1f); if (goma) ed.tamanoGoma = t else ed.tamano = t }
                BarraVertical(ed.opacidad, "${(ed.opacidad * 100).roundToInt()}%") { ed.opacidad = it.coerceAtLeast(0.03f) }
                Flecha(atras = true, activa = ed.puedeDeshacer) { ed.deshacer() }
                Flecha(atras = false, activa = ed.puedeRehacer) { ed.rehacer() }
            }
            if (ed.aviso.isNotBlank() || ed.ocupado) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).background(T.CABECERA).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(if (ed.ocupado) "Un momento…" else ed.aviso, style = T.texto(14.sp, T.PAPEL))
                }
            }
            ed.regla?.let { r ->
                val km = kotlin.math.hypot(r[2] - r[0], r[3] - r[1]).toDouble() * ed.mapa.kmPorPx
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).background(T.CABECERA).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Distancia: " + Medidas.formatoKm(km) + if (!ed.mapa.escalaPuesta) " (pon la escala en Mundo)" else "", style = T.texto(14.sp, T.PAPEL))
                }
            }
            panel?.let { p ->
                Column(Modifier.align(Alignment.TopEnd).width(340.dp).fillMaxHeight().background(T.PAPEL).border(1.dp, T.TINTA).navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().background(T.CABECERA).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Etiqueta(titulos[p] ?: p, Modifier.weight(1f), 12.sp, T.PAPEL)
                        Etiqueta("Cerrar", Modifier.pulsable { panel = null; if (p == "sel") ed.seleccion = null }, 11.sp, T.SOBRE_CABECERA)
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (p) {
                            "pinceles" -> PanelPinceles(e, ed)
                            "calco" -> PanelCalco(e, ed, mesa)
                            "color" -> PanelColor(ed)
                            "capas" -> PanelCapas(e, ed, mesa)
                            "territorios" -> PanelTerritorios(e, ed, mesa)
                            "lugares" -> PanelLugares(ed)
                            "rutas" -> PanelRutas(ed)
                            "dibujos" -> PanelDibujos(e, ed)
                            "mas" -> PanelMas(e, ed, mesa, taller)
                            "sel" -> PanelSeleccion(e, ed, mesa)
                        }
                    }
                }
            }
        }
    }
}

private val conPanel = setOf(Herramienta.TERRITORIO, Herramienta.LUGAR, Herramienta.RUTA, Herramienta.SELLO)

private val titulos = mapOf("pinceles" to "Pinceles", "color" to "Color", "capas" to "Capas", "territorios" to "Territorios", "lugares" to "Lugares",
    "rutas" to "Rutas", "dibujos" to "Tus dibujos", "mas" to "Más", "sel" to "Lo elegido", "calco" to "Imagen para calcar")

@Composable
private fun BarraHerramientas(e: EstadoApp, ed: Editor, mesa: Mesa, taller: Boolean, panel: String?, abre: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(T.PAPEL).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable fun H(h: Herramienta, t: String = h.nombre, panelDe: String? = null) {
            val sel = ed.herramienta == h
            Box(Modifier.background(if (sel) T.TINTA else Color.Transparent).pulsable {
                if (sel && panelDe != null) abre(panelDe) else { ed.herramienta = h; ed.seleccion = null; if (h != Herramienta.REGLA) ed.regla = null; if (panelDe != null && (panel != null || h in conPanel)) { if (panel != panelDe) abre(panelDe) } }
            }.padding(horizontal = 10.dp, vertical = 8.dp)) { Etiqueta(t, tam = 12.sp, color = if (sel) T.PAPEL else T.TINTA) }
        }
        @Composable fun P(k: String, t: String) {
            Box(Modifier.border(1.5.dp, if (panel == k) T.ACENTO else T.TINTA).background(if (panel == k) T.ACENTO else Color.Transparent).pulsable { abre(k) }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Etiqueta(t, tam = 12.sp, color = if (panel == k) T.PAPEL else T.TINTA)
            }
        }
        H(Herramienta.PINCEL, Pinceles.de(ed.pincel).nombre, "pinceles")
        H(Herramienta.GOMA, "Goma", "pinceles")
        H(Herramienta.RELLENO)
        H(Herramienta.CUENTAGOTAS, "Gotas")
        if (!taller) {
            Box(Modifier.width(1.dp).height(26.dp).background(T.GRIS_CLARO))
            H(Herramienta.TERRITORIO, "Territorios", "territorios")
            H(Herramienta.LUGAR, "Lugares", "lugares")
            H(Herramienta.ROTULO, "Rótulos")
            H(Herramienta.RUTA, "Rutas", "rutas")
            H(Herramienta.SELLO, "Dibujos", "dibujos")
            H(Herramienta.REGLA, "Regla")
        }
        H(Herramienta.ELEGIR)
        Box(Modifier.width(1.dp).height(26.dp).background(T.GRIS_CLARO))
        P("calco", "Calco")
        P("capas", "Capas")
        Box(Modifier.size(30.dp).background(Color(ed.color)).border(2.dp, if (panel == "color") T.ACENTO else T.TINTA).pulsable { abre("color") })
        P("mas", "Más")
    }
}

// ==================================================================== paneles

@Composable
private fun PanelPinceles(e: EstadoApp, ed: Editor) {
    val goma = ed.herramienta == Herramienta.GOMA
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = com.gonzalo.atlas.lienzo.Pinceles.version
    var msg by remember { mutableStateOf("") }
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        msg = "Importando…"
        Thread {
            val r = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { com.gonzalo.atlas.lienzo.Procreate.importa(it.readBytes(), e.bib.dirRecursos, ctx.resources) } }
            android.os.Handler(android.os.Looper.getMainLooper()).post { msg = r.fold({ n -> if (n == 0) "No he encontrado pinceles en ese archivo" else "Importados: $n" }, { "No se pudo: ${it.message}" }) }
        }.start()
    }
    Deslizador("Estabilización", ed.estabilizacion, 0f..1f, if (ed.estabilizacion < 0.01f) "No" else "${(ed.estabilizacion * 100).roundToInt()}%") { ed.estabilizacion = it }
    Text("Cuanto más, más limpias salen las líneas (el pincel va detrás del lápiz, como la StreamLine de Procreate).", style = T.texto(11.sp, T.GRIS))
    Boton("Importar pinceles de Procreate") { importar.launch(arrayOf("*/*")) }
    if (msg.isNotBlank()) Text(msg, style = T.texto(12.sp, T.ACENTO))
    Filete(1)
    for (p in Pinceles.todos().filter { it.goma == goma }) {
        val sel = (if (goma) ed.goma else ed.pincel) == p.clave
        Column(Modifier.fillMaxWidth().background(if (sel) T.TINTA else Color.Transparent).border(1.dp, T.TINTA).pulsable {
            if (goma) { ed.goma = p.clave; ed.tamanoGoma = p.tamano } else { ed.pincel = p.clave; ed.tamano = p.tamano; ed.opacidad = p.opacidad }
        }.padding(10.dp)) {
            Text(p.nombre, style = T.nombre(18.sp, if (sel) T.PAPEL else T.TINTA))
            Text(p.desc, style = T.texto(12.sp, if (sel) T.SOBRE_CABECERA else T.GRIS))
        }
    }
}

@Composable
private fun PanelColor(ed: Editor) {
    SelectorColor(ed.color) { ed.color = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Color(ed.color)).border(1.5.dp, T.TINTA))
        Text("#%06X".format(ed.color and 0xFFFFFF), style = T.titular(18.sp))
        Spacer(Modifier.weight(1f))
        Boton("Guardar") { ed.ponColor(ed.color) }
    }
    Etiqueta("Recientes", tam = 10.sp, color = T.GRIS)
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (c in ed.recientes) Muestra(c, c == ed.color, 28) { ed.color = c } }
    Filete(1)
    Paletas(ed.color) { ed.ponColor(it) }
}

@Composable
private fun PanelCapas(e: EstadoApp, ed: Editor, mesa: Mesa) {
    val lz = ed.lienzo
    val ctx = LocalContext.current
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val b = runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return@rememberLauncherForActivityResult
        val vis = mesa.vista.visible()
        lz.enHilo { lz.importaImagen(b, "Imagen", vis.left + vis.width() * 0.1f, vis.top + vis.height() * 0.1f, vis.width() * 0.8f) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Boton("+ Capa", lleno = true) { lz.cambiaCapas("Nueva capa") { val c = CapaPx(CapaInfo("c" + System.nanoTime().toString(36), "Capa ${lz.capas.size + 1}")); val i = lz.capas.indexOfFirst { it.info.id == lz.activa }; lz.capas.add(if (i < 0) lz.capas.size else i + 1, c); lz.activa = c.info.id }; ed.capaActiva = "" }
        Boton("Imagen") { importar.launch("image/*") }
    }
    Text("Arriba, la que se ve encima. Toca una para dibujar en ella.", style = T.texto(12.sp, T.GRIS))
    for (c in lz.capas.reversed()) {
        val activa = c.info.id == lz.activa
        Column(Modifier.fillMaxWidth().border(if (activa) 2.5.dp else 1.dp, if (activa) T.ACENTO else T.TINTA).background(if (activa) T.CRUDO else Color.Transparent).pulsable { ed.ponCapa(c.info.id) }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.info.nombre, Modifier.weight(1f), style = T.nombre(17.sp))
                Etiqueta(if (c.info.visible) "Se ve" else "Oculta", Modifier.pulsable { c.info.visible = !c.info.visible; lz.cambiaCapas("Ver capa") {} }, 10.sp, if (c.info.visible) T.TINTA else T.GRIS_CLARO)
            }
            if (activa) {
                var nombre by remember(c) { mutableStateOf(c.info.nombre) }
                Campo(nombre, { nombre = it; c.info.nombre = it }, Modifier.fillMaxWidth().padding(top = 6.dp))
                Deslizador("Opacidad", c.info.opacidad, 0f..1f, "${(c.info.opacidad * 100).roundToInt()}%") { c.info.opacidad = it; mesa.piramide.todoSucio(); mesa.refresca(); ed.cambios++ }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((k, n) in Lienzo.FUSIONES) Ficha(n, c.info.fusion == k) { lz.cambiaCapas("Fusión") { c.info.fusion = k } }
                }
                Interruptor("Bloquear transparencia (pintas solo encima de lo que hay)", c.info.bloqueoAlfa) { v -> lz.cambiaCapas("Bloquear") { c.info.bloqueoAlfa = v } }
                Interruptor("Referencia (el relleno y los territorios miran sus líneas)", c.info.referencia) { v -> lz.cambiaCapas("Referencia") { c.info.referencia = v } }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Enlace("Subir") { lz.cambiaCapas("Ordenar") { val i = lz.capas.indexOf(c); if (i < lz.capas.size - 1) { lz.capas.removeAt(i); lz.capas.add(i + 1, c) } } }
                    Enlace("Bajar") { lz.cambiaCapas("Ordenar") { val i = lz.capas.indexOf(c); if (i > 0) { lz.capas.removeAt(i); lz.capas.add(i - 1, c) } } }
                    Enlace("Duplicar") { lz.cambiaCapas("Duplicar") { val n = CapaPx(CapaInfo.de(c.info.json().put("id", "c" + System.nanoTime().toString(36)).put("n", c.info.nombre + " (copia)"))); n.teselas.putAll(c.teselas); lz.capas.add(lz.capas.indexOf(c) + 1, n) } }
                    Enlace("Combinar abajo") { combinaAbajo(lz, c) }
                    Enlace("Vaciar") { lz.cambiaCapas("Vaciar capa") { c.teselas.clear() } }
                    if (lz.capas.size > 1) Enlace("Borrar", color = T.ROJO) { lz.cambiaCapas("Borrar capa") { lz.capas.remove(c); lz.activa = lz.capas.last().info.id } }
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val u = e.listas
}

/** Funde la capa con la de debajo (con su opacidad y su modo). */
private fun combinaAbajo(lz: Lienzo, c: CapaPx) {
    lz.cambiaCapas("Combinar") {
        val i = lz.capas.indexOf(c); if (i <= 0) return@cambiaCapas
        val abajo = lz.capas[i - 1]
        val claves = (c.teselas.keys + abajo.teselas.keys).toSet()
        val nuevo = HashMap<Long, android.graphics.Bitmap>()
        for (k in claves) {
            val b = abajo.teselas[k]?.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: android.graphics.Bitmap.createBitmap(Lienzo.L, Lienzo.L, android.graphics.Bitmap.Config.ARGB_8888)
            c.teselas[k]?.let { arriba -> android.graphics.Canvas(b).drawBitmap(arriba, 0f, 0f, android.graphics.Paint().apply { alpha = (c.info.opacidad * 255).toInt(); blendMode = Lienzo.fusion(c.info.fusion) }) }
            nuevo[k] = b
        }
        abajo.teselas.clear(); abajo.teselas.putAll(nuevo)
        lz.capas.remove(c); lz.activa = abajo.info.id
    }
}

@Composable
private fun PanelTerritorios(e: EstadoApp, ed: Editor, mesa: Mesa) {
    val m = ed.mapa
    Text("Dibuja las fronteras con el pincel (mejor en la capa «Líneas») y luego toca dentro de cada zona para decir de quién es.", style = T.texto(12.sp, T.GRIS))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Ficha("Tocar dentro", ed.modoTerritorio == ModoTerritorio.ZONA) { ed.modoTerritorio = ModoTerritorio.ZONA }
        Ficha("Pintar", ed.modoTerritorio == ModoTerritorio.PINCEL) { ed.modoTerritorio = ModoTerritorio.PINCEL }
        Ficha("Quitar", ed.modoTerritorio == ModoTerritorio.QUITAR) { ed.modoTerritorio = ModoTerritorio.QUITAR }
    }
    if (ed.modoTerritorio != ModoTerritorio.ZONA) Deslizador("Grosor", ed.radioTerritorio, 4f..200f, "${ed.radioTerritorio.roundToInt()} px") { ed.radioTerritorio = it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Boton("+ Imperio") { ed.nuevoTerritorio("Imperio", 0)?.let { ed.seleccion = it } }
        Boton("+ Reino", lleno = true) { ed.nuevoTerritorio("Reino", m.territorio(ed.territorio)?.takeIf { it.tipo == "Imperio" }?.id ?: 0)?.let { ed.seleccion = it } }
    }
    Boton("+ Provincia de «${m.territorio(ed.territorio)?.nombre ?: "…"}»", activo = ed.territorio != 0) { ed.nuevoTerritorio("Provincia", ed.territorio)?.let { ed.seleccion = it } }
    Filete(1)
    @Composable fun Arbol(padre: Int, nivel: Int) {
        for (t in m.hijos(padre).sortedBy { it.nombre.lowercase() }) {
            val sel = ed.territorio == t.id
            Row(Modifier.fillMaxWidth().background(if (sel) T.CRUDO else Color.Transparent).border(if (sel) 2.dp else 0.dp, if (sel) T.ACENTO else Color.Transparent)
                .pulsable { ed.territorio = t.id; ed.herramienta = Herramienta.TERRITORIO }.padding(start = (8 + nivel * 18).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).background(Color(t.color)).border(1.dp, T.TINTA))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.nombre, style = T.nombre(if (nivel == 0) 18.sp else 16.sp))
                    Text(t.tipo + " · " + Medidas.formatoKm2(Medidas.km2(m, ed.superficies.getOrElse(t.id) { 0L })), style = T.texto(11.sp, T.GRIS))
                }
                Etiqueta("Editar", Modifier.pulsable { ed.territorio = t.id; ed.seleccion = t }, 10.sp, T.ACENTO)
            }
            Arbol(t.id, nivel + 1)
        }
    }
    if (m.territorios.isEmpty()) Text("Aún no hay territorios.", style = T.nombre(16.sp, T.GRIS)) else Arbol(0, 0)
    @Suppress("UNUSED_VARIABLE") val u = e.listas + mesa.hashCode()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelLugares(ed: Editor) {
    Text("Toca el mapa para poner un lugar del tipo elegido. Tócalo otra vez para editarlo.", style = T.texto(12.sp, T.GRIS))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in Catalogo.LUGARES) Ficha(t.nombre, ed.tipoLugar == t.clave) { ed.tipoLugar = t.clave; ed.herramienta = Herramienta.LUGAR }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelRutas(ed: Editor) {
    Text("Dibuja la ruta arrastrando. Se puede elegir después para cambiarla o medirla.", style = T.texto(12.sp, T.GRIS))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in Catalogo.RUTAS) Ficha(t.nombre, ed.tipoRuta == t.clave) { ed.tipoRuta = t.clave; ed.herramienta = Herramienta.RUTA }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelDibujos(e: EstadoApp, ed: Editor) {
    @Suppress("UNUSED_VARIABLE") val v = e.recursos.version
    val lista = e.recursos.lista()
    if (lista.isEmpty()) Text("Aún no tienes dibujos sueltos. Dibuja uno en el Taller (montañas, bosques, ciudades…) y guárdalo: luego lo pones aquí tantas veces como quieras.", style = T.texto(13.sp, T.GRIS))
    Deslizador("Tamaño al poner", ed.escalaSello, 0.2f..6f, "×%.1f".format(ed.escalaSello)) { ed.escalaSello = it }
    for ((cat, l) in lista.groupBy { it.categoria }) {
        Etiqueta(cat, tam = 10.sp, color = T.GRIS)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in l) {
                val b = e.recursos.bitmap(r.id)
                Box(Modifier.size(72.dp).border(if (ed.recurso == r.id) 3.dp else 1.dp, if (ed.recurso == r.id) T.ACENTO else T.TINTA).background(T.CRUDO)
                    .pulsable { ed.recurso = r.id; ed.herramienta = Herramienta.SELLO }.padding(4.dp), contentAlignment = Alignment.Center) {
                    if (b != null) Image(b.asImageBitmap(), r.nombre, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun PanelMas(e: EstadoApp, ed: Editor, mesa: Mesa, taller: Boolean) {
    val m = ed.mapa
    Interruptor("Girar el lienzo con dos dedos", mesa.girar) { mesa.girar = it; ed.cambios++ }
    Interruptor("Dibujar también con el dedo (aunque uses lápiz)", mesa.dedoDibuja) { mesa.dedoDibuja = it; ed.cambios++ }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Boton("Norte arriba") { mesa.endereza() }
        Boton("Ver todo") { mesa.encuadra(false) }
    }
    if (!taller) {
        Filete(1)
        Interruptor("Cuadrícula (casillas en km)", mesa.opciones.cuadricula) { mesa.opciones.cuadricula = it; mesa.refresca(); ed.cambios++ }
        Interruptor("Ver los territorios", m.verTerritorios) { m.verTerritorios = it; mesa.velo.olvida(); mesa.piramide.todoSucio(); m.avisaVector() }
        Deslizador("Color de los territorios", m.tinteTerritorios, 0f..0.8f, "${(m.tinteTerritorios * 100).roundToInt()}%") { m.tinteTerritorios = it; mesa.velo.olvida(); mesa.piramide.todoSucio(); mesa.refresca(); ed.cambios++ }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Etiqueta("Colorear por", tam = 10.sp, color = T.GRIS)
            Ficha("Imperios", m.colorPorNivel == 0) { m.colorPorNivel = 0; mesa.velo.olvida(); mesa.piramide.todoSucio(); m.avisaVector() }
            Ficha("Reinos", m.colorPorNivel == 1) { m.colorPorNivel = 1; mesa.velo.olvida(); mesa.piramide.todoSucio(); m.avisaVector() }
            Ficha("Todo", m.colorPorNivel == 2) { m.colorPorNivel = 2; mesa.velo.olvida(); mesa.piramide.todoSucio(); m.avisaVector() }
        }
        Filete(1)
        Etiqueta("Leyenda", tam = 11.sp)
        Interruptor("Poner la leyenda en el mapa", m.leyenda.visible) { v -> ed.cambiaVector("Leyenda") { m.leyenda.visible = v } }
        if (m.leyenda.visible) {
            var t by remember { mutableStateOf(m.leyenda.titulo) }
            Campo(t, { t = it; m.leyenda.titulo = it; m.avisaVector() }, Modifier.fillMaxWidth(), "Título")
            Interruptor("Imperios y reinos", m.leyenda.territorios) { v -> ed.cambiaVector("Leyenda") { m.leyenda.territorios = v } }
            Interruptor("Tipos de lugar", m.leyenda.lugares) { v -> ed.cambiaVector("Leyenda") { m.leyenda.lugares = v } }
            Interruptor("Escala", m.leyenda.escalaKm) { v -> ed.cambiaVector("Leyenda") { m.leyenda.escalaKm = v } }
            Deslizador("Tamaño", m.leyenda.escala, 0.3f..6f, "×%.1f".format(m.leyenda.escala)) { m.leyenda.escala = it; m.avisaVector() }
            Text("Para moverla: herramienta Elegir, tócala y arrástrala.", style = T.texto(12.sp, T.GRIS))
        }
    }
    Filete(1)
    Etiqueta("Papel", tam = 11.sp)
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((n, c) in Catalogo.PAPELES) Ficha(n, m.papel == c) { m.papel = c; mesa.refresca(); m.avisaVector() }
    }
    var otro by remember { mutableStateOf(false) }
    Enlace(if (otro) "Cerrar el color" else "Otro color de fondo") { otro = !otro }
    if (otro) SelectorColor(if (android.graphics.Color.alpha(m.papel) == 0) 0xFFFFFFFF.toInt() else m.papel) { m.papel = it; mesa.refresca(); ed.cambios++; m.avisaVector() }
    Interruptor("Grano del papel", m.granoPapel) { m.granoPapel = it; mesa.refresca(); m.avisaVector() }
    Filete(1)
    Boton("Guardar imagen (PNG)") { e.exporta(ed, mesa) }
    @Suppress("UNUSED_VARIABLE") val u = e.listas
}

@Composable
private fun PanelSeleccion(e: EstadoApp, ed: Editor, mesa: Mesa) {
    when (val s = ed.seleccion) {
        is Territorio -> EditaTerritorio(e, ed, s)
        is Lugar -> EditaLugar(e, ed, s)
        is Rotulo -> EditaRotulo(ed, s)
        is Linea -> EditaRuta(ed, s)
        is Sello -> EditaSello(ed, s)
        is Leyenda -> { Text("La leyenda: arrástrala con «Elegir» para moverla; su contenido, en «Más».", style = T.texto(13.sp)); Boton("Quitar la leyenda", color = T.ROJO) { ed.borraSeleccion() } }
        else -> Text("Toca algo del mapa con «Elegir» (un lugar, un rótulo, una ruta, un nombre, uno de tus dibujos) para cambiarlo.", style = T.texto(13.sp, T.GRIS))
    }
    @Suppress("UNUSED_VARIABLE") val u = mesa.hashCode()
}

/** Un campo de texto que cambia lo vectorial al escribir (y se apunta para deshacer al salir). */
@Composable
private fun CampoVector(ed: Editor, valor: String, pista: String, lineas: Int = 1, poner: (String) -> Unit) {
    var t by remember(valor.hashCode(), ed.seleccion) { mutableStateOf(valor) }
    Campo(t, { t = it; poner(it); ed.mapa.avisaVector() }, Modifier.fillMaxWidth(), pista, lineas = lineas)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditaTerritorio(e: EstadoApp, ed: Editor, t: Territorio) {
    val m = ed.mapa
    CampoVector(ed, t.nombre, "Nombre") { t.nombre = it }
    Text(t.tipo + " · " + Medidas.formatoKm2(Medidas.km2(m, ed.superficies.getOrElse(t.id) { 0L })) + if (!m.escalaPuesta) " (pon la escala en Mundo)" else "", style = T.texto(13.sp, T.GRIS))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (k in Catalogo.TIPOS_TERRITORIO) Ficha(k, t.tipo == k) { ed.cambiaVector("Tipo") { t.tipo = k } }
    }
    Etiqueta("Color", tam = 10.sp, color = T.GRIS)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in Catalogo.COLORES) Muestra(c, t.color == c, 28) { ed.cambiaVector("Color") { t.color = c } }
    }
    Etiqueta("Depende de", tam = 10.sp, color = T.GRIS)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Ficha("Nadie (independiente)", t.padre == 0) { ed.cambiaVector("Depende") { t.padre = 0 } }
        for (o in m.territorios.filter { it.id != t.id && m.linaje(it.id).none { a -> a.id == t.id } }) Ficha(o.nombre, t.padre == o.id) { ed.cambiaVector("Depende") { t.padre = o.id } }
    }
    CampoVector(ed, t.capital, "Capital") { t.capital = it }
    Interruptor("Escribir su nombre en el mapa", t.verNombre) { v -> ed.cambiaVector("Nombre") { t.verNombre = v } }
    Deslizador("Tamaño del nombre", if (t.tam <= 0f) 0f else t.tam, 0f..400f, if (t.tam <= 0f) "Auto" else "${t.tam.roundToInt()}") { t.tam = if (it < 4f) 0f else it; m.avisaVector() }
    Deslizador("Giro", t.giro, -90f..90f, "${t.giro.roundToInt()}°") { t.giro = it; m.avisaVector() }
    Deslizador("Curva", t.curva, -0.5f..0.5f, "%.2f".format(t.curva)) { t.curva = it; m.avisaVector() }
    Text("Para mover el nombre: «Elegir», tócalo y arrástralo.", style = T.texto(12.sp, T.GRIS))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Boton("Pintarlo", lleno = true) { ed.territorio = t.id; ed.herramienta = Herramienta.TERRITORIO; ed.seleccion = null }
        Boton("Su ficha") { e.fichaDe(m, "t", t.id.toString(), "reino", t.nombre) }
    }
    MapaVinculado(e, ed, t.mapa, t.nombre) { t.mapa = it }
    Boton("Borrar territorio", color = T.ROJO) { ed.borraTerritorio(t) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditaLugar(e: EstadoApp, ed: Editor, l: Lugar) {
    CampoVector(ed, l.nombre, "Nombre") { l.nombre = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in Catalogo.LUGARES) Ficha(t.nombre, l.tipo == t.clave) { ed.cambiaVector("Tipo") { l.tipo = t.clave } }
    }
    Deslizador("Importancia", l.tamano.toFloat(), 1f..10f, "${l.tamano}") { l.tamano = it.roundToInt(); ed.mapa.avisaVector() }
    CampoVector(ed, l.nota, "Nota", 3) { l.nota = it }
    val terr = ed.lienzo.territorioEn(l.x.toInt(), l.y.toInt()).takeIf { it != 0 }?.let { ed.mapa.territorio(it) }
    if (terr != null) Text("En: " + ed.mapa.linaje(terr.id).joinToString(" · ") { it.nombre }, style = T.texto(13.sp, T.GRIS))
    MapaVinculado(e, ed, l.mapa, l.nombre) { l.mapa = it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Boton("Su ficha") { e.fichaDe(ed.mapa, "p", l.id, "lugar", l.nombre) }
        Boton("Borrar", color = T.ROJO) { ed.borraSeleccion() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditaRotulo(ed: Editor, r: Rotulo) {
    CampoVector(ed, r.texto, "Texto") { r.texto = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((k, n) in listOf("serif" to "Serif", "cursiva" to "Cursiva", "negrita" to "Negrita", "grotesca" to "Grotesca")) Ficha(n, r.estilo == k) { ed.cambiaVector("Letra") { r.estilo = k } }
    }
    Deslizador("Tamaño", r.tam, 6f..600f, "${r.tam.roundToInt()}") { r.tam = it; ed.mapa.avisaVector() }
    Deslizador("Espaciado", r.espaciado, 0f..1f, "%.2f".format(r.espaciado)) { r.espaciado = it; ed.mapa.avisaVector() }
    Deslizador("Giro", r.giro, -180f..180f, "${r.giro.roundToInt()}°") { r.giro = it; ed.mapa.avisaVector() }
    Deslizador("Curva", r.curva, -0.5f..0.5f, "%.2f".format(r.curva)) { r.curva = it; ed.mapa.avisaVector() }
    Boton("Borrar", color = T.ROJO) { ed.borraSeleccion() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditaRuta(ed: Editor, l: Linea) {
    CampoVector(ed, l.nombre, "Nombre (opcional)") { l.nombre = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in Catalogo.RUTAS) Ficha(t.nombre, l.tipo == t.clave) { ed.cambiaVector("Ruta") { l.tipo = t.clave } }
    }
    var largo = 0.0; for (i in 1 until l.n) largo += kotlin.math.hypot((l.xs[i] - l.xs[i - 1]).toDouble(), (l.ys[i] - l.ys[i - 1]).toDouble())
    Text("Largo: " + Medidas.formatoKm(largo * ed.mapa.kmPorPx), style = T.titular(18.sp))
    Boton("Borrar", color = T.ROJO) { ed.borraSeleccion() }
}

@Composable
private fun EditaSello(ed: Editor, s: Sello) {
    Deslizador("Tamaño", s.escala, 0.1f..10f, "×%.1f".format(s.escala)) { s.escala = it; ed.mapa.avisaVector() }
    Deslizador("Giro", s.giro, -180f..180f, "${s.giro.roundToInt()}°") { s.giro = it; ed.mapa.avisaVector() }
    Interruptor("Espejo", s.espejo) { v -> ed.cambiaVector("Espejo") { s.espejo = v } }
    Boton("Borrar", color = T.ROJO) { ed.borraSeleccion() }
}

@Suppress("unused") private val sinUsar = Lienzo.L

/** Deshacer / rehacer: una flecha curva dibujada (las letras de la familia no traen el símbolo). */
@Composable
private fun Flecha(atras: Boolean, activa: Boolean, alPulsar: () -> Unit) {
    val col = if (activa) T.TINTA else T.GRIS_CLARO
    Box(Modifier.size(40.dp).border(2.dp, col).pulsable { if (activa) alPulsar() }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
            val s = size.width
            val k = if (atras) 1f else -1f
            val cx = s / 2
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx - k * s * 0.3f, s * 0.35f)
                cubicTo(cx - k * s * 0.05f, s * 0.05f, cx + k * s * 0.45f, s * 0.2f, cx + k * s * 0.4f, s * 0.55f)
                cubicTo(cx + k * s * 0.36f, s * 0.8f, cx + k * s * 0.1f, s * 0.9f, cx - k * s * 0.1f, s * 0.85f)
            }
            drawPath(p, col, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
            val punta = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx - k * s * 0.42f, s * 0.12f); lineTo(cx - k * s * 0.38f, s * 0.48f); lineTo(cx - k * s * 0.06f, s * 0.36f); close()
            }
            drawPath(punta, col)
        }
    }
}

@Composable
private fun PanelCalco(e: EstadoApp, ed: Editor, mesa: Mesa) {
    val m = ed.mapa
    val ctx = LocalContext.current
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val dir = m.dir ?: return@rememberLauncherForActivityResult
        Thread {
            val b = runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return@Thread
            // Como mucho 4.096 px de lado (más no se nota al calcar y gasta memoria)
            val k = minOf(1f, 4096f / maxOf(b.width, b.height))
            val r = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
            java.io.File(dir, "calco.png").outputStream().use { r.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val vis = mesa.vista.visible()
                ed.cambiaVector("Calco") { m.calco = com.gonzalo.atlas.motor.Calco(true, vis.centerX(), vis.centerY(), vis.width() * 0.7f, -mesa.vista.giro, 0.5f) }
                ed.herramienta = Herramienta.CALCO
                mesa.refresca()
            }
        }.start()
    }
    Text("Pon detrás una imagen (una casa de internet, una foto, un boceto) y dibuja encima para darle tu estilo. No sale al exportar ni en la lámina.", style = T.texto(13.sp, T.GRIS))
    Boton(if (m.calco == null) "Elegir imagen" else "Cambiar imagen", lleno = true) { importar.launch("image/*") }
    val k = m.calco ?: return
    Deslizador("Opacidad", k.opacidad, 0.05f..1f, "${(k.opacidad * 100).roundToInt()}%") { k.opacidad = it; mesa.refresca(); ed.cambios++ }
    Interruptor("Se ve", k.visible) { k.visible = it; mesa.refresca(); m.avisaVector() }
    Interruptor("Encima del dibujo (si no, detrás)", k.encima) { k.encima = it; mesa.refresca(); m.avisaVector() }
    Boton(if (ed.herramienta == Herramienta.CALCO) "Moviendo el calco: arrastra, pellizca, gira" else "Mover, escalar y girar el calco", lleno = ed.herramienta == Herramienta.CALCO) {
        ed.herramienta = if (ed.herramienta == Herramienta.CALCO) Herramienta.PINCEL else Herramienta.CALCO; mesa.refresca()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Boton("Reflejar") { k.ancho = -k.ancho; mesa.refresca(); m.avisaVector() }
        Boton("Quitar", color = T.ROJO) { ed.cambiaVector("Quitar calco") { m.calco = null }; ed.herramienta = Herramienta.PINCEL; m.dir?.let { java.io.File(it, "calco.png").delete() }; mesa.refresca() }
    }
    @Suppress("UNUSED_VARIABLE") val u = e.listas
}

/** El mapa que se abre al tocar algo (otro mapa del mismo mundo), o crear uno nuevo para eso. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapaVinculado(e: EstadoApp, ed: Editor, actual: String, nombre: String, poner: (String) -> Unit) {
    val otros = remember(e.listas) { e.bib.mapas().filter { it.mundo == ed.mapa.mundo && it.id != ed.mapa.id } }
    Etiqueta("Su mapa (se abre al tocarlo con «Mover»)", tam = 10.sp, color = T.GRIS)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Ficha("Ninguno", actual.isBlank()) { ed.cambiaVector("Vincular") { poner("") } }
        for (o in otros) Ficha(o.nombre, actual == o.id) { ed.cambiaVector("Vincular") { poner(o.id) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (actual.isNotBlank()) Boton("Abrir su mapa", lleno = true) { e.abreVinculado(actual) }
        Boton("+ Dibujar su mapa") {
            val m = e.bib.creaMapa(ed.mapa.mundo, nombre, 0, 0, ed.mapa.papel)
            ed.cambiaVector("Vincular") { poner(m.id) }
            ed.guardaYa()
            e.listas++
            e.abreVinculado(m.id)
        }
    }
}
