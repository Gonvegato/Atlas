package com.gonzalo.atlas.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gonzalo.atlas.editor.Editor
import com.gonzalo.atlas.editor.Herramienta
import com.gonzalo.atlas.motor.Cronica
import com.gonzalo.atlas.motor.Mapa
import com.gonzalo.atlas.motor.Medidas
import com.gonzalo.atlas.motor.Nombres
import com.gonzalo.atlas.motor.Territorio

// ==================================================================== fuera: la lista de mundos

/** Tus mundos: cada uno con sus mapas, su política, sus guerras y su crónica. Se entra en uno para trabajar en él. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantMundos(e: EstadoApp) {
    @Suppress("UNUSED_VARIABLE") val l = e.listas
    val mundos = remember(e.listas) { e.bib.mundos() }
    val mapas = remember(e.listas) { e.bib.mapas() }
    var nuevo by remember { mutableStateOf(false) }
    var renombrar by remember { mutableStateOf<Pair<String, String>?>(null) }
    var borrar by remember { mutableStateOf<Pair<String, String>?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Relleno).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Tus mundos", style = T.titular(36.sp))
        Text("Cada mundo tiene dentro sus mapas (el mundo entero, sus continentes, sus reinos, sus ciudades…), su política, sus guerras y su crónica. Entra en uno para trabajar en él.", style = T.texto(15.sp, T.GRIS))
        Boton("+ Mundo nuevo", lleno = true) { nuevo = true }
        if (mundos.isEmpty()) Text("Aún no tienes ninguno.", style = T.nombre(18.sp, T.GRIS))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for (w in mundos) {
                val suyos = mapas.filter { it.mundo == w.id }
                val cr = remember(w.id, e.listas) { e.cronica(w.id) }
                Column(Modifier.width(300.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.5f).border(2.dp, T.TINTA).background(T.MESA).pulsable { e.entraMundo(w.id) }) {
                        val primero = suyos.firstOrNull()
                        if (primero != null) Miniatura(e.bib.miniatura(primero.id), Modifier.fillMaxSize(), e.listas)
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Sin mapas todavía", style = T.nombre(16.sp, T.GRIS)) }
                        Box(Modifier.align(Alignment.BottomStart).background(T.BLOQUE2).padding(horizontal = 10.dp, vertical = 5.dp)) { Etiqueta("Entrar", tam = 11.sp, color = T.PAPEL) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(w.nombre, Modifier.pulsable { e.entraMundo(w.id) }, style = T.titular(26.sp), maxLines = 1)
                    Text("${suyos.size} ${if (suyos.size == 1) "mapa" else "mapas"} · ${cr.de("reino").size} potencias · ${cr.de("guerra").size} guerras", style = T.texto(13.sp, T.GRIS))
                    Row { Enlace("Entrar") { e.entraMundo(w.id) }; Enlace("Renombrar") { renombrar = w.id to w.nombre }; Enlace("Borrar", color = T.ROJO) { borrar = w.id to w.nombre } }
                }
            }
        }
        if (e.mensaje.isNotBlank()) Text(e.mensaje, style = T.texto(14.sp, T.ACENTO))
        Spacer(Modifier.height(30.dp))
    }
    if (nuevo) DialogoTexto("¿Cómo se llama el mundo?", Nombres.mundo(), { nuevo = false }) { t -> val w = e.bib.creaMundo(t); nuevo = false; e.entraMundo(w.id) }
    renombrar?.let { (id, n) -> DialogoTexto("Nuevo nombre", n, { renombrar = null }) { t -> e.bib.renombraMundo(id, t); e.listas++; renombrar = null } }
    borrar?.let { (id, n) ->
        Dialog({ borrar = null }) {
            Column(Modifier.background(T.PAPEL).border(2.dp, T.TINTA).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("¿Borrar «$n»?", style = T.titular(24.sp))
                Text("Se borran el mundo, todos sus mapas, su política, sus guerras y su crónica de este dispositivo.", style = T.texto(14.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Boton("Borrar", lleno = true, color = T.ROJO) { e.bib.mapas().filter { it.mundo == id }.forEach { e.cierraMapaSiEs(it.id) }; e.bib.borraMundo(id); e.listas++; borrar = null }
                    Boton("No") { borrar = null }
                }
            }
        }
    }
}

// ==================================================================== dentro: la cabecera y las pestañas del mundo

/** La cabecera de dentro de un mundo: la misma familia, pero en azul de tinta y con el nombre del mundo en grande. */
@Composable
fun CabeceraMundo(e: EstadoApp) {
    val w = e.mundoActual ?: return
    val nombre = remember(w, e.listas) { e.bib.mundo(w)?.nombre ?: "Tu mundo" }
    Column(Modifier.fillMaxWidth().background(T.BLOQUE2).statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Etiqueta("‹ Tus mundos", Modifier.pulsable { e.saleMundo() }.padding(end = 16.dp, top = 2.dp, bottom = 2.dp), 11.sp, T.PAPEL)
            Spacer(Modifier.weight(1f))
            Text(buildAnnotatedString { append("ATLAS"); withStyle(SpanStyle(color = T.ACENTO_NEGRO)) { append(".") } }, style = T.titular(15.sp, T.SOBRE_CABECERA))
        }
        Spacer(Modifier.height(2.dp))
        Text(buildAnnotatedString { append(nombre); withStyle(SpanStyle(color = T.ACENTO_NEGRO)) { append(".") } }, style = T.titular(44.sp, T.PAPEL), maxLines = 1)
    }
}

