package com.gonzalo.atlas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonzalo.atlas.BuildConfig
import com.gonzalo.atlas.familia.Actualizador
import com.gonzalo.atlas.familia.Cuenta
import com.gonzalo.atlas.familia.Nube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Mundo: lo de este mapa (escala, novelas), la cuenta de la familia, la nube y las actualizaciones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantMundo(e: EstadoApp) {
    val ctx = LocalContext.current
    val m = e.mapa
    val c = e.cuenta
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Relleno).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (m != null) {
            Seccion(1, "Este mapa")
            var nombre by remember(m.id) { mutableStateOf(m.nombre) }
            Etiqueta("Nombre", tam = 10.sp, color = T.GRIS)
            Campo(nombre, { nombre = it; m.nombre = it; m.avisaVector() }, Modifier.fillMaxWidth())
            Etiqueta("Escala: cuántos km mide un píxel del lienzo", tam = 10.sp, color = T.GRIS)
            var km by remember(m.id) { mutableStateOf(if (m.escalaPuesta) m.kmPorPx.toString().replace('.', ',') else "") }
            Campo(km, { t -> km = t; t.replace(',', '.').toFloatOrNull()?.takeIf { it > 0 }?.let { m.kmPorPx = it; m.escalaPuesta = true; m.avisaVector() } }, Modifier.fillMaxWidth(), "Por ejemplo 0,5", teclado = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            val ancho = if (m.infinito) null else m.ancho * m.kmPorPx
            Text(if (ancho != null) "El lienzo mide ${com.gonzalo.atlas.motor.Medidas.formatoKm(ancho.toDouble())} de ancho." else "Lienzo infinito.", style = T.texto(14.sp, T.GRIS))
            Spacer(Modifier.height(6.dp))
            Seccion(2, "En qué novelas sale")
            ObrasDelMapa(e)
        }
        Seccion(3, "Cuenta de la familia")
        if (c.dentro) {
            Text("Dentro como ${c.usuario}. Es la misma cuenta que Polaris, Nova y Lyra.", style = T.texto(15.sp))
            Interruptor("Subir sola este mapa a la cuenta al guardarlo", c.autoSubir) { c.ponAutoSubir(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Boton(if (c.subiendo) "Subiendo…" else "Subir ahora", lleno = true, activo = m != null && !c.subiendo) { Nube.subeEnSegundoPlano(e, manual = true) }
                Boton("Salir") { c.salir(); e.listas++ }
            }
            if (c.estado.isNotBlank()) Text(c.estado, style = T.texto(13.sp, T.ACENTO))
            MapasDeLaNube(e)
        } else Entrar(c, e)
        Seccion(4, "Actualizaciones")
        Text("ATLAS ${BuildConfig.VERSION_NAME}. Se actualiza sola desde la carpeta «versiones» de tu Google Drive, como las demás apps de la familia.", style = T.texto(14.sp))
        var act by remember { mutableStateOf(Actualizador.activo(ctx)) }
        Interruptor("Actualizarse sola", act) { act = it; Actualizador.ponActivo(ctx, it) }
        var res by remember { mutableStateOf("") }
        var mirando by remember { mutableStateOf(false) }
        LaunchedEffect(mirando) { if (mirando) { res = withContext(Dispatchers.IO) { Actualizador.ahora(ctx) }; mirando = false } }
        Boton(if (mirando) "Mirando…" else "Actualizar ahora", activo = !mirando) { mirando = true }
        Text((res.ifBlank { Actualizador.estado }), style = T.texto(13.sp, T.GRIS))
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Entrar(c: Cuenta, e: EstadoApp) {
    var u by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var entrando by remember { mutableStateOf(false) }
    LaunchedEffect(entrando) {
        if (!entrando) return@LaunchedEffect
        error = withContext(Dispatchers.IO) { c.entrar(u, p) }
        entrando = false
        if (error.isBlank()) e.listas++
    }
    Text("Si ya has entrado en otra app de la familia, ATLAS entra sola. Si no, entra aquí con tu usuario de la familia.", style = T.texto(14.sp))
    Campo(u, { u = it }, Modifier.fillMaxWidth(), "Usuario")
    Campo(p, { p = it }, Modifier.fillMaxWidth(), "Contraseña", teclado = KeyboardOptions(keyboardType = KeyboardType.Password), oculto = true)
    Boton(if (entrando) "Entrando…" else "Entrar", lleno = true, activo = !entrando) { entrando = true }
    if (error.isNotBlank()) Text(error, style = T.texto(14.sp, T.ROJO))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObrasDelMapa(e: EstadoApp) {
    val m = e.mapa ?: return
    val c = e.cuenta
    var obras by remember { mutableStateOf<List<Cuenta.Obra>?>(null) }
    LaunchedEffect(c.dentro) { if (c.dentro) obras = withContext(Dispatchers.IO) { runCatching { c.obras() }.getOrNull() } }
    if (!c.dentro) { Text("Entra en la cuenta para ligar este mapa con tus novelas de Nova (así se ve en sus escritos).", style = T.texto(14.sp, T.GRIS)); return }
    val l = obras ?: run { Text("Cargando tus novelas…", style = T.texto(14.sp, T.GRIS)); return }
    var v by remember { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val vv = v
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (o in l) Ficha(o.titulo, o.id in m.obras) { if (o.id in m.obras) m.obras.remove(o.id) else m.obras.add(o.id); m.avisaVector(); v++ }
    }
    Text("Al subirlo, el mapa se ve en los escritos de estas novelas (solo para mirar).", style = T.texto(12.sp, T.GRIS))
}

@Composable
private fun MapasDeLaNube(e: EstadoApp) {
    val c = e.cuenta
    var lista by remember { mutableStateOf<List<Cuenta.MapaNube>?>(null) }
    var cargar by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(cargar) { if (cargar) { lista = withContext(Dispatchers.IO) { runCatching { c.mapasNube() }.getOrNull() }; cargar = false } }
    Enlace("Ver los mapas de la cuenta") { cargar = true }
    val locales = remember(e.listas) { e.bib.mapas().map { it.id }.toSet() }
    lista?.let { l ->
        if (l.isEmpty()) Text("No hay mapas en la cuenta.", style = T.texto(14.sp, T.GRIS))
        for (mn in l) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(mn.titulo, style = T.nombre(18.sp))
                Text(if (mn.id in locales) "También en este dispositivo" else "Solo en la cuenta", style = T.texto(12.sp, T.GRIS))
            }
            Enlace("Bajar") {
                msg = "Bajando…"
                Thread { val r = Nube.baja(e, mn); android.os.Handler(android.os.Looper.getMainLooper()).post { msg = r.ifBlank { "Bajado: «${mn.titulo}»" }; e.listas++ } }.start()
            }
        }
    }
    if (msg.isNotBlank()) Text(msg, style = T.texto(13.sp, T.ACENTO))
}
