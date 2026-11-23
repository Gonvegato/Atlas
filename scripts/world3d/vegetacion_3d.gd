class_name Vegetacion3D
extends Node3D

## Los árboles (y arbustos, cactus, setas, rocas) del relieve 3D: modelos CC0 del Nature Kit de Kenney
## (assets/kenney/nature), un MultiMeshInstance3D por modelo. Dónde va cada uno sale de la capa de biomas; a qué
## altura, de la del relieve. Se recalcula al entrar en 3D o al cambiar algo estando en 3D.

const NATURE := "res://assets/kenney/nature/"
## Cuántos árboles como mucho según la densidad elegida (Pocos · Normal · Muchos).
const CAPS: Array[int] = [3500, 8000, 16000]
## Puntos candidatos por lado del mapa (cada uno puede llevar una planta o no).
const GRID: int = 300

var density_idx: int = 1
## Cómo pinta el estilo elegido el color de un bioma (la misma cuenta que el 2D); la pone main.
var style_color: Callable = func(c: Color) -> Color: return c
var _mesh_cache: Dictionary = {}
var _veg_shader: Shader = preload("res://shaders/vegetation.gdshader")
var _instances: Array[MultiMeshInstance3D] = []

## nombre del bioma → modelos [archivo, peso], densidad (0..1), escala, tinte, variación de tinte.
const VEG = {
	"PRADERA": {"m": [["flower_yellowA", 2], ["flower_purpleB", 1], ["grass_large", 4], ["plant_bushSmall", 1]], "d": 0.10, "s": 0.8},
	"BOSQUE": {"m": [["tree_default", 3], ["tree_oak", 3], ["tree_detailed", 2], ["tree_simple", 2], ["tree_small", 1]], "d": 0.85, "s": 1.0},
	"BOSQUE VIEJO": {"m": [["tree_fat_darkh", 3], ["tree_oak_dark", 3], ["tree_detailed_dark", 2], ["tree_default_dark", 2]], "d": 0.98, "s": 1.35},
	"TAIGA": {"m": [["tree_pineTallA_detailed", 3], ["tree_pineTallB_detailed", 2], ["tree_pineDefaultA", 2], ["tree_pineRoundC", 2], ["tree_pineTallD", 1]], "d": 0.9, "s": 1.05},
	"SELVA": {"m": [["tree_palmDetailedTall", 2], ["tree_tall_dark", 2], ["tree_fat_darkh", 2], ["tree_palmBend", 1], ["plant_bushLarge", 2]], "d": 1.0, "s": 1.2},
	"SABANA": {"m": [["tree_plateau", 4], ["plant_bush", 2], ["grass_large", 2]], "d": 0.16, "s": 1.1},
	"ESTEPA": {"m": [["grass_large", 3], ["plant_bushSmall", 1], ["rock_largeA", 1]], "d": 0.10, "s": 0.8},
	"DESIERTO": {"m": [["cactus_tall", 2], ["cactus_short", 3], ["rock_largeB", 1]], "d": 0.05, "s": 0.9},
	"DESIERTO DE ROCA": {"m": [["rock_tallA", 2], ["rock_largeC", 2], ["rock_tallD", 1], ["cactus_short", 1]], "d": 0.12, "s": 0.9, "tint": Color(0.95, 0.85, 0.72)},
	"TIERRAS ROJAS": {"m": [["rock_tallB", 2], ["rock_tallE", 2], ["rock_largeD", 1]], "d": 0.14, "s": 1.0, "tint": Color(1.0, 0.6, 0.45)},
	"TUNDRA": {"m": [["plant_bushSmall", 3], ["rock_largeA", 1], ["tree_pineSmallA", 1]], "d": 0.08, "s": 0.8, "tint": Color(0.85, 0.9, 0.85)},
	"GLACIAR": {"m": [["rock_tallF", 1], ["rock_largeE", 1]], "d": 0.03, "s": 1.0, "tint": Color(0.92, 0.97, 1.0)},
	"PANTANO": {"m": [["tree_thin_dark", 2], ["plant_bushSmall", 3], ["stump_old", 1], ["log", 1]], "d": 0.35, "s": 0.9, "tint": Color(0.8, 0.9, 0.75)},
	"MANGLAR": {"m": [["tree_detailed_dark", 2], ["tree_palmBend", 1], ["plant_bushLarge", 1]], "d": 0.7, "s": 0.9},
	"BREZAL": {"m": [["plant_bushSmall", 3], ["flower_purpleA", 2], ["flower_purpleC", 2]], "d": 0.25, "s": 0.8},
	"MONTE BAJO": {"m": [["plant_bush", 3], ["plant_bushLarge", 2], ["tree_small", 1], ["plant_bushDetailed", 2]], "d": 0.45, "s": 0.9},
	"CULTIVOS": {"m": [["grass_large", 1]], "d": 0.0, "s": 1.0},
	"VOLCÁNICO": {"m": [["rock_tallC", 2], ["rock_tallG", 2], ["rock_largeF", 1]], "d": 0.12, "s": 1.0, "tint": Color(0.35, 0.3, 0.3)},
	"SALAR": {"m": [["rock_largeA", 1]], "d": 0.0, "s": 1.0},
	"BOSQUE EN FLOR": {"m": [["tree_default", 3], ["tree_blocks", 2], ["tree_simple", 2]], "d": 0.85, "s": 1.0, "tint": Color(1.0, 0.62, 0.78), "var": 0.12},
	"BOSQUE OTOÑAL": {"m": [["tree_default_fall", 3], ["tree_oak_fall", 3], ["tree_detailed_fall", 2], ["tree_fat_fall", 2], ["tree_tall_fall", 1]], "d": 0.88, "s": 1.05},
	"BOSQUE DE ABEDULES": {"m": [["tree_thin", 3], ["tree_tall", 2], ["tree_cone", 1]], "d": 0.8, "s": 1.0, "tint": Color(0.95, 1.05, 0.8)},
	"PALMERAL": {"m": [["tree_palm", 2], ["tree_palmTall", 2], ["tree_palmShort", 2], ["tree_palmDetailedShort", 1]], "d": 0.55, "s": 1.0},
	"BOSQUE MIXTO": {"m": [["tree_default", 2], ["tree_pineDefaultB", 2], ["tree_oak", 2], ["tree_pineRoundB", 2]], "d": 0.85, "s": 1.05},
	"BOSQUE ENCANTADO": {"m": [["tree_default", 2], ["tree_detailed", 2], ["mushroom_redTall", 1], ["mushroom_tanGroup", 1]], "d": 0.9, "s": 1.05, "tint": Color(0.72, 0.55, 1.0), "var": 0.15},
	"BOSQUE DE CRISTAL": {"m": [["rock_tallH", 2], ["rock_tallI", 2], ["rock_tallJ", 1]], "d": 0.55, "s": 1.0, "tint": Color(0.55, 0.95, 1.1)},
	"BOSQUE DE HONGOS": {"m": [["mushroom_redTall", 2], ["mushroom_tanTall", 2], ["mushroom_redGroup", 1], ["mushroom_tanGroup", 1]], "d": 0.75, "s": 4.5},
	"BOSQUE MUERTO": {"m": [["tree_thin_dark", 2], ["stump_oldTall", 2], ["stump_old", 1], ["log", 1]], "d": 0.5, "s": 1.0, "tint": Color(0.7, 0.62, 0.55)},
	"TIERRAS MALDITAS": {"m": [["tree_thin_dark", 2], ["stump_oldTall", 1], ["rock_tallC", 1]], "d": 0.3, "s": 1.0, "tint": Color(0.55, 0.4, 0.6)},
}

