package com.gonzalo.atlas.motor

/** Lo que se puede pintar y poner en un mapa: biomas, zonas, lugares, caminos, reinos y accidentes. */
object Catalogo {
    class Bioma(val id: Int, val clave: String, val nombre: String, val color: Int, val textura: Textura, val esAgua: Boolean = false, val desc: String = "")

    /** Cómo se dibuja el grano de cada bioma (a pincel, sin árboles: los árboles los dibujas tú). */
    enum class Textura { LISA, HIERBA, BOSQUE, SELVA, CONIFERAS, ARENA, DUNAS, ROCA, NIEVE, PANTANO, GRIETAS, CAMPOS, LAVA, CORAL, AGUA, SAL }

    const val AUTO = 0

    val BIOMAS: List<Bioma> = listOf(
        Bioma(1, "pradera", "Pradera", 0xFFAFBB72.toInt(), Textura.HIERBA, desc = "Hierba y llanura templada"),
        Bioma(2, "bosque", "Bosque", 0xFF7D9453.toInt(), Textura.BOSQUE, desc = "Bosque templado (el color; los árboles los pones tú)"),
        Bioma(3, "selva", "Selva", 0xFF557A45.toInt(), Textura.SELVA, desc = "Bosque cerrado y húmedo"),
        Bioma(4, "taiga", "Taiga", 0xFF667E62.toInt(), Textura.CONIFERAS, desc = "Bosque frío de coníferas"),
        Bioma(5, "estepa", "Estepa", 0xFFC6BC86.toInt(), Textura.HIERBA, desc = "Llanura seca"),
        Bioma(6, "sabana", "Sabana", 0xFFD3BE72.toInt(), Textura.HIERBA, desc = "Hierba alta y calor"),
        Bioma(7, "desierto", "Desierto", 0xFFE6CD95.toInt(), Textura.ARENA, desc = "Arena y piedra"),
        Bioma(8, "dunas", "Dunas", 0xFFEAC886.toInt(), Textura.DUNAS, desc = "Mar de arena"),
        Bioma(9, "tundra", "Tundra", 0xFFB0AE94.toInt(), Textura.HIERBA, desc = "Llanura helada"),
        Bioma(10, "nieve", "Nieve y glaciar", 0xFFF2F1EB.toInt(), Textura.NIEVE, desc = "Cumbres y hielos perpetuos"),
        Bioma(11, "pantano", "Pantano", 0xFF84906A.toInt(), Textura.PANTANO, desc = "Ciénaga, marisma"),
        Bioma(12, "roca", "Roca y páramo", 0xFFA39A88.toInt(), Textura.ROCA, desc = "Piedra desnuda"),
        Bioma(13, "basalto", "Tierra volcánica", 0xFF615955.toInt(), Textura.GRIETAS, desc = "Basalto negro"),
        Bioma(14, "ceniza", "Ceniza", 0xFF8E8580.toInt(), Textura.ROCA, desc = "Laderas de ceniza"),
        Bioma(15, "lava", "Lava", 0xFFD65A2C.toInt(), Textura.LAVA, desc = "Ríos y lagos de lava"),
        Bioma(16, "salar", "Salar", 0xFFEFEADF.toInt(), Textura.SAL, desc = "Llanura de sal"),
        Bioma(17, "cultivos", "Tierras de cultivo", 0xFFCDBF78.toInt(), Textura.CAMPOS, desc = "Campos y huertas"),
        Bioma(18, "arrecife", "Arrecife de coral", 0xFFE79A82.toInt(), Textura.CORAL, desc = "Coral en aguas someras"),
        Bioma(19, "hielo", "Banquisa", 0xFFE6EFF0.toInt(), Textura.NIEVE, desc = "Mar helado"),
        Bioma(20, "agua", "Agua dulce", 0xFF8FB3B8.toInt(), Textura.AGUA, esAgua = true, desc = "Lagos y lagunas a cualquier altura"),
        Bioma(21, "playa", "Playa", 0xFFEBD9A9.toInt(), Textura.ARENA, desc = "Arena de costa"),
        Bioma(22, "yermo", "Yermo rojo", 0xFFBB7F55.toInt(), Textura.GRIETAS, desc = "Tierras baldías, cañones rojos"),
        Bioma(23, "brezal", "Brezal", 0xFF97806F.toInt(), Textura.HIERBA, desc = "Brezo y monte bajo"),
    )
    private val porId = arrayOfNulls<Bioma>(256).also { a -> BIOMAS.forEach { a[it.id] = it } }
    fun bioma(id: Int): Bioma? = porId[id and 255]
    fun bioma(clave: String): Bioma = BIOMAS.first { it.clave == clave }
    val B_PRADERA = 1; val B_BOSQUE = 2; val B_SELVA = 3; val B_TAIGA = 4; val B_ESTEPA = 5; val B_SABANA = 6; val B_DESIERTO = 7
    val B_DUNAS = 8; val B_TUNDRA = 9; val B_NIEVE = 10; val B_PANTANO = 11; val B_ROCA = 12; val B_BASALTO = 13; val B_CENIZA = 14
    val B_LAVA = 15; val B_SALAR = 16; val B_CULTIVOS = 17; val B_ARRECIFE = 18; val B_HIELO = 19; val B_AGUA = 20; val B_PLAYA = 21
    val B_YERMO = 22; val B_BREZAL = 23

    // ---------------------------------------------------------------- zonas (se dibujan y se llenan solas)

    class Zona(val id: Int, val clave: String, val nombre: String, val desc: String)

