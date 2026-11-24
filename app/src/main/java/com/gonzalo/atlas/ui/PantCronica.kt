package com.gonzalo.atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonzalo.atlas.editor.Herramienta
import com.gonzalo.atlas.motor.Cronica
import com.gonzalo.atlas.motor.Medidas

/**
 * La crónica del mundo: su enciclopedia por categorías (reinos e imperios, personajes y líderes, ciudades, regiones, pueblos,
 * historia, fe…). Los territorios y lugares del mapa aparecen solos; al escribir sobre uno queda ligado y lleva al mapa.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantCronica(e: EstadoApp) {
    val m = e.mapa ?: run { Vacio("Abre o crea un mapa: la crónica es de su mundo"); return }
    val cr = remember(m.mundo, e.listas) { e.cronica(m.mundo) }
    var version by remember { mutableIntStateOf(0) }
    var cat by remember { mutableStateOf("reino") }
    var elegida by remember { mutableStateOf<String?>(null) }
    // La ficha pedida desde el mapa (o al tocar algo del mapa sin ficha)
    e.fichaPedida?.let { id -> if (elegida != id) { elegida = id; cr.entradas[id]?.let { cat = it.cat } } }
    androidx.compose.runtime.LaunchedEffect(e.fichaPedida) { e.fichaPedida = null }
    val ed = e.editor
    @Suppress("UNUSED_VARIABLE") val v = version + (ed?.cambios ?: 0)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ancho = maxWidth > 760.dp
        val lista = @Composable { mod: Modifier ->
            Column(mod.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Crónica de " + (e.bib.mundo(m.mundo)?.nombre ?: "tu mundo"), style = T.titular(30.sp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in Cronica.CATEGORIAS) Ficha(c.nombre + " " + cr.de(c.clave).size, cat == c.clave) { cat = c.clave }
                }
                Seccion(null, Cronica.categoria(cat).nombre, derecha = { Enlace("+ Nueva") { val f = cr.nueva(cat, "Sin nombre"); elegida = f.id; version++; e.listas++ } })
                // Lo del mapa que aún no tiene ficha
                if (cat == "reino") for (t in m.territorios.filter { cr.deEnlace("${m.id}:t:${it.id}") == null }) {
                    Fila(t.nombre, t.tipo + " · en el mapa, sin ficha", Color(t.color), false) { e.fichaDe(m, "t", t.id.toString(), "reino", t.nombre); elegida = e.fichaPedida; version++ }
                }
                if (cat == "lugar") for (l in m.lugares.filter { cr.deEnlace("${m.id}:p:${it.id}") == null }) {
                    Fila(l.nombre, com.gonzalo.atlas.motor.Catalogo.lugar(l.tipo).nombre + " · en el mapa, sin ficha", null, false) { e.fichaDe(m, "p", l.id, "lugar", l.nombre); elegida = e.fichaPedida; version++ }
                }
                for (f in cr.de(cat)) Fila(f.titulo, f.campos.values.filter { it.isNotBlank() }.take(2).joinToString(" · ").ifBlank { if (f.enlace.isNotBlank()) "En el mapa" else "" }, null, f.id == elegida) { elegida = f.id }
                Spacer(Modifier.height(30.dp))
            }
        }
        val ficha = @Composable { mod: Modifier ->
            val f = elegida?.let { cr.entradas[it] }
            Column(mod.verticalScroll(rememberScrollState()).padding(18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (f == null) { Text("Elige una ficha o crea una nueva.", style = T.nombre(18.sp, T.GRIS)); return@Column }
                if (!ancho) Enlace("← Volver a la lista") { elegida = null }
                var titulo by remember(f.id) { mutableStateOf(f.titulo) }
                Campo(titulo, { titulo = it; f.titulo = it; f.u = System.currentTimeMillis(); cr.guarda(); version++ }, Modifier.fillMaxWidth(), "Nombre", T.titular(26.sp))
                Etiqueta(Cronica.categoria(f.cat).nombre, tam = 10.sp, color = T.ACENTO)
                Datos(e, f)
                for (campo in Cronica.categoria(f.cat).campos + f.campos.keys.filter { k -> Cronica.categoria(f.cat).campos.none { it == k } }) {
                    var valor by remember(f.id, campo) { mutableStateOf(f.campos[campo].orEmpty()) }
                    Etiqueta(campo, tam = 10.sp, color = T.GRIS)
                    Campo(valor, { valor = it; f.campos[campo] = it; f.u = System.currentTimeMillis(); cr.guarda() }, Modifier.fillMaxWidth())
                }
                Etiqueta("Historia y notas", tam = 10.sp, color = T.GRIS)
                var texto by remember(f.id) { mutableStateOf(f.texto) }
                Campo(texto, { texto = it; f.texto = it; f.u = System.currentTimeMillis(); cr.guarda() }, Modifier.fillMaxWidth(), "Escribe aquí…", T.nombre(17.sp), lineas = 8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (f.enlace.isNotBlank()) Boton("Ver en el mapa", lleno = true) { verEnMapa(e, f.enlace) }
                    Boton("Borrar ficha", color = T.ROJO) { cr.borra(f.id); elegida = null; version++; e.listas++ }
                }
            }
        }
        if (ancho) Row(Modifier.fillMaxSize()) {
            lista(Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(T.TINTA))
            ficha(Modifier.weight(1.3f).fillMaxHeight())
        } else if (elegida != null) ficha(Modifier.fillMaxSize()) else lista(Modifier.fillMaxSize())
    }
}

@Composable
private fun Fila(t: String, sub: String, color: Color?, sel: Boolean, alPulsar: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (sel) T.CRUDO else Color.Transparent).border(if (sel) 2.dp else 0.dp, if (sel) T.ACENTO else Color.Transparent).pulsable(alPulsar).padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (color != null) { Box(Modifier.size(16.dp).background(color).border(1.dp, T.TINTA)); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) { Text(t, style = T.nombre(19.sp)); if (sub.isNotBlank()) Text(sub, style = T.texto(12.sp, T.GRIS), maxLines = 1) }
    }
    Filete(1, T.GRIS_CLARO)
}

/** Lo que el mapa sabe de lo ligado: superficie, de quién depende, qué tiene dentro. */
@Composable
private fun Datos(e: EstadoApp, f: Cronica.Entrada) {
    val ed = e.editor ?: return
    val p = f.enlace.split(':'); if (p.size != 3 || p[0] != ed.mapa.id) return
    val m = ed.mapa
    val lineas = ArrayList<String>()
    when (p[1]) {
        "t" -> m.territorio(p[2].toIntOrNull() ?: 0)?.let { t ->
            lineas += t.tipo + " · " + Medidas.formatoKm2(Medidas.km2(m, ed.superficies.getOrElse(t.id) { 0L }))
            m.linaje(t.id).drop(1).takeIf { it.isNotEmpty() }?.let { lineas += "Parte de: " + it.joinToString(" · ") { a -> a.nombre } }
            m.hijos(t.id).takeIf { it.isNotEmpty() }?.let { lineas += "Dentro: " + it.joinToString(", ") { h -> h.nombre } }
            val dentro = m.lugares.filter { l -> m.linaje(ed.lienzo.territorioEn(l.x.toInt(), l.y.toInt())).any { it.id == t.id } }
            if (dentro.isNotEmpty()) lineas += "Lugares: " + dentro.sortedByDescending { it.tamano }.joinToString(", ") { it.nombre }
            if (t.capital.isNotBlank()) lineas += "Capital: " + t.capital
        }
        "p" -> m.lugares.firstOrNull { it.id == p[2] }?.let { l ->
            lineas += com.gonzalo.atlas.motor.Catalogo.lugar(l.tipo).nombre
            ed.lienzo.territorioEn(l.x.toInt(), l.y.toInt()).takeIf { it != 0 }?.let { lineas += "En: " + m.linaje(it).joinToString(" · ") { a -> a.nombre } }
        }
    }
    if (lineas.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(T.CRUDO).border(1.dp, T.TINTA).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Etiqueta("Del mapa", tam = 10.sp, color = T.ACENTO)
        for (l in lineas) Text(l, style = T.texto(14.sp))
    }
}

