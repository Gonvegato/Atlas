package com.gonzalo.atlas.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gonzalo.atlas.R

/** La cara oscura de la mesa de dibujo (como Procreate): los mismos componentes, con colores de estudio. */
/**
 * Los tokens de la familia (dirección «C»): papel #F8F0D4, tinta negra, cabecera negra con el nombre en papel y el punto
 * del acento, pestañas sobre papel con la elegida en bloque. Bricolage Grotesque ExtraBold (tamaño óptico 48) para nombres,
 * titulares y cifras; Inter para etiquetas y texto; Fraunces para los nombres de las cosas.
 */
object O {
    val PAPEL = Color(0xFF242426)
    val CRUDO = Color(0xFF2F2F33)
    val TINTA = Color(0xFFF2F2F2)
    val CABECERA = Color(0xFF141416)
    val GRIS = Color(0xFFA3A3AB)
    val SOBRE_CABECERA = Color(0xFF9A9AA2)
    val GRIS_CLARO = Color(0xFF5E5E66)
    val MESA = Color(0xFF1A1A1C)
    val ACENTO = Color(0xFF3D8BFF)
    val ACENTO_NEGRO = Color(0xFF6AA6FF)
    val BLOQUE2 = Color(0xFF1F3A5F)
    val ROJO = Color(0xFFFF5A4F)

    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val grotesca = FontFamily(Font(R.font.bricolage, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(
        FontVariation.weight(800), FontVariation.width(100f), FontVariation.Setting("opsz", 48f))))
    val inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal), Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold), Font(R.font.inter_italic, FontWeight.Normal, FontStyle.Italic))
    val serif = FontFamily(
        Font(R.font.fraunces_regular, FontWeight.Normal), Font(R.font.fraunces_semibold, FontWeight.SemiBold),
        Font(R.font.fraunces_italic, FontWeight.Normal, FontStyle.Italic))

    /** Las etiquetas en mayúsculas espaciadas («+ NUEVO», «BUSCAR»). */
    fun versalitas(tam: TextUnit = 12.sp, color: Color = TINTA) = TextStyle(fontFamily = inter, fontWeight = FontWeight.Bold, fontSize = tam, letterSpacing = 0.12.em, color = color)
    fun texto(tam: TextUnit = 15.sp, color: Color = TINTA) = TextStyle(fontFamily = inter, fontSize = tam, color = color, lineHeight = tam * 1.4f)
    fun nombre(tam: TextUnit = 20.sp, color: Color = TINTA) = TextStyle(fontFamily = serif, fontSize = tam, color = color, lineHeight = tam * 1.2f)
    fun titular(tam: TextUnit = 28.sp, color: Color = TINTA) = TextStyle(fontFamily = grotesca, fontWeight = FontWeight.ExtraBold, fontSize = tam, color = color, letterSpacing = (-0.01).em, lineHeight = tam * 1.05f)
}

@Composable
fun EtiquetaO(t: String, mod: Modifier = Modifier, tam: TextUnit = 12.sp, color: Color = O.TINTA) =
    Text(t.uppercase(), mod, style = O.versalitas(tam, color), maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Un botón de texto plano de la familia: [lleno] en bloque de tinta (o del color dado), si no con borde. */
@Composable
fun BotonO(t: String, mod: Modifier = Modifier, lleno: Boolean = false, color: Color = O.TINTA, activo: Boolean = true, alPulsar: () -> Unit) {
    val fondo = if (lleno) color else Color.Transparent
    val letra = if (lleno) O.PAPEL else color
    Box(
        mod.then(if (!lleno) Modifier.border(BorderStroke(2.dp, if (activo) color else O.GRIS_CLARO)) else Modifier)
            .background(if (activo) fondo else if (lleno) O.GRIS_CLARO else Color.Transparent)
            .clickable(enabled = activo, onClick = alPulsar)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(t.uppercase(), style = O.versalitas(12.sp, if (activo) letra else if (lleno) O.PAPEL else O.GRIS_CLARO), maxLines = 1) }
}

/** Un enlace en versalitas (sin caja), como «+ NUEVA» de las hermanas. */
@Composable
fun EnlaceO(t: String, mod: Modifier = Modifier, color: Color = O.ACENTO, alPulsar: () -> Unit) =
    Text(t.uppercase(), mod.clickable(onClick = alPulsar).padding(vertical = 8.dp, horizontal = 4.dp), style = O.versalitas(12.sp, color))

/** Un título de sección con su número en el acento y la raya debajo («01 MUNDOS»). */
@Composable
fun SeccionO(numero: Int?, t: String, mod: Modifier = Modifier, derecha: @Composable () -> Unit = {}) {
    Column(mod.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (numero != null) { Text("%02d".format(numero), style = O.titular(22.sp, O.ACENTO)); Spacer(Modifier.width(10.dp)) }
            Text(t.uppercase(), Modifier.weight(1f), style = O.titular(22.sp))
            derecha()
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(O.TINTA))
    }
}

