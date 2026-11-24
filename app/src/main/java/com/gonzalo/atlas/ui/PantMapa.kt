package com.gonzalo.atlas.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
    LaunchedEffect(ed.aviso) { if (ed.aviso.isNotBlank()) { kotlinx.coroutines.delay(2200); ed.aviso = "" } }
    LaunchedEffect(ed.seleccion) { if (ed.seleccion != null) panel = "sel" }
    LaunchedEffect(ed.herramienta) {
        if (ed.herramienta == Herramienta.TRANSFORMAR) panel = "transformar" else if (panel == "transformar") panel = null
        if (ed.herramienta != Herramienta.LICUAR && panel == "licuar") panel = null
    }

    Box(Modifier.fillMaxSize().background(O.MESA)) {
        AndroidView({ mesa.also { (it.parent as? android.view.ViewGroup)?.removeView(it) } }, Modifier.fillMaxSize())
        if (!taller) ed.alAbrirMapa = { id -> e.abreVinculado(id) }
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            BarraSuperior(e, ed, mesa, taller, panel) { p -> panel = if (panel == p) null else p }
            if (taller) CabezaTaller(e)
        }
        BarraLateral(ed, Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
        if (!taller && e.pila.isNotEmpty()) {
            val anterior = remember(e.pila.size) { e.bib.abre(e.pila.last())?.nombre ?: "el mapa anterior" }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).clip(RoundedCornerShape(20.dp)).background(O.CABECERA).pulsableO { e.vuelve() }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                EtiquetaO("← Volver a $anterior", tam = 12.sp)
            }
        }
        // Viendo un año de la línea del tiempo: que se sepa (y volver al mapa tal cual de un toque)
        @Suppress("UNUSED_VARIABLE") val alDiaAno = ed.cambios
        ed.mapa.anoVista?.let { a ->
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = if (!taller && e.pila.isNotEmpty()) 70.dp else 18.dp).clip(RoundedCornerShape(20.dp)).background(O.BLOQUE2), verticalAlignment = Alignment.CenterVertically) {
                Text("AÑO $a", Modifier.pulsableO { panel = "tiempo" }.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), style = O.titular(14.sp, O.TINTA))
                Text("✕ Sin año", Modifier.pulsableO { verAno(ed, mesa, null) }.padding(end = 16.dp, top = 10.dp, bottom = 10.dp), style = O.texto(12.sp, O.TINTA))
            }
        }
        if (ed.aviso.isNotBlank() || ed.ocupado) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 100.dp).clip(RoundedCornerShape(20.dp)).background(O.CABECERA.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(if (ed.ocupado) "Un momento…" else ed.aviso, style = O.texto(13.sp, O.TINTA))
            }
        }
        ed.regla?.let { r ->
            val km = kotlin.math.hypot(r[2] - r[0], r[3] - r[1]).toDouble() * ed.mapa.kmPorPx
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).clip(RoundedCornerShape(20.dp)).background(O.CABECERA).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Distancia: " + Medidas.formatoKm(km) + if (!ed.mapa.escalaPuesta) " (fija la escala en Mundo)" else "", style = O.texto(14.sp, O.TINTA))
            }
        }
        panel?.let { p ->
            val ancho = if (p == "pinceles" || p == "estudio") 520.dp else if (p == "consulta") 440.dp else 380.dp
            Column(Modifier.align(Alignment.TopEnd).padding(top = if (taller) 110.dp else 144.dp, start = 10.dp, end = 10.dp, bottom = 10.dp).widthIn(max = ancho).fillMaxWidth().heightIn(max = 760.dp)
                .shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(O.PAPEL)) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(titulos[p] ?: p, Modifier.weight(1f), style = O.titular(20.sp))
                    if (p == "capas") {
                        BotonO("+ Grupo") {
                            val c = ed.lienzo.capa(ed.lienzo.activa)?.let { if (it.info.mascara) ed.lienzo.duenaDe(it) else it }
                            if (c == null) ed.avisa("Elige antes una capa") else ed.lienzo.cambiaCapas("Agrupar") {
                                if (c.info.grupo.isNotEmpty()) com.gonzalo.atlas.lienzo.Grupos.desagrupa(ed.lienzo, c)
                                val gr = com.gonzalo.atlas.lienzo.Grupos.agrupa(ed.lienzo, c, null)
                                ed.avisa("Grupo nuevo, «${gr.nombre}», con «${c.info.nombre}». Mete más capas desde sus opciones («⋯» → Grupo).")
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        BotonO("+ Capa nueva", lleno = true) { nuevaCapa(ed) }
                    }
                    Text("✕", Modifier.pulsableO { panel = null; if (p == "sel") ed.seleccion = null }.padding(8.dp), style = O.texto(16.sp, O.GRIS))
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (p) {
                        "pinceles" -> BibliotecaPinceles(e, ed) { panel = "estudio" }
                        "estudio" -> EstudioPincel(ed) { panel = "pinceles" }
                        "color" -> PanelColor(ed)
                        "capas" -> PanelCapas(e, ed, mesa)
                        "acciones" -> PanelAcciones(e, ed, mesa, taller)
                        "ajustes" -> PanelAjustes(ed) { panel = it }
                        "licuar" -> PanelLicuar(ed)
                        "marco" -> PanelMarco(ed)
                        "consulta" -> PanelConsulta(e, ed)
                        "continente" -> PanelContinente(ed, mesa)
                        "tierras" -> PanelTierras(ed, mesa) { panel = it }
                        "transformar" -> PanelTransformar(ed, mesa)
                        "mundo" -> PanelMundo(ed) { panel = it }
                        "territorios" -> PanelTerritorios(e, ed, mesa)
                        "lugares" -> PanelLugares(ed)
                        "rutas" -> PanelRutas(ed)
                        "dibujos" -> PanelDibujos(e, ed)
                        "calco" -> PanelCalco(e, ed, mesa)
                        "secciones" -> PanelSecciones(ed, mesa)
                        "costa" -> PanelCosta(ed)
                        "viajes" -> PanelViajes(e, ed)
                        "tiempo" -> PanelTiempo(e, ed, mesa)
                        "sel" -> PanelSeleccion(e, ed, mesa)
                    }
                }
            }
        }
    }
}

private val titulos = mapOf("pinceles" to "Biblioteca de pinceles", "estudio" to "Estudio del pincel", "color" to "Color", "capas" to "Capas",
    "acciones" to "Acciones", "ajustes" to "Ajustes", "transformar" to "Transformar", "mundo" to "Mundo", "territorios" to "Territorios",
    "lugares" to "Lugares", "rutas" to "Rutas", "dibujos" to "Tus dibujos", "calco" to "Imagen para calcar", "secciones" to "Secciones del terreno", "sel" to "Lo elegido",
    "marco" to "Selección", "licuar" to "Licuar", "continente" to "Generar un continente", "tierras" to "Tierras generadas", "consulta" to "Política del mundo",
    "costa" to "Acabado de la costa", "viajes" to "Viajes", "tiempo" to "Línea del tiempo")

private fun nuevaCapa(ed: Editor) {
    val lz = ed.lienzo
    lz.cambiaCapas("Nueva capa") {
        // (Un nombre que no esté ya: «Capa 3», «Capa 4»…)
        var n = lz.capas.count { !it.info.mascara } + 1; while (lz.capas.any { it.info.nombre == "Capa $n" }) n++
        // Encima de la elegida (y de su máscara), en su mismo grupo
        val activa = lz.capa(lz.activa)
        val c = CapaPx(CapaInfo("c" + System.nanoTime().toString(36), "Capa $n", grupo = activa?.info?.grupo.orEmpty()))
        lz.capas.add(com.gonzalo.atlas.lienzo.Grupos.trasUnidad(lz.capas, activa), c); lz.activa = c.info.id
        ed.avisa("Capa nueva: «Capa $n». Es la elegida: lo que pintes va en ella.")
    }
    ed.capaActiva = ""
}

private fun borraCapa(ed: Editor, c: CapaPx) {
    val lz = ed.lienzo
    if (lz.capas.count { !it.info.mascara } <= 1) { ed.avisa("Es la única capa: no se puede borrar (puedes vaciarla)"); return }
    lz.cambiaCapas("Borrar capa") {
        val i = lz.capas.indexOf(c); if (i < 0) return@cambiaCapas
        lz.capas.removeAll(com.gonzalo.atlas.lienzo.Grupos.unidad(lz.capas, c))
        if (lz.capa(lz.activa) == null) lz.activa = (lz.capas.take(i).lastOrNull { !it.info.mascara } ?: lz.capas.first { !it.info.mascara }).info.id
        ed.avisa("Capa «${c.info.nombre}» borrada. Deshacer la recupera.")
    }
    ed.capaActiva = ""
}

/** Un botón con icono de la barra de arriba (claro si está elegido, como en Procreate). */
@Composable
private fun Icono(i: ImageVector, desc: String, activo: Boolean = false, alPulsar: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (activo) O.CRUDO else Color.Transparent).pulsableO(alPulsar), contentAlignment = Alignment.Center) {
        Icon(i, desc, Modifier.size(24.dp), tint = if (activo) O.ACENTO else O.TINTA)
    }
}

