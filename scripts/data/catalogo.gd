class_name Catalogo
extends RefCounted

## Lo que se puede poner en el mapa: biomas (con su color y su símbolo), tipos de reino, colores heráldicos y
## tipos de lugar. Añadir uno es añadir una línea: el shader lee los biomas de una textura de paleta.

# Símbolos que dibuja map_2d.gdshader (el número es el que lee el shader).
const SIMB_NADA := 0
const SIMB_ARBOL := 1
const SIMB_CONIFERA := 2
const SIMB_SELVA := 3
const SIMB_HIERBA := 4
const SIMB_DUNAS := 5
const SIMB_PUNTEADO := 6
const SIMB_PANTANO := 7
const SIMB_GRIETAS := 8
const SIMB_CULTIVOS := 9
const SIMB_CRISTAL := 10
const SIMB_HONGO := 11
const SIMB_HIELO := 12
const SIMB_ARBOL_SECO := 13
const SIMB_MANGLAR := 14
const SIMB_ACACIA := 15
const SIMB_PALMERA := 16
const SIMB_MIXTO := 17
const SIMB_ABEDUL := 18
const SIMB_ARBUSTO := 19
const SIMB_FLOR := 20

## [nombre, color, símbolo, densidad 0..1, tamaño del árbol (1 = normal), segundo color, variación 0..1].
## El segundo color se mezcla árbol a árbol según la variación (otoño: ocres y rojos) o pinta los detalles (flores,
## motas de las setas, tronco del abedul). El índice + 1 es el id en la capa (0 = automático: color por altura).
const BIOMAS = [
	["PRADERA", Color(0.62, 0.74, 0.42), SIMB_HIERBA, 0.45, 1.0, Color(0.5, 0.62, 0.32), 0.2],
	["BOSQUE", Color(0.36, 0.56, 0.29), SIMB_ARBOL, 0.96, 1.0, Color(0.46, 0.62, 0.30), 0.5],
	["BOSQUE VIEJO", Color(0.20, 0.36, 0.21), SIMB_ARBOL, 1.0, 1.35, Color(0.26, 0.42, 0.24), 0.5],
	["TAIGA", Color(0.24, 0.42, 0.34), SIMB_CONIFERA, 0.97, 1.05, Color(0.30, 0.48, 0.38), 0.5],
	["SELVA", Color(0.14, 0.44, 0.22), SIMB_SELVA, 1.0, 1.25, Color(0.24, 0.55, 0.22), 0.6],
	["SABANA", Color(0.80, 0.73, 0.42), SIMB_ACACIA, 0.30, 1.1, Color(0.47, 0.55, 0.26), 0.3],
	["ESTEPA", Color(0.77, 0.75, 0.52), SIMB_PUNTEADO, 0.45, 1.0, Color(0.6, 0.6, 0.4), 0.0],
	["DESIERTO", Color(0.93, 0.80, 0.52), SIMB_DUNAS, 0.75, 1.0, Color(0.85, 0.7, 0.45), 0.0],
	["DESIERTO DE ROCA", Color(0.77, 0.63, 0.48), SIMB_PUNTEADO, 0.95, 1.0, Color(0.6, 0.5, 0.4), 0.0],
	["TIERRAS ROJAS", Color(0.74, 0.43, 0.30), SIMB_GRIETAS, 1.0, 1.0, Color(0.6, 0.3, 0.2), 0.0],
	["TUNDRA", Color(0.66, 0.70, 0.63), SIMB_HIERBA, 0.22, 1.0, Color(0.5, 0.55, 0.5), 0.0],
	["GLACIAR", Color(0.90, 0.95, 0.98), SIMB_HIELO, 1.0, 1.0, Color(0.75, 0.85, 0.95), 0.0],
	["PANTANO", Color(0.42, 0.52, 0.37), SIMB_PANTANO, 0.80, 1.0, Color(0.3, 0.4, 0.3), 0.0],
	["MANGLAR", Color(0.28, 0.46, 0.34), SIMB_MANGLAR, 0.90, 0.9, Color(0.35, 0.52, 0.32), 0.4],
	["BREZAL", Color(0.60, 0.48, 0.58), SIMB_ARBUSTO, 0.55, 0.7, Color(0.70, 0.45, 0.66), 0.6],
	["MONTE BAJO", Color(0.56, 0.60, 0.38), SIMB_ARBUSTO, 0.65, 0.85, Color(0.45, 0.55, 0.30), 0.5],
	["CULTIVOS", Color(0.85, 0.78, 0.48), SIMB_CULTIVOS, 1.0, 1.0, Color(0.7, 0.75, 0.4), 0.0],
	["VOLCÁNICO", Color(0.27, 0.23, 0.23), SIMB_GRIETAS, 1.0, 1.0, Color(0.8, 0.3, 0.1), 0.0],
	["SALAR", Color(0.94, 0.93, 0.89), SIMB_GRIETAS, 1.0, 1.0, Color(0.85, 0.85, 0.8), 0.0],
	["BOSQUE EN FLOR", Color(0.90, 0.62, 0.72), SIMB_FLOR, 0.95, 1.0, Color(1.0, 0.95, 0.96), 0.4],
	["BOSQUE ENCANTADO", Color(0.42, 0.30, 0.62), SIMB_FLOR, 0.95, 1.1, Color(0.62, 0.95, 0.92), 0.4],
	["BOSQUE DE CRISTAL", Color(0.56, 0.82, 0.87), SIMB_CRISTAL, 0.85, 1.0, Color(0.85, 0.7, 0.95), 0.6],
	["BOSQUE DE HONGOS", Color(0.74, 0.30, 0.28), SIMB_HONGO, 0.85, 1.15, Color(0.97, 0.94, 0.86), 0.5],
	["BOSQUE MUERTO", Color(0.50, 0.46, 0.40), SIMB_ARBOL_SECO, 0.75, 1.0, Color(0.4, 0.36, 0.3), 0.0],
	["TIERRAS MALDITAS", Color(0.31, 0.21, 0.31), SIMB_ARBOL_SECO, 0.45, 1.0, Color(0.4, 0.2, 0.35), 0.0],
	["BOSQUE OTOÑAL", Color(0.88, 0.62, 0.22), SIMB_ARBOL, 0.96, 1.0, Color(0.78, 0.26, 0.16), 1.0],
	["BOSQUE DE ABEDULES", Color(0.62, 0.74, 0.36), SIMB_ABEDUL, 0.95, 1.0, Color(0.96, 0.95, 0.90), 0.3],
	["PALMERAL", Color(0.82, 0.76, 0.50), SIMB_PALMERA, 0.60, 1.05, Color(0.30, 0.55, 0.25), 0.3],
	["BOSQUE MIXTO", Color(0.32, 0.52, 0.32), SIMB_MIXTO, 0.96, 1.05, Color(0.24, 0.42, 0.34), 0.6],
]

