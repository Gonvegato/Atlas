package com.gonzalo.atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.motor.AcabadoCosta
import com.gonzalo.atlas.motor.CambioTerritorio
import com.gonzalo.atlas.motor.Cronica
import com.gonzalo.atlas.motor.Linea
import com.gonzalo.atlas.motor.Medidas
import com.gonzalo.atlas.vista.Mesa
import kotlin.math.roundToInt

// ---------------------------------------------------------------- el acabado de la costa

/** Los colores de serie para las líneas de agua (y el que ya tenga). */
private val COLORES_AGUA = listOf(0xFF567884.toInt(), 0xFF2E4A5C.toInt(), 0xFF6F8C7A.toInt(), 0xFF3B3324.toInt(), 0xFF8A6A3A.toInt(), 0xFF1E2A44.toInt())

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PanelCosta(ed: Editor) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    val puesto = ed.mapa.acabadoCosta
    val a = remember(puesto) { puesto?.let { AcabadoCosta.de(it.json()) } ?: AcabadoCosta() }
    var lineas by remember(puesto) { mutableStateOf(a.lineas) }
    var sep by remember(puesto) { mutableStateOf(a.separacionKm) }
    var apagado by remember(puesto) { mutableStateOf(a.apagado) }
    var sombra by remember(puesto) { mutableStateOf(a.sombra) }
    var color by remember(puesto) { mutableStateOf(a.color) }
    if (ed.mapa.tierras.isEmpty()) {
        Text("Se hace a partir de las costas de las tierras generadas (Mundo → Generar un continente). Este mapa aún no tiene ninguna; las costas dibujadas a mano no llevan acabado.", style = O.texto(13.sp, O.GRIS))
        return
    }
    Text("Líneas de agua alrededor de la costa y una sombra en el mar pegada a ella, hechas solas a partir de las tierras generadas. Quedan en su propia capa, «Acabado de la costa», sobre el mar y debajo de todo lo tuyo. Si mueves o cambias una tierra, se rehacen.", style = O.texto(12.sp, O.GRIS))
    Text("Líneas de agua", style = O.texto(14.sp, O.TINTA))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (n in listOf(0, 2, 3, 5)) FichaO(if (n == 0) "Ninguna" else "$n", lineas == n) { lineas = n } }
    val sepTexto = if (sep <= 0f) "Automática" else Medidas.formatoKm(sep.toDouble())
    DeslizadorO("Separación", sep, 0f..(200f * ed.mapa.kmPorPx).coerceAtLeast(5f), sepTexto) { sep = if (it < 0.02f * ed.mapa.kmPorPx) 0f else it }
    DeslizadorO("Se apagan hacia el mar", apagado, 0f..1f, "${(apagado * 100).roundToInt()} %") { apagado = it }
    InterruptorO("Sombra del litoral", sombra) { sombra = it }
    Text("Color", style = O.texto(14.sp, O.TINTA))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in (COLORES_AGUA + color).distinct()) Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(c)).border(if (c == color) 3.dp else 1.dp, O.TINTA, RoundedCornerShape(15.dp)).pulsableO { color = c })
    }
    BotonO(if (puesto == null) "Poner el acabado" else "Cambiarlo", lleno = true) {
        ed.ponAcabadoCosta(AcabadoCosta(lineas, sep, apagado, sombra, color))
    }
    if (puesto != null) BotonO("Quitar el acabado", color = O.ROJO) { ed.ponAcabadoCosta(null) }
}

// ---------------------------------------------------------------- tiempos de viaje

/** Los días de viaje de una ruta de [km], con las velocidades del mundo ([cr]). */
@Composable
internal fun TiemposRuta(e: EstadoApp, ed: Editor, l: Linea, km: Double) {
    val cr = remember(ed.mapa.mundo, e.listas) { e.cronica(ed.mapa.mundo) }
    val t = cr.tiempos(km, l.tipo)
    if (t.isEmpty() || km <= 0.0) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((modo, dias) in t) Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(O.CRUDO).padding(8.dp)) {
            Text(Cronica.MODOS.first { it.first == modo }.second, style = O.texto(11.sp, O.GRIS))
            Text(dias(dias), style = O.titular(16.sp, O.TINTA))
        }
    }
    Text("Con las velocidades de este mundo (Mundo → Viajes)" + when (l.tipo) { "calzada" -> "."; "sendero" -> ", por sendero (más lento que por calzada)."; "maritima" -> "."; else -> ", por camino (algo más lento que por calzada)." }, style = O.texto(11.sp, O.GRIS))
    BotonO("Apuntarlo en la Crónica") {
        val titulo = "Viaje: " + l.nombre.ifBlank { com.gonzalo.atlas.motor.Catalogo.ruta(l.tipo).nombre }
        val f = cr.de("otro").firstOrNull { it.titulo == titulo } ?: cr.nueva("otro", titulo)
        f.texto = Medidas.formatoKm(km) + " · " + t.joinToString(" · ") { (m, d) -> Cronica.MODOS.first { it.first == m }.second.lowercase() + " " + dias(d) }
        f.u = System.currentTimeMillis(); cr.guarda(); e.listas++
        ed.avisa("Apuntado en la Crónica: «$titulo»")
    }
}