@Composable
private fun BarraSuperior(e: EstadoApp, ed: Editor, mesa: Mesa, taller: Boolean, panel: String?, abre: (String) -> Unit) {
    val h = ed.herramienta
    fun herramienta(x: Herramienta, p: String) { if (ed.herramienta == x) abre(p) else { ed.herramienta = x; ed.seleccion = null; ed.regla = null; if (panel != null && panel != p) abre(p) } }
    // En un móvil no cabe todo en una fila: las herramientas de dibujo van en una segunda
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val estrecho = maxWidth < 700.dp
        @Composable fun Izquierda() {
            Text("Galería", Modifier.pulsableO { ed.guardaYa(); if (taller) e.cierraDibujo() else e.dibujando = false }.padding(horizontal = 10.dp, vertical = 8.dp), style = O.texto(17.sp, O.TINTA))
            Icono(Icons.Outlined.Build, "Acciones", panel == "acciones") { abre("acciones") }
            Icono(Icons.Outlined.AutoFixHigh, "Ajustes", panel == "ajustes") { abre("ajustes") }
            Icono(Icons.Outlined.HighlightAlt, "Selección", h == Herramienta.SELECCION || panel == "marco") { if (h != Herramienta.SELECCION) { ed.herramienta = Herramienta.SELECCION; ed.seleccion = null; if (panel != "marco") abre("marco") } else abre("marco") }
            Icono(Icons.Outlined.NearMe, "Transformar", h == Herramienta.TRANSFORMAR) { ed.herramienta = if (h == Herramienta.TRANSFORMAR) Herramienta.PINCEL else Herramienta.TRANSFORMAR; if (h == Herramienta.TRANSFORMAR) ed.transformacion = null }
            Icono(Icons.Outlined.Wallpaper, "Calco", panel == "calco" || h == Herramienta.CALCO) { abre("calco") }
            if (!taller) {
                Spacer(Modifier.width(4.dp)); Box(Modifier.width(1.dp).height(26.dp).background(O.GRIS_CLARO)); Spacer(Modifier.width(4.dp))
                Icono(Icons.Outlined.Public, "Mundo", panel == "mundo" || panel == "continente" || panel == "tierras" || h in herramientasMundo) { abre("mundo") }
                if (h in herramientasMundo && !estrecho) Text(h.nombre, Modifier.padding(start = 4.dp), style = O.versalitas(11.sp, O.ACENTO))
            }
        }
        @Composable fun Derecha() {
            Icono(Icons.Outlined.Brush, "Pintar", h == Herramienta.PINCEL) { herramienta(Herramienta.PINCEL, "pinceles") }
            Icono(Icons.Outlined.TouchApp, "Difuminar", h == Herramienta.DIFUMINAR) { herramienta(Herramienta.DIFUMINAR, "pinceles") }
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (h == Herramienta.GOMA) O.CRUDO else Color.Transparent).pulsableO { herramienta(Herramienta.GOMA, "pinceles") }, contentAlignment = Alignment.Center) { IconoGoma(if (h == Herramienta.GOMA) O.ACENTO else O.TINTA) }
            // El bote de pintura: se elige y se toca dentro de la zona que se quiere rellenar del color elegido
            Icono(Icons.Outlined.FormatColorFill, "Rellenar", h == Herramienta.RELLENO) {
                if (h == Herramienta.RELLENO) ed.herramienta = Herramienta.PINCEL
                else { ed.herramienta = Herramienta.RELLENO; ed.seleccion = null; ed.avisa("Bote de pintura: toca dentro de una zona y se rellena del color elegido (sobre un color, lo cambia)") }
            }
            Icono(Icons.Outlined.Layers, "Capas", panel == "capas") { abre("capas") }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(32.dp).clip(CircleShape).background(Color(ed.color)).border(2.dp, if (panel == "color") O.ACENTO else O.TINTA, CircleShape).pulsableO { abre("color") })
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.fillMaxWidth().background(O.CABECERA.copy(alpha = 0.96f)).statusBarsPadding()) {
            if (!taller) FilaMundo(e, ed.mapa, panel == "consulta") { abre("consulta") }
            Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Izquierda()
                if (!estrecho) { Spacer(Modifier.weight(1f)); Derecha() }
            }
            if (estrecho) Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) { Derecha() }
        }
    }
}

private val herramientasMundo = setOf(Herramienta.TIERRA, Herramienta.SECCION, Herramienta.TERRITORIO, Herramienta.LUGAR, Herramienta.ROTULO, Herramienta.RUTA, Herramienta.SELLO, Herramienta.REGLA, Herramienta.ELEGIR, Herramienta.MANO)

/** La goma (las librerías de iconos no traen una que se parezca). */
@Composable
private fun IconoGoma(c: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val s = size.width
        rotate(-40f) {
            drawRoundRect(c, topLeft = androidx.compose.ui.geometry.Offset(s * 0.18f, s * 0.3f), size = androidx.compose.ui.geometry.Size(s * 0.64f, s * 0.38f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f), style = androidx.compose.ui.graphics.drawscope.Stroke(s * 0.08f))
            drawLine(c, androidx.compose.ui.geometry.Offset(s * 0.42f, s * 0.3f), androidx.compose.ui.geometry.Offset(s * 0.42f, s * 0.68f), s * 0.08f)
        }
    }
}

/** La barra de la izquierda (como la de Procreate): tamaño, cuentagotas, opacidad, deshacer y rehacer. */
@Composable
private fun BarraLateral(ed: Editor, mod: Modifier) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val alta = if (cfg.screenHeightDp < 620 || cfg.screenWidthDp < 700) 120.dp else 180.dp
    @Composable fun Boton(i: ImageVector, desc: String, h: Herramienta) {
        val activo = ed.herramienta == h
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(if (activo) O.CRUDO else Color.Transparent).pulsableO { ed.herramienta = if (activo) Herramienta.PINCEL else h }, contentAlignment = Alignment.Center) {
            Icon(i, desc, Modifier.size(21.dp), tint = if (activo) O.ACENTO else O.TINTA)
        }
    }
    Column(mod.clip(RoundedCornerShape(16.dp)).background(O.CABECERA.copy(alpha = 0.92f)).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val tam = ed.tamanoActual()
        BarraVerticalO((ln(tam / 0.3f) / ln(800f / 0.3f)).coerceIn(0f, 1f), if (tam < 10f) "%.1f".format(tam).replace('.', ',') else "${tam.roundToInt()}", alturaBarra = alta) { v -> ed.ponTamano(0.3f * (800f / 0.3f).pow(v)) }
        Boton(Icons.Outlined.Colorize, "Cuentagotas", Herramienta.CUENTAGOTAS)
        Boton(Icons.Outlined.FormatColorFill, "Bote de pintura", Herramienta.RELLENO)
        BarraVerticalO(ed.opacidadActual(), "${(ed.opacidadActual() * 100).roundToInt()}%", alturaBarra = alta) { ed.ponOpacidad(it.coerceAtLeast(0.02f)) }
        Flecha(atras = true, activa = ed.puedeDeshacer) { ed.deshacer() }
        Flecha(atras = false, activa = ed.puedeRehacer) { ed.rehacer() }
    }
}

// ==================================================================== pinceles

@Composable
private fun BibliotecaPinceles(e: EstadoApp, ed: Editor, abreEstudio: () -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = Pinceles.version
    val actual = when (ed.herramienta) { Herramienta.GOMA -> ed.goma; Herramienta.DIFUMINAR -> ed.pincelDifuminar; else -> ed.pincel }
    var cat by remember { mutableStateOf(Pinceles.def(actual).categoria) }
    var msg by remember { mutableStateOf("") }
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        msg = "Importando…"
        Thread {
            val r = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { com.gonzalo.atlas.lienzo.Procreate.importa(it.readBytes(), e.bib.dirRecursos) } }
            android.os.Handler(android.os.Looper.getMainLooper()).post { msg = r.fold({ n -> if (n == 0) "No hay pinceles en ese archivo" else "Importados: $n"; }, { "No se pudo: ${it.message}" }); if (r.getOrNull() ?: 0 > 0) cat = "Importados" }
        }.start()
    }
    Text("Para " + when (ed.herramienta) { Herramienta.GOMA -> "borrar"; Herramienta.DIFUMINAR -> "difuminar"; else -> "pintar" }, style = O.versalitas(11.sp, O.ACENTO))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (c in com.gonzalo.atlas.lienzo.Catalogo2.CATEGORIAS) {
                val n = Pinceles.defs().count { it.categoria == c }
                if (n == 0 && c != "Importados") continue
                Text(c, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (cat == c) O.ACENTO else Color.Transparent).pulsableO { cat = c }.padding(horizontal = 10.dp, vertical = 9.dp),
                    style = O.texto(14.sp, O.TINTA))
            }
            Spacer(Modifier.height(8.dp))
            Text("+ Importar pinceles (.brush, .brushset)", Modifier.pulsableO { importar.launch(arrayOf("*/*")) }.padding(10.dp), style = O.texto(12.sp, O.ACENTO))
            if (msg.isNotBlank()) Text(msg, Modifier.padding(horizontal = 10.dp), style = O.texto(11.sp, O.GRIS))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val lista = Pinceles.defs().filter { it.categoria == cat }
            if (lista.isEmpty()) Text(if (cat == "Importados") "Importa pinceles .brush o .brushset (los de Procreate o los exportados desde ATLAS). Los que traen su propia punta salen muy parecidos; los que usan puntas internas de Procreate (que no vienen en el archivo) llevan la de ATLAS que más se le parece." else "Aún no hay.", style = O.texto(13.sp, O.GRIS))
            for (d in lista) {
                val sel = d.id == actual
                val muestra by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, d.json().toString()) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.gonzalo.atlas.lienzo.MuestrasPincel.de(d) }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) O.ACENTO.copy(alpha = 0.85f) else O.CRUDO).pulsableO { if (sel) abreEstudio() else ed.eligePincel(d.id) }.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.nombre, Modifier.weight(1f), style = O.texto(14.sp, O.TINTA))
                        if (Pinceles.cambiado(d.id)) Text("cambiado", style = O.texto(10.sp, O.GRIS))
                    }
                    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterStart) {
                        muestra?.let { Image(it.asImageBitmap(), d.nombre, Modifier.fillMaxSize()) }
                    }
                }
            }
            Text("Toca un pincel elegido para cambiar sus ajustes.", style = O.texto(11.sp, O.GRIS))
        }
    }
}