@Composable
fun FileteO(grosor: Int = 1, color: Color = O.TINTA, mod: Modifier = Modifier) = Box(mod.fillMaxWidth().height(grosor.dp).background(color))

/** Una ficha elegible (filtros, tipos). */
@Composable
fun FichaO(t: String, elegida: Boolean, mod: Modifier = Modifier, color: Color = O.TINTA, alPulsar: () -> Unit) {
    Box(
        mod.border(1.5.dp, if (elegida) color else O.GRIS_CLARO).background(if (elegida) color else Color.Transparent)
            .clickable(onClick = alPulsar).padding(horizontal = 10.dp, vertical = 7.dp),
    ) { Text(t, style = O.texto(13.sp, if (elegida) O.PAPEL else O.TINTA), maxLines = 1) }
}

@Composable
fun DeslizadorO(t: String, valor: Float, rango: ClosedFloatingPointRange<Float>, texto: String, mod: Modifier = Modifier, alCambiar: (Float) -> Unit) {
    Column(mod.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EtiquetaO(t, Modifier.weight(1f), 11.sp, O.GRIS)
            Text(texto, style = O.titular(15.sp))
        }
        Slider(valor, alCambiar, valueRange = rango, modifier = Modifier.height(30.dp),
            colors = SliderDefaults.colors(thumbColor = O.TINTA, activeTrackColor = O.TINTA, inactiveTrackColor = O.GRIS_CLARO.copy(alpha = 0.5f)))
    }
}