const TIPOS_REINO = ["REINO", "IMPERIO", "REPÚBLICA", "DUCADO", "CONDADO", "PRINCIPADO", "CALIFATO", "TEOCRACIA", "CLANES", "LIGA", "TRIBUS", "MARCA"]

## Colores heráldicos (esmaltes y algunos más), para los reinos.
const COLORES_REINO = [
	Color("A23B2C"), Color("2F4F7F"), Color("3E6B3A"), Color("6B3F7A"), Color("C69A2B"), Color("3A3A3A"),
	Color("B5652B"), Color("5E8FB5"), Color("8B3A5C"), Color("6E7A33"), Color("2E7F7A"), Color("7A2E2E"),
]

## [id, nombre, zoom mínimo para que se vea el nombre]
const TIPOS_LUGAR = [
	["capital", "CAPITAL", 0.0],
	["ciudad", "CIUDAD", 0.0],
	["pueblo", "PUEBLO", 1.3],
	["aldea", "ALDEA", 2.2],
	["castillo", "CASTILLO", 0.9],
	["puerto", "PUERTO", 1.0],
	["templo", "TEMPLO", 1.3],
	["torre", "TORRE", 1.6],
	["ruinas", "RUINAS", 1.6],
	["mina", "MINA", 2.0],
	["batalla", "BATALLA", 1.3],
]

## Regiones con nombre: [id, nombre, ¿es agua?, bioma que pinta de serie (0 = ninguno)]. El agua se rotula en
## azul y en cursiva; la tierra, en tinta y en cursiva.
const TIPOS_REGION = [
	["bosque", "BOSQUE", false, 2],
	["selva", "SELVA", false, 5],
	["montes", "MONTES", false, 0],
	["sierra", "SIERRA", false, 0],
	["colinas", "COLINAS", false, 0],
	["desierto", "DESIERTO", false, 8],
	["llanura", "LLANURA", false, 1],
	["pantano", "PANTANO", false, 13],
	["valle", "VALLE", false, 0],
	["meseta", "MESETA", false, 0],
	["tierras", "TIERRAS", false, 0],
	["isla", "ISLA", false, 0],
	["peninsula", "PENÍNSULA", false, 0],
	["cabo", "CABO", false, 0],
	["mar", "MAR", true, 0],
	["oceano", "OCÉANO", true, 0],
	["lago", "LAGO", true, 0],
	["bahia", "BAHÍA", true, 0],
	["golfo", "GOLFO", true, 0],
	["estrecho", "ESTRECHO", true, 0],
	["rio", "RÍO", true, 0],
]

static func tipo_region(id: String) -> Array:
	for t in TIPOS_REGION:
		if t[0] == id:
			return t
	return TIPOS_REGION[0]

static func tipo_lugar(id: String) -> Array:
	for t in TIPOS_LUGAR:
		if t[0] == id:
			return t
	return TIPOS_LUGAR[1]

## La paleta de biomas como textura (N+1 × 3, filtro nearest en el shader): fila 0 el color; fila 1 el símbolo
## (r = símbolo / 255), la densidad (g), la variación (b) y el tamaño (a = tamaño / 2); fila 2 el segundo color.
static func textura_biomas() -> ImageTexture:
	var n: int = BIOMAS.size() + 1
	var img := Image.create(n, 3, false, Image.FORMAT_RGBA8)
	for y in 3:
		img.set_pixel(0, y, Color(0, 0, 0, 0))
	for i in BIOMAS.size():
		var b: Array = BIOMAS[i]
		var c: Color = b[1]
		var simb: int = b[2]
		var dens: float = b[3]
		var size: float = b[4]
		var c2: Color = b[5]
		var var_amt: float = b[6]
		img.set_pixel(i + 1, 0, c)
		img.set_pixel(i + 1, 1, Color(float(simb) / 255.0, dens, var_amt, size / 2.0))
		img.set_pixel(i + 1, 2, c2)
	return ImageTexture.create_from_image(img)