/** El estudio del pincel (como el Brush Studio de Procreate): todos sus ajustes, con la muestra al momento. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EstudioPincel(ed: Editor, volver: () -> Unit) {
    val ctx = LocalContext.current
    val actual = when (ed.herramienta) { Herramienta.GOMA -> ed.goma; Herramienta.DIFUMINAR -> ed.pincelDifuminar; else -> ed.pincel }
    var d by remember(actual) { mutableStateOf(Pinceles.def(actual).copy()) }
    fun pon(n: com.gonzalo.atlas.lienzo.DefPincel) { d = n }
    val exportar = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val def = d
        Thread {
            val r = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(com.gonzalo.atlas.lienzo.Procreate.exporta(def)) } }
            android.os.Handler(android.os.Looper.getMainLooper()).post { ed.aviso = if (r.isSuccess) "Pincel exportado" else "No se pudo exportar: ${r.exceptionOrNull()?.message}" }
        }.start()
    }
    val muestra by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, d) {
        kotlinx.coroutines.delay(120)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.gonzalo.atlas.lienzo.MuestrasPincel.de(d, 460, 110) }
    }
    Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(O.MESA)) { muestra?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) } }
    CampoO(d.nombre, { pon(d.copy(nombre = it)) }, Modifier.fillMaxWidth(), "Nombre")
    @Composable fun S(t: String, v: Float, r: ClosedFloatingPointRange<Float>, f: String, set: (Float) -> com.gonzalo.atlas.lienzo.DefPincel) = DeslizadorO(t, v, r, f) { pon(set(it)) }
    @Composable fun P(t: String, v: Float, set: (Float) -> com.gonzalo.atlas.lienzo.DefPincel) = S(t, v, 0f..1f, "${(v * 100).roundToInt()}%", set)
    EtiquetaO("Trazo", tam = 11.sp, color = O.ACENTO)
    P("Estabilización", d.estabilizacion) { d.copy(estabilizacion = it) }
    S("Espaciado", d.espaciado, 0f..3f, if (d.espaciado < 0.02f) "Continuo" else "%.2f".format(d.espaciado)) { d.copy(espaciado = if (it < 0.02f) 0f else it) }
    S("Dispersión", d.dispersion, 0f..4f, "%.2f".format(d.dispersion)) { d.copy(dispersion = it) }
    S("Estampas en cada punto", d.numero.toFloat(), 1f..8f, "${d.numero}") { d.copy(numero = it.roundToInt().coerceIn(1, 8)) }
    EtiquetaO("Afilado", tam = 11.sp, color = O.ACENTO)
    S("Al empezar", d.afiladoInicio, 0f..20f, "%.1f".format(d.afiladoInicio)) { d.copy(afiladoInicio = it) }
    S("Al terminar", d.afiladoFin, 0f..20f, "%.1f".format(d.afiladoFin)) { d.copy(afiladoFin = it) }
    Text("Con el dedo (sin presión), los pinceles que cambian de grosor se afilan solos al empezar y al acabar.", style = O.texto(11.sp, O.GRIS))
    EtiquetaO("Forma", tam = 11.sp, color = O.ACENTO)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FichaO("Redonda", d.forma == "redonda") { pon(d.copy(forma = "redonda")) }
        FichaO("Plana", d.forma == "plana") { pon(d.copy(forma = "plana")) }
        for ((k, n) in com.gonzalo.atlas.lienzo.Texturas.PUNTAS) FichaO(n, d.forma == k) { pon(d.copy(forma = k, espaciado = if (d.espaciado < 0.02f) 0.1f else d.espaciado)) }
        if (d.forma.contains(":")) FichaO("La importada", true) {}
    }
    if (d.forma == "redonda") P("Dureza del borde", d.dureza) { d.copy(dureza = it) }
    S("Achatado", d.achatado, 0.05f..1f, "%.2f".format(d.achatado)) { d.copy(achatado = it) }
    S("Ángulo", d.angulo, -90f..90f, "${d.angulo.roundToInt()}°") { d.copy(angulo = it) }
    S("Giro al azar", d.giroAzar, 0f..1f, "${(d.giroAzar * 180).roundToInt()}°") { d.copy(giroAzar = it) }
    InterruptorO("La punta sigue la dirección del trazo", d.sigueDireccion) { pon(d.copy(sigueDireccion = it)) }
    InterruptorO("La punta gira con el lápiz (acimut)", d.acimut) { pon(d.copy(acimut = it)) }
    InterruptorO("Voltear al azar", d.volteo) { pon(d.copy(volteo = it)) }
    EtiquetaO("Grano", tam = 11.sp, color = O.ACENTO)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FichaO("Sin grano", d.grano.isBlank()) { pon(d.copy(grano = "")) }
        for ((k, n) in com.gonzalo.atlas.lienzo.Texturas.GRANOS) FichaO(n, d.grano == k) { pon(d.copy(grano = k)) }
        if (d.grano.contains(":")) FichaO("El importado", true) {}
    }
    if (d.grano.isNotBlank()) {
        S("Escala del grano", d.escalaGrano, 16f..2048f, "${d.escalaGrano.roundToInt()} px") { d.copy(escalaGrano = it) }
        P("Cuánto se nota", d.profGrano) { d.copy(profGrano = it) }
        InterruptorO("El grano se mueve con cada estampa", d.granoMovil) { pon(d.copy(granoMovil = it)) }
    }
    EtiquetaO("Pintura", tam = 11.sp, color = O.ACENTO)
    S("Flujo (pintura de cada estampa)", d.flujo, 0.02f..1f, "${(d.flujo * 100).roundToInt()}%") { d.copy(flujo = it) }
    InterruptorO("Acumula donde se cruza (acuarela, aerógrafo)", d.acumula) { pon(d.copy(acumula = it)) }
    InterruptorO("Veladura: la presión marca la opacidad máxima (como los pinceles de Procreate)", d.velado) { pon(d.copy(velado = it)) }
    P("Bordes húmedos", d.bordes) { d.copy(bordes = it) }
    P("Mezcla con lo que hay (óleo)", d.mezcla) { d.copy(mezcla = it) }
    EtiquetaO("Lápiz", tam = 11.sp, color = O.ACENTO)
    P("Presión → tamaño", d.presionTamano) { d.copy(presionTamano = it) }
    P("Presión → opacidad", d.presionOpacidad) { d.copy(presionOpacidad = it) }
    P("Inclinación → tamaño", d.inclinacion) { d.copy(inclinacion = it) }
    P("Inclinación → más claro", d.inclinacionOpacidad) { d.copy(inclinacionOpacidad = it) }
    EtiquetaO("Velocidad y azar", tam = 11.sp, color = O.ACENTO)
    S("Velocidad → tamaño", d.velocidadTamano, -0.9f..1f, "%+.2f".format(d.velocidadTamano)) { d.copy(velocidadTamano = it) }
    S("Velocidad → opacidad", d.velocidadOpacidad, -0.9f..0f, "%+.2f".format(d.velocidadOpacidad)) { d.copy(velocidadOpacidad = it) }
    P("Azar de tamaño", d.azarTamano) { d.copy(azarTamano = it) }
    P("Azar de opacidad", d.azarOpacidad) { d.copy(azarOpacidad = it) }
    P("Azar de tono", d.azarTono) { d.copy(azarTono = it) }
    P("Azar de saturación", d.azarSat) { d.copy(azarSat = it) }
    P("Azar de brillo", d.azarBrillo) { d.copy(azarBrillo = it) }
    EtiquetaO("Propiedades", tam = 11.sp, color = O.ACENTO)
    S("Tamaño al elegirlo", d.tamano, 1f..400f, "${d.tamano.roundToInt()} px") { d.copy(tamano = it) }
    S("Opacidad al elegirlo", d.opacidad, 0.02f..1f, "${(d.opacidad * 100).roundToInt()}%") { d.copy(opacidad = it) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotonO("Hecho", lleno = true, color = O.ACENTO) { Pinceles.guarda(d); ed.eligePincel(d.id); volver() }
        BotonO("Duplicar") { val n = Pinceles.duplica(d); ed.eligePincel(n.id); volver() }
        BotonO("Exportar") { exportar.launch(d.nombre.replace(Regex("[^\\p{L}\\p{N} _-]"), "").ifBlank { "Pincel" } + ".brush") }
        if (Pinceles.cambiado(d.id)) BotonO("Restablecer") { Pinceles.restablece(d.id); d = Pinceles.def(d.id).copy() }
        if (d.propio || d.categoria == "Importados") BotonO("Borrar", color = O.ROJO) { Pinceles.borra(d.id); ed.eligePincel("lapiz6b"); volver() }
    }
}

// ==================================================================== color

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelColor(ed: Editor) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    var modo by remember { mutableStateOf("disco") }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((k, n) in listOf("disco" to "Disco", "clasico" to "Clásico", "valores" to "Valores", "paletas" to "Paletas")) FichaO(n, modo == k) { modo = k }
    }
    when (modo) {
        "disco" -> DiscoColor(ed.color) { ed.color = it }
        "clasico" -> SelectorColorO(ed.color) { ed.color = it }
        "valores" -> ValoresColor(ed.color) { ed.color = it }
        else -> PaletasO(ed.color) { ed.ponColor(it) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(ed.color)))
        Text("#%06X".format(ed.color and 0xFFFFFF), style = O.titular(16.sp))
        Spacer(Modifier.weight(1f))
        BotonO("Guardar en recientes") { ed.ponColor(ed.color) }
    }
    EtiquetaO("Historial", tam = 10.sp, color = O.GRIS)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (c in ed.recientes) MuestraO(c, c == ed.color, 28) { ed.color = c } }
}

/** El disco de color: el anillo del tono y, dentro, el cuadro de saturación y brillo. */
@Composable
private fun DiscoColor(color: Int, alCambiar: (Int) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var h by remember(color) { mutableStateOf(hsv[0]) }; var s by remember(color) { mutableStateOf(hsv[1]) }; var v by remember(color) { mutableStateOf(hsv[2]) }
    var enAnillo by remember { mutableStateOf(false) }
    fun manda() = alCambiar(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    fun toca(o: androidx.compose.ui.geometry.Offset, w: Float, primera: Boolean) {
        val c = w / 2; val dx = o.x - c; val dy = o.y - c; val r = kotlin.math.hypot(dx, dy)
        if (primera) enAnillo = r > c * 0.74f
        if (enAnillo) { h = ((Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()) + 360) % 360).toFloat() }
        else { val lado = c * 0.62f * 1.41f / 2; s = ((dx + lado) / (2 * lado)).coerceIn(0f, 1f); v = (1f - (dy + lado) / (2 * lado)).coerceIn(0f, 1f) }
        manda()
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp)
        .pointerInput(Unit) { detectDragGestures(onDragStart = { toca(it, size.width.toFloat(), true) }) { ch, _ -> toca(ch.position, size.width.toFloat(), false) } }
        .pointerInput(Unit) { detectTapGestures { toca(it, size.width.toFloat(), true) } }) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val c = size.width / 2
            drawCircle(androidx.compose.ui.graphics.Brush.sweepGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f))) }), radius = c * 0.88f, style = androidx.compose.ui.graphics.drawscope.Stroke(c * 0.24f))
            val lado = c * 0.62f * 1.41f
            val tl = androidx.compose.ui.geometry.Offset(c - lado / 2, c - lado / 2); val sz = androidx.compose.ui.geometry.Size(lado, lado)
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White, Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)))), tl.x, tl.x + lado), tl, sz)
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black), tl.y, tl.y + lado), tl, sz)
            val a = Math.toRadians(h.toDouble())
            drawCircle(Color.White, c * 0.1f, androidx.compose.ui.geometry.Offset(c + (kotlin.math.cos(a) * c * 0.88f).toFloat(), c + (kotlin.math.sin(a) * c * 0.88f).toFloat()), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            drawCircle(Color.White, c * 0.06f, androidx.compose.ui.geometry.Offset(tl.x + s * lado, tl.y + (1 - v) * lado), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
        }
    }
}

