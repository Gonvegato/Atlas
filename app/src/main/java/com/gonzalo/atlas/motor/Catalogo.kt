package com.gonzalo.atlas.motor

/** Lo que se pone sobre el dibujo: tipos de territorio, de lugar y de ruta, y los colores. */
object Catalogo {
    val TIPOS_TERRITORIO = listOf("Imperio", "Reino", "Principado", "Ducado", "Condado", "Marca", "Provincia", "República", "Ciudad-estado",
        "Tribu", "Clan", "Región", "Tierra salvaje", "Otro")

    /** Colores de los territorios (tintas de mapa antiguo). */
    val COLORES = intArrayOf(
        0xFFB5523B.toInt(), 0xFF3E6A8A.toInt(), 0xFF6E8B3D.toInt(), 0xFF8A5A9E.toInt(), 0xFFC28A2E.toInt(), 0xFF2F7F74.toInt(),
        0xFF9C3D5C.toInt(), 0xFF5A5F9E.toInt(), 0xFF8C6D3F.toInt(), 0xFF4E8A55.toInt(), 0xFFB0663A.toInt(), 0xFF6B4E8A.toInt(),
        0xFF3B3B3B.toInt(), 0xFFA88B2C.toInt(), 0xFF4A7FA8.toInt(), 0xFF8F2F2F.toInt(),
    )

    class TipoLugar(val clave: String, val nombre: String, val tamano: Int)

    val LUGARES = listOf(
        TipoLugar("capital", "Capital", 7), TipoLugar("ciudad", "Ciudad", 5), TipoLugar("villa", "Villa", 3), TipoLugar("aldea", "Aldea", 2),
        TipoLugar("puerto", "Puerto", 4), TipoLugar("castillo", "Castillo", 3), TipoLugar("fortaleza", "Fortaleza", 4), TipoLugar("templo", "Templo", 3),
        TipoLugar("monasterio", "Monasterio", 2), TipoLugar("mina", "Mina", 2), TipoLugar("torre", "Torre", 2), TipoLugar("ruina", "Ruinas", 2),
        TipoLugar("cueva", "Cueva", 1), TipoLugar("faro", "Faro", 1), TipoLugar("campamento", "Campamento", 1), TipoLugar("batalla", "Batalla", 2),
        TipoLugar("punto", "Lugar", 1),
    )
    fun lugar(clave: String) = LUGARES.firstOrNull { it.clave == clave } ?: LUGARES.last()

    class TipoRuta(val clave: String, val nombre: String, val ancho: Float)

    val RUTAS = listOf(TipoRuta("camino", "Camino", 2f), TipoRuta("calzada", "Calzada", 3f), TipoRuta("sendero", "Sendero", 1.5f),
        TipoRuta("maritima", "Ruta por mar", 2f), TipoRuta("frontera", "Frontera", 2.5f), TipoRuta("viaje", "Viaje (flechas)", 2.5f))
    fun ruta(clave: String) = RUTAS.firstOrNull { it.clave == clave } ?: RUTAS[0]

    /** Papeles de fondo. */
    val PAPELES = listOf("Pergamino" to 0xFFF1E6C8.toInt(), "Papel" to 0xFFFBF8F0.toInt(), "Crudo" to 0xFFE9DDBF.toInt(),
        "Viejo" to 0xFFDCC9A0.toInt(), "Gris" to 0xFFE3E2DE.toInt(), "Noche" to 0xFF22262B.toInt(), "Transparente" to 0x00000000)
}