@Composable
fun CampoO(valor: String, alCambiar: (String) -> Unit, mod: Modifier = Modifier, pista: String = "", estilo: TextStyle = O.texto(16.sp), lineas: Int = 1, teclado: KeyboardOptions = KeyboardOptions.Default, oculto: Boolean = false) {
    Box(mod.border(1.5.dp, O.TINTA).background(O.CRUDO).padding(horizontal = 10.dp, vertical = 9.dp)) {
        if (valor.isEmpty()) Text(pista, style = estilo.copy(color = O.GRIS_CLARO))
        BasicTextField(valor, alCambiar, Modifier.fillMaxWidth(), textStyle = estilo, singleLine = lineas == 1, minLines = lineas, cursorBrush = SolidColor(O.ACENTO), keyboardOptions = teclado,
            visualTransformation = if (oculto) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
    }
}

/** Para pulsar sin la onda de Material (las hermanas no la tienen). */
@Composable
fun Modifier.pulsableO(alPulsar: () -> Unit): Modifier = this.clickable(remember { MutableInteractionSource() }, null, onClick = alPulsar)



/** Una barra vertical (tamaño y opacidad del pincel, como en Procreate). [valor] 0..1, arriba = 1. */
@Composable
fun BarraVerticalO(valor: Float, etiqueta: String, mod: Modifier = Modifier, alCambiar: (Float) -> Unit) {
    var alto by remember { mutableFloatStateOf(1f) }
    Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(30.dp).height(180.dp)
                .pointerInput(Unit) {
                    alto = size.height.toFloat()
                    detectDragGestures(onDragStart = { o -> alCambiar((1f - o.y / alto).coerceIn(0f, 1f)) }) { ch, _ -> alCambiar((1f - ch.position.y / alto).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) { detectTapGestures { o -> alCambiar((1f - o.y / size.height).coerceIn(0f, 1f)) } },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Un raíl fino y un tirador blanco (como los de Procreate)
                val w = 6.dp.toPx(); val x = (size.width - w) / 2
                drawRoundRect(Color(0xFF4A4A50), Offset(x, 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(w / 2))
                val y = (1f - valor.coerceIn(0f, 1f)) * (size.height - 14.dp.toPx())
                drawRoundRect(Color(0xFF8E8E96), Offset(x, y), Size(w, size.height - y), androidx.compose.ui.geometry.CornerRadius(w / 2))
                drawRoundRect(Color.White, Offset(2.dp.toPx(), y), Size(size.width - 4.dp.toPx(), 14.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(etiqueta, style = O.versalitas(9.sp, O.GRIS))
    }
}

/** El selector de color: tono (barra), saturación y brillo (cuadro), y opacidad aparte. */
@Composable
fun SelectorColorO(color: Int, alCambiar: (Int) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var h by remember(color) { mutableFloatStateOf(hsv[0]) }
    var s by remember(color) { mutableFloatStateOf(hsv[1]) }
    var v by remember(color) { mutableFloatStateOf(hsv[2]) }
    fun manda() = alCambiar(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.4f).border(1.5.dp, O.TINTA)
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
            Modifier.fillMaxWidth().height(28.dp).border(1.5.dp, O.TINTA)
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
fun MuestraO(c: Int, elegida: Boolean, tam: Int = 30, alPulsar: () -> Unit) {
    Box(Modifier.size(tam.dp).clip(CircleShape).background(Color(c)).border(if (elegida) 3.dp else 1.dp, if (elegida) O.ACENTO else O.TINTA, CircleShape).pulsableO(alPulsar))
}

/** Paletas pensadas para mapas. */
private val PALETAS_O: List<Pair<String, List<Int>>> = listOf(
    "Tintas" to listOf(0xFF1B1712, 0xFF2A2118, 0xFF4A3B2A, 0xFF6B5A44, 0xFF7A2E22, 0xFF1F3A5F, 0xFF2E4A3A, 0xFF5A4636),
    "Tierra" to listOf(0xFFD9C38A, 0xFFC9B47A, 0xFFAFBB72, 0xFF8FA35C, 0xFF6E8B3D, 0xFF4F6B33, 0xFF9A8F78, 0xFF7C7262),
    "Agua" to listOf(0xFFCFE3E0, 0xFFB4D3CD, 0xFF8DB4BE, 0xFF6F9BAA, 0xFF4E7A8E, 0xFF34586C, 0xFF223F52, 0xFFE8F1F2),
    "Relieve" to listOf(0xFFF4F3EE, 0xFFD8D2C4, 0xFFB5AC99, 0xFF948A76, 0xFF7A6F5B, 0xFF5E5444, 0xFFC8A46A, 0xFFA87C4A),
    "Reinos" to listOf(0xFFB5523B, 0xFF3E6A8A, 0xFF6E8B3D, 0xFF8A5A9E, 0xFFC28A2E, 0xFF2F7F74, 0xFF9C3D5C, 0xFF5A5F9E),
).map { (n, l) -> n to l.map { it.toInt() } }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaletasO(actual: Int, alElegir: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((n, l) in PALETAS_O) {
            EtiquetaO(n, tam = 10.sp, color = O.GRIS)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (c in l) MuestraO(c, c == actual, 26) { alElegir(c) } }
        }
    }
}

/** Un interruptor con texto, de la familia (caja que se rellena). */
@Composable
fun InterruptorO(t: String, valor: Boolean, mod: Modifier = Modifier, alCambiar: (Boolean) -> Unit) {
    Row(mod.fillMaxWidth().pulsableO { alCambiar(!valor) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).border(2.dp, O.TINTA).background(if (valor) O.TINTA else Color.Transparent), contentAlignment = Alignment.Center) {
            if (valor) Text("✓", style = O.texto(13.sp, O.PAPEL))
        }
        Spacer(Modifier.width(10.dp))
        Text(t, style = O.texto(14.sp))
    }
}