    val ZONAS = listOf(
        Zona(1, "ciudad", "Ciudad", "Casas y calles: cuanto más grande la pintes, más ciudad"),
        Zona(2, "nobles", "Barrio noble", "Mansiones con patio y jardín"),
        Zona(3, "templos", "Templos", "Un templo o, si pintas mucho, un complejo de templos"),
        Zona(4, "minas", "Minas", "Bocaminas, escombreras y galerías"),
        Zona(5, "granjas", "Granjas", "Campos en parcelas con sus casas"),
        Zona(6, "fortaleza", "Fortaleza", "Murallas, torres y cuarteles"),
        Zona(7, "puerto", "Puerto", "Muelles, almacenes y atarazanas"),
        Zona(8, "ruinas", "Ruinas", "Muros caídos y piedras"),
    )
    fun zona(id: Int) = ZONAS.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- lugares

    class TipoLugar(val clave: String, val nombre: String, val tamano: Int, val desc: String)

    val LUGARES = listOf(
        TipoLugar("capital", "Capital", 7, "La sede de un reino"),
        TipoLugar("ciudad", "Ciudad", 5, ""),
        TipoLugar("villa", "Villa", 3, "Pueblo grande"),
        TipoLugar("aldea", "Aldea", 2, ""),
        TipoLugar("puerto", "Puerto", 4, ""),
        TipoLugar("castillo", "Castillo", 3, ""),
        TipoLugar("fortaleza", "Fortaleza", 4, ""),
        TipoLugar("templo", "Templo", 3, ""),
        TipoLugar("monasterio", "Monasterio", 2, ""),
        TipoLugar("mina", "Mina", 2, ""),
        TipoLugar("torre", "Torre", 2, ""),
        TipoLugar("ruina", "Ruinas", 2, ""),
        TipoLugar("cueva", "Cueva", 1, ""),
        TipoLugar("faro", "Faro", 1, ""),
        TipoLugar("campamento", "Campamento", 1, ""),
        TipoLugar("punto", "Lugar", 1, "Cualquier otro sitio con nombre"),
    )
    fun lugar(clave: String) = LUGARES.firstOrNull { it.clave == clave } ?: LUGARES.last()

    // ---------------------------------------------------------------- caminos

    /** [ancho] en píxeles del mapa; [coste]: lo que se tarda por él respecto a ir campo a través. */
    class TipoCamino(val clave: String, val nombre: String, val ancho: Float, val coste: Float, val desc: String)

    val CAMINOS = listOf(
        TipoCamino("sendero", "Sendero", 1.2f, 0.85f, "De tierra, a pie o a caballo"),
        TipoCamino("camino", "Camino", 1.8f, 0.75f, "Carretero, de grava"),
        TipoCamino("calzada", "Calzada", 2.4f, 0.65f, "Empedrada"),
        TipoCamino("imperial", "Vía imperial", 3.2f, 0.55f, "Losas, puentes y postas"),
        TipoCamino("maritima", "Ruta marítima", 1.4f, 0.4f, "Por mar (línea de puntos)"),
    )
    fun camino(clave: String) = CAMINOS.firstOrNull { it.clave == clave } ?: CAMINOS[1]

    // ---------------------------------------------------------------- reinos

    val TIPOS_REINO = listOf("Imperio", "Reino", "Principado", "Ducado", "Condado", "República", "Ciudad-estado", "Tribu", "Provincia", "Otro")

    /** Colores de los reinos (tintas de mapa antiguo). */
    val COLORES_REINO = intArrayOf(
        0xFFB5523B.toInt(), 0xFF3E6A8A.toInt(), 0xFF6E8B3D.toInt(), 0xFF8A5A9E.toInt(), 0xFFC28A2E.toInt(), 0xFF2F7F74.toInt(),
        0xFF9C3D5C.toInt(), 0xFF5A5F9E.toInt(), 0xFF8C6D3F.toInt(), 0xFF4E8A55.toInt(), 0xFFB0663A.toInt(), 0xFF6B4E8A.toInt(),
    )

    // ---------------------------------------------------------------- accidentes geográficos

    /** [trazo]: se dibuja arrastrando (fiordo, cordillera…); si no, se pone con un toque (y su tamaño es el del pincel). */
    class Accidente(val clave: String, val nombre: String, val trazo: Boolean, val desc: String)

    val ACCIDENTES = listOf(
        Accidente("volcan", "Volcán", false, "Cono con cráter, ceniza y coladas de lava"),
        Accidente("caldera", "Caldera", false, "Volcán hundido con lago dentro"),
        Accidente("crater", "Cráter", false, "Impacto con borde alzado"),
        Accidente("isla", "Isla", false, "Una isla con playa"),
        Accidente("archipielago", "Archipiélago", false, "Un rosario de islas"),
        Accidente("atolon", "Atolón", false, "Anillo de coral con laguna"),
        Accidente("arrecife", "Arrecife de coral", true, "Coral bajo el agua, junto a la costa"),
        Accidente("lago", "Lago", false, "Lago de agua dulce"),
        Accidente("oasis", "Oasis", false, "Agua y verde en el desierto"),
        Accidente("geiser", "Géiseres", false, "Campo de fumarolas y aguas termales"),
        Accidente("meseta", "Meseta", false, "Llanura alta de bordes cortados"),
        Accidente("glaciar", "Glaciar", true, "Lengua de hielo ladera abajo"),
        Accidente("cordillera", "Cordillera", true, "Una sierra a lo largo del trazo"),
        Accidente("fiordo", "Fiordo", true, "Brazo de mar entre paredes"),
        Accidente("delta", "Delta", false, "Desembocadura en abanico, con marismas"),
        Accidente("sima", "Sima", false, "Hondonada sin fondo a la vista"),
        Accidente("dunas", "Mar de dunas", false, "Dunas en ondas"),
        Accidente("salar", "Salar", false, "Llanura de sal en una cuenca"),
    )
    fun accidente(clave: String) = ACCIDENTES.first { it.clave == clave }
}
