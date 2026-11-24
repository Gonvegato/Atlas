@file:OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)

package com.gonzalo.atlas.lienzo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import com.gonzalo.atlas.motor.Ruido
import com.gonzalo.atlas.motor.RuidoTabla
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Un pincel del estudio (como el Brush Studio de Procreate): todos sus ajustes, que se traducen a un pincel de Ink
 * (punta, comportamientos y texturas). Se puede duplicar y cambiar cualquiera.
 */
data class DefPincel(
    val id: String,
    var nombre: String,
    var categoria: String,
    /** "redonda", "plana" o el id de una textura de punta (se estampa a lo largo del trazo). */
    var forma: String = "redonda",
    /** Tamaño y opacidad al elegirlo. */
    var tamano: Float = 8f,
    var opacidad: Float = 1f,
    /** Cuánto cambia el tamaño con la presión (0: nada, 1: de casi nada a todo). */
    var presionTamano: Float = 0.6f,
    var presionOpacidad: Float = 0f,
    /** Con la velocidad: negativo adelgaza al ir rápido (como la tinta), positivo engorda. */
    var velocidadTamano: Float = 0f,
    /** Con la inclinación del lápiz: más ancho tumbado (como un lápiz de grafito). */
    var inclinacion: Float = 0f,
    /** Afilado al empezar y al terminar, en tamaños de pincel (0: sin afilar). */
    var afiladoInicio: Float = 0f,
    var afiladoFin: Float = 0f,
    /** Separación entre estampas (0: trazo continuo). */
    var espaciado: Float = 0f,
    /** Dispersión lateral y giro al azar de cada estampa. */
    var dispersion: Float = 0f,
    var giroAzar: Float = 0f,
    var sigueDireccion: Boolean = false,
    /** Achatado de la punta (1 redonda; 0,2 una plumilla plana) y su ángulo. */
    var achatado: Float = 1f,
    var angulo: Float = 0f,
    /** El grano (textura de papel, lienzo…) y su escala en píxeles. "" sin grano. */
    var grano: String = "",
    var escalaGrano: Float = 128f,
    /** Acumula donde se cruza (acuarela, aerógrafo) o no (tinta). */
    var acumula: Boolean = false,
    /** Estabilización propia (0..1). */
    var estabilizacion: Float = 0.15f,
    /** Uno de los pinceles de Ink tal cual ("pluma", "rotulador", "lapiz", "resaltador", "discontinua"); "" si no. */
    var stock: String = "",
    val propio: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("n", nombre).put("c", categoria).put("f", forma).put("t", tamano.toDouble()).put("o", opacidad.toDouble())
        .put("pt", presionTamano.toDouble()).put("po", presionOpacidad.toDouble()).put("v", velocidadTamano.toDouble()).put("i", inclinacion.toDouble())
        .put("ai", afiladoInicio.toDouble()).put("af", afiladoFin.toDouble()).put("e", espaciado.toDouble()).put("d", dispersion.toDouble())
        .put("ga", giroAzar.toDouble()).put("sd", sigueDireccion).put("ac", achatado.toDouble()).put("an", angulo.toDouble()).put("g", grano)
        .put("eg", escalaGrano.toDouble()).put("acu", acumula).put("est", estabilizacion.toDouble()).put("s", stock).put("p", propio)

    companion object {
        fun de(o: JSONObject) = DefPincel(o.getString("id"), o.optString("n"), o.optString("c", "Míos"), o.optString("f", "redonda"),
            o.optDouble("t", 8.0).toFloat(), o.optDouble("o", 1.0).toFloat(), o.optDouble("pt", 0.6).toFloat(), o.optDouble("po").toFloat(),
            o.optDouble("v").toFloat(), o.optDouble("i").toFloat(), o.optDouble("ai").toFloat(), o.optDouble("af").toFloat(), o.optDouble("e").toFloat(),
            o.optDouble("d").toFloat(), o.optDouble("ga").toFloat(), o.optBoolean("sd"), o.optDouble("ac", 1.0).toFloat(), o.optDouble("an").toFloat(),
            o.optString("g"), o.optDouble("eg", 128.0).toFloat(), o.optBoolean("acu"), o.optDouble("est", 0.15).toFloat(), o.optString("s"), o.optBoolean("p"))
    }

    /** El pincel de Ink con estos ajustes. */
    fun familia(): BrushFamily {
        when (stock) {
            "pluma" -> return StockBrushes.pressurePen()
            "rotulador" -> return StockBrushes.marker()
            "lapiz" -> return StockBrushes.pencilUnstable
            "resaltador" -> return StockBrushes.highlighter(SelfOverlap.DISCARD)
            "discontinua" -> return StockBrushes.dashedLine()
        }
        val c = ArrayList<BrushBehavior>()
        fun de(f: BrushBehavior.Source, a: Float, b: Float, t: BrushBehavior.Target, x: Float, y: Float) =
            BrushBehavior(source = f, target = t, sourceValueRangeStart = a, sourceValueRangeEnd = b, targetModifierRangeStart = x, targetModifierRangeEnd = y)
        val S = BrushBehavior.Source; val T = BrushBehavior.Target
        if (presionTamano > 0.01f) c += de(S.NORMALIZED_PRESSURE, 0f, 1f, T.SIZE_MULTIPLIER, (1f - presionTamano).coerceAtLeast(0.03f), 1f)
        if (presionOpacidad > 0.01f) c += de(S.NORMALIZED_PRESSURE, 0f, 1f, T.OPACITY_MULTIPLIER, (1f - presionOpacidad).coerceAtLeast(0.02f), 1f)
        if (kotlin.math.abs(velocidadTamano) > 0.01f) c += de(S.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, 0f, 60f, T.SIZE_MULTIPLIER, 1f, (1f + velocidadTamano).coerceAtLeast(0.1f))
        if (inclinacion > 0.01f) c += de(S.TILT_IN_RADIANS, 0.2f, 1.3f, T.SIZE_MULTIPLIER, 1f, 1f + inclinacion * 2.5f)
        if (afiladoInicio > 0.01f) c += de(S.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, afiladoInicio, T.SIZE_MULTIPLIER, 0.05f, 1f)
        if (afiladoFin > 0.01f) c += de(S.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, afiladoFin, T.SIZE_MULTIPLIER, 0.05f, 1f)
        if (sigueDireccion) c += de(S.DIRECTION_ABOUT_ZERO_IN_RADIANS, -PI.toFloat(), PI.toFloat(), T.ROTATION_OFFSET_IN_RADIANS, -PI.toFloat(), PI.toFloat())
        if (dispersion > 0.01f) c += BrushBehavior(listOf(BrushBehavior.TargetNode(T.POSITION_OFFSET_LATERAL_IN_MULTIPLES_OF_BRUSH_SIZE, -dispersion, dispersion,
            BrushBehavior.NoiseNode(17, BrushBehavior.DampingSource.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, 0.4f))))
        if (giroAzar > 0.01f) c += BrushBehavior(listOf(BrushBehavior.TargetNode(T.ROTATION_OFFSET_IN_RADIANS, -giroAzar * PI.toFloat(), giroAzar * PI.toFloat(),
            BrushBehavior.NoiseNode(29, BrushBehavior.DampingSource.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, 0.3f))))
        val sello = forma != "redonda" && forma != "plana"
        val gap = if (sello && espaciado < 0.02f) 0.15f else espaciado
        val punta = BrushTip(scaleX = 1f, scaleY = if (forma == "plana") achatado.coerceIn(0.05f, 1f) else 1f, cornerRounding = if (forma == "plana") 0.4f else 1f,
            rotationDegrees = angulo, particleGapDistanceScale = gap, behaviors = c)
        val capas = ArrayList<BrushPaint.TextureLayer>()
        if (sello) capas += BrushPaint.TextureLayer(clientTextureId = forma, sizeX = 1f, sizeY = 1f, sizeUnit = BrushPaint.TextureSizeUnit.BRUSH_SIZE, mapping = BrushPaint.TextureMapping.STAMPING)
        if (grano.isNotBlank()) capas += BrushPaint.TextureLayer(clientTextureId = grano, sizeX = escalaGrano, sizeY = escalaGrano,
            sizeUnit = BrushPaint.TextureSizeUnit.STROKE_COORDINATES, mapping = BrushPaint.TextureMapping.TILING,
            blendMode = if (sello) BrushPaint.BlendMode.MODULATE else BrushPaint.BlendMode.SRC_IN)
        val paint = BrushPaint(capas, emptyList(), if (acumula) SelfOverlap.ACCUMULATE else SelfOverlap.DISCARD)
        return BrushFamily(punta, paint, "atlas:$id")
    }

    fun tipo(): TipoPincel = TipoPincel(id, nombre, categoria, tamano, opacidad) {
        // Si alguna combinación no le gusta a Ink, se intenta sin grano y luego sin nada raro
        runCatching { familia() }.getOrElse { runCatching { copy(grano = "").familia() }.getOrElse { copy(grano = "", forma = "redonda", dispersion = 0f, giroAzar = 0f).familia() } }
    }
}

