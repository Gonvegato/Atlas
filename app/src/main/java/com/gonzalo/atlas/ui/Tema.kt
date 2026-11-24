package com.gonzalo.atlas.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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

/**
 * Los tokens de la familia (dirección «C»): papel #F8F0D4, tinta negra, cabecera negra con el nombre en papel y el punto
 * del acento, pestañas sobre papel con la elegida en bloque. Bricolage Grotesque ExtraBold (tamaño óptico 48) para nombres,
 * titulares y cifras; Inter para etiquetas y texto; Fraunces para los nombres de las cosas.
 */
object T {
    val PAPEL = Color(0xFFF8F0D4)
    val CRUDO = Color(0xFFFBF6E5)
    val TINTA = Color(0xFF000000)
    val CABECERA = Color(0xFF0C0A09)
    val GRIS = Color(0xFF57544A)
    val SOBRE_CABECERA = Color(0xFFB9B2A2)
    val GRIS_CLARO = Color(0xFF888475)
    val MESA = Color(0xFFE4DABB)
    val ACENTO = Color(0xFF5C7894)
    val ACENTO_NEGRO = Color(0xFF7D97B0)
    val BLOQUE2 = Color(0xFF1F3A5F)
    val ROJO = Color(0xFFA23B2A)

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
fun Etiqueta(t: String, mod: Modifier = Modifier, tam: TextUnit = 12.sp, color: Color = T.TINTA) =
    Text(t.uppercase(), mod, style = T.versalitas(tam, color), maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Un botón de texto plano de la familia: [lleno] en bloque de tinta (o del color dado), si no con borde. */
@Composable
fun Boton(t: String, mod: Modifier = Modifier, lleno: Boolean = false, color: Color = T.TINTA, activo: Boolean = true, alPulsar: () -> Unit) {
    val fondo = if (lleno) color else Color.Transparent
    val letra = if (lleno) T.PAPEL else color
    Box(
        mod.then(if (!lleno) Modifier.border(BorderStroke(2.dp, if (activo) color else T.GRIS_CLARO)) else Modifier)
            .background(if (activo) fondo else if (lleno) T.GRIS_CLARO else Color.Transparent)
            .clickable(enabled = activo, onClick = alPulsar)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(t.uppercase(), style = T.versalitas(12.sp, if (activo) letra else if (lleno) T.PAPEL else T.GRIS_CLARO), maxLines = 1) }
}

/** Un enlace en versalitas (sin caja), como «+ NUEVA» de las hermanas. */
@Composable
fun Enlace(t: String, mod: Modifier = Modifier, color: Color = T.ACENTO, alPulsar: () -> Unit) =
    Text(t.uppercase(), mod.clickable(onClick = alPulsar).padding(vertical = 8.dp, horizontal = 4.dp), style = T.versalitas(12.sp, color))

/** Un título de sección con su número en el acento y la raya debajo («01 MUNDOS»). */
@Composable
fun Seccion(numero: Int?, t: String, mod: Modifier = Modifier, derecha: @Composable () -> Unit = {}) {
    Column(mod.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (numero != null) { Text("%02d".format(numero), style = T.titular(22.sp, T.ACENTO)); Spacer(Modifier.width(10.dp)) }
            Text(t.uppercase(), Modifier.weight(1f), style = T.titular(22.sp))
            derecha()
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(T.TINTA))
    }
}

@Composable
fun Filete(grosor: Int = 1, color: Color = T.TINTA, mod: Modifier = Modifier) = Box(mod.fillMaxWidth().height(grosor.dp).background(color))

/** Una ficha elegible (filtros, tipos). */
@Composable
fun Ficha(t: String, elegida: Boolean, mod: Modifier = Modifier, color: Color = T.TINTA, alPulsar: () -> Unit) {
    Box(
        mod.border(1.5.dp, if (elegida) color else T.GRIS_CLARO).background(if (elegida) color else Color.Transparent)
            .clickable(onClick = alPulsar).padding(horizontal = 10.dp, vertical = 7.dp),
    ) { Text(t, style = T.texto(13.sp, if (elegida) T.PAPEL else T.TINTA), maxLines = 1) }
}

@Composable
fun Deslizador(t: String, valor: Float, rango: ClosedFloatingPointRange<Float>, texto: String, mod: Modifier = Modifier, alCambiar: (Float) -> Unit) {
    Column(mod.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Etiqueta(t, Modifier.weight(1f), 11.sp, T.GRIS)
            Text(texto, style = T.titular(15.sp))
        }
        Slider(valor, alCambiar, valueRange = rango, modifier = Modifier.height(30.dp),
            colors = SliderDefaults.colors(thumbColor = T.TINTA, activeTrackColor = T.TINTA, inactiveTrackColor = T.GRIS_CLARO.copy(alpha = 0.5f)))
    }
}

@Composable
fun Campo(valor: String, alCambiar: (String) -> Unit, mod: Modifier = Modifier, pista: String = "", estilo: TextStyle = T.texto(16.sp), lineas: Int = 1, teclado: KeyboardOptions = KeyboardOptions.Default, oculto: Boolean = false) {
    Box(mod.border(1.5.dp, T.TINTA).background(T.CRUDO).padding(horizontal = 10.dp, vertical = 9.dp)) {
        if (valor.isEmpty()) Text(pista, style = estilo.copy(color = T.GRIS_CLARO))
        BasicTextField(valor, alCambiar, Modifier.fillMaxWidth(), textStyle = estilo, singleLine = lineas == 1, minLines = lineas, cursorBrush = SolidColor(T.ACENTO), keyboardOptions = teclado,
            visualTransformation = if (oculto) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
    }
}

/** Para pulsar sin la onda de Material (las hermanas no la tienen). */
@Composable
fun Modifier.pulsable(alPulsar: () -> Unit): Modifier = this.clickable(remember { MutableInteractionSource() }, null, onClick = alPulsar)

val Relleno = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

@Suppress("unused") private val sinUsar = Arrangement.Start