## El primer MeshInstance3D del .glb con su transformación, y materiales que se tiñen con el color de cada instancia.
func _mesh(model: String) -> Array:
	if _mesh_cache.has(model):
		return _mesh_cache[model]
	var res: Array = []
	var sc: PackedScene = load(NATURE + model + ".glb")
	if sc != null:
		var root: Node = sc.instantiate()
		var found: Array = []
		_find_mesh(root, Transform3D.IDENTITY, found)
		if not found.is_empty():
			var mi: MeshInstance3D = found[0]
			var xf: Transform3D = found[1]
			var mesh: Mesh = mi.mesh.duplicate()
			for si in mesh.get_surface_count():
				var m: Material = mi.get_active_material(si)
				var col: Color = Color(0.5, 0.6, 0.4)
				var mname: String = ""
				if m != null:
					mname = m.resource_name
				if m is StandardMaterial3D:
					col = (m as StandardMaterial3D).albedo_color
				var sm := ShaderMaterial.new()
				sm.shader = _veg_shader
				sm.set_shader_parameter("albedo", col)
				# Hojas y hierba: color del bioma. Roca y tierra: teñidas. Lo demás (troncos, flores, setas), tal cual.
				if mname.begins_with("leafs") or mname == "grass":
					sm.set_shader_parameter("part", 1)
					sm.set_shader_parameter("leaf_shade", 0.82 if mname == "leafsDark" else 1.0)
				elif mname == "_defaultMat" or mname == "dirt":
					sm.set_shader_parameter("part", 2)
				elif mname == "woodBark" or mname == "woodBarkDark" or mname == "woodInner":
					sm.set_shader_parameter("part", 3)
				mesh.surface_set_material(si, sm)
			res = [mesh, xf, mesh.get_aabb()]
		root.free()
	_mesh_cache[model] = res
	return res

func _find_mesh(n: Node, xf: Transform3D, out: Array) -> void:
	if not out.is_empty():
		return
	var t: Transform3D = xf
	if n is Node3D:
		t = xf * (n as Node3D).transform
	if n is MeshInstance3D and (n as MeshInstance3D).mesh != null:
		out.append(n)
		out.append(t)
		return
	for c in n.get_children():
		_find_mesh(c, t, out)

func clear() -> void:
	for mmi in _instances:
		mmi.queue_free()
	_instances.clear()

