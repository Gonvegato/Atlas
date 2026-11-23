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

## [nombre, color (saturado, el de «Fantasía»), símbolo, densidad 0..1]. El índice + 1 es el id en la capa
## (0 = automático: el color sale de la altura).
const BIOMAS = [
	["PRADERA", Color(0.62, 0.74, 0.42), SIMB_HIERBA, 0.45],
	["BOSQUE", Color(0.38, 0.58, 0.31), SIMB_ARBOL, 0.92],
	["BOSQUE VIEJO", Color(0.23, 0.40, 0.24), SIMB_ARBOL, 1.0],
	["TAIGA", Color(0.30, 0.46, 0.38), SIMB_CONIFERA, 0.92],
	["SELVA", Color(0.17, 0.47, 0.25), SIMB_SELVA, 1.0],
	["SABANA", Color(0.80, 0.73, 0.42), SIMB_ACACIA, 0.30],
	["ESTEPA", Color(0.77, 0.75, 0.52), SIMB_PUNTEADO, 0.45],
	["DESIERTO", Color(0.93, 0.80, 0.52), SIMB_DUNAS, 0.75],
	["DESIERTO DE ROCA", Color(0.77, 0.63, 0.48), SIMB_PUNTEADO, 0.95],
	["TIERRAS ROJAS", Color(0.74, 0.43, 0.30), SIMB_GRIETAS, 1.0],
	["TUNDRA", Color(0.66, 0.70, 0.63), SIMB_HIERBA, 0.22],
	["GLACIAR", Color(0.90, 0.95, 0.98), SIMB_HIELO, 1.0],
	["PANTANO", Color(0.42, 0.52, 0.37), SIMB_PANTANO, 0.80],
	["MANGLAR", Color(0.30, 0.46, 0.35), SIMB_MANGLAR, 0.85],
	["BREZAL", Color(0.60, 0.48, 0.58), SIMB_PUNTEADO, 0.70],
	["MONTE BAJO", Color(0.58, 0.60, 0.40), SIMB_ARBOL, 0.32],
	["CULTIVOS", Color(0.85, 0.78, 0.48), SIMB_CULTIVOS, 1.0],
	["VOLCÁNICO", Color(0.27, 0.23, 0.23), SIMB_GRIETAS, 1.0],
	["SALAR", Color(0.94, 0.93, 0.89), SIMB_GRIETAS, 1.0],
	["BOSQUE EN FLOR", Color(0.91, 0.66, 0.74), SIMB_ARBOL, 0.9],
	["BOSQUE ENCANTADO", Color(0.45, 0.33, 0.60), SIMB_ARBOL, 0.95],
	["BOSQUE DE CRISTAL", Color(0.56, 0.82, 0.87), SIMB_CRISTAL, 0.8],
	["BOSQUE DE HONGOS", Color(0.71, 0.47, 0.53), SIMB_HONGO, 0.85],
	["BOSQUE MUERTO", Color(0.50, 0.46, 0.40), SIMB_ARBOL_SECO, 0.7],
	["TIERRAS MALDITAS", Color(0.31, 0.21, 0.31), SIMB_ARBOL_SECO, 0.45],
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

static func tipo_lugar(id: String) -> Array:
	for t in TIPOS_LUGAR:
		if t[0] == id:
			return t
	return TIPOS_LUGAR[1]

## La paleta de biomas como textura (N+1 × 2, filtro nearest en el shader): fila 0 el color; fila 1 el símbolo
## (r = símbolo / 255) y la densidad (g).
static func textura_biomas() -> ImageTexture:
	var n: int = BIOMAS.size() + 1
	var img := Image.create(n, 2, false, Image.FORMAT_RGBA8)
	img.set_pixel(0, 0, Color(0, 0, 0, 0))
	img.set_pixel(0, 1, Color(0, 0, 0, 0))
	for i in BIOMAS.size():
		var b: Array = BIOMAS[i]
		var c: Color = b[1]
		var simb: int = b[2]
		var dens: float = b[3]
		img.set_pixel(i + 1, 0, c)
		img.set_pixel(i + 1, 1, Color(float(simb) / 255.0, dens, 0.0, 1.0))
	return ImageTexture.create_from_image(img)