private fun cuentaDe(e: EstadoApp, w: String, k: String): Int = when (k) {
    "mapa" -> e.bib.mapas().count { it.mundo == w }
    "politica" -> e.cronica(w).de("reino").size
    "guerras" -> e.cronica(w).de("guerra").size
    else -> e.cronica(w).entradas.values.count { it.cat !in Cronica.APARTE }
}

/** Ir a un apartado del mundo. «Mapas» otra vez (estando ya en él) lleva de la mesa de dibujo a la lista de mapas. */
private fun irA(e: EstadoApp, k: String) { if (k == "mapa" && e.seccion == "mapa") e.dibujando = false; e.seccion = k }

@Composable
fun PestanasMundo(e: EstadoApp) {
    val w = e.mundoActual ?: return
    Column(Modifier.fillMaxWidth().background(T.PAPEL)) {
        Row(Modifier.fillMaxWidth().height(46.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((k, n) in SECCIONES_MUNDO) {
                val sel = e.seccion == k
                Box(Modifier.fillMaxHeight().background(if (sel) T.TINTA else T.PAPEL).pulsable { irA(e, k) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Etiqueta(n, tam = 13.sp, color = if (sel) T.PAPEL else T.TINTA)
                        val cuenta = remember(e.listas, w, k) { cuentaDe(e, w, k) }
                        if (cuenta > 0) { Spacer(Modifier.width(6.dp)); Text("$cuenta", style = T.titular(14.sp, if (sel) T.ACENTO_NEGRO else T.ACENTO)) }
                    }
                }
            }
        }
        Filete(2)
    }
}

/**
 * La barra del mundo encima de la mesa de dibujo: para saltar a Política, Guerras o Crónica sin perder el mapa (al volver a
 * «Mapas» sigues dibujando donde estabas) y para abrir la consulta al lado del lienzo.
 */
@Composable
internal fun FilaMundo(e: EstadoApp, mapa: Mapa, consultando: Boolean, alConsultar: () -> Unit) {
    val w = e.mundoActual ?: return
    val nombre = remember(w, e.listas) { e.bib.mundo(w)?.nombre ?: "Tu mundo" }
    Row(Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF16283F)).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹ Mundos", Modifier.pulsableO { e.saleMundo() }.padding(end = 12.dp), style = O.versalitas(10.sp, O.GRIS))
        Text(nombre.uppercase() + "  ·  " + mapa.nombre, Modifier.padding(end = 18.dp), style = O.versalitas(10.sp, O.TINTA), maxLines = 1)
        for ((k, n) in SECCIONES_MUNDO) {
            val sel = k == "mapa"
            Box(Modifier.fillMaxHeight().background(if (sel) O.TINTA else Color.Transparent).pulsableO { irA(e, k) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(n.uppercase(), style = O.versalitas(10.sp, if (sel) Color(0xFF16283F) else O.TINTA))
            }
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (consultando) O.ACENTO else Color(0xFF2A4468)).pulsableO(alConsultar).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("CONSULTAR SIN SALIR", style = O.versalitas(10.sp, O.TINTA))
        }
    }
}

