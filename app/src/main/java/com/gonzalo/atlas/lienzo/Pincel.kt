package com.gonzalo.atlas.lienzo

import org.json.JSONObject

/**
 * Un pincel (como los del Brush Studio de Procreate): una punta (la forma) que se estampa a lo largo del trazo, sobre un
 * grano (el papel), con su dinámica (presión, inclinación, velocidad, azar). Todos sus ajustes se pueden cambiar y duplicar.
 */
data class DefPincel(
    val id: String,
    var nombre: String,
    var categoria: String,
    /** "redonda", "plana" o el nombre de una textura de punta. */
    var forma: String = "redonda",
    /** Borde de la punta redonda: 1 nítido, 0 difuminado del todo (aerógrafo). */
    var dureza: Float = 1f,
    /** Tamaño y opacidad al elegirlo. */
    var tamano: Float = 8f,
    var opacidad: Float = 1f,
    /** Cuánta pintura deja cada estampa. */
    var flujo: Float = 1f,
    /** Cuánto cambian el tamaño y la opacidad con la presión (0: nada, 1: de casi nada a todo). */
    var presionTamano: Float = 0.6f,
    var presionOpacidad: Float = 0f,
    /** Con la velocidad: negativo adelgaza (o aclara) al ir rápido, positivo engorda. */
    var velocidadTamano: Float = 0f,
    var velocidadOpacidad: Float = 0f,
    /** Con la inclinación del lápiz: más ancho y más claro tumbado (como sombrear con un lápiz de grafito). */
    var inclinacion: Float = 0f,
    var inclinacionOpacidad: Float = 0f,
    /** Afilado al empezar y al terminar, en tamaños de pincel (0: sin afilar). */
    var afiladoInicio: Float = 0f,
    var afiladoFin: Float = 0f,
    /** Separación entre estampas en tamaños de pincel (0: trazo continuo). */
    var espaciado: Float = 0f,
    /** Dispersión de las estampas alrededor del trazo, y cuántas en cada sitio. */
    var dispersion: Float = 0f,
    var numero: Int = 1,
    /** Giro al azar de cada estampa (1: cualquiera), si sigue la dirección del trazo o la del lápiz, y si se voltea al azar. */
    var giroAzar: Float = 0f,
    var sigueDireccion: Boolean = false,
    var acimut: Boolean = false,
    var volteo: Boolean = false,
    /** Achatado de la punta (1 redonda; 0,2 una plumilla plana) y su ángulo en grados. */
    var achatado: Float = 1f,
    var angulo: Float = 0f,
    /** El grano (papel, lienzo…), su escala en píxeles, cuánto se nota y si se mueve con cada estampa. "" sin grano. */
    var grano: String = "",
    var escalaGrano: Float = 128f,
    var profGrano: Float = 1f,
    var granoMovil: Boolean = false,
    /** Acumula donde se cruza consigo mismo (acuarela, aerógrafo) o no (tinta, lápiz). */
    var acumula: Boolean = false,
    /** Bordes húmedos: el contorno del trazo queda más cargado (acuarela). */
    var bordes: Float = 0f,
    /** Azar en cada estampa: tamaño, opacidad y color (tono, saturación, brillo). */
    var azarTamano: Float = 0f,
    var azarOpacidad: Float = 0f,
    var azarTono: Float = 0f,
    var azarSat: Float = 0f,
    var azarBrillo: Float = 0f,
    /** Mezcla húmeda: cuánto arrastra el color que encuentra (óleo). */
    var mezcla: Float = 0f,
    /** Estabilización propia (0..1). */
    var estabilizacion: Float = 0.15f,
    val propio: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("n", nombre).put("c", categoria).put("f", forma).put("du", dureza.toDouble()).put("t", tamano.toDouble()).put("o", opacidad.toDouble())
        .put("fl", flujo.toDouble()).put("pt", presionTamano.toDouble()).put("po", presionOpacidad.toDouble()).put("v", velocidadTamano.toDouble()).put("vo", velocidadOpacidad.toDouble())
        .put("i", inclinacion.toDouble()).put("io", inclinacionOpacidad.toDouble()).put("ai", afiladoInicio.toDouble()).put("af", afiladoFin.toDouble()).put("e", espaciado.toDouble())
        .put("d", dispersion.toDouble()).put("nu", numero).put("ga", giroAzar.toDouble()).put("sd", sigueDireccion).put("az", acimut).put("vl", volteo)
        .put("ac", achatado.toDouble()).put("an", angulo.toDouble()).put("g", grano).put("eg", escalaGrano.toDouble()).put("pg", profGrano.toDouble()).put("gm", granoMovil)
        .put("acu", acumula).put("bo", bordes.toDouble()).put("zt", azarTamano.toDouble()).put("zo", azarOpacidad.toDouble()).put("zh", azarTono.toDouble())
        .put("zs", azarSat.toDouble()).put("zb", azarBrillo.toDouble()).put("mz", mezcla.toDouble()).put("est", estabilizacion.toDouble()).put("p", propio)

    /** Hay que guardar el color píxel a píxel mientras se pinta (cada estampa puede traer un color distinto). */
    val colorVariable get() = azarTono > 0.001f || azarSat > 0.001f || azarBrillo > 0.001f || mezcla > 0.001f

    /** Se puede rehacer el trazo a cualquier tamaño (nítido de cerca): todos menos los que mezclan con lo que hay debajo. */
    val repetible get() = mezcla <= 0.001f

    companion object {
        fun de(o: JSONObject): DefPincel {
            fun f(k: String, def: Double = 0.0) = o.optDouble(k, def).toFloat()
            val d = DefPincel(o.getString("id"), o.optString("n"), o.optString("c", "Míos"), o.optString("f", "redonda"), f("du", 1.0), f("t", 8.0), f("o", 1.0), f("fl", 1.0),
                f("pt", 0.6), f("po"), f("v"), f("vo"), f("i"), f("io"), f("ai"), f("af"), f("e"), f("d"), o.optInt("nu", 1).coerceIn(1, 16), f("ga"), o.optBoolean("sd"), o.optBoolean("az"),
                o.optBoolean("vl"), f("ac", 1.0), f("an"), o.optString("g"), f("eg", 128.0), f("pg", 1.0), o.optBoolean("gm"), o.optBoolean("acu"), f("bo"), f("zt"), f("zo"), f("zh"),
                f("zs"), f("zb"), f("mz"), f("est", 0.15), o.optBoolean("p"))
            // Los pinceles guardados con el motor anterior (los de Ink «tal cual» y la punta suave)
            when (o.optString("s")) {
                "pluma" -> { d.presionTamano = 0.85f; d.afiladoFin = 2f }
                "rotulador" -> { d.presionTamano = 0f }
                "lapiz" -> { d.presionTamano = 0.35f; d.presionOpacidad = 0.8f; d.grano = "g_papel"; d.escalaGrano = 80f; d.dureza = 0.7f }
                "resaltador" -> { d.forma = "plana"; d.achatado = 0.4f; d.presionTamano = 0f }
                "discontinua" -> { d.forma = "plana"; d.achatado = 0.3f; d.sigueDireccion = true; d.espaciado = 2.2f; d.presionTamano = 0f }
            }
            if (d.forma == "t_suave") { d.forma = "redonda"; d.dureza = 0f; if (!o.has("fl")) d.flujo = 0.12f }
            return d
        }
    }
}

