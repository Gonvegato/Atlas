@file:OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)

package com.gonzalo.atlas.lienzo

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.StockTextureBitmapStore
import com.gonzalo.atlas.motor.Ruido
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Los pinceles del lienzo, sobre la biblioteca Ink de Google (trazo de baja latencia: se dibuja en el búfer delantero de la
 * pantalla). [goma]: borra en lugar de pintar. El tamaño va en píxeles del lienzo.
 */
class TipoPincel(
    val clave: String,
    val nombre: String,
    val desc: String,
    val tamano: Float,
    val opacidad: Float,
    val goma: Boolean = false,
    private val familia: () -> BrushFamily,
) {
    val fam: BrushFamily by lazy { runCatching { familia() }.getOrElse { StockBrushes.pressurePen() } }
    fun brush(color: Int, tam: Float): Brush = Brush.createWithColorIntArgb(fam, color, tam.coerceAtLeast(0.5f), 0.1f)
}

object Pinceles {
    const val GRANO = "atlas_grano"
    const val PUNTO = "atlas_punto"
    const val LIENZO = "atlas_lienzo"

    @Volatile private var almacen: StockTextureBitmapStore? = null

    /** Las texturas de los pinceles (las de Ink y las nuestras: grano de papel, punto difuminado, tela). */
    fun almacen(res: Resources): StockTextureBitmapStore = almacen ?: synchronized(this) {
        almacen ?: StockTextureBitmapStore(res).also { s ->
            s.addTexture(GRANO, grano(256, 3f, 7))
            s.addTexture(PUNTO, punto(128))
            s.addTexture(LIENZO, grano(256, 1.2f, 13))
            runCatching { s.preloadStockBrushesTextures(StockBrushes.pencilUnstable) }
            almacen = s
        }
    }

    /** Grano: blanco con transparencia irregular (multiplica el color del pincel). */
    private fun grano(lado: Int, escala: Float, semilla: Long): Bitmap {
        val t = com.gonzalo.atlas.motor.RuidoTabla(lado, semilla, lado / escala / 8f, 3)
        val px = IntArray(lado * lado)
        for (i in px.indices) {
            val v = (t.v[i] * 0.5f + 0.5f)
            val fino = Ruido.hash01(i % lado, i / lado, semilla.toInt()) * 0.35f
            val a = ((v * 0.8f + fino) * 255).toInt().coerceIn(30, 255)
            px[i] = Color.argb(a, 255, 255, 255)
        }
        return Bitmap.createBitmap(px, lado, lado, Bitmap.Config.ARGB_8888)
    }

    /** Un punto blanco difuminado (para el aerógrafo y la acuarela). */
    private fun punto(lado: Int): Bitmap {
        val px = IntArray(lado * lado)
        val c = (lado - 1) / 2f
        for (y in 0 until lado) for (x in 0 until lado) {
            val d = sqrt((x - c) * (x - c) + (y - c) * (y - c)) / c
            val a = if (d >= 1f) 0f else exp(-d * d * 3.2f) * (1f - d * d)
            px[y * lado + x] = Color.argb((a * 255).toInt().coerceIn(0, 255), 255, 255, 255)
        }
        return Bitmap.createBitmap(px, lado, lado, Bitmap.Config.ARGB_8888)
    }

    private fun presionTamano(min: Float, max: Float = 1f) = BrushBehavior(
        source = BrushBehavior.Source.NORMALIZED_PRESSURE, target = BrushBehavior.Target.SIZE_MULTIPLIER,
        sourceValueRangeStart = 0f, sourceValueRangeEnd = 1f, targetModifierRangeStart = min, targetModifierRangeEnd = max)

    private fun presionOpacidad(min: Float) = BrushBehavior(
        source = BrushBehavior.Source.NORMALIZED_PRESSURE, target = BrushBehavior.Target.OPACITY_MULTIPLIER,
        sourceValueRangeStart = 0f, sourceValueRangeEnd = 1f, targetModifierRangeStart = min, targetModifierRangeEnd = 1f)

    private fun texturado(id: String, tamTextura: Float, unidad: BrushPaint.TextureSizeUnit, mapeo: BrushPaint.TextureMapping, solape: SelfOverlap) =
        BrushPaint(listOf(BrushPaint.TextureLayer(clientTextureId = id, sizeX = tamTextura, sizeY = tamTextura, sizeUnit = unidad, mapping = mapeo)), emptyList(), solape)