/** Los valores: tono, saturación y brillo, rojo, verde y azul, y el código. */
@Composable
private fun ValoresColor(color: Int, alCambiar: (Int) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    DeslizadorO("Tono", hsv[0], 0f..359.9f, "${hsv[0].roundToInt()}°") { alCambiar(android.graphics.Color.HSVToColor(floatArrayOf(it, hsv[1], hsv[2]))) }
    DeslizadorO("Saturación", hsv[1], 0f..1f, "${(hsv[1] * 100).roundToInt()}%") { alCambiar(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], it, hsv[2]))) }
    DeslizadorO("Brillo", hsv[2], 0f..1f, "${(hsv[2] * 100).roundToInt()}%") { alCambiar(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], it))) }
    val r = android.graphics.Color.red(color); val g = android.graphics.Color.green(color); val b = android.graphics.Color.blue(color)
    DeslizadorO("Rojo", r.toFloat(), 0f..255f, "$r") { alCambiar(android.graphics.Color.rgb(it.roundToInt(), g, b)) }
    DeslizadorO("Verde", g.toFloat(), 0f..255f, "$g") { alCambiar(android.graphics.Color.rgb(r, it.roundToInt(), b)) }
    DeslizadorO("Azul", b.toFloat(), 0f..255f, "$b") { alCambiar(android.graphics.Color.rgb(r, g, it.roundToInt())) }
    var hex by remember(color) { mutableStateOf("%06X".format(color and 0xFFFFFF)) }
    CampoO(hex, { t -> hex = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6); if (hex.length == 6) alCambiar(0xFF000000.toInt() or hex.toInt(16)) }, Modifier.fillMaxWidth(), "Código (RRGGBB)")
}

