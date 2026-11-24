package com.gonzalo.atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Una barra vertical (tamaño y opacidad del pincel, como en Procreate). [valor] 0..1, arriba = 1. */
@Composable
fun BarraVertical(valor: Float, etiqueta: String, mod: Modifier = Modifier, alCambiar: (Float) -> Unit) {
    var alto by remember { mutableFloatStateOf(1f) }
    Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(34.dp).height(170.dp).background(T.CRUDO).border(1.5.dp, T.TINTA)
                .pointerInput(Unit) {
                    alto = size.height.toFloat()
                    detectDragGestures(onDragStart = { o -> alCambiar((1f - o.y / alto).coerceIn(0f, 1f)) }) { ch, _ -> alCambiar((1f - ch.position.y / alto).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) { detectTapGestures { o -> alCambiar((1f - o.y / size.height).coerceIn(0f, 1f)) } },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val h = size.height * valor.coerceIn(0f, 1f)
                drawRect(T.TINTA.copy(alpha = 0.12f), Offset(0f, size.height - h), Size(size.width, h))
                drawRect(T.TINTA, Offset(0f, size.height - h - 3.dp.toPx() / 2), Size(size.width, 3.dp.toPx()))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(etiqueta, style = T.versalitas(9.sp, T.GRIS))
    }
}

/** El selector de color: tono (barra), saturación y brillo (cuadro), y opacidad aparte. */
@Composable
fun SelectorColor(color: Int, alCambiar: (Int) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var h by remember(color) { mutableFloatStateOf(hsv[0]) }
    var s by remember(color) { mutableFloatStateOf(hsv[1]) }
    var v by remember(color) { mutableFloatStateOf(hsv[2]) }
    fun manda() = alCambiar(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.4f).border(1.5.dp, T.TINTA)
                .pointerInput(Unit) {
                    fun pon(o: Offset) { s = (o.x / size.width).coerceIn(0f, 1f); v = (1f - o.y / size.height).coerceIn(0f, 1f); manda() }
                    detectDragGestures(onDragStart = { pon(it) }) { ch, _ -> pon(ch.position) }
                }
                .pointerInput(Unit) { detectTapGestures { o -> s = (o.x / size.width).coerceIn(0f, 1f); v = (1f - o.y / size.height).coerceIn(0f, 1f); manda() } },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val puro = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)))
                drawRect(Brush.horizontalGradient(listOf(Color.White, puro)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val p = Offset(s * size.width, (1f - v) * size.height)
                drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                drawCircle(Color.Black, 11.dp.toPx(), p, style = Stroke(1.dp.toPx()))
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().height(28.dp).border(1.5.dp, T.TINTA)
                .pointerInput(Unit) {
                    fun pon(o: Offset) { h = (o.x / size.width * 360f).coerceIn(0f, 359.9f); manda() }
                    detectDragGestures(onDragStart = { pon(it) }) { ch, _ -> pon(ch.position) }
                }
                .pointerInput(Unit) { detectTapGestures { o -> h = (o.x / size.width * 360f).coerceIn(0f, 359.9f); manda() } },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f))) }))
                val x = h / 360f * size.width
                drawRect(Color.Black, Offset(x - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height))
            }
        }
    }
}

/** Una muestra de color redonda. */
@Composable
fun Muestra(c: Int, elegida: Boolean, tam: Int = 30, alPulsar: () -> Unit) {
    Box(Modifier.size(tam.dp).clip(CircleShape).background(Color(c)).border(if (elegida) 3.dp else 1.dp, if (elegida) T.ACENTO else T.TINTA, CircleShape).pulsable(alPulsar))
}

/** Paletas pensadas para mapas. */
val PALETAS: List<Pair<String, List<Int>>> = listOf(
    "Tintas" to listOf(0xFF1B1712, 0xFF2A2118, 0xFF4A3B2A, 0xFF6B5A44, 0xFF7A2E22, 0xFF1F3A5F, 0xFF2E4A3A, 0xFF5A4636),
    "Tierra" to listOf(0xFFD9C38A, 0xFFC9B47A, 0xFFAFBB72, 0xFF8FA35C, 0xFF6E8B3D, 0xFF4F6B33, 0xFF9A8F78, 0xFF7C7262),
    "Agua" to listOf(0xFFCFE3E0, 0xFFB4D3CD, 0xFF8DB4BE, 0xFF6F9BAA, 0xFF4E7A8E, 0xFF34586C, 0xFF223F52, 0xFFE8F1F2),
    "Relieve" to listOf(0xFFF4F3EE, 0xFFD8D2C4, 0xFFB5AC99, 0xFF948A76, 0xFF7A6F5B, 0xFF5E5444, 0xFFC8A46A, 0xFFA87C4A),
    "Reinos" to listOf(0xFFB5523B, 0xFF3E6A8A, 0xFF6E8B3D, 0xFF8A5A9E, 0xFFC28A2E, 0xFF2F7F74, 0xFF9C3D5C, 0xFF5A5F9E),
).map { (n, l) -> n to l.map { it.toInt() } }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Paletas(actual: Int, alElegir: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((n, l) in PALETAS) {
            Etiqueta(n, tam = 10.sp, color = T.GRIS)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (c in l) Muestra(c, c == actual, 26) { alElegir(c) } }
        }
    }
}

/** Un interruptor con texto, de la familia (caja que se rellena). */
@Composable
fun Interruptor(t: String, valor: Boolean, mod: Modifier = Modifier, alCambiar: (Boolean) -> Unit) {
    Row(mod.fillMaxWidth().pulsable { alCambiar(!valor) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).border(2.dp, T.TINTA).background(if (valor) T.TINTA else Color.Transparent), contentAlignment = Alignment.Center) {
            if (valor) Text("✓", style = T.texto(13.sp, T.PAPEL))
        }
        Spacer(Modifier.width(10.dp))
        Text(t, style = T.texto(14.sp))
    }
}

@Suppress("unused") private val sinUsar = mutableStateOf(0)