## Planta la vegetación. world: tamaño de la malla (ancho, fondo); height_scale: el del shader del terreno.
func build(biome_img: Image, height_img: Image, sea_level: float, world: Vector2, height_scale: float, seed: int) -> int:
	clear()
	var w: int = biome_img.get_width()
	var h: int = biome_img.get_height()
	var data: PackedByteArray = biome_img.get_data()
	# Qué vegetación lleva cada id de bioma.
	var defs: Array = [null]
	var leaf_a: Array[Color] = [Color.WHITE]
	var leaf_b: Array[Color] = [Color.WHITE]
	var leaf_var: Array[float] = [0.0]
	for b in Catalogo.BIOMAS:
		defs.append(VEG.get(b[0], null))
		leaf_a.append(style_color.call(b[1]))
		leaf_b.append(style_color.call(b[5]))
		leaf_var.append(float(b[6]))
	var rng := RandomNumberGenerator.new()
	rng.seed = seed
	var long_side: int = maxi(w, h)
	var step: float = float(long_side) / float(GRID)
	var step_world: float = step / float(long_side) * maxf(world.x, world.y)
	# Candidatos: [x, y, id de bioma]
	var cand: Array[Vector3] = []
	var gy: float = 0.0
	while gy < h:
		var gx: float = 0.0
		while gx < w:
			var x: int = clampi(int(gx + rng.randf() * step), 0, w - 1)
			var y: int = clampi(int(gy + rng.randf() * step), 0, h - 1)
			var id: int = data[y * w + x]
			if id > 0 and id < defs.size() and defs[id] != null:
				var d: Dictionary = defs[id]
				if rng.randf() < float(d["d"]):
					cand.append(Vector3(x, y, id))
			gx += step
		gy += step
	var cap: int = CAPS[density_idx]
	if cand.size() > cap:
		# Submuestreo al azar, sin preferir ninguna zona.
		for i in range(cand.size() - 1, 0, -1):
			var j: int = rng.randi_range(0, i)
			var tmp: Vector3 = cand[i]
			cand[i] = cand[j]
			cand[j] = tmp
		cand.resize(cap)
	# Agrupar por modelo.
	var per_model: Dictionary = {}
	for c in cand:
		var id: int = int(c.z)
		var d: Dictionary = defs[id]
		var hv: float = height_img.get_pixel(int(c.x), int(c.y)).r
		if hv <= sea_level + 0.003:
			continue
		var model: String = _pick(d["m"], rng)
		if not per_model.has(model):
			per_model[model] = []
		var arr: Array = per_model[model]
		arr.append([c, hv, d])
	var total: int = 0
	for model in per_model.keys():
		var info: Array = _mesh(model)
		if info.is_empty():
			continue
		var mesh: Mesh = info[0]
		var base_xf: Transform3D = info[1]
		var aabb: AABB = info[2]
		var foot: float = maxf(maxf(aabb.size.x, aabb.size.z), 0.05)
		var items: Array = per_model[model]
		var mm := MultiMesh.new()
		mm.transform_format = MultiMesh.TRANSFORM_3D
		mm.use_colors = true
		mm.use_custom_data = true
		mm.mesh = mesh
		mm.instance_count = items.size()
		for i in items.size():
			var it: Array = items[i]
			var c: Vector3 = it[0]
			var hv: float = it[1]
			var d: Dictionary = it[2]
			var u: float = (c.x + 0.5) / float(w)
			var v: float = (c.y + 0.5) / float(h)
			var pos := Vector3((u - 0.5) * world.x, maxf(hv - sea_level, 0.0) * height_scale, (v - 0.5) * world.y)
			var s: float = step_world * 1.7 / foot * float(d["s"]) * rng.randf_range(0.75, 1.25)
			var basis := Basis(Vector3.UP, rng.randf() * TAU).scaled(Vector3(s, s * rng.randf_range(0.9, 1.15), s))
			mm.set_instance_transform(i, Transform3D(basis, pos) * base_xf)
			# Hojas: el color del bioma mezclado con el segundo según su variación (otoño: ocres y rojos), y un poco de
			# luz distinta por árbol. Se pasa a lineal porque el shader lo usa como albedo.
			var bid: int = int(c.z)
			var leaf: Color = leaf_a[bid].lerp(leaf_b[bid], rng.randf() * leaf_var[bid])
			var k: float = rng.randf_range(0.88, 1.12)
			leaf = Color(leaf.r * k, leaf.g * k, leaf.b * k).srgb_to_linear()
			mm.set_instance_color(i, Color(leaf.r, leaf.g, leaf.b, 1.0))
			var tint: Color = d.get("tint", Color.WHITE)
			mm.set_instance_custom_data(i, Color(tint.r, tint.g, tint.b, 1.0))
		var mmi := MultiMeshInstance3D.new()
		mmi.multimesh = mm
		mmi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		add_child(mmi)
		_instances.append(mmi)
		total += items.size()
	return total

func _pick(models: Array, rng: RandomNumberGenerator) -> String:
	var sum: float = 0.0
	for m in models:
		sum += float(m[1])
	var r: float = rng.randf() * sum
	for m in models:
		r -= float(m[1])
		if r <= 0.0:
			return m[0]
	return models[0][0]
