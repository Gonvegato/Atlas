package com.gonzalo.atlas.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** El Taller sin nada abierto: tus dibujos sueltos (montañas, bosques, ciudades…) y empezar uno. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TallerSinDibujo(e: EstadoApp) {
    @Suppress("UNUSED_VARIABLE") val v = e.recursos.version
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Relleno).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Taller", style = T.titular(36.sp))
        Text("Dibuja aquí lo que vas a repetir en tus mapas: una montaña, un árbol, un castillo, una rosa de los vientos. Lo guardas en tu biblioteca y en el mapa lo pones con «Dibujos» tantas veces como quieras (y lo mueves, giras y escalas).", style = T.texto(15.sp, T.GRIS))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Boton("+ Dibujo pequeño (512)", lleno = true) { e.nuevoDibujo(512) }
            Boton("+ Grande (1024)") { e.nuevoDibujo(1024) }
        }
        Seccion(1, "Tu biblioteca")
        val lista = e.recursos.lista()
        if (lista.isEmpty()) Text("Vacía.", style = T.nombre(18.sp, T.GRIS))
        for ((cat, l) in lista.groupBy { it.categoria }) {
            Etiqueta(cat, tam = 11.sp, color = T.ACENTO)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in l) Column(Modifier.width(120.dp)) {
                    Box(Modifier.size(120.dp).border(1.dp, T.TINTA).background(T.CRUDO).pulsable { e.abreDibujo(r.id) }.padding(6.dp), contentAlignment = Alignment.Center) {
                        e.recursos.bitmap(r.id)?.let { Image(it.asImageBitmap(), r.nombre, Modifier.fillMaxSize()) }
                    }
                    Text(r.nombre, style = T.nombre(15.sp), maxLines = 1)
                    Row { Enlace("Editar") { e.abreDibujo(r.id) }; Enlace("Borrar", color = T.ROJO) { e.recursos.borra(r.id) } }
                }
            }
        }
        if (e.mensaje.isNotBlank()) Text(e.mensaje, style = T.texto(14.sp, T.ACENTO))
    }
}

/** La franja de arriba del Taller con un dibujo abierto: volver, nombre, categoría y guardar en la biblioteca. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CabezaTaller(e: EstadoApp) {
    val id = e.recursoTaller
    val actual = remember(id, e.recursos.version) { id?.let { i -> e.recursos.lista().firstOrNull { it.id == i } } }
    var nombre by remember(id) { mutableStateOf(actual?.nombre ?: "Mi dibujo") }
    var cat by remember(id) { mutableStateOf(actual?.categoria ?: Recursos.CATEGORIAS.first()) }
    var cats by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(T.CRUDO).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Enlace("← Biblioteca") { e.editorTaller?.guardaYa(); e.cierraDibujo() }
            Campo(nombre, { nombre = it }, Modifier.weight(1f), "Nombre")
            Ficha(cat, cats) { cats = !cats }
            Boton("Guardar en la biblioteca", lleno = true) { e.guardaDibujo(nombre.ifBlank { "Dibujo" }, cat) }
        }
        if (cats) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            for (c in Recursos.CATEGORIAS) Ficha(c, c == cat) { cat = c; cats = false }
        }
        if (e.mensaje.isNotBlank()) Text(e.mensaje, style = T.texto(12.sp, T.ACENTO))
    }
}