    val TODOS: List<TipoPincel> = listOf(
        TipoPincel("pluma", "Pluma", "Tinta con presión: líneas finas y gruesas", 6f, 1f) { StockBrushes.pressurePen() },
        TipoPincel("rotulador", "Rotulador", "Trazo igual de principio a fin", 8f, 1f) { StockBrushes.marker() },
        TipoPincel("lapiz", "Lápiz", "Con grano de papel, para bocetar", 4f, 0.9f) { StockBrushes.pencilUnstable },
        TipoPincel("carboncillo", "Carboncillo", "Grueso y con grano; sombrea con la presión", 18f, 0.85f) {
            BrushFamily(BrushTip(cornerRounding = 1f, behaviors = listOf(presionTamano(0.35f), presionOpacidad(0.35f))),
                texturado(GRANO, 64f, BrushPaint.TextureSizeUnit.STROKE_COORDINATES, BrushPaint.TextureMapping.TILING, SelfOverlap.DISCARD), "atlas:carboncillo")
        },
        TipoPincel("pincel", "Pincel", "Pincel redondo con presión, para colorear", 28f, 1f) {
            BrushFamily(BrushTip(cornerRounding = 1f, behaviors = listOf(presionTamano(0.2f))), BrushPaint(emptyList(), emptyList(), SelfOverlap.DISCARD), "atlas:pincel")
        },
        TipoPincel("acuarela", "Acuarela", "Manchas suaves que se funden; ve cargando color", 60f, 0.35f) {
            BrushFamily(BrushTip(cornerRounding = 1f, particleGapDistanceScale = 0.12f, behaviors = listOf(presionTamano(0.5f), presionOpacidad(0.2f))),
                texturado(PUNTO, 1f, BrushPaint.TextureSizeUnit.BRUSH_SIZE, BrushPaint.TextureMapping.STAMPING, SelfOverlap.ACCUMULATE), "atlas:acuarela")
        },
        TipoPincel("aerografo", "Aerógrafo", "Difuminado para sombras, mar y relieve", 90f, 0.25f) {
            BrushFamily(BrushTip(cornerRounding = 1f, particleGapDistanceScale = 0.08f, behaviors = listOf(presionOpacidad(0.1f))),
                texturado(PUNTO, 1f, BrushPaint.TextureSizeUnit.BRUSH_SIZE, BrushPaint.TextureMapping.STAMPING, SelfOverlap.ACCUMULATE), "atlas:aerografo")
        },
        TipoPincel("tiza", "Tiza", "Textura de tela, para rellenar con grano", 36f, 0.8f) {
            BrushFamily(BrushTip(cornerRounding = 0.6f, behaviors = listOf(presionTamano(0.4f))),
                texturado(LIENZO, 48f, BrushPaint.TextureSizeUnit.STROKE_COORDINATES, BrushPaint.TextureMapping.TILING, SelfOverlap.DISCARD), "atlas:tiza")
        },
        TipoPincel("resaltador", "Resaltador", "Transparente y de punta plana", 24f, 0.45f) { StockBrushes.highlighter(SelfOverlap.DISCARD) },
        TipoPincel("discontinua", "Discontinua", "Fronteras, caminos y rutas", 4f, 1f) { StockBrushes.dashedLine() },
        TipoPincel("puntos", "Puntos", "Línea de puntos (rutas, sendas)", 5f, 1f) {
            BrushFamily(BrushTip(cornerRounding = 1f, particleGapDistanceScale = 2.2f), BrushPaint(emptyList(), emptyList(), SelfOverlap.ANY), "atlas:puntos")
        },
        TipoPincel("goma", "Goma", "Borra con presión", 30f, 1f, goma = true) { StockBrushes.pressurePen() },
        TipoPincel("goma_suave", "Goma suave", "Borra difuminando", 80f, 0.5f, goma = true) {
            BrushFamily(BrushTip(cornerRounding = 1f, particleGapDistanceScale = 0.1f, behaviors = listOf(presionOpacidad(0.1f))),
                texturado(PUNTO, 1f, BrushPaint.TextureSizeUnit.BRUSH_SIZE, BrushPaint.TextureMapping.STAMPING, SelfOverlap.ACCUMULATE), "atlas:goma_suave")
        },
    )

    fun de(clave: String) = TODOS.firstOrNull { it.clave == clave } ?: TODOS[0]
}