// ==================================================================== política

/** Los territorios de los mapas del mundo que aún no tienen ficha de política (ni por su enlace ni por su nombre). */
private fun sinFicha(mapas: List<Mapa>, cr: Cronica): List<Pair<Mapa, Territorio>> {
    val vistos = HashSet<String>(cr.de("reino").map { it.titulo.trim().lowercase() })
    val l = ArrayList<Pair<Mapa, Territorio>>()
    for (m in mapas) for (t in m.territorios) if (cr.deEnlace("${m.id}:t:${t.id}") == null && vistos.add(t.nombre.trim().lowercase())) l += m to t
    return l
}

@Composable
private fun FilaLista(t: String, sub: String, color: Color?, sel: Boolean, alPulsar: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (sel) T.CRUDO else Color.Transparent).border(if (sel) 2.dp else 0.dp, if (sel) T.BLOQUE2 else Color.Transparent).pulsable(alPulsar).padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (color != null) { Box(Modifier.size(16.dp).background(color).border(1.dp, T.TINTA)); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) { Text(t, style = T.nombre(19.sp)); if (sub.isNotBlank()) Text(sub, style = T.texto(12.sp, T.GRIS), maxLines = 2) }
    }
    Filete(1, T.GRIS_CLARO)
}

/** El color con el que está pintada una potencia en alguno de los mapas (para reconocerla en la lista). */
private fun colorDe(f: Cronica.Entrada, mapas: List<Mapa>): Color? {
    for (en in f.enlaces) { val p = en.split(':'); if (p.size == 3 && p[1] == "t") mapas.firstOrNull { it.id == p[0] }?.territorio(p[2].toIntOrNull() ?: 0)?.let { return Color(it.color) } }
    return null
}

/** Cómo se lleva una potencia con las demás, en una línea por tipo de relación («Aliados: X, Y»). */
private fun resumenRelaciones(cr: Cronica, f: Cronica.Entrada): List<String> {
    val otras = cr.de("reino").filter { it.id != f.id }
    return Cronica.RESUMEN.mapNotNull { (k, n) -> otras.filter { cr.relacion(f, it) == k }.takeIf { it.isNotEmpty() }?.let { l -> n + ": " + l.joinToString(", ") { it.titulo } } }
}