/** El catálogo de pinceles de serie (por categorías, como en Procreate). */
object Catalogo2 {
    val CATEGORIAS = listOf("Bocetos", "Entintado", "Pintura", "Acuarela", "Aerógrafo", "Texturas", "Cartografía", "Sellos del mapa", "Importados", "Míos")

    val SERIE: List<DefPincel> = listOf(
        // ---- Bocetos
        DefPincel("lapiz6b", "Lápiz 6B", "Bocetos", dureza = 0.75f, tamano = 7f, opacidad = 0.95f, presionTamano = 0.35f, presionOpacidad = 0.85f, inclinacion = 0.9f, inclinacionOpacidad = 0.5f, grano = "g_papel", escalaGrano = 90f, estabilizacion = 0.05f),
        DefPincel("lapizhb", "Lápiz HB", "Bocetos", dureza = 0.8f, tamano = 3.5f, opacidad = 0.85f, presionTamano = 0.25f, presionOpacidad = 0.75f, inclinacion = 0.5f, inclinacionOpacidad = 0.4f, grano = "g_papel", escalaGrano = 64f, estabilizacion = 0.05f),
        DefPincel("portaminas", "Portaminas", "Bocetos", tamano = 2f, opacidad = 0.9f, presionTamano = 0.15f, presionOpacidad = 0.45f, grano = "g_papel", escalaGrano = 48f, profGrano = 0.5f, estabilizacion = 0.1f),
        DefPincel("carboncillo", "Carboncillo", "Bocetos", forma = "t_carbon", tamano = 26f, opacidad = 0.9f, flujo = 0.55f, presionTamano = 0.35f, presionOpacidad = 0.75f, espaciado = 0.08f, giroAzar = 1f, acumula = true, grano = "g_papel", escalaGrano = 110f, estabilizacion = 0f),
        DefPincel("grafito", "Grafito suave", "Bocetos", dureza = 0.25f, tamano = 20f, opacidad = 0.8f, flujo = 0.28f, presionTamano = 0.3f, presionOpacidad = 0.85f, inclinacion = 1f, acumula = true, grano = "g_ruido", escalaGrano = 56f, profGrano = 0.8f, estabilizacion = 0f),
        DefPincel("sanguina", "Sanguina", "Bocetos", forma = "t_tiza", tamano = 14f, opacidad = 0.9f, flujo = 0.7f, presionTamano = 0.3f, presionOpacidad = 0.7f, espaciado = 0.07f, giroAzar = 1f, grano = "g_papel", escalaGrano = 80f, estabilizacion = 0f),
        // ---- Entintado
        DefPincel("pluma", "Pluma", "Entintado", tamano = 6f, presionTamano = 0.85f, afiladoInicio = 1.5f, afiladoFin = 3f, estabilizacion = 0.3f),
        DefPincel("tinta", "Tinta de estudio", "Entintado", tamano = 8f, presionTamano = 0.95f, velocidadTamano = -0.25f, afiladoInicio = 3f, afiladoFin = 6f, estabilizacion = 0.4f),
        DefPincel("pinceltinta", "Pincel de tinta", "Entintado", tamano = 18f, presionTamano = 1f, velocidadTamano = -0.15f, afiladoInicio = 1f, afiladoFin = 4f, estabilizacion = 0.3f),
        DefPincel("tecnico", "Rotulador técnico", "Entintado", tamano = 4f, presionTamano = 0f, estabilizacion = 0.3f),
        DefPincel("plumilla", "Plumilla", "Entintado", forma = "plana", tamano = 10f, presionTamano = 0.4f, achatado = 0.22f, angulo = 40f, estabilizacion = 0.25f),
        DefPincel("caligrafia", "Caligrafía", "Entintado", forma = "plana", tamano = 16f, presionTamano = 0.15f, achatado = 0.16f, angulo = -45f, estabilizacion = 0.3f),
        DefPincel("boligrafo", "Bolígrafo", "Entintado", tamano = 2.4f, opacidad = 0.95f, presionTamano = 0.2f, presionOpacidad = 0.35f, estabilizacion = 0.2f),
        DefPincel("tintaseca", "Tinta seca", "Entintado", tamano = 10f, presionTamano = 0.7f, grano = "g_papel", escalaGrano = 60f, profGrano = 0.9f, afiladoFin = 3f),
        DefPincel("rotulador", "Rotulador", "Entintado", tamano = 9f, opacidad = 0.9f, presionTamano = 0.1f, estabilizacion = 0.15f),
        DefPincel("resaltador", "Resaltador", "Entintado", forma = "plana", tamano = 26f, opacidad = 0.45f, presionTamano = 0f, achatado = 0.4f, angulo = 60f),
        // ---- Pintura
        DefPincel("redondo", "Pincel redondo", "Pintura", dureza = 0.9f, tamano = 30f, presionTamano = 0.6f, presionOpacidad = 0.15f, afiladoFin = 1.5f),
        DefPincel("plano", "Pincel plano", "Pintura", forma = "t_cerdas", tamano = 40f, presionTamano = 0.3f, presionOpacidad = 0.25f, espaciado = 0.03f, sigueDireccion = true, estabilizacion = 0.05f),
        DefPincel("oleo", "Óleo", "Pintura", forma = "t_cerdas", tamano = 44f, flujo = 0.9f, presionTamano = 0.35f, presionOpacidad = 0.2f, espaciado = 0.03f, sigueDireccion = true, grano = "g_lienzo", escalaGrano = 150f, profGrano = 0.3f, mezcla = 0.55f, acumula = true, estabilizacion = 0f),
        DefPincel("acrilico", "Acrílico", "Pintura", forma = "t_aspera", tamano = 36f, presionTamano = 0.4f, espaciado = 0.05f, giroAzar = 0.15f, sigueDireccion = true, grano = "g_lienzo", escalaGrano = 160f, profGrano = 0.25f, estabilizacion = 0f),
        DefPincel("gouache", "Gouache", "Pintura", dureza = 0.92f, tamano = 34f, presionTamano = 0.4f, presionOpacidad = 0.35f, grano = "g_papel", escalaGrano = 190f, profGrano = 0.2f),
        DefPincel("mezclador", "Pincel húmedo", "Pintura", dureza = 0.6f, tamano = 40f, opacidad = 0.9f, flujo = 0.5f, presionTamano = 0.3f, presionOpacidad = 0.4f, mezcla = 0.85f, acumula = true, estabilizacion = 0f),
        DefPincel("pastel", "Pastel", "Pintura", forma = "t_tiza", tamano = 34f, flujo = 0.8f, presionTamano = 0.25f, presionOpacidad = 0.6f, espaciado = 0.06f, giroAzar = 1f, grano = "g_papel", escalaGrano = 150f, profGrano = 0.8f, acumula = true, estabilizacion = 0f),
        // ---- Acuarela
        DefPincel("acuarela", "Acuarela", "Acuarela", dureza = 0.35f, tamano = 70f, opacidad = 0.5f, flujo = 0.2f, presionTamano = 0.45f, presionOpacidad = 0.5f, acumula = true, bordes = 0.7f, grano = "g_acuarela", escalaGrano = 180f, profGrano = 0.45f, estabilizacion = 0f),
        DefPincel("aguada", "Aguada", "Acuarela", dureza = 0.05f, tamano = 140f, opacidad = 0.4f, flujo = 0.07f, presionTamano = 0.2f, presionOpacidad = 0.7f, acumula = true, bordes = 0.25f, grano = "g_acuarela", escalaGrano = 220f, profGrano = 0.3f, estabilizacion = 0f),
        DefPincel("acuarelaseca", "Acuarela seca", "Acuarela", forma = "t_aspera", tamano = 50f, opacidad = 0.5f, flujo = 0.4f, presionOpacidad = 0.6f, espaciado = 0.1f, giroAzar = 1f, acumula = true, grano = "g_papel", escalaGrano = 120f, estabilizacion = 0f),
        DefPincel("manchas", "Manchas de agua", "Acuarela", forma = "t_esponja", tamano = 90f, opacidad = 0.6f, flujo = 0.7f, presionTamano = 0.3f, espaciado = 0.35f, dispersion = 0.35f, giroAzar = 1f, azarTamano = 0.4f, acumula = true, bordes = 0.5f, estabilizacion = 0f),
        // ---- Aerógrafo
        DefPincel("aerosuave", "Aerógrafo suave", "Aerógrafo", dureza = 0f, tamano = 120f, opacidad = 1f, flujo = 0.07f, presionTamano = 0f, presionOpacidad = 0.85f, acumula = true, estabilizacion = 0f),
        DefPincel("aeromedio", "Aerógrafo medio", "Aerógrafo", dureza = 0.5f, tamano = 80f, opacidad = 1f, flujo = 0.12f, presionTamano = 0.1f, presionOpacidad = 0.8f, acumula = true, estabilizacion = 0f),
        DefPincel("aeroduro", "Aerógrafo duro", "Aerógrafo", dureza = 0.9f, tamano = 60f, opacidad = 1f, flujo = 0.35f, presionTamano = 0f, presionOpacidad = 0.7f, acumula = true, estabilizacion = 0f),
        DefPincel("salpicaduras", "Salpicaduras", "Aerógrafo", forma = "t_salpicadura", tamano = 90f, presionTamano = 0.4f, espaciado = 0.8f, dispersion = 0.6f, giroAzar = 1f, azarTamano = 0.5f, acumula = true, estabilizacion = 0f),
        DefPincel("motas", "Motas", "Aerógrafo", tamano = 5f, presionTamano = 0.3f, espaciado = 1.4f, dispersion = 4f, numero = 3, azarTamano = 0.7f, azarOpacidad = 0.4f, acumula = true, estabilizacion = 0f),
        // ---- Texturas
        DefPincel("tiza", "Tiza", "Texturas", forma = "t_tiza", tamano = 36f, opacidad = 0.95f, presionTamano = 0.3f, presionOpacidad = 0.5f, espaciado = 0.07f, giroAzar = 1f, grano = "g_lienzo", escalaGrano = 150f, profGrano = 0.7f, estabilizacion = 0f),
        DefPincel("esponja", "Esponja", "Texturas", forma = "t_esponja", tamano = 70f, opacidad = 0.8f, flujo = 0.6f, presionTamano = 0.3f, presionOpacidad = 0.4f, espaciado = 0.25f, dispersion = 0.2f, giroAzar = 1f, acumula = true, estabilizacion = 0f),
        DefPincel("ruido", "Ruido", "Texturas", dureza = 0.6f, tamano = 60f, opacidad = 0.7f, presionTamano = 0.3f, presionOpacidad = 0.4f, grano = "g_ruido", escalaGrano = 128f),
        DefPincel("rayado", "Rayado", "Texturas", tamano = 50f, opacidad = 0.85f, presionTamano = 0.2f, grano = "g_rayado", escalaGrano = 120f),
        DefPincel("arena", "Arena", "Texturas", dureza = 0.5f, tamano = 60f, opacidad = 0.6f, presionTamano = 0.2f, presionOpacidad = 0.4f, grano = "g_arena", escalaGrano = 96f),
        DefPincel("roca", "Roca", "Texturas", dureza = 0.7f, tamano = 70f, opacidad = 0.8f, presionTamano = 0.2f, presionOpacidad = 0.4f, grano = "g_piedra", escalaGrano = 200f),
        // ---- Cartografía (líneas)
        DefPincel("costa", "Costa", "Cartografía", tamano = 4f, presionTamano = 0.55f, afiladoInicio = 2f, afiladoFin = 2f, estabilizacion = 0.45f),
        DefPincel("costarugosa", "Costa a mano", "Cartografía", tamano = 3.5f, presionTamano = 0.45f, dispersion = 0.12f, azarTamano = 0.25f, grano = "g_papel", escalaGrano = 40f, profGrano = 0.35f, estabilizacion = 0.3f),
        DefPincel("rio", "Río", "Cartografía", tamano = 6f, presionTamano = 0.5f, afiladoInicio = 14f, afiladoFin = 0.5f, estabilizacion = 0.5f),
        DefPincel("frontera", "Frontera", "Cartografía", forma = "plana", tamano = 4f, presionTamano = 0f, achatado = 0.3f, sigueDireccion = true, espaciado = 2.2f, estabilizacion = 0.4f),
        DefPincel("camino", "Camino", "Cartografía", forma = "plana", tamano = 3f, presionTamano = 0f, achatado = 0.4f, sigueDireccion = true, espaciado = 1.6f, estabilizacion = 0.4f),
        DefPincel("puntos", "Puntos", "Cartografía", tamano = 5f, presionTamano = 0f, espaciado = 2.2f, estabilizacion = 0.4f),
        DefPincel("sombracosta", "Sombra de la costa", "Cartografía", dureza = 0.1f, tamano = 40f, opacidad = 0.5f, flujo = 0.12f, presionTamano = 0.2f, presionOpacidad = 0.6f, acumula = true, estabilizacion = 0.2f),
        DefPincel("mar", "Rayas del mar", "Cartografía", tamano = 46f, opacidad = 0.7f, presionTamano = 0.1f, presionOpacidad = 0.3f, grano = "g_rayado", escalaGrano = 90f, estabilizacion = 0.1f),
        // ---- Sellos del mapa (estampas a lo largo del trazo)
        DefPincel("montanas", "Montañas", "Sellos del mapa", forma = "t_montana", tamano = 46f, presionTamano = 0.25f, espaciado = 0.8f, dispersion = 0.12f, azarTamano = 0.3f, volteo = true, estabilizacion = 0f),
        DefPincel("colinas", "Colinas", "Sellos del mapa", forma = "t_colina", tamano = 34f, presionTamano = 0.25f, espaciado = 0.85f, dispersion = 0.18f, azarTamano = 0.3f, volteo = true, estabilizacion = 0f),
        DefPincel("pinos", "Pinar", "Sellos del mapa", forma = "t_pino", tamano = 26f, presionTamano = 0.2f, espaciado = 0.5f, dispersion = 0.55f, azarTamano = 0.35f, estabilizacion = 0f),
        DefPincel("bosque", "Bosque", "Sellos del mapa", forma = "t_arbol", tamano = 30f, presionTamano = 0.2f, espaciado = 0.55f, dispersion = 0.55f, azarTamano = 0.3f, volteo = true, estabilizacion = 0f),
        DefPincel("hojas", "Fronda", "Sellos del mapa", forma = "t_hojas", tamano = 40f, presionTamano = 0.4f, espaciado = 0.5f, dispersion = 0.5f, giroAzar = 0.3f, estabilizacion = 0f),
        DefPincel("montes", "Piedras", "Sellos del mapa", forma = "t_piedra", tamano = 40f, presionTamano = 0.3f, espaciado = 0.7f, dispersion = 0.4f, giroAzar = 1f, estabilizacion = 0f),
        DefPincel("hierba", "Hierba", "Sellos del mapa", forma = "t_hierba", tamano = 26f, presionTamano = 0.3f, espaciado = 0.6f, dispersion = 0.5f, azarTamano = 0.3f, volteo = true, estabilizacion = 0f),
        DefPincel("pantano", "Pantano", "Sellos del mapa", forma = "t_pantano", tamano = 34f, presionTamano = 0.2f, espaciado = 0.9f, dispersion = 0.6f, azarTamano = 0.25f, volteo = true, estabilizacion = 0f),
        DefPincel("dunas", "Dunas", "Sellos del mapa", forma = "t_duna", tamano = 40f, presionTamano = 0.2f, espaciado = 0.9f, dispersion = 0.5f, azarTamano = 0.3f, volteo = true, estabilizacion = 0f),
        DefPincel("olas", "Olas", "Sellos del mapa", forma = "t_ola", tamano = 28f, presionTamano = 0.2f, espaciado = 1.1f, dispersion = 0.8f, azarTamano = 0.3f, estabilizacion = 0f),
    )
}