/** Las texturas de las puntas y los granos de los pinceles (hechas aquí, sin imágenes de nadie). */
object Texturas {
    val PUNTAS = listOf("t_suave" to "Suave", "t_aspera" to "Áspera", "t_salpicadura" to "Salpicadura", "t_carbon" to "Carbón", "t_hojas" to "Hojas", "t_piedra" to "Piedras", "t_hierba" to "Hierba", "t_ola" to "Ola")
    val GRANOS = listOf("g_papel" to "Papel", "g_lienzo" to "Lienzo", "g_ruido" to "Ruido", "g_rayado" to "Rayado", "g_piedra" to "Piedra", "g_arena" to "Arena")

    fun todas(): Map<String, Bitmap> = mapOf(
        "t_suave" to punta(128) { d, _, _ -> exp(-d * d * 3.2f) * (1f - d * d) },
        "t_aspera" to punta(128) { d, x, y -> val n = Ruido.hash01(x / 3, y / 3, 5); if (d < 0.75f + n * 0.25f) (0.6f + n * 0.4f) else 0f },
        "t_salpicadura" to salpicadura(),
        "t_carbon" to punta(128) { d, x, y -> val n = Ruido.hash01(x / 2, y / 2, 9); if (d < 0.95f && n > 0.25f + d * 0.6f) 0.5f + n * 0.5f else 0f },
        "t_hojas" to hojas(),
        "t_piedra" to piedras(),
        "t_hierba" to hierba(),
        "t_ola" to ola(),
        "g_papel" to grano(256, 7, 3f, 0.35f),
        "g_lienzo" to lienzo(),
        "g_ruido" to grano(256, 11, 1.2f, 0.6f),
        "g_rayado" to rayado(),
        "g_piedra" to grano(256, 13, 8f, 0.2f),
        "g_arena" to grano(256, 17, 0.8f, 0.8f),
    )