private fun resumenGuerra(cr: Cronica, g: Cronica.Entrada): String {
    val a = cr.bando(g, "a").joinToString(", ") { it.titulo }; val b = cr.bando(g, "b").joinToString(", ") { it.titulo }
    return listOf(g.campos["Estado"].orEmpty(), if (a.isNotBlank() || b.isNotBlank()) (a.ifBlank { "?" } + " contra " + b.ifBlank { "?" }) else "").filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * La política del mundo: la situación general y sus potencias (imperios, reinos, ciudades libres…), con quién manda, cómo
 * está cada una, cómo se llevan entre ellas y en qué guerras andan. Los territorios que pintas en los mapas aparecen aquí
 * solos; un reino que sale en varios mapas con el mismo nombre es el mismo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantPolitica(e: EstadoApp) {
    val w = e.mundoActual ?: run { Vacio("Entra en un mundo"); return }
    var version by remember { mutableIntStateOf(0) }
    val cr = remember(w, e.listas) { e.cronica(w) }
    val mapas = remember(w, e.listas, e.editor?.cambios) { e.mapasDe(w) }
    var elegida by remember { mutableStateOf<String?>(null) }
    e.potenciaPedida?.let { id -> if (elegida != id) elegida = id }
    LaunchedEffect(e.potenciaPedida) { e.potenciaPedida = null }
    @Suppress("UNUSED_VARIABLE") val v = version

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ancho = maxWidth > 760.dp
        val lista = @Composable { mod: Modifier ->
            Column(mod.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Situación política", style = T.titular(30.sp))
                var sit by remember(w) { mutableStateOf(cr.situacion) }
                Campo(sit, { sit = it; cr.situacion = it; cr.guarda() }, Modifier.fillMaxWidth(), "Cuenta aquí cómo está el mundo: quién manda, quién amenaza a quién, qué se está rompiendo…", T.nombre(17.sp), lineas = 4)
                Seccion(null, "Potencias", derecha = { Enlace("+ Nueva") { val f = cr.nueva("reino", "Sin nombre"); elegida = f.id; version++; e.listas++ } })
                val fichas = cr.de("reino")
                if (fichas.isEmpty() && mapas.all { it.territorios.isEmpty() }) Text("Aún no hay ninguna. Crea una aquí, o pinta territorios en un mapa (Mundo → Territorios): aparecen solos.", style = T.texto(14.sp, T.GRIS))
                for (f in fichas) FilaLista(f.titulo, (listOf(f.campos["Tipo"], f.campos["Gobernante"]).filter { !it.isNullOrBlank() } + resumenRelaciones(cr, f).take(1)).joinToString(" · "), colorDe(f, mapas), f.id == elegida) { elegida = f.id }
                for ((m, t) in sinFicha(mapas, cr)) FilaLista(t.nombre, t.tipo + " · pintado en «${m.nombre}», sin ficha todavía", Color(t.color), false) { e.fichaDe(m, "t", t.id.toString(), "reino", t.nombre); version++ }
                Spacer(Modifier.height(30.dp))
            }
        }
        val ficha = @Composable { mod: Modifier ->
            val f = elegida?.let { cr.entradas[it] }?.takeIf { it.cat == "reino" }
            Column(mod.verticalScroll(rememberScrollState()).padding(18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (f == null) { Text("Elige una potencia o crea una nueva.", style = T.nombre(18.sp, T.GRIS)); return@Column }
                if (!ancho) Enlace("← Volver a la lista") { elegida = null }
                var titulo by remember(f.id) { mutableStateOf(f.titulo) }
                Campo(titulo, { titulo = it; f.titulo = it; f.u = System.currentTimeMillis(); cr.guarda(); version++ }, Modifier.fillMaxWidth(), "Nombre", T.titular(26.sp))
                EnLosMapas(e, f, mapas)
                for (campo in Cronica.categoria("reino").campos + f.campos.keys.filter { k -> !k.startsWith("_") && Cronica.categoria("reino").campos.none { it == k } }) {
                    var valor by remember(f.id, campo) { mutableStateOf(f.campos[campo].orEmpty()) }
                    Etiqueta(campo, tam = 10.sp, color = T.GRIS)
                    Campo(valor, { valor = it; f.campos[campo] = it; f.u = System.currentTimeMillis(); cr.guarda() }, Modifier.fillMaxWidth(), lineas = if (campo == "Situación actual") 3 else 1)
                }
                // Cómo se lleva con las demás
                val otras = cr.de("reino").filter { it.id != f.id }
                if (otras.isNotEmpty()) {
                    Seccion(null, "Relaciones")
                    var abierta by remember(f.id) { mutableStateOf<String?>(null) }
                    for (o in otras) {
                        val r = cr.relacion(f, o)
                        Row(Modifier.fillMaxWidth().pulsable { abierta = if (abierta == o.id) null else o.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(o.titulo, Modifier.weight(1f), style = T.nombre(17.sp))
                            Ficha(if (r.isBlank()) "Sin decir" else Cronica.nombreRelacion(r), r.isNotBlank(), color = if (r == "guerra" || r == "enemigo") T.ROJO else T.BLOQUE2) { abierta = if (abierta == o.id) null else o.id }
                        }
                        if (abierta == o.id) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for ((k, n) in Cronica.RELACIONES) Ficha(n, r == k) { cr.ponRelacion(f, o, k); abierta = null; version++ }
                        }
                    }
                }
                val guerras = cr.guerrasDe(f)
                if (guerras.isNotEmpty()) {
                    Seccion(null, "Sus guerras")
                    for (g in guerras) FilaLista(g.titulo, resumenGuerra(cr, g), null, false) { e.fichaPedida = g.id; e.seccion = "guerras" }
                }
                Etiqueta("Historia y notas", tam = 10.sp, color = T.GRIS)
                var texto by remember(f.id) { mutableStateOf(f.texto) }
                Campo(texto, { texto = it; f.texto = it; f.u = System.currentTimeMillis(); cr.guarda() }, Modifier.fillMaxWidth(), "Escribe aquí…", T.nombre(17.sp), lineas = 8)
                Boton("Borrar ficha", color = T.ROJO) { cr.borra(f.id); elegida = null; version++; e.listas++ }
            }
        }
        if (ancho) Row(Modifier.fillMaxSize()) {
            lista(Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(T.TINTA))
            ficha(Modifier.weight(1.3f).fillMaxHeight())
        } else if (elegida != null) ficha(Modifier.fillMaxSize()) else lista(Modifier.fillMaxSize())
    }
}

/** Dónde sale una potencia en los mapas del mundo (con lo que mide, si el mapa está abierto) y cómo ir a verla. */
@Composable
private fun EnLosMapas(e: EstadoApp, f: Cronica.Entrada, mapas: List<Mapa>) {
    val sitios = f.enlaces.mapNotNull { en -> val p = en.split(':'); if (p.size != 3 || p[1] != "t") null else mapas.firstOrNull { it.id == p[0] }?.let { m -> m.territorio(p[2].toIntOrNull() ?: 0)?.let { Triple(en, m, it) } } }
    if (sitios.isEmpty()) { Text("Aún no está pintada en ningún mapa. Pinta un territorio con este mismo nombre en un mapa y quedará ligado.", style = T.texto(13.sp, T.GRIS)); return }
    Column(Modifier.fillMaxWidth().background(T.CRUDO).border(1.dp, T.TINTA).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Etiqueta("En los mapas", tam = 10.sp, color = T.BLOQUE2)
        for ((en, m, t) in sitios) {
            val ed = e.editor?.takeIf { it.mapa.id == m.id }
            val lineas = ArrayList<String>()
            lineas += t.tipo + (ed?.let { " · " + Medidas.formatoKm2(Medidas.km2(m, it.superficies.getOrElse(t.id) { 0L })) }.orEmpty())
            m.linaje(t.id).drop(1).takeIf { it.isNotEmpty() }?.let { lineas += "Parte de: " + it.joinToString(" · ") { a -> a.nombre } }
            m.hijos(t.id).takeIf { it.isNotEmpty() }?.let { lineas += "Dentro: " + it.joinToString(", ") { h -> h.nombre } }
            if (t.capital.isNotBlank()) lineas += "Capital: " + t.capital
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(Color(t.color)).border(1.dp, T.TINTA)); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { Text("«${m.nombre}»", style = T.nombre(16.sp)); Text(lineas.joinToString(" · "), style = T.texto(12.sp, T.GRIS)) }
                Enlace("Ver en el mapa") { verEnMapa(e, en) }
            }
        }
    }
}