// ==================================================================== capas

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelCapas(e: EstadoApp, ed: Editor, mesa: Mesa) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    val lz = ed.lienzo
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val ver = lz.version
    var abierta by remember { mutableStateOf<String?>(null) }
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val b = runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return@rememberLauncherForActivityResult
        val vis = mesa.vista.visible()
        lz.enHilo { lz.importaImagen(b, "Imagen", vis.left + vis.width() * 0.1f, vis.top + vis.height() * 0.1f, vis.width() * 0.8f) }
    }
    val lista = lz.capas.toList()
    val normales = lista.count { !it.info.mascara }
    Text("$normales ${if (normales == 1) "capa" else "capas"}. La de arriba tapa a las de abajo. Toca una para pintar en ella; «⋯» abre sus opciones y la papelera la borra. Un grupo se ve, se esconde y se ordena entero.", style = O.texto(12.sp, O.GRIS))
    var abiertoGrupo by remember { mutableStateOf<String?>(null) }
    var grupoPintado = ""
    for (c in lista.reversed()) {
        if (c.info.mascara) continue
        val g = c.info.grupo
        val grupo = ed.mapa.grupos.firstOrNull { it.id == g }
        // La cabecera de su grupo (una vez, encima de sus capas)
        if (g.isNotEmpty() && g != grupoPintado && grupo != null) {
            grupoPintado = g
            val cuantas = lista.count { it.info.grupo == g && !it.info.mascara }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(O.CRUDO)) {
                Row(Modifier.fillMaxWidth().pulsableO { abiertoGrupo = if (abiertoGrupo == g) null else g }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (grupo.plegado) "▸" else "▾", Modifier.pulsableO { lz.cambiaCapas(if (grupo.plegado) "Desplegar grupo" else "Plegar grupo") { grupo.plegado = !grupo.plegado } }.padding(horizontal = 8.dp, vertical = 4.dp), style = O.titular(16.sp, O.GRIS))
                    Text("▭", Modifier.padding(end = 8.dp), style = O.titular(16.sp, O.TINTA))
                    Text(grupo.nombre, Modifier.weight(1f), style = O.titular(15.sp, O.TINTA), maxLines = 1)
                    Text("$cuantas ${if (cuantas == 1) "capa" else "capas"}", Modifier.padding(horizontal = 8.dp), style = O.texto(11.sp, O.GRIS))
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).border(2.dp, O.TINTA, RoundedCornerShape(5.dp)).background(if (grupo.visible) O.TINTA else Color.Transparent)
                        .pulsableO { lz.cambiaCapas(if (grupo.visible) "Esconder grupo" else "Ver grupo") { grupo.visible = !grupo.visible } }, contentAlignment = Alignment.Center) {
                        if (grupo.visible) Text("✓", style = O.texto(13.sp, O.PAPEL))
                    }
                }
                if (abiertoGrupo == g) Column(Modifier.fillMaxWidth().background(O.PAPEL.copy(alpha = 0.5f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var nombre by remember(g) { mutableStateOf(grupo.nombre) }
                    CampoO(nombre, { nombre = it; grupo.nombre = it; ed.mapa.avisaVector(); ed.cambios++ }, Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotonO("Subir el grupo") { lz.cambiaCapas("Ordenar") { com.gonzalo.atlas.lienzo.Grupos.mueveGrupo(lz, g, true) } }
                        BotonO("Bajar el grupo") { lz.cambiaCapas("Ordenar") { com.gonzalo.atlas.lienzo.Grupos.mueveGrupo(lz, g, false) } }
                        BotonO("Deshacer el grupo") { abiertoGrupo = null; lz.cambiaCapas("Deshacer grupo") { com.gonzalo.atlas.lienzo.Grupos.deshazGrupo(lz, g) } }
                        BotonO("Borrar el grupo y sus capas", color = O.ROJO) {
                            abiertoGrupo = null
                            lz.cambiaCapas("Borrar grupo") { if (!com.gonzalo.atlas.lienzo.Grupos.borraGrupo(lz, g)) ed.avisa("Son todas las capas: no se puede borrar el grupo (puedes deshacerlo)") else ed.avisa("Grupo «${grupo.nombre}» borrado. Deshacer lo recupera.") }
                        }
                    }
                }
            }
        }
        if (grupo?.plegado == true) continue
        val mascara = com.gonzalo.atlas.lienzo.Grupos.mascara(lz, c)
        val enMascara = mascara != null && mascara.info.id == lz.activa
        val activa = c.info.id == lz.activa || enMascara
        val mini by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, c.teselas.size, lz.version / 4) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { com.gonzalo.atlas.lienzo.Ajustes.vistaCapa(c, 96)?.first }.getOrNull() }
        }
        val miniM by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, mascara?.teselas?.size, lz.version / 4, mascara?.info?.id) {
            value = mascara?.let { m -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { com.gonzalo.atlas.lienzo.Ajustes.vistaCapa(m, 96)?.first }.getOrNull() } }
        }
        Column(Modifier.fillMaxWidth().padding(start = if (g.isNotEmpty()) 18.dp else 0.dp).clip(RoundedCornerShape(10.dp)).background(if (activa) O.ACENTO else O.CRUDO)) {
            Row(Modifier.fillMaxWidth().pulsableO { if (c.info.id == lz.activa) abierta = if (abierta == c.info.id) null else c.info.id else { ed.ponCapa(c.info.id); abierta = null } }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 64.dp, height = 46.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF8E8E93)).border(if (activa && !enMascara) 2.dp else 0.dp, O.TINTA, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { val l = 8.dp.toPx(); var y = 0f; var f = 0; while (y < size.height) { var x = if (f % 2 == 0) 0f else l; while (x < size.width) { drawRect(Color(0xFFB0B0B5), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(l, l)); x += 2 * l }; y += l; f++ } }
                    mini?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                }
                // La máscara: blanca donde deja ver, con lo pintado (lo que esconde) encima. Tocarla la elige para pintar en ella.
                if (mascara != null) Box(Modifier.padding(start = 6.dp).size(width = 46.dp, height = 46.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)
                    .border(if (enMascara) 2.dp else 0.dp, O.TINTA, RoundedCornerShape(6.dp)).pulsableO { ed.ponCapa(mascara.info.id); abierta = null }) {
                    miniM?.let { Image(it.asImageBitmap(), "La máscara de «${c.info.nombre}»", Modifier.fillMaxSize(), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black)) }
                    if (!mascara.info.visible) Text("✕", Modifier.align(Alignment.Center), style = O.titular(16.sp, O.ROJO))
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(c.info.nombre, style = O.texto(15.sp, O.TINTA), maxLines = 1)
                    val extra = listOfNotNull("Pintando la máscara".takeIf { enMascara }, "↳ Recortada a la de abajo".takeIf { c.info.recorte }, "Referencia".takeIf { c.info.referencia }, "Transparencia bloqueada".takeIf { c.info.bloqueoAlfa })
                    if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = O.texto(11.sp, if (activa) O.TINTA else O.GRIS))
                }
                Text(letraFusion(c.info.fusion), Modifier.padding(horizontal = 6.dp), style = O.titular(14.sp))
                // Las opciones de la capa y borrarla, a la vista (sin tener que saber que se abren tocándola otra vez)
                Text("⋯", Modifier.pulsableO { ed.ponCapa(c.info.id); abierta = if (abierta == c.info.id) null else c.info.id }.padding(horizontal = 8.dp, vertical = 4.dp), style = O.titular(20.sp, O.TINTA))
                if (normales > 1) androidx.compose.material3.Icon(Icons.Outlined.Delete, "Borrar la capa", Modifier.padding(end = 8.dp).size(22.dp).pulsableO { borraCapa(ed, c) }, tint = if (activa) O.TINTA else O.GRIS)
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).border(2.dp, O.TINTA, RoundedCornerShape(5.dp)).background(if (c.info.visible) O.TINTA else Color.Transparent)
                    .pulsableO { lz.cambiaCapas("Ver capa") { c.info.visible = !c.info.visible } }, contentAlignment = Alignment.Center) {
                    if (c.info.visible) Text("✓", style = O.texto(13.sp, if (activa) O.ACENTO else O.PAPEL))
                }
            }
            if (abierta == c.info.id) Column(Modifier.fillMaxWidth().background(O.PAPEL.copy(alpha = 0.5f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var nombre by remember(c) { mutableStateOf(c.info.nombre) }
                CampoO(nombre, { nombre = it; c.info.nombre = it }, Modifier.fillMaxWidth())
                DeslizadorO("Opacidad", c.info.opacidad, 0f..1f, "${(c.info.opacidad * 100).roundToInt()}%") { c.info.opacidad = it; mesa.piramide.todoSucio(); mesa.refresca(); ed.cambios++ }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((k, n) in Lienzo.FUSIONES) FichaO(n, c.info.fusion == k) { lz.cambiaCapas("Fusión") { c.info.fusion = k } }
                }
                // Su máscara
                Text("MÁSCARA", style = O.texto(11.sp, O.GRIS))
                if (mascara == null) {
                    BotonO("Añadir una máscara") { lz.cambiaCapas("Añadir máscara") { com.gonzalo.atlas.lienzo.Grupos.nuevaMascara(lz, c) }; ed.capaActiva = ""; ed.avisa("Máscara nueva, elegida: lo que pintes en ella esconde la capa, y borrar vuelve a enseñarla.") }
                } else {
                    InterruptorO("Máscara encendida", mascara.info.visible) { v -> lz.cambiaCapas(if (v) "Encender máscara" else "Apagar máscara") { mascara.info.visible = v } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (enMascara) BotonO("Pintar la capa") { ed.ponCapa(c.info.id) } else BotonO("Pintar la máscara", lleno = true) { ed.ponCapa(mascara.info.id) }
                        BotonO("Invertir la máscara") { lz.cambiaCapas("Invertir máscara") { com.gonzalo.atlas.lienzo.Grupos.invierteMascara(lz, c) } }
                        BotonO("Aplicarla y quitarla") { lz.cambiaCapas("Aplicar máscara") { com.gonzalo.atlas.lienzo.Grupos.aplicaMascara(lz, c) }; ed.capaActiva = ""; ed.avisa("Máscara aplicada: lo que escondía se ha borrado de la capa (deshacer lo recupera).") }
                        BotonO("Quitarla", color = O.ROJO) { lz.cambiaCapas("Quitar máscara") { com.gonzalo.atlas.lienzo.Grupos.quitaMascara(lz, c) }; ed.capaActiva = "" }
                    }
                    Text("En la máscara, pintar esconde la capa y borrar la vuelve a enseñar. No se pierde nada de la capa hasta que la aplicas.", style = O.texto(11.sp, O.GRIS))
                }
                // Su grupo
                Text("GRUPO", style = O.texto(11.sp, O.GRIS))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (g.isNotEmpty()) BotonO("Sacarla del grupo") { lz.cambiaCapas("Sacar del grupo") { com.gonzalo.atlas.lienzo.Grupos.desagrupa(lz, c) } }
                    else {
                        BotonO("Meterla en un grupo nuevo") { lz.cambiaCapas("Agrupar") { val gr = com.gonzalo.atlas.lienzo.Grupos.agrupa(lz, c, null); ed.avisa("Grupo nuevo: «${gr.nombre}». Toca su cabecera para cambiarle el nombre.") } }
                        for (otro in ed.mapa.grupos) BotonO("A «${otro.nombre}»") { lz.cambiaCapas("Agrupar") { com.gonzalo.atlas.lienzo.Grupos.agrupa(lz, c, otro) } }
                    }
                }
                InterruptorO("Máscara de recorte (solo se ve sobre lo pintado en la capa de abajo)", c.info.recorte) { v -> lz.cambiaCapas("Máscara de recorte") { c.info.recorte = v } }
                InterruptorO("Bloquear transparencia (pintar solo sobre lo ya pintado)", c.info.bloqueoAlfa) { v -> lz.cambiaCapas("Bloquear") { c.info.bloqueoAlfa = v } }
                InterruptorO("Referencia (para rellenar y marcar territorios)", c.info.referencia) { v -> lz.cambiaCapas("Referencia") { c.info.referencia = v } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotonO("Subir") { lz.cambiaCapas("Ordenar") { if (!com.gonzalo.atlas.lienzo.Grupos.mueve(lz, c, true) && g.isNotEmpty()) ed.avisa("Es la de arriba de su grupo: para sacarla, «Sacarla del grupo»") } }
                    BotonO("Bajar") { lz.cambiaCapas("Ordenar") { if (!com.gonzalo.atlas.lienzo.Grupos.mueve(lz, c, false) && g.isNotEmpty()) ed.avisa("Es la de abajo de su grupo: para sacarla, «Sacarla del grupo»") } }
                    BotonO("Duplicar") { lz.cambiaCapas("Duplicar") { duplicaCapa(lz, c) } }
                    BotonO("Combinar abajo") { combinaAbajo(lz, c) }
                    BotonO("Rellenar capa") { lz.cambiaCapas("Rellenar capa") { c.soloPixeles(); rellenaCapa(lz, c, ed.color) } }
                    BotonO("Vaciar") { lz.cambiaCapas("Vaciar capa") { c.soloPixeles(); c.teselas.clear() } }
                    if (normales > 1) BotonO("Borrar", color = O.ROJO) { borraCapa(ed, c) }
                }
            }
        }
    }
    // El color de fondo (el papel), como la última fila de las capas de Procreate
    var fondo by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(O.CRUDO).pulsableO { fondo = !fondo }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(8.dp)).background(Color(ed.mapa.papel)))
        Text("Color de fondo", Modifier.weight(1f).padding(horizontal = 10.dp), style = O.texto(15.sp, O.TINTA))
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).border(2.dp, O.TINTA, RoundedCornerShape(5.dp)).background(if (android.graphics.Color.alpha(ed.mapa.papel) > 0) O.TINTA else Color.Transparent)
            .pulsableO { ed.mapa.papel = if (android.graphics.Color.alpha(ed.mapa.papel) > 0) 0 else 0xFFF1E6C8.toInt(); mesa.refresca(); ed.mapa.avisaVector() })
    }
    if (fondo) {
        DiscoColor(if (android.graphics.Color.alpha(ed.mapa.papel) == 0) 0xFFFFFFFF.toInt() else ed.mapa.papel) { ed.mapa.papel = it; mesa.refresca(); ed.cambios++; ed.mapa.avisaVector() }
        InterruptorO("Grano del papel", ed.mapa.granoPapel) { ed.mapa.granoPapel = it; mesa.refresca(); ed.mapa.avisaVector() }
    }
    BotonO("Insertar una foto en una capa nueva") { importar.launch("image/*") }
    @Suppress("UNUSED_VARIABLE") val u = e.listas
}

private fun letraFusion(f: String) = when (f) { "multiplicar" -> "M"; "pantalla" -> "P"; "superponer" -> "Sp"; "luz_suave" -> "Ls"; "luz_fuerte" -> "Lf"; "aclarar" -> "Ac"; "oscurecer" -> "Os"; "anadir" -> "Añ"; "color" -> "Co";
    "subexponer" -> "Sb"; "sobreexponer" -> "Sx"; "diferencia" -> "Di"; "exclusion" -> "Ex"; "tono" -> "To"; "saturacion" -> "Sa"; "luminosidad" -> "Lu"; else -> "N" }