    private fun bmp(l: Int, f: (Int, Int) -> Float): Bitmap {
        val px = IntArray(l * l) { i -> Color.argb((f(i % l, i / l).coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255) }
        return Bitmap.createBitmap(px, l, l, Bitmap.Config.ARGB_8888)
    }

    private fun punta(l: Int, f: (Float, Int, Int) -> Float): Bitmap {
        val c = (l - 1) / 2f
        return bmp(l) { x, y -> val d = hypot(x - c, y - c) / c; if (d >= 1f) 0f else f(d, x, y) }
    }

    private fun grano(l: Int, semilla: Long, escala: Float, fino: Float): Bitmap {
        val t = RuidoTabla(l, semilla, l / escala / 8f, 3)
        return bmp(l) { x, y -> (t.v[y * l + x] * 0.5f + 0.5f) * (1f - fino) + Ruido.hash01(x, y, semilla.toInt()) * fino }
    }

    private fun lienzo() = bmp(128) { x, y -> val a = (sin(x * 0.8f) * 0.5f + 0.5f); val b = (sin(y * 0.8f) * 0.5f + 0.5f); 0.45f + 0.55f * maxOf(a * (if ((y / 4) % 2 == 0) 1f else 0.6f), b * (if ((x / 4) % 2 == 0) 0.6f else 1f)) }
    private fun rayado() = bmp(128) { x, y -> if (((x + y) % 9) < 2) 1f else 0.15f + Ruido.hash01(x, y, 3) * 0.1f }

    private fun salpicadura(): Bitmap {
        val l = 128; val px = FloatArray(l * l)
        for (k in 0 until 28) {
            val a = Ruido.hash01(k, 1, 7) * 2 * PI; val r = Ruido.hash01(k, 2, 7) * 50f; val s = 2f + Ruido.hash01(k, 3, 7) * 8f
            val cx = 64 + cos(a).toFloat() * r; val cy = 64 + sin(a).toFloat() * r
            for (y in 0 until l) for (x in 0 until l) if (hypot(x - cx, y - cy) < s) px[y * l + x] = 1f
        }
        return bmp(l) { x, y -> px[y * l + x] }
    }

    private fun hojas(): Bitmap {
        val l = 128; val px = FloatArray(l * l)
        for (k in 0 until 14) {
            val a = Ruido.hash01(k, 1, 3) * 2 * PI; val r = Ruido.hash01(k, 2, 3) * 40f
            val cx = 64 + cos(a).toFloat() * r; val cy = 64 + sin(a).toFloat() * r; val g = Ruido.hash01(k, 4, 3) * PI.toFloat()
            for (y in 0 until l) for (x in 0 until l) {
                val dx = x - cx; val dy = y - cy
                val u = dx * cos(g) + dy * sin(g); val v = -dx * sin(g) + dy * cos(g)
                if ((u / 16f) * (u / 16f) + (v / 7f) * (v / 7f) < 1f) px[y * l + x] = maxOf(px[y * l + x], 0.7f + 0.3f * Ruido.hash01(k, 5, 3))
            }
        }
        return bmp(l) { x, y -> px[y * l + x] }
    }

    private fun piedras(): Bitmap {
        val l = 128; val px = FloatArray(l * l)
        for (k in 0 until 18) {
            val cx = 10 + Ruido.hash01(k, 1, 11) * 108; val cy = 10 + Ruido.hash01(k, 2, 11) * 108; val s = 4 + Ruido.hash01(k, 3, 11) * 10
            for (y in 0 until l) for (x in 0 until l) { val d = hypot(x - cx, y - cy) / s; if (d < 1f && hypot(x - 64f, y - 64f) < 62f) px[y * l + x] = if (d > 0.8f) 1f else 0.35f }
        }
        return bmp(l) { x, y -> px[y * l + x] }
    }

    private fun hierba(): Bitmap {
        val l = 128; val px = FloatArray(l * l)
        for (k in 0 until 22) {
            val bx = 20 + Ruido.hash01(k, 1, 13) * 88; val alto = 30 + Ruido.hash01(k, 2, 13) * 50; val curva = (Ruido.hash01(k, 3, 13) - 0.5f) * 30
            for (t in 0..100) { val f = t / 100f; val x = bx + curva * f * f; val y = 110 - alto * f; val w = 2.5f * (1 - f) + 0.5f
                for (yy in (y - w).toInt()..(y + w).toInt()) for (xx in (x - w).toInt()..(x + w).toInt()) if (xx in 0 until l && yy in 0 until l) px[yy * l + xx] = 1f }
        }
        return bmp(l) { x, y -> px[y * l + x] }
    }

    private fun ola() = bmp(128) { x, y -> val yy = 64 + sin(x / 128f * 2 * PI).toFloat() * 14f; val d = kotlin.math.abs(y - yy); if (d < 3f && x in 8..120) 1f else 0f }
}

/** El catálogo de pinceles: los de serie (por categorías, como en Procreate) y los tuyos. */
object Catalogo2 {
    val CATEGORIAS = listOf("Bocetos", "Entintado", "Pintura", "Aerógrafo", "Texturas", "Cartografía", "Importados", "Míos")