// ==================================================================== guerras

/** Las guerras del mundo: quién contra quién, cómo van, por qué empezaron y cómo acaban. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantGuerras(e: EstadoApp) {
    val w = e.mundoActual ?: run { Vacio("Entra en un mundo"); return }
    var version by remember { mutableIntStateOf(0) }
    val cr = remember(w, e.listas) { e.cronica(w) }
    var elegida by remember { mutableStateOf<String?>(null) }
    e.fichaPedida?.let { id -> if (cr.entradas[id]?.cat == "guerra" && elegida != id) elegida = id }
    LaunchedEffect(e.fichaPedida) { e.fichaPedida = null }
    @Suppress("UNUSED_VARIABLE") val v = version

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ancho = maxWidth > 760.dp
        val lista = @Composable { mod: Modifier ->
            Column(mod.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Guerras", style = T.titular(30.sp))
                Seccion(null, "Las de este mundo", derecha = { Enlace("+ Nueva") { val f = cr.nueva("guerra", "Guerra sin nombre"); f.campos["Estado"] = "En curso"; cr.guarda(); elegida = f.id; version++; e.listas++ } })
                val l = cr.de("guerra")
                if (l.isEmpty()) Text("Ninguna todavía. Una guerra tiene dos bandos (las potencias de Política), un estado y su historia.", style = T.texto(14.sp, T.GRIS))
                for (estado in Cronica.ESTADOS_GUERRA + "") {
                    val suyas = l.filter { (it.campos["Estado"].orEmpty()) == estado || (estado == "" && it.campos["Estado"].orEmpty() !in Cronica.ESTADOS_GUERRA) }
                    if (suyas.isEmpty()) continue
                    Etiqueta(estado.ifBlank { "Sin estado" }, tam = 10.sp, color = if (estado == "En curso") T.ROJO else T.BLOQUE2)
                    for (g in suyas) FilaLista(g.titulo, resumenGuerra(cr, g).substringAfter(" · ", ""), null, g.id == elegida) { elegida = g.id }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
        val ficha = @Composable { mod: Modifier ->
            val g = elegida?.let { cr.entradas[it] }?.takeIf { it.cat == "guerra" }
            Column(mod.verticalScroll(rememberScrollState()).padding(18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (g == null) { Text("Elige una guerra o crea una nueva.", style = T.nombre(18.sp, T.GRIS)); return@Column }
                if (!ancho) Enlace("← Volver a la lista") { elegida = null }
                var titulo by remember(g.id) { mutableStateOf(g.titulo) }
                Campo(titulo, { titulo = it; g.titulo = it; g.u = System.currentTimeMillis(); cr.guarda(); version++ }, Modifier.fillMaxWidth(), "Nombre", T.titular(26.sp))
                val potencias = cr.de("reino")
                fun alDia() {
                    // Las potencias de un bando y las del otro quedan «en guerra» (o «en tregua») también en Política
                    val r = when (g.campos["Estado"]) { "En curso" -> "guerra"; "En tregua" -> "tregua"; else -> return }
                    for (a in cr.bando(g, "a")) for (b in cr.bando(g, "b")) cr.ponRelacion(a, b, r)
                }
                Etiqueta("Estado", tam = 10.sp, color = T.GRIS)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (s in Cronica.ESTADOS_GUERRA) Ficha(s, g.campos["Estado"] == s, color = if (s == "En curso") T.ROJO else T.TINTA) { g.campos["Estado"] = s; cr.guarda(); alDia(); version++; e.listas++ }
                }
                if (potencias.isEmpty()) Text("Para poner los bandos, crea antes las potencias en Política.", style = T.texto(13.sp, T.GRIS))
                else for ((k, n) in listOf("a" to "Un bando", "b" to "El otro bando")) {
                    Etiqueta(n, tam = 10.sp, color = T.GRIS)
                    val suyos = cr.bando(g, k).map { it.id }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (p in potencias) Ficha(p.titulo, p.id in suyos, color = T.BLOQUE2) { cr.ponEnBando(g, k, p, p.id !in suyos); alDia(); version++ }
                    }
                }
                Text("Mientras está «En curso» o «En tregua», las potencias de un bando y las del otro quedan así también en sus relaciones de Política.", style = T.texto(12.sp, T.GRIS))
                for (campo in Cronica.categoria("guerra").campos.filter { it != "Estado" }) {
                    var valor by remember(g.id, campo) { mutableStateOf(g.campos[campo].orEmpty()) }
                    Etiqueta(campo, tam = 10.sp, color = T.GRIS)
                    Campo(valor, { valor = it; g.campos[campo] = it; g.u = System.currentTimeMillis(); cr.guarda() }, Modifier.fillMaxWidth())
                }
                Etiqueta("Cómo va y qué ha pasado", tam = 10.sp, color = T.GRIS)
                var texto by remember(g.id) { mutableStateOf(g.texto) }
                Campo(texto, { texto = it; g.texto = it; g.u = System.currentTimeMillis(); cr.guarda() }, Modifier.fillMaxWidth(), "Batallas, asedios, traiciones…", T.nombre(17.sp), lineas = 8)
                Boton("Borrar guerra", color = T.ROJO) { cr.borra(g.id); elegida = null; version++; e.listas++ }
            }
        }
        if (ancho) Row(Modifier.fillMaxSize()) {
            lista(Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(T.TINTA))
            ficha(Modifier.weight(1.3f).fillMaxHeight())
        } else if (elegida != null) ficha(Modifier.fillMaxSize()) else lista(Modifier.fillMaxSize())
    }
}

// ==================================================================== la consulta, al lado del lienzo

/**
 * Consultar la política del mundo sin salir del mapa que estás dibujando: la situación general, las guerras que hay y cada
 * potencia con lo que has escrito de ella (para que lo que dibujas cuadre con lo que has contado). Si tienes elegido un
 * territorio, se abre la suya.
 */