/** Llena la capa del color (en lo que ya tiene, o entera si está vacía en lo que se ve). */
private fun rellenaCapa(lz: Lienzo, c: CapaPx, color: Int) {
    val claves = c.teselas.keys.toList().ifEmpty { return }
    for (k in claves) { val b = android.graphics.Bitmap.createBitmap(Lienzo.L, Lienzo.L, android.graphics.Bitmap.Config.ARGB_8888); b.eraseColor(color); c.teselas[k] = b }
}

// ==================================================================== acciones, ajustes, transformar, mundo

@Composable
private fun PanelAcciones(e: EstadoApp, ed: Editor, mesa: Mesa, taller: Boolean) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    val m = ed.mapa
    EtiquetaO("Lienzo", tam = 11.sp, color = O.ACENTO)
    if (!taller) {
        InterruptorO("Cuadrícula (casillas en km; de cerca, en metros)", mesa.opciones.cuadricula) { mesa.opciones.cuadricula = it; mesa.refresca(); ed.cambios++ }
        Text("Mide en km: fija cuántos km es un píxel en Mundo → Escala (ahora ${m.kmPorPx.toString().replace('.', ',')} km por píxel${if (m.escalaPuesta) "" else ", sin fijar"}).", style = O.texto(12.sp, O.GRIS))
    }
    GuiasDibujo(ed, mesa)
    EtiquetaO("Gestos", tam = 11.sp, color = O.ACENTO)
    InterruptorO("Girar el lienzo con dos dedos", mesa.girar) { mesa.girar = it; ed.cambios++ }
    InterruptorO("Dibujar también con el dedo", mesa.dedoDibuja) { mesa.dedoDibuja = it; ed.cambios++ }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { BotonO("Norte arriba") { mesa.endereza() }; BotonO("Ver todo") { mesa.encuadra(false) } }
    Text("Gestos: dos dedos tocando, deshacer; tres, rehacer. Con el lápiz, mantén un dedo quieto para coger un color.", style = O.texto(12.sp, O.GRIS))
    if (!taller) {
        EtiquetaO("Leyenda", tam = 11.sp, color = O.ACENTO)
        InterruptorO("Poner la leyenda en el mapa", m.leyenda.visible) { v -> ed.cambiaVector("Leyenda") { m.leyenda.visible = v } }
        if (m.leyenda.visible) {
            var t by remember { mutableStateOf(m.leyenda.titulo) }
            CampoO(t, { t = it; m.leyenda.titulo = it; m.avisaVector() }, Modifier.fillMaxWidth(), "Título")
            InterruptorO("Imperios y reinos", m.leyenda.territorios) { v -> ed.cambiaVector("Leyenda") { m.leyenda.territorios = v } }
            InterruptorO("Tipos de lugar", m.leyenda.lugares) { v -> ed.cambiaVector("Leyenda") { m.leyenda.lugares = v } }
            InterruptorO("Escala", m.leyenda.escalaKm) { v -> ed.cambiaVector("Leyenda") { m.leyenda.escalaKm = v } }
            DeslizadorO("Tamaño", m.leyenda.escala, 0.3f..6f, "×%.1f".format(m.leyenda.escala)) { m.leyenda.escala = it; m.avisaVector() }
        }
        EtiquetaO("Territorios", tam = 11.sp, color = O.ACENTO)
        InterruptorO("Ver los territorios", m.verTerritorios) { m.verTerritorios = it; mesa.velo.olvida(); mesa.piramide.todoSucio(); m.avisaVector() }
        DeslizadorO("Color de los territorios", m.tinteTerritorios, 0f..0.8f, "${(m.tinteTerritorios * 100).roundToInt()}%") { m.tinteTerritorios = it; mesa.velo.olvida(); mesa.piramide.todoSucio(); mesa.refresca(); ed.cambios++ }
    }
    EtiquetaO("Compartir", tam = 11.sp, color = O.ACENTO)
    BotonO("Guardar imagen (PNG)") { e.exporta(ed, mesa) }
    if (e.mensaje.isNotBlank()) Text(e.mensaje, style = O.texto(12.sp, O.ACENTO))
}

@Composable
private fun PanelTransformar(ed: Editor, mesa: Mesa) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    Text("Arrastra para mover la capa; con dos dedos, escala y gira.", style = O.texto(13.sp, O.GRIS))
    fun cambia(f: (android.graphics.Matrix, Float, Float) -> Unit) {
        val m = ed.transformacion ?: android.graphics.Matrix(); val v = mesa.vista.visible()
        f(m, v.centerX(), v.centerY()); ed.transformacion = android.graphics.Matrix(m); mesa.refresca()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotonO("Voltear ↔") { cambia { m, x, y -> m.postScale(-1f, 1f, x, y) } }
        BotonO("Voltear ↕") { cambia { m, x, y -> m.postScale(1f, -1f, x, y) } }
        BotonO("Girar 45°") { cambia { m, x, y -> m.postRotate(45f, x, y) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotonO("Hecho", lleno = true, color = O.ACENTO) { ed.aplicaTransformacion(); mesa.refresca() }
        BotonO("Cancelar") { ed.transformacion = null; ed.herramienta = Herramienta.PINCEL; mesa.refresca() }
    }
}

/** Las herramientas del mundo (lo que hace de esta app de dibujo una de mapas). */
@Composable
private fun PanelMundo(ed: Editor, abre: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    @Composable fun F(h: Herramienta, t: String, d: String, panel: String?) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (ed.herramienta == h) O.ACENTO else O.CRUDO)
            .pulsableO { ed.herramienta = h; ed.seleccion = null; if (h != Herramienta.REGLA) ed.regla = null; if (panel != null) abre(panel) else abre("mundo") }.padding(12.dp)) {
            Column { Text(t, style = O.texto(15.sp, O.TINTA)); Text(d, style = O.texto(12.sp, if (ed.herramienta == h) O.TINTA else O.GRIS)) }
        }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(O.BLOQUE2).pulsableO { abre("continente") }.padding(12.dp)) {
        Column { Text("Generar un continente", style = O.texto(15.sp, O.TINTA)); Text("Das el tamaño total en km y la costa se dibuja sola", style = O.texto(12.sp, O.TINTA)) }
    }
    F(Herramienta.TIERRA, "Tierras generadas", "Mover, juntar, separar o borrar los continentes generados", "tierras")
    @Composable fun P(t: String, d: String, panel: String) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(O.CRUDO).pulsableO { abre(panel) }.padding(12.dp)) {
            Column { Text(t, style = O.texto(15.sp, O.TINTA)); Text(d, style = O.texto(12.sp, O.GRIS)) }
        }
    }
    P("Acabado de la costa", "Líneas de agua y sombra del litoral alrededor de las tierras generadas", "costa")
    P("Línea del tiempo", "Las fronteras de cada año: conquistas, independencias, guerras", "tiempo")
    P("Viajes", "Cuánto se recorre en un día: los días de viaje de cada ruta", "viajes")
    F(Herramienta.SECCION, "Secciones del terreno", "Marcos con su tamaño en km: dibujas solo dentro", "secciones")
    F(Herramienta.TERRITORIO, "Territorios", "Imperios, reinos, provincias: toca dentro de tus fronteras", "territorios")
    F(Herramienta.LUGAR, "Lugares", "Ciudades, castillos, templos… con su mapa propio", "lugares")
    F(Herramienta.ROTULO, "Rótulos", "Nombres de mares, sierras, lo que quieras", null)
    F(Herramienta.RUTA, "Rutas", "Caminos, rutas por mar, fronteras, viajes", "rutas")
    F(Herramienta.SELLO, "Tus dibujos", "Lo que dibujaste en el Taller, tantas veces como quieras", "dibujos")
    F(Herramienta.REGLA, "Regla", "Mide distancias en km", null)
    F(Herramienta.ELEGIR, "Elegir", "Toca algo para cambiarlo o moverlo", null)
    F(Herramienta.MANO, "Mover y abrir", "Toca un lugar con mapa para abrirlo", null)
}