    val SERIE: List<DefPincel> = listOf(
        DefPincel("lapiz6b", "Lápiz 6B", "Bocetos", tamano = 6f, opacidad = 0.85f, presionTamano = 0.5f, presionOpacidad = 0.6f, inclinacion = 0.8f, grano = "g_papel", escalaGrano = 96f, estabilizacion = 0.05f),
        DefPincel("lapizhb", "Lápiz HB", "Bocetos", tamano = 3f, opacidad = 0.75f, presionTamano = 0.3f, presionOpacidad = 0.5f, inclinacion = 0.4f, grano = "g_papel", escalaGrano = 64f, estabilizacion = 0.05f),
        DefPincel("lapizink", "Lápiz de Ink", "Bocetos", tamano = 4f, stock = "lapiz"),
        DefPincel("carboncillo", "Carboncillo", "Bocetos", forma = "t_carbon", tamano = 22f, opacidad = 0.8f, presionTamano = 0.5f, presionOpacidad = 0.5f, espaciado = 0.08f, giroAzar = 1f, acumula = true, grano = "g_papel"),
        DefPincel("grafito", "Grafito suave", "Bocetos", tamano = 14f, opacidad = 0.5f, presionTamano = 0.4f, presionOpacidad = 0.7f, inclinacion = 1.2f, grano = "g_ruido", escalaGrano = 48f),
        DefPincel("pluma", "Pluma", "Entintado", tamano = 6f, presionTamano = 0.8f, afiladoInicio = 2f, afiladoFin = 3f, estabilizacion = 0.3f),
        DefPincel("tinta", "Tinta de estudio", "Entintado", tamano = 8f, presionTamano = 0.9f, velocidadTamano = -0.3f, afiladoInicio = 4f, afiladoFin = 6f, estabilizacion = 0.4f),
        DefPincel("tecnico", "Rotulador técnico", "Entintado", tamano = 4f, presionTamano = 0f, estabilizacion = 0.3f),
        DefPincel("plumilla", "Plumilla", "Entintado", forma = "plana", tamano = 10f, presionTamano = 0.5f, achatado = 0.25f, angulo = 40f, afiladoFin = 2f, estabilizacion = 0.25f),
        DefPincel("boligrafo", "Bolígrafo", "Entintado", tamano = 2.5f, presionTamano = 0.25f, estabilizacion = 0.2f),
        DefPincel("tintaseca", "Tinta seca", "Entintado", tamano = 10f, presionTamano = 0.7f, grano = "g_papel", escalaGrano = 64f, afiladoFin = 3f),
        DefPincel("penstock", "Pluma de Ink", "Entintado", tamano = 6f, stock = "pluma"),
        DefPincel("redondo", "Pincel redondo", "Pintura", tamano = 30f, presionTamano = 0.6f, afiladoFin = 2f),
        DefPincel("plano", "Pincel plano", "Pintura", forma = "plana", tamano = 36f, presionTamano = 0.3f, achatado = 0.35f, sigueDireccion = true),
        DefPincel("oleo", "Óleo", "Pintura", forma = "plana", tamano = 40f, presionTamano = 0.4f, achatado = 0.5f, grano = "g_lienzo", escalaGrano = 64f, sigueDireccion = true),
        DefPincel("gouache", "Gouache", "Pintura", tamano = 34f, presionTamano = 0.4f, presionOpacidad = 0.3f, grano = "g_papel", escalaGrano = 192f),
        DefPincel("acuarela", "Acuarela", "Pintura", forma = "t_suave", tamano = 70f, opacidad = 0.3f, presionTamano = 0.4f, presionOpacidad = 0.6f, espaciado = 0.1f, acumula = true),
        DefPincel("acuarelaseca", "Acuarela seca", "Pintura", forma = "t_aspera", tamano = 50f, opacidad = 0.35f, presionOpacidad = 0.6f, espaciado = 0.12f, giroAzar = 1f, acumula = true, grano = "g_papel"),
        DefPincel("resaltador", "Resaltador", "Pintura", tamano = 24f, opacidad = 0.45f, stock = "resaltador"),
        DefPincel("aerosuave", "Aerógrafo suave", "Aerógrafo", forma = "t_suave", tamano = 120f, opacidad = 0.2f, presionTamano = 0f, presionOpacidad = 0.8f, espaciado = 0.06f, acumula = true, estabilizacion = 0f),
        DefPincel("aeroduro", "Aerógrafo duro", "Aerógrafo", tamano = 60f, opacidad = 0.5f, presionTamano = 0f, presionOpacidad = 0.7f, estabilizacion = 0f),
        DefPincel("salpicaduras", "Salpicaduras", "Aerógrafo", forma = "t_salpicadura", tamano = 90f, presionTamano = 0.4f, espaciado = 0.9f, dispersion = 0.6f, giroAzar = 1f, acumula = true, estabilizacion = 0f),
        DefPincel("tiza", "Tiza", "Texturas", tamano = 36f, opacidad = 0.85f, presionTamano = 0.4f, grano = "g_lienzo", escalaGrano = 48f),
        DefPincel("ruido", "Ruido", "Texturas", tamano = 60f, opacidad = 0.6f, presionTamano = 0.3f, grano = "g_ruido", escalaGrano = 128f),
        DefPincel("rayado", "Rayado", "Texturas", tamano = 50f, opacidad = 0.8f, presionTamano = 0.2f, grano = "g_rayado", escalaGrano = 48f),
        DefPincel("arena", "Arena", "Texturas", tamano = 60f, opacidad = 0.5f, presionTamano = 0.2f, grano = "g_arena", escalaGrano = 96f),
        DefPincel("costa", "Costa", "Cartografía", tamano = 4f, presionTamano = 0.7f, afiladoInicio = 3f, afiladoFin = 3f, estabilizacion = 0.45f),
        DefPincel("bosque", "Bosque", "Cartografía", forma = "t_hojas", tamano = 40f, presionTamano = 0.4f, espaciado = 0.5f, dispersion = 0.5f, giroAzar = 0.3f, acumula = true, estabilizacion = 0f),
        DefPincel("montes", "Piedras", "Cartografía", forma = "t_piedra", tamano = 40f, presionTamano = 0.3f, espaciado = 0.7f, dispersion = 0.4f, giroAzar = 1f, acumula = true),
        DefPincel("hierba", "Hierba", "Cartografía", forma = "t_hierba", tamano = 30f, presionTamano = 0.3f, espaciado = 0.4f, dispersion = 0.3f, acumula = true),
        DefPincel("olas", "Olas", "Cartografía", forma = "t_ola", tamano = 26f, presionTamano = 0.2f, espaciado = 1.2f, dispersion = 0.8f, acumula = true),
        DefPincel("frontera", "Frontera", "Cartografía", tamano = 4f, stock = "discontinua"),
        DefPincel("puntos", "Puntos", "Cartografía", tamano = 5f, presionTamano = 0f, espaciado = 2.2f, acumula = false),
    )
}