/** «4 días», «medio día», «2 horas». */
internal fun dias(d: Double): String = when {
    d >= 1.5 -> "${d.roundToInt()} días"
    d >= 0.75 -> "1 día"
    d >= 0.4 -> "medio día"
    else -> "${(d * 24).roundToInt().coerceAtLeast(1)} h"
}

/** Lo que se recorre en un día de cada manera, en este mundo. */
@Composable
internal fun PanelViajes(e: EstadoApp, ed: Editor) {
    val cr = remember(ed.mapa.mundo, e.listas) { e.cronica(ed.mapa.mundo) }
    var v by remember { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val x = v
    Text("Cuánto se recorre en un día. Las rutas (Mundo → Rutas, y al elegir una) dicen con esto cuántos días se tarda. Por calzada; por camino se va a un 85 % y por sendero a un 65 %. Vale para todos los mapas de este mundo.", style = O.texto(12.sp, O.GRIS))
    for ((k, n) in Cronica.MODOS) {
        val val0 = (cr.velocidades[k] ?: Cronica.VELOCIDADES[k]!!).toFloat()
        DeslizadorO(n, val0, 5f..(if (k == "barco") 400f else 150f), "${val0.roundToInt()} km al día") { cr.velocidades[k] = it.roundToInt().toDouble(); cr.guarda(); v++; e.listas++ }
    }
    BotonO("Volver a las de serie") { cr.velocidades.clear(); cr.velocidades.putAll(Cronica.VELOCIDADES); cr.guarda(); v++; e.listas++ }
}

// ---------------------------------------------------------------- la línea del tiempo

/** Cambia el año que se ve (null: el mapa tal como está dibujado) y repinta los territorios. */
internal fun verAno(ed: Editor, mesa: Mesa, ano: Int?) {
    ed.mapa.anoVista = ano
    mesa.velo.olvida(); mesa.piramide.todoSucio(); mesa.refresca(); ed.cambios++
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PanelTiempo(e: EstadoApp, ed: Editor, mesa: Mesa) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    val m = ed.mapa
    val cr = remember(m.mundo, e.listas) { e.cronica(m.mundo) }
    val guerras = remember(cr) { cr.anosDeGuerras() }
    val anos = (m.cambios.map { it.ano } + guerras.flatMap { listOfNotNull(it.second, it.third) } + listOfNotNull(cr.anoActual)).sorted()
    val ano = m.anoVista
    Text("Cómo cambian las fronteras con los años. Un cambio dice de quién es un territorio desde un año (conquista, cesión, independencia) o cuándo aparece o desaparece. Lo que dibujas no tiene año: vale siempre. Las guerras de Guerras salen solas, con sus años («Empieza» y «Termina»).", style = O.texto(12.sp, O.GRIS))
    if (m.territorios.isEmpty()) { Text("Primero marca territorios (Mundo → Territorios): los cambios son de ellos.", style = O.texto(13.sp, O.TINTA)); return }
    // El año que se ve
    val desde = (anos.firstOrNull() ?: (cr.anoActual ?: 0)) - 10
    val hasta = (anos.lastOrNull() ?: (cr.anoActual ?: 0)) + 10
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(if (ano == null) "EL MAPA TAL COMO ESTÁ DIBUJADO" else "EL MUNDO EN EL AÑO", style = O.texto(11.sp, O.GRIS))
            Text(ano?.toString() ?: "—", style = O.titular(34.sp))
        }
        if (ano != null) BotonO("Sin año") { verAno(ed, mesa, null) }
    }
    DeslizadorO("Año", (ano ?: cr.anoActual ?: desde + 10).toFloat().coerceIn(desde.toFloat(), hasta.toFloat()), desde.toFloat()..hasta.toFloat(), "${ano ?: "—"}") { verAno(ed, mesa, it.roundToInt()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ano != null) { BotonO("− 1") { verAno(ed, mesa, ano - 1) }; BotonO("+ 1") { verAno(ed, mesa, ano + 1) } }
        for (a in anos.distinct()) FichaO("$a", a == ano) { verAno(ed, mesa, a) }
    }
    // El año de ahora en el mundo
    var actual by remember(cr) { mutableStateOf(cr.anoActual?.toString().orEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Año de ahora", style = O.texto(13.sp, O.TINTA))
        CampoO(actual, { t -> actual = t; cr.anoActual = Cronica.ano(t); cr.guarda() }, Modifier.weight(1f), pista = "el de la novela")
        cr.anoActual?.let { BotonO("Ir") { verAno(ed, mesa, it) } }
    }
    if (ano == null) return
    // Lo que pasa ese año
    val enGuerra = guerras.filter { (_, a, b) -> a != null && a <= ano && (b == null || ano <= b) }
    for ((g, a, b) in enGuerra) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(O.CRUDO).padding(10.dp)) {
        Text("EN GUERRA", style = O.texto(11.sp, O.ROJO))
        Text(g.titulo, style = O.texto(14.sp, O.TINTA))
        val bandos = listOf(cr.bando(g, "a"), cr.bando(g, "b")).map { l -> l.joinToString(", ") { it.titulo }.ifBlank { "?" } }
        Text("${bandos[0]} contra ${bandos[1]} · $a–${b ?: "…"}", style = O.texto(12.sp, O.GRIS))
    }
    val vigentes = m.cambios.filter { it.ano <= ano }.sortedByDescending { it.ano }
    for (c in m.cambios.filter { it.ano == ano }) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(O.CRUDO).padding(10.dp)) {
        Text(if (c.ocupado) "OCUPACIÓN" else "CAMBIO", style = O.texto(11.sp, O.ACENTO_NEGRO))
        Text(frase(ed, c), style = O.texto(14.sp, O.TINTA))
        if (c.nota.isNotBlank()) Text(c.nota, style = O.texto(12.sp, O.GRIS))
    }
    // Un cambio nuevo en este año
    var nuevo by remember { mutableStateOf(false) }
    if (!nuevo) BotonO("+ Un cambio en este año", lleno = true) { nuevo = true }
    else {
        var ter by remember { mutableStateOf(0) }; var tipo by remember { mutableStateOf("pasa") }; var padre by remember { mutableStateOf(0) }
        var ocupado by remember { mutableStateOf(false) }; var nota by remember { mutableStateOf("") }
        Text("¿Qué territorio?", style = O.texto(13.sp, O.TINTA))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { for (t in m.territorios) FichaO(t.nombre, ter == t.id) { ter = t.id } }
        Text("¿Qué le pasa en $ano?", style = O.texto(13.sp, O.TINTA))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { for ((k, n) in CambioTerritorio.TIPOS) FichaO(n, tipo == k) { tipo = k } }
        if (tipo == "pasa") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { for (t in m.territorios.filter { it.id != ter }) FichaO(t.nombre, padre == t.id) { padre = t.id } }
        }
        if (tipo == "pasa" || tipo == "independiente") InterruptorO("Es una ocupación (se ve rayado, con el color de antes)", ocupado) { ocupado = it }
        CampoO(nota, { nota = it }, Modifier.fillMaxWidth(), pista = "Nota (opcional): por qué, quién, cómo")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonO("Apuntar", lleno = true, activo = ter != 0 && (tipo != "pasa" || padre != 0)) {
                ed.cambiaVector("Cambio en el tiempo") { m.cambios += CambioTerritorio(m.nuevoId("ct"), ter, ano, tipo, if (tipo == "pasa") padre else 0, ocupado && (tipo == "pasa" || tipo == "independiente"), nota.trim()) }
                nuevo = false; verAno(ed, mesa, ano)
            }
            BotonO("Cancelar") { nuevo = false }
        }
    }
    // Todos los cambios
    if (m.cambios.isNotEmpty()) {
        Text("TODOS LOS CAMBIOS", style = O.texto(11.sp, O.GRIS))
        for (c in m.cambios.sortedBy { it.ano }) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (c in vigentes) O.CRUDO else Color.Transparent).pulsableO { verAno(ed, mesa, c.ano) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${c.ano}", Modifier.padding(end = 10.dp), style = O.titular(14.sp, O.TINTA))
            Text(frase(ed, c), Modifier.weight(1f), style = O.texto(13.sp, O.TINTA))
            Text("✕", Modifier.pulsableO { ed.cambiaVector("Quitar cambio") { m.cambios.remove(c) }; verAno(ed, mesa, ano) }.padding(horizontal = 8.dp), style = O.texto(14.sp, O.GRIS))
        }
    }
}