@Composable
internal fun PanelConsulta(e: EstadoApp, ed: Editor) {
    @Suppress("UNUSED_VARIABLE") val alDia = ed.cambios
    val w = ed.mapa.mundo
    val cr = remember(w, e.listas, ed.cambios) { e.cronica(w) }
    val m = ed.mapa
    val delElegido = (ed.seleccion as? Territorio)?.let { cr.deEnlace("${m.id}:t:${it.id}")?.id }
    var abierta by remember(delElegido) { mutableStateOf(delElegido) }
    if (cr.situacion.isNotBlank()) { EtiquetaO("Situación del mundo", tam = 10.sp, color = O.ACENTO); Text(cr.situacion, style = O.nombre(15.sp)) }
    val guerras = cr.de("guerra").filter { it.campos["Estado"] != "Terminada" }
    if (guerras.isNotEmpty()) {
        EtiquetaO("Guerras", tam = 10.sp, color = O.ROJO)
        for (g in guerras) Column { Text(g.titulo, style = O.texto(14.sp, O.TINTA)); Text(resumenGuerra(cr, g), style = O.texto(12.sp, O.GRIS)) }
    }
    EtiquetaO("Potencias", tam = 10.sp, color = O.ACENTO)
    val fichas = cr.de("reino")
    if (fichas.isEmpty()) Text("Aún no has descrito ninguna en Política.", style = O.texto(13.sp, O.GRIS))
    for (f in fichas) {
        val t = f.enlaces.firstNotNullOfOrNull { en -> val p = en.split(':'); if (p.size == 3 && p[0] == m.id && p[1] == "t") m.territorio(p[2].toIntOrNull() ?: 0) else null }
        val si = abierta == f.id
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (si) O.CRUDO else Color.Transparent)) {
            Row(Modifier.fillMaxWidth().pulsableO { abierta = if (si) null else f.id }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(t?.let { Color(it.color) } ?: O.GRIS_CLARO)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.titulo, style = O.texto(15.sp, O.TINTA))
                    val sub = listOf(f.campos["Tipo"], f.campos["Gobernante"]).filter { !it.isNullOrBlank() }.joinToString(" · ")
                    if (sub.isNotBlank() && !si) Text(sub, style = O.texto(11.sp, O.GRIS), maxLines = 1)
                }
                Text(if (si) "–" else "+", style = O.titular(18.sp, O.GRIS))
            }
            if (si) Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for ((k, valor) in f.campos) if (!k.startsWith("_") && valor.isNotBlank()) Text(buildAnnotatedString { withStyle(SpanStyle(color = O.GRIS)) { append("$k: ") }; append(valor) }, style = O.texto(13.sp, O.TINTA))
                for (r in resumenRelaciones(cr, f)) Text(r, style = O.texto(13.sp, O.ACENTO_NEGRO))
                for (g in cr.guerrasDe(f)) Text("Guerra: " + g.titulo + " (" + g.campos["Estado"].orEmpty() + ")", style = O.texto(13.sp, O.ROJO))
                if (f.texto.isNotBlank()) Text(f.texto.take(900) + if (f.texto.length > 900) "…" else "", style = O.nombre(14.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotonO("Abrir en Política") { e.potenciaPedida = f.id; e.seccion = "politica" }
                    if (t != null) BotonO("Verla en este mapa") { ed.herramienta = Herramienta.ELEGIR; ed.seleccion = t; ed.territorio = t.id }
                }
            }
        }
    }
    Text("Para escribir o cambiar algo: Política, en la barra de arriba. Al volver a Mapas sigues dibujando donde estabas.", style = O.texto(11.sp, O.GRIS))
}