/** Lleva al mapa y centra lo ligado (un territorio o un lugar). */
fun verEnMapa(e: EstadoApp, enlace: String) {
    val p = enlace.split(':'); if (p.size != 3) return
    if (e.mapa?.id != p[0]) e.abreMapa(p[0], irAlMapa = false)
    val ed = e.editor ?: return
    e.seccion = "mapa"; e.dibujando = true
    // Se apunta dónde mirar: la mesa lo usa al abrirse (o ya, si está abierta)
    val punto = when (p[1]) {
        "t" -> { val id = p[2].toIntOrNull() ?: return; ed.territorio = id; ed.herramienta = Herramienta.ELEGIR; ed.seleccion = ed.mapa.territorio(id)
            ed.centros.firstOrNull { it.id == id }?.let { floatArrayOf(it.x, it.y) } }
        "p" -> ed.mapa.lugares.firstOrNull { it.id == p[2] }?.let { l -> ed.herramienta = Herramienta.ELEGIR; ed.seleccion = l; floatArrayOf(l.x, l.y) }
        else -> null
    } ?: return
    ed.mapa.vista[0] = punto[0]; ed.mapa.vista[1] = punto[1]
    e.mesaAbierta(ed)?.let { mesa -> mesa.post { mesa.irA(punto[0], punto[1], kotlin.math.max(mesa.vista.zoom, 0.5f)) } }
}

@Suppress("unused") private val sinUsar = Modifier.fillMaxWidth()