/** «Ostra pasa a ser de Corvia (ocupada)». */
private fun frase(ed: Editor, c: CambioTerritorio): String {
    val m = ed.mapa
    val t = m.territorio(c.territorio)?.nombre ?: "?"
    return when (c.tipo) {
        "pasa" -> "$t pasa a ser de ${m.territorio(c.padre)?.nombre ?: "?"}" + if (c.ocupado) " (ocupada)" else ""
        "independiente" -> "$t se hace independiente" + if (c.ocupado) " (ocupada)" else ""
        "aparece" -> "Aparece $t"
        else -> "Desaparece $t"
    }
}

// ---------------------------------------------------------------- medir el trazo

/**
 * Medir el trazo en la tableta: se enciende, se dibuja como siempre en el mapa (un rato, rápido y despacio) y se ven las
 * cifras al momento; «Compartir el informe» lo manda (con el modelo de la tableta) para arreglar lo que salga lento.
 */
@Composable
internal fun MedirTrazo(ed: Editor, mesa: Mesa) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val med = com.gonzalo.atlas.lienzo.Medidor
    var activo by remember { mutableStateOf(med.activo) }
    var r by remember { mutableStateOf(med.resultado()) }
    androidx.compose.runtime.LaunchedEffect(activo) { while (activo) { r = med.resultado(); kotlinx.coroutines.delay(400) } }
    EtiquetaO("Medir el trazo", tam = 11.sp, color = O.ACENTO)
    if (!activo && r.muestras == 0) Text("Mide lo que tarda el trazo en tu tableta: enciéndelo, cierra este panel y dibuja como siempre un rato (rápido y despacio, largo y corto; también con un pincel grande y muy de cerca). Luego vuelve aquí y comparte el informe.", style = O.texto(12.sp, O.GRIS))
    if (activo || r.muestras > 0) {
        fun f(v: Double) = String.format(java.util.Locale("es"), "%.1f", v)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cifra("Del lápiz al dibujo", "${f(r.latenciaMediana)} ms", "peor 5 %: ${f(r.latenciaP95)} ms", Modifier.weight(1f))
            Cifra("Imágenes por segundo", f(r.fps), "dibujando", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cifra("Pintar cada imagen", "${f(r.pintarMedia)} ms", "peor 5 %: ${f(r.pintarP95)} ms", Modifier.weight(1f))
            Cifra("Estampar", "${f(r.estamparMedia)} ms", "peor 5 %: ${f(r.estamparP95)} ms", Modifier.weight(1f))
        }
        Text("${f(r.puntosPorSegundo)} puntos del lápiz por segundo · ${f(r.segundos)} s dibujando · ${r.muestras} medidas. No cuenta el retraso de la propia pantalla (eso solo se ve grabando a cámara lenta).", style = O.texto(11.sp, O.GRIS))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!activo) BotonO(if (r.muestras > 0) "Volver a medir" else "Empezar a medir", lleno = true) { med.empieza(); activo = true; r = med.resultado(); ed.avisa("Midiendo: dibuja como siempre") }
        else BotonO("Terminar", lleno = true) { med.para(); activo = false; r = med.resultado() }
        if (!activo && r.muestras > 0) BotonO("Compartir el informe") {
            val hz = runCatching { (ctx as? android.app.Activity)?.display?.refreshRate }.getOrNull() ?: 60f
            val texto = med.informe(com.gonzalo.atlas.BuildConfig.VERSION_NAME, ed.pincel, ed.tamano, mesa.vista.zoom, ed.lienzo.capas.size, hz)
            ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "ATLAS: medir el trazo").putExtra(android.content.Intent.EXTRA_TEXT, texto), "Compartir el informe"))
        }
    }
}

@Composable
private fun Cifra(t: String, v: String, nota: String, mod: Modifier) {
    Column(mod.clip(RoundedCornerShape(10.dp)).background(O.CRUDO).padding(10.dp)) {
        Text(t, style = O.texto(11.sp, O.GRIS)); Text(v, style = O.titular(20.sp, O.TINTA)); Text(nota, style = O.texto(10.sp, O.GRIS))
    }
}