/** Funde la capa con la de debajo (con su opacidad y su modo). */
private fun combinaAbajo(lz: Lienzo, c: CapaPx) {
    lz.cambiaCapas("Combinar") {
        val i = lz.capas.indexOf(c); if (i <= 0) return@cambiaCapas
        val abajo = lz.capas.take(i).lastOrNull { !it.info.mascara } ?: return@cambiaCapas
        // (Las máscaras de las dos se aplican antes de juntarlas)
        com.gonzalo.atlas.lienzo.Grupos.hornea(lz, c); com.gonzalo.atlas.lienzo.Grupos.hornea(lz, abajo)
        c.soloPixeles(); abajo.soloPixeles()
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
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    val m = ed.mapa
    Text("Dibuja las fronteras con el pincel (mejor en la capa «Líneas») y luego toca dentro de cada zona para decir de quién es.", style = O.texto(12.sp, O.GRIS))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FichaO("Tocar dentro", ed.modoTerritorio == ModoTerritorio.ZONA) { ed.modoTerritorio = ModoTerritorio.ZONA }
        FichaO("Pintar", ed.modoTerritorio == ModoTerritorio.PINCEL) { ed.modoTerritorio = ModoTerritorio.PINCEL }
        FichaO("Quitar", ed.modoTerritorio == ModoTerritorio.QUITAR) { ed.modoTerritorio = ModoTerritorio.QUITAR }
    }
    if (ed.modoTerritorio != ModoTerritorio.ZONA) DeslizadorO("Grosor", ed.radioTerritorio, 4f..200f, "${ed.radioTerritorio.roundToInt()} px") { ed.radioTerritorio = it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotonO("+ Imperio") { ed.nuevoTerritorio("Imperio", 0)?.let { ed.seleccion = it } }
        BotonO("+ Reino", lleno = true) { ed.nuevoTerritorio("Reino", m.territorio(ed.territorio)?.takeIf { it.tipo == "Imperio" }?.id ?: 0)?.let { ed.seleccion = it } }
    }
    BotonO("+ Provincia de «${m.territorio(ed.territorio)?.nombre ?: "…"}»", activo = ed.territorio != 0) { ed.nuevoTerritorio("Provincia", ed.territorio)?.let { ed.seleccion = it } }
    FileteO(1)
    @Composable fun Arbol(padre: Int, nivel: Int) {
        for (t in m.hijos(padre).sortedBy { it.nombre.lowercase() }) {
            val sel = ed.territorio == t.id
            Row(Modifier.fillMaxWidth().background(if (sel) O.CRUDO else Color.Transparent).border(if (sel) 2.dp else 0.dp, if (sel) O.ACENTO else Color.Transparent)
                .pulsableO { ed.territorio = t.id; ed.herramienta = Herramienta.TERRITORIO }.padding(start = (8 + nivel * 18).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).background(Color(t.color)).border(1.dp, O.TINTA))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.nombre, style = O.nombre(if (nivel == 0) 18.sp else 16.sp))
                    Text(t.tipo + " · " + Medidas.formatoKm2(Medidas.km2(m, ed.superficies.getOrElse(t.id) { 0L })), style = O.texto(11.sp, O.GRIS))
                }
                EtiquetaO("Editar", Modifier.pulsableO { ed.territorio = t.id; ed.seleccion = t }, 10.sp, O.ACENTO)
            }
            Arbol(t.id, nivel + 1)
        }
    }
    if (m.territorios.isEmpty()) Text("Aún no hay territorios.", style = O.nombre(16.sp, O.GRIS)) else Arbol(0, 0)
    @Suppress("UNUSED_VARIABLE") val u = e.listas + mesa.hashCode()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelLugares(ed: Editor) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    Text("Toca el mapa para poner un lugar del tipo elegido. Tócalo otra vez para editarlo.", style = O.texto(12.sp, O.GRIS))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in Catalogo.LUGARES) FichaO(t.nombre, ed.tipoLugar == t.clave) { ed.tipoLugar = t.clave; ed.herramienta = Herramienta.LUGAR }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelRutas(ed: Editor) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    Text("Dibuja la ruta arrastrando. Se puede elegir después para cambiarla o medirla.", style = O.texto(12.sp, O.GRIS))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in Catalogo.RUTAS) FichaO(t.nombre, ed.tipoRuta == t.clave) { ed.tipoRuta = t.clave; ed.herramienta = Herramienta.RUTA }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelDibujos(e: EstadoApp, ed: Editor) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    @Suppress("UNUSED_VARIABLE") val v = e.recursos.version
    val lista = e.recursos.lista()
    if (lista.isEmpty()) Text("Aún no tienes dibujos sueltos. Dibuja uno en el Taller (montañas, bosques, ciudades…) y guárdalo: luego lo pones aquí tantas veces como quieras.", style = O.texto(13.sp, O.GRIS))
    DeslizadorO("Tamaño al poner", ed.escalaSello, 0.2f..6f, "×%.1f".format(ed.escalaSello)) { ed.escalaSello = it }
    for ((cat, l) in lista.groupBy { it.categoria }) {
        EtiquetaO(cat, tam = 10.sp, color = O.GRIS)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in l) {
                val b = e.recursos.bitmap(r.id)
                Box(Modifier.size(72.dp).border(if (ed.recurso == r.id) 3.dp else 1.dp, if (ed.recurso == r.id) O.ACENTO else O.TINTA).background(O.CRUDO)
                    .pulsableO { ed.recurso = r.id; ed.herramienta = Herramienta.SELLO }.padding(4.dp), contentAlignment = Alignment.Center) {
                    if (b != null) Image(b.asImageBitmap(), r.nombre, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun PanelSeleccion(e: EstadoApp, ed: Editor, mesa: Mesa) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    when (val s = ed.seleccion) {
        is Territorio -> EditaTerritorio(e, ed, s)
        is Lugar -> EditaLugar(e, ed, s)
        is Rotulo -> EditaRotulo(ed, s)
        is Linea -> EditaRuta(e, ed, s)
        is Sello -> EditaSello(ed, s)
        is Leyenda -> { Text("La leyenda: arrástrala con «Elegir» para moverla; su contenido, en «Más».", style = O.texto(13.sp)); BotonO("Quitar la leyenda", color = O.ROJO) { ed.borraSeleccion() } }
        else -> Text("Toca algo del mapa con «Elegir» (un lugar, un rótulo, una ruta, un nombre, uno de tus dibujos) para cambiarlo.", style = O.texto(13.sp, O.GRIS))
    }
    @Suppress("UNUSED_VARIABLE") val u = mesa.hashCode()
}

/** Un campo de texto que cambia lo vectorial al escribir (y se apunta para deshacer al salir). */
@Composable
private fun CampoVector(ed: Editor, valor: String, pista: String, lineas: Int = 1, poner: (String) -> Unit) {
    var t by remember(valor.hashCode(), ed.seleccion) { mutableStateOf(valor) }
    CampoO(t, { t = it; poner(it); ed.mapa.avisaVector() }, Modifier.fillMaxWidth(), pista, lineas = lineas)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditaTerritorio(e: EstadoApp, ed: Editor, t: Territorio) {
    val m = ed.mapa
    CampoVector(ed, t.nombre, "Nombre") { t.nombre = it }
    Text(t.tipo + " · " + Medidas.formatoKm2(Medidas.km2(m, ed.superficies.getOrElse(t.id) { 0L })) + if (!m.escalaPuesta) " (pon la escala en Mundo)" else "", style = O.texto(13.sp, O.GRIS))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (k in Catalogo.TIPOS_TERRITORIO) FichaO(k, t.tipo == k) { ed.cambiaVector("Tipo") { t.tipo = k } }
    }
    EtiquetaO("Color", tam = 10.sp, color = O.GRIS)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in Catalogo.COLORES) MuestraO(c, t.color == c, 28) { ed.cambiaVector("Color") { t.color = c } }
    }
    EtiquetaO("Depende de", tam = 10.sp, color = O.GRIS)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FichaO("Nadie (independiente)", t.padre == 0) { ed.cambiaVector("Depende") { t.padre = 0 } }
        for (o in m.territorios.filter { it.id != t.id && m.linaje(it.id).none { a -> a.id == t.id } }) FichaO(o.nombre, t.padre == o.id) { ed.cambiaVector("Depende") { t.padre = o.id } }
    }
    CampoVector(ed, t.capital, "Capital") { t.capital = it }
    InterruptorO("Escribir su nombre en el mapa", t.verNombre) { v -> ed.cambiaVector("Nombre") { t.verNombre = v } }
    DeslizadorO("Tamaño del nombre", if (t.tam <= 0f) 0f else t.tam, 0f..400f, if (t.tam <= 0f) "Auto" else "${t.tam.roundToInt()}") { t.tam = if (it < 4f) 0f else it; m.avisaVector() }
    DeslizadorO("Giro", t.giro, -90f..90f, "${t.giro.roundToInt()}°") { t.giro = it; m.avisaVector() }
    DeslizadorO("Curva", t.curva, -0.5f..0.5f, "%.2f".format(t.curva)) { t.curva = it; m.avisaVector() }
    Text("Para mover el nombre: «Elegir», tócalo y arrástralo.", style = O.texto(12.sp, O.GRIS))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotonO("Pintarlo", lleno = true) { ed.territorio = t.id; ed.herramienta = Herramienta.TERRITORIO; ed.seleccion = null }
        BotonO("Su ficha en Política") { e.fichaDe(m, "t", t.id.toString(), "reino", t.nombre) }
    }
    MapaVinculado(e, ed, t.mapa, t.nombre) { t.mapa = it }
    BotonO("Borrar territorio", color = O.ROJO) { ed.borraTerritorio(t) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditaLugar(e: EstadoApp, ed: Editor, l: Lugar) {
    CampoVector(ed, l.nombre, "Nombre") { l.nombre = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in Catalogo.LUGARES) FichaO(t.nombre, l.tipo == t.clave) { ed.cambiaVector("Tipo") { l.tipo = t.clave } }
    }
    DeslizadorO("Importancia", l.tamano.toFloat(), 1f..10f, "${l.tamano}") { l.tamano = it.roundToInt(); ed.mapa.avisaVector() }
    CampoVector(ed, l.nota, "Nota", 3) { l.nota = it }
    val terr = ed.lienzo.territorioEn(l.x.toInt(), l.y.toInt()).takeIf { it != 0 }?.let { ed.mapa.territorio(it) }
    if (terr != null) Text("En: " + ed.mapa.linaje(terr.id).joinToString(" · ") { it.nombre }, style = O.texto(13.sp, O.GRIS))
    MapaVinculado(e, ed, l.mapa, l.nombre) { l.mapa = it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotonO("Su ficha") { e.fichaDe(ed.mapa, "p", l.id, "lugar", l.nombre) }
        BotonO("Borrar", color = O.ROJO) { ed.borraSeleccion() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditaRotulo(ed: Editor, r: Rotulo) {
    CampoVector(ed, r.texto, "Texto") { r.texto = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((k, n) in listOf("serif" to "Serif", "cursiva" to "Cursiva", "negrita" to "Negrita", "grotesca" to "Grotesca")) FichaO(n, r.estilo == k) { ed.cambiaVector("Letra") { r.estilo = k } }
    }
    val k = 1f / ed.enMapa(1f)
    DeslizadorO("Tamaño", (r.tam * k).coerceIn(6f, 600f), 6f..600f, "${(r.tam * k).roundToInt()}") { r.tam = it / k; ed.mapa.avisaVector() }
    DeslizadorO("Espaciado", r.espaciado, 0f..1f, "%.2f".format(r.espaciado)) { r.espaciado = it; ed.mapa.avisaVector() }
    DeslizadorO("Giro", r.giro, -180f..180f, "${r.giro.roundToInt()}°") { r.giro = it; ed.mapa.avisaVector() }
    DeslizadorO("Curva", r.curva, -0.5f..0.5f, "%.2f".format(r.curva)) { r.curva = it; ed.mapa.avisaVector() }
    BotonO("Borrar", color = O.ROJO) { ed.borraSeleccion() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditaRuta(e: EstadoApp, ed: Editor, l: Linea) {
    CampoVector(ed, l.nombre, "Nombre (opcional)") { l.nombre = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in Catalogo.RUTAS) FichaO(t.nombre, l.tipo == t.clave) { ed.cambiaVector("Ruta") { l.tipo = t.clave } }
    }
    var largo = 0.0; for (i in 1 until l.n) largo += kotlin.math.hypot((l.xs[i] - l.xs[i - 1]).toDouble(), (l.ys[i] - l.ys[i - 1]).toDouble())
    Text("Largo: " + Medidas.formatoKm(largo * ed.mapa.kmPorPx), style = O.titular(18.sp))
    TiemposRuta(e, ed, l, largo * ed.mapa.kmPorPx)
    BotonO("Borrar", color = O.ROJO) { ed.borraSeleccion() }
}

@Composable
private fun EditaSello(ed: Editor, s: Sello) {
    // (De cerca, el tamaño se mide como se ve en la pantalla: la barra da para afinarlo a cualquier acercamiento)
    val k = 1f / ed.enMapa(1f)
    DeslizadorO("Tamaño", (s.escala * k).coerceIn(0.1f, 10f), 0.1f..10f, "×%.1f".format(s.escala * k)) { s.escala = it / k; ed.mapa.avisaVector() }
    DeslizadorO("Giro", s.giro, -180f..180f, "${s.giro.roundToInt()}°") { s.giro = it; ed.mapa.avisaVector() }
    InterruptorO("Espejo", s.espejo) { v -> ed.cambiaVector("Espejo") { s.espejo = v } }
    BotonO("Borrar", color = O.ROJO) { ed.borraSeleccion() }
}

@Suppress("unused") private val sinUsar = Lienzo.L

/** Deshacer / rehacer: una flecha curva dibujada (las letras de la familia no traen el símbolo). */
@Composable
private fun Flecha(atras: Boolean, activa: Boolean, alPulsar: () -> Unit) {
    val col = if (activa) O.TINTA else O.GRIS_CLARO
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).pulsableO { if (activa) alPulsar() }, contentAlignment = Alignment.Center) {
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
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
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
    Text("Pon detrás una imagen (una casa de internet, una foto, un boceto) y dibuja encima para darle tu estilo. No sale al exportar ni en la lámina.", style = O.texto(13.sp, O.GRIS))
    BotonO(if (m.calco == null) "Elegir imagen" else "Cambiar imagen", lleno = true) { importar.launch("image/*") }
    val k = m.calco ?: return
    DeslizadorO("Opacidad", k.opacidad, 0.05f..1f, "${(k.opacidad * 100).roundToInt()}%") { k.opacidad = it; mesa.refresca(); ed.cambios++ }
    InterruptorO("Se ve", k.visible) { k.visible = it; mesa.refresca(); m.avisaVector() }
    InterruptorO("Encima del dibujo (si no, detrás)", k.encima) { k.encima = it; mesa.refresca(); m.avisaVector() }
    BotonO(if (ed.herramienta == Herramienta.CALCO) "Moviendo el calco: arrastra, pellizca, gira" else "Mover, escalar y girar el calco", lleno = ed.herramienta == Herramienta.CALCO) {
        ed.herramienta = if (ed.herramienta == Herramienta.CALCO) Herramienta.PINCEL else Herramienta.CALCO; mesa.refresca()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotonO("Reflejar") { k.ancho = -k.ancho; mesa.refresca(); m.avisaVector() }
        BotonO("Quitar", color = O.ROJO) { ed.cambiaVector("Quitar calco") { m.calco = null }; ed.herramienta = Herramienta.PINCEL; m.dir?.let { java.io.File(it, "calco.png").delete() }; mesa.refresca() }
    }
    @Suppress("UNUSED_VARIABLE") val u = e.listas
}

/** El mapa que se abre al tocar algo (otro mapa del mismo mundo), o crear uno nuevo para eso. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapaVinculado(e: EstadoApp, ed: Editor, actual: String, nombre: String, poner: (String) -> Unit) {
    val otros = remember(e.listas) { e.bib.mapas().filter { it.mundo == ed.mapa.mundo && it.id != ed.mapa.id } }
    EtiquetaO("Su mapa (se abre al tocarlo con «Mover»)", tam = 10.sp, color = O.GRIS)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FichaO("Ninguno", actual.isBlank()) { ed.cambiaVector("Vincular") { poner("") } }
        for (o in otros) FichaO(o.nombre, actual == o.id) { ed.cambiaVector("Vincular") { poner(o.id) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (actual.isNotBlank()) BotonO("Abrir su mapa", lleno = true) { e.abreVinculado(actual) }
        BotonO("+ Dibujar su mapa") {
            val m = e.bib.creaMapa(ed.mapa.mundo, nombre, 0, 0, ed.mapa.papel)
            ed.cambiaVector("Vincular") { poner(m.id) }
            ed.guardaYa()
            e.listas++
            e.abreVinculado(m.id)
        }
    }
}

/** Las secciones del terreno: marcos con su tamaño real; con una activa, lo que dibujas no se sale de ella. */
@Composable
private fun PanelSecciones(ed: Editor, mesa: Mesa) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    val m = ed.mapa
    Text("Arrastra en el lienzo para hacer una sección (verás sus km mientras), o escribe su tamaño. Con una activa, lo que dibujes y rellenes se queda dentro: sabes que mide eso como mucho.", style = O.texto(12.sp, O.GRIS))
    if (!m.escalaPuesta) Text("Antes, fija la escala en Mundo → Escala (ahora 1 px = ${m.kmPorPx.toString().replace('.', ',')} km).", style = O.texto(12.sp, O.ACENTO))
    var ancho by remember { mutableStateOf("500") }; var alto by remember { mutableStateOf("300") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        CampoO(ancho, { ancho = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, Modifier.width(90.dp), "Ancho")
        Text("×", style = O.texto(16.sp, O.TINTA))
        CampoO(alto, { alto = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, Modifier.width(90.dp), "Alto")
        Text("km", style = O.texto(14.sp, O.GRIS))
        BotonO("Crear", lleno = true, color = O.ACENTO) {
            val w = (ancho.replace(',', '.').toFloatOrNull() ?: 0f) / m.kmPorPx; val h = (alto.replace(',', '.').toFloatOrNull() ?: 0f) / m.kmPorPx
            if (w > 1f && h > 1f) { val v = mesa.vista.visible(); ed.nuevaSeccion(v.centerX() - w / 2, v.centerY() - h / 2, w, h); mesa.refresca() }
        }
    }
    InterruptorO("Dibujar en todo el lienzo (ninguna activa)", m.seccionActiva.isBlank()) { if (it) { ed.cambiaVector("Sección") { m.seccionActiva = "" }; mesa.refresca() } }
    for (s in m.secciones) {
        val activa = s.id == m.seccionActiva
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (activa) O.ACENTO else O.CRUDO).pulsableO { ed.cambiaVector("Sección") { m.seccionActiva = if (activa) "" else s.id }; mesa.refresca() }.padding(10.dp)) {
            Text(s.nombre, style = O.texto(15.sp, O.TINTA))
            Text(Medidas.formatoKm(s.w.toDouble() * m.kmPorPx) + " × " + Medidas.formatoKm(s.h.toDouble() * m.kmPorPx) + " · " + Medidas.formatoKm2(s.w.toDouble() * s.h * m.kmPorPx * m.kmPorPx) + if (activa) " · dibujas aquí" else "", style = O.texto(12.sp, if (activa) O.TINTA else O.GRIS))
            if (activa) {
                var nombre by remember(s.id) { mutableStateOf(s.nombre) }
                CampoO(nombre, { nombre = it; s.nombre = it; m.avisaVector() }, Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    BotonO("Ir a ella") { val k = minOf(mesa.width / s.w, mesa.height / s.h) * 0.85f; mesa.irA(s.x + s.w / 2, s.y + s.h / 2, k) }
                    BotonO("Borrar", color = O.ROJO) { ed.cambiaVector("Borrar sección") { m.secciones.remove(s); if (m.seccionActiva == s.id) m.seccionActiva = "" }; mesa.refresca() }
                }
            }
        }
    }
}

/** Una copia de la capa (con su máscara, si tiene) justo encima, en su mismo grupo. */
private fun duplicaCapa(lz: Lienzo, c: CapaPx) {
    fun copia(x: CapaPx, nombre: String): CapaPx {
        val n = CapaPx(CapaInfo.de(x.info.json().put("id", "c" + System.nanoTime().toString(36)).put("n", nombre)))
        n.teselas.putAll(x.teselas); n.trazos = x.trazos; n.fondo.putAll(x.fondo); n.seq = x.seq; n.trazosSucios = true
        return n
    }
    val u = com.gonzalo.atlas.lienzo.Grupos.unidad(lz.capas, c)
    val nuevas = u.mapIndexed { k, x -> copia(x, if (k == 0) c.info.nombre + " (copia)" else x.info.nombre) }
    lz.capas.addAll(com.gonzalo.atlas.lienzo.Grupos.trasUnidad(lz.capas, c), nuevas)
}
