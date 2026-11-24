package com.gonzalo.atlas.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.editor.Herramienta
import com.gonzalo.atlas.lienzo.Ajustes
import com.gonzalo.atlas.motor.FormaTierra
import com.gonzalo.atlas.motor.Medidas
import com.gonzalo.atlas.vista.Mesa
import kotlin.math.roundToInt

/** La selección del lienzo: rodear una zona (con el lazo, un rectángulo o una elipse) para trabajar solo dentro de ella. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PanelMarco(ed: Editor) {
    Text("Rodea una zona del lienzo: desde entonces solo se pinta, se borra, se rellena y se ajusta dentro de ella.", style = O.texto(13.sp, O.GRIS))
    EtiquetaO("Forma", tam = 11.sp, color = O.ACENTO)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((k, n) in listOf("lazo" to "A mano", "rect" to "Rectángulo", "elipse" to "Elipse", "zona" to "Automática")) FichaO(n, ed.formaSeleccion == k) { ed.formaSeleccion = k; ed.herramienta = Herramienta.SELECCION }
    }
    if (ed.formaSeleccion == "zona") Text("Toca dentro de una zona cerrada por tus líneas (un reino, un lago, una isla): queda seleccionada entera.", style = O.texto(12.sp, O.GRIS))
    EtiquetaO("Lo que rodees", tam = 11.sp, color = O.ACENTO)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((k, n) in listOf("nueva" to "Sustituye", "sumar" to "Se suma", "restar" to "Se resta")) FichaO(n, ed.operSeleccion == k) { ed.operSeleccion = k; ed.herramienta = Herramienta.SELECCION }
    }
    if (ed.marco == null) Text("Aún no hay nada seleccionado.", style = O.texto(13.sp, O.GRIS))
    else {
        EtiquetaO("Con lo seleccionado", tam = 11.sp, color = O.ACENTO)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BotonO("Invertir") { ed.invierteMarco() }
            BotonO("Quitar la selección") { ed.ponMarco(null) }
            BotonO("Borrar lo de dentro") { ed.borraMarco() }
            BotonO("Rellenar de color") { ed.rellenaMarco() }
            BotonO("Copiar a una capa") { ed.copiaMarco(false) }
            BotonO("Cortar a una capa") { ed.copiaMarco(true) }
            BotonO("Mover y transformar", lleno = true, color = O.ACENTO) { ed.copiaMarco(true, mover = true) }
        }
        Text("Para pintar dentro, elige el pincel: la selección sigue puesta hasta que la quites.", style = O.texto(12.sp, O.GRIS))
    }
}

private val DEGRADADOS = listOf(
    "Sepia" to intArrayOf(0x2B1B10, 0x8A6240, 0xF3E4C2), "Pergamino" to intArrayOf(0x3A2A18, 0xB89A6A, 0xF6EDD6), "Mar" to intArrayOf(0x0B2233, 0x2F7A99, 0xD8F0EE),
    "Bosque" to intArrayOf(0x10210F, 0x4F7A3A, 0xE6E9B0), "Fuego" to intArrayOf(0x1A0505, 0xC0391B, 0xFFE9A0), "Noche" to intArrayOf(0x05061A, 0x3B3F8A, 0xE0D9FF),
    "Hielo" to intArrayOf(0x1D2A3A, 0x86A9C4, 0xFFFFFF), "Grises" to intArrayOf(0x000000, 0x808080, 0xFFFFFF))

/** Los ajustes de imagen de la capa activa (como los de Procreate). Los de color se ven al momento mientras mueves las barras. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PanelAjustes(ed: Editor, abre: (String) -> Unit) {
    var cual by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { ed.vistaAjuste = null } }
    Text("Sobre la capa «${ed.lienzo.capaActiva()?.info?.nombre}»" + if (ed.marco != null) ", dentro de la selección." else ".", style = O.texto(13.sp, O.GRIS))
    @Composable fun Fila(k: String, t: String, d: String, f: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (cual == k) O.CRUDO else Color.Transparent)) {
            Row(Modifier.fillMaxWidth().pulsableO { ed.vistaAjuste = null; cual = if (cual == k) "" else k }.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(t, style = O.texto(15.sp, O.TINTA)); if (cual != k) Text(d, style = O.texto(11.sp, O.GRIS)) }
                Text(if (cual == k) "–" else "+", style = O.titular(18.sp, O.GRIS))
            }
            if (cual == k) Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { f() }
        }
    }
    @Composable fun Aplicar(nombre: String, reinicia: () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotonO("Aplicar", lleno = true, color = O.ACENTO) { ed.aplicaAjuste(nombre); reinicia() }
        BotonO("Dejarlo como estaba") { ed.vistaAjuste = null; reinicia() }
    }
    Fila("hsb", "Tono, saturación y brillo", "Cambia los colores de la capa") {
        var tono by remember { mutableStateOf(0f) }; var sat by remember { mutableStateOf(1f) }; var brillo by remember { mutableStateOf(1f) }
        fun ve() { ed.vistaAjuste = Ajustes.matrizColor(tono, sat, brillo) }
        DeslizadorO("Tono", tono, -180f..180f, "${tono.roundToInt()}°") { tono = it; ve() }
        DeslizadorO("Saturación", sat, 0f..2f, "${(sat * 100).roundToInt()}%") { sat = it; ve() }
        DeslizadorO("Brillo", brillo, 0.2f..2f, "${(brillo * 100).roundToInt()}%") { brillo = it; ve() }
        Aplicar("Tono, saturación y brillo") { tono = 0f; sat = 1f; brillo = 1f }
    }
    Fila("bc", "Brillo y contraste", "Más luz, más fuerza") {
        var b by remember { mutableStateOf(0f) }; var c by remember { mutableStateOf(1f) }
        fun ve() { ed.vistaAjuste = Ajustes.matrizBrilloContraste(b, c) }
        DeslizadorO("Brillo", b, -0.6f..0.6f, "%+d".format((b * 100).roundToInt())) { b = it; ve() }
        DeslizadorO("Contraste", c, 0.2f..2.2f, "${(c * 100).roundToInt()}%") { c = it; ve() }
        Aplicar("Brillo y contraste") { b = 0f; c = 1f }
    }
    Fila("bal", "Balance de color", "Tira hacia el rojo, el verde o el azul") {
        var r by remember { mutableStateOf(0f) }; var g by remember { mutableStateOf(0f) }; var b by remember { mutableStateOf(0f) }
        fun ve() { ed.vistaAjuste = Ajustes.matrizBalance(r, g, b) }
        DeslizadorO("Cian – Rojo", r, -1f..1f, "%+d".format((r * 100).roundToInt())) { r = it; ve() }
        DeslizadorO("Magenta – Verde", g, -1f..1f, "%+d".format((g * 100).roundToInt())) { g = it; ve() }
        DeslizadorO("Amarillo – Azul", b, -1f..1f, "%+d".format((b * 100).roundToInt())) { b = it; ve() }
        Aplicar("Balance de color") { r = 0f; g = 0f; b = 0f }
    }
    Fila("deg", "Mapa de degradado", "Pasa la capa a una gama: sepia, pergamino, mar…") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((n, cs) in DEGRADADOS) Column(Modifier.width(78.dp).pulsableO { ed.ajusta("Aplicando…") { lz, c -> Ajustes.degradado(lz, c, cs) } }) {
                Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(cs.map { Color(0xFF000000.toInt() or it) })))
                Text(n, style = O.texto(11.sp, O.TINTA))
            }
        }
    }
    Fila("inv", "Invertir colores", "El negativo de la capa") { BotonO("Invertir", lleno = true, color = O.ACENTO) { ed.ajusta("Invirtiendo…") { lz, c -> Ajustes.matriz(lz, c, Ajustes.matrizInvertir(), "Invertir colores") } } }
    Fila("gauss", "Desenfoque gaussiano", "Suaviza toda la capa") {
        var radio by remember { mutableStateOf(6f) }
        DeslizadorO("Radio", radio, 1f..60f, "${radio.roundToInt()} px") { radio = it }
        BotonO("Desenfocar", lleno = true, color = O.ACENTO) { ed.desenfoca(radio.roundToInt()) }
    }
    Fila("mov", "Desenfoque de movimiento", "Arrastra la imagen en una dirección") {
        var largo by remember { mutableStateOf(12f) }; var ang by remember { mutableStateOf(0f) }
        DeslizadorO("Distancia", largo, 2f..60f, "${largo.roundToInt()} px") { largo = it }
        DeslizadorO("Dirección", ang, 0f..180f, "${ang.roundToInt()}°") { ang = it }
        BotonO("Desenfocar", lleno = true, color = O.ACENTO) { val l = largo.roundToInt(); val a = ang; ed.ajusta("Desenfocando…") { lz, c -> Ajustes.movimiento(lz, c, l, a) } }
    }
    Fila("enf", "Enfocar", "Realza los bordes") {
        var k by remember { mutableStateOf(0.6f) }
        DeslizadorO("Cantidad", k, 0.1f..2f, "${(k * 100).roundToInt()}%") { k = it }
        BotonO("Enfocar", lleno = true, color = O.ACENTO) { val q = k; ed.ajusta("Enfocando…") { lz, c -> Ajustes.enfoca(lz, c, q) } }
    }
    Fila("ruido", "Ruido", "Granula los colores (papel viejo, arena)") {
        var k by remember { mutableStateOf(0.25f) }
        DeslizadorO("Cantidad", k, 0.02f..1f, "${(k * 100).roundToInt()}%") { k = it }
        BotonO("Añadir ruido", lleno = true, color = O.ACENTO) { val q = k; ed.ajusta("Añadiendo ruido…") { lz, c -> Ajustes.ruido(lz, c, q) } }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (ed.herramienta == Herramienta.LICUAR) O.ACENTO else Color.Transparent).pulsableO { ed.herramienta = Herramienta.LICUAR; abre("licuar") }.padding(horizontal = 10.dp, vertical = 9.dp)) {
        Column(Modifier.weight(1f)) { Text("Licuar", style = O.texto(15.sp, O.TINTA)); Text("Empuja, arremolina, pellizca o hincha la pintura con el dedo", style = O.texto(11.sp, O.GRIS)) }
        Text("→", style = O.texto(16.sp, O.GRIS))
    }
    Text("Todo se deshace (dos dedos sobre el lienzo).", style = O.texto(12.sp, O.GRIS))
}

@Composable
internal fun PanelLicuar(ed: Editor) {
    Text("Arrastra sobre el lienzo. Para los remolinos, pellizcar e hinchar, deja el dedo y muévelo despacio.", style = O.texto(13.sp, O.GRIS))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for ((k, n) in listOf("empujar" to "Empujar", "remolino" to "Remolino ↻", "remolino2" to "Remolino ↺")) FichaO(n, ed.modoLicuar == k) { ed.modoLicuar = k; ed.herramienta = Herramienta.LICUAR } }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for ((k, n) in listOf("pellizcar" to "Pellizcar", "hinchar" to "Hinchar")) FichaO(n, ed.modoLicuar == k) { ed.modoLicuar = k; ed.herramienta = Herramienta.LICUAR } }
    DeslizadorO("Tamaño", ed.tamanoLicuar, 20f..600f, "${ed.tamanoLicuar.roundToInt()} px") { ed.herramienta = Herramienta.LICUAR; ed.ponTamano(it) }
    DeslizadorO("Fuerza", ed.fuerzaLicuar, 0.05f..1f, "${(ed.fuerzaLicuar * 100).roundToInt()}%") { ed.fuerzaLicuar = it }
    BotonO("Hecho", lleno = true, color = O.ACENTO) { ed.herramienta = Herramienta.PINCEL }
}

/** Las guías de dibujo: la simetría (lo que dibujas se repite reflejado) y las formas rápidas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuiasDibujo(ed: Editor, mesa: Mesa) {
    val g = ed.mapa.simetria
    EtiquetaO("Simetría", tam = 11.sp, color = O.ACENTO)
    fun pon(t: String?) { val v = mesa.vista.visible(); ed.ponSimetria(t, g?.x ?: v.centerX(), g?.y ?: v.centerY(), g?.n ?: 6); mesa.refresca() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FichaO("Sin simetría", g == null) { pon(null) }
        for ((k, n) in listOf("vertical" to "Espejo ↔", "horizontal" to "Espejo ↕", "cuadrante" to "Cuatro", "radial" to "Radial")) FichaO(n, g?.tipo == k) { pon(k) }
    }
    if (g != null) {
        if (g.tipo == "radial") DeslizadorO("Gajos", g.n.toFloat(), 3f..16f, "${g.n}") { g.n = it.roundToInt(); ed.mapa.avisaVector(); mesa.refresca() }
        BotonO("Centrar la guía en lo que se ve") { val v = mesa.vista.visible(); g.x = v.centerX(); g.y = v.centerY(); ed.mapa.avisaVector(); mesa.refresca() }
    }
    InterruptorO("Formas rápidas: deja el lápiz quieto al acabar un trazo y se vuelve una recta, un arco, una elipse o un polígono", ed.formasRapidas) { ed.formasRapidas = it }
}

/**
 * Generar un continente: das el tamaño total (ancho y alto en km) y la app dibuja su costa. Se ve en el lienzo antes de
 * ponerlo; la costa queda como trazos de tinta en la capa de líneas (para rellenar y marcar territorios encima).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PanelContinente(ed: Editor, mesa: Mesa) {
    var ancho by remember { mutableStateOf("4000") }; var alto by remember { mutableStateOf("3000") }
    var tipo by remember { mutableStateOf("continente") }
    var tierra by remember { mutableStateOf(0.36f) }; var rug by remember { mutableStateOf(0.5f) }; var islas by remember { mutableStateOf(0.35f) }
    var semilla by remember { mutableStateOf((System.nanoTime() / 1000L) % 100000L) }
    var conTierra by remember { mutableStateOf(true) }; var conMar by remember { mutableStateOf(false) }; var seccion by remember { mutableStateOf(false) }
    var aqui by remember { mutableStateOf(0) }
    val colTierra = 0xFFE6D8AE.toInt(); val colMar = 0xFFB9D3DB.toInt()
    fun km(s: String) = s.replace(',', '.').replace(" ", "").toDoubleOrNull()?.takeIf { it > 0.0 }
    val kmA = km(ancho); val kmH = km(alto)
    DisposableEffect(Unit) { onDispose { ed.vistaPrevia = null } }
    fun forma() = FormaTierra(semilla, tipo, tierra, rug, islas)
    val muestra by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, tipo, tierra, rug, islas, semilla, ancho + "x" + alto + aqui) {
        if (kmA == null || kmH == null) { value = null; ed.vistaPrevia = null; return@produceState }
        kotlinx.coroutines.delay(120)
        val asp = (kmA / kmH).toFloat().coerceIn(0.2f, 5f)
        val w = if (asp >= 1f) 300 else (300 * asp).toInt(); val h = if (asp >= 1f) (300 / asp).toInt() else 300
        val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Editor.imagenContinente(forma(), w, h, colTierra, colMar, 0xFF2A2118.toInt()) }
        value = b
        ed.vistaPrevia = b to mesa.recuadro(kmA, kmH); mesa.refresca()
    }
    Text("Di cuánto mide en total y elige la forma: la costa se dibuja sola, dentro de ese tamaño.", style = O.texto(13.sp, O.GRIS))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CampoO(ancho, { ancho = it }, Modifier.weight(1f), "Ancho (km)", teclado = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Text("×", style = O.texto(16.sp, O.GRIS))
        CampoO(alto, { alto = it }, Modifier.weight(1f), "Alto (km)", teclado = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Text("km", style = O.texto(14.sp, O.GRIS))
    }
    if (kmA != null && kmH != null) {
        val r = ed.vistaPrevia?.second
        val escala = if (ed.mapa.escalaPuesta) "A la escala del mapa (${ed.mapa.kmPorPx.toString().replace('.', ',')} km por píxel)" else "Fijará la escala del mapa" + (r?.let { ": ${"%.2f".format(kmA / it.width()).replace('.', ',')} km por píxel" } ?: "")
        Text("${Medidas.formatoKm2(kmA * kmH)} en total, ${Medidas.formatoKm2(kmA * kmH * tierra)} de tierra. $escala.", style = O.texto(12.sp, O.GRIS))
    } else Text("Escribe el ancho y el alto en kilómetros.", style = O.texto(12.sp, O.ROJO))
    Box(Modifier.fillMaxWidth().aspectRatio(((kmA ?: 4.0) / (kmH ?: 3.0)).toFloat().coerceIn(0.5f, 3f)).clip(RoundedCornerShape(10.dp)).background(O.MESA)) {
        muestra?.let { Image(it.asImageBitmap(), "La forma", Modifier.fillMaxSize()) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotonO("Otra forma", lleno = true) { semilla = (System.nanoTime() / 1000L) % 100000L }
        BotonO("Colocarla aquí") { aqui++ }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { for ((k, n) in FormaTierra.TIPOS) FichaO(n, tipo == k) { tipo = k } }
    DeslizadorO("Tierra", tierra, 0.08f..0.8f, "${(tierra * 100).roundToInt()}%") { tierra = it }
    DeslizadorO("Costa recortada", rug, 0f..1f, "${(rug * 100).roundToInt()}%") { rug = it }
    DeslizadorO("Islas", islas, 0f..1f, "${(islas * 100).roundToInt()}%") { islas = it }
    InterruptorO("Pintar la tierra de color", conTierra) { conTierra = it }
    InterruptorO("Pintar el mar", conMar) { conMar = it }
    InterruptorO("Crear una sección con ese tamaño", seccion) { seccion = it }
    Text("La costa se dibuja con el color elegido (ahora ${"#%06X".format(ed.color and 0xFFFFFF)}), en la capa de líneas.", style = O.texto(12.sp, O.GRIS))
    BotonO("Poner en el mapa", lleno = true, color = O.ACENTO, activo = kmA != null && kmH != null) {
        val r = ed.vistaPrevia?.second ?: mesa.recuadro(kmA ?: return@BotonO, kmH ?: return@BotonO)
        ed.ponContinente(forma(), r, kmA ?: 0.0, ed.color, if (conTierra) colTierra else null, if (conMar) colMar else null, seccion)
    }
}
