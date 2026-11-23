class_name Vegetacion3D
extends Node3D

## Los árboles (y arbustos, cactus, setas, rocas) del relieve 3D: modelos CC0 del Nature Kit de Kenney
## (assets/kenney/nature), un MultiMeshInstance3D por modelo. Dónde va cada uno sale de la capa de biomas; a qué
## altura, de la del relieve. Se recalcula al entrar en 3D o al cambiar algo estando en 3D.

const NATURE := "res://assets/kenney/nature/"
## Prefijos de los modelos: sin prefijo, Kenney Nature Kit · "y:" KayKit (árboles sueltos, rocas, plantas de agua) ·
## "q:" Quaternius Stylized Nature MegaKit (sotobosque y árboles protagonistas, con textura).
const PACKS = {"y": "res://assets/kaykit/nature/%s.gltf", "q": "res://assets/quaternius/%s.gltf", "n": "res://assets/kaykit/buildings/neutral/%s.gltf"}
## Los modelos pesados (más de 1.500 triángulos, los árboles de Quaternius) van con tope por modelo.
const HEAVY_TRIS: int = 1500
const HEAVY_CAPS: Array[int] = [60, 160, 320]
## Cuántos árboles como mucho según la densidad elegida (Pocos · Normal · Muchos).
const CAPS: Array[int] = [3500, 8000, 16000]
## Puntos candidatos por lado del mapa (cada uno puede llevar una planta o no).
const GRID: int = 300

var density_idx: int = 1
## Cómo pinta el estilo elegido el color de un bioma (la misma cuenta que el 2D); la pone main.
var style_color: Callable = func(c: Color) -> Color: return c
var _mesh_cache: Dictionary = {}
var _veg_shader: Shader = preload("res://shaders/vegetation.gdshader")
var _veg_tex_shader: Shader = preload("res://shaders/vegetation_tex.gdshader")
var _instances: Array[MultiMeshInstance3D] = []

## nombre del bioma → modelos [archivo, peso, escala propia opcional], densidad (0..1), escala, tinte de rocas.
## Mezcla Kenney (ligeros, la masa del bosque), KayKit (árboles sueltos, plantas de agua) y Quaternius (sotobosque y
## algún árbol protagonista, con tope: son 20-50 veces más pesados).
const VEG = {
	"PRADERA": {"m": [["q:Flower_3_Group", 2, 0.5], ["q:Flower_4_Group", 2, 0.5], ["q:Grass_Common_Tall", 3, 0.45], ["q:Clover_1", 2, 0.4], ["flower_yellowA", 1, 0.7], ["y:tree_single_A", 0.3]], "d": 0.16, "s": 0.8},
	"BOSQUE": {"m": [["tree_default", 3], ["tree_oak", 3], ["tree_detailed", 2], ["tree_simple", 1], ["y:tree_single_B", 2], ["y:tree_single_A", 1], ["q:CommonTree_3", 0.4, 1.3], ["q:CommonTree_5", 0.4, 1.3], ["q:Fern_1", 1.2, 0.45], ["q:Bush_Common", 0.6, 0.6], ["q:Mushroom_Common", 0.2, 0.3]], "d": 0.9, "s": 1.0},
	"BOSQUE VIEJO": {"m": [["tree_fat_darkh", 3], ["tree_oak_dark", 3], ["tree_detailed_dark", 2], ["q:CommonTree_3", 0.8, 1.5], ["q:Fern_1", 2, 0.5], ["q:Mushroom_Laetiporus", 0.3, 0.4], ["stump_old", 0.3, 0.6], ["log", 0.3, 0.7]], "d": 0.98, "s": 1.35},
	"TAIGA": {"m": [["tree_pineTallA_detailed", 3], ["tree_pineTallB_detailed", 2], ["tree_pineDefaultA", 2], ["tree_pineRoundC", 2], ["q:Pine_5", 0.5, 1.2], ["q:Pine_4", 0.3, 1.2], ["q:Fern_1", 0.6, 0.4]], "d": 0.9, "s": 1.05},
	"SELVA": {"m": [["tree_palmDetailedTall", 2], ["tree_tall_dark", 2], ["tree_fat_darkh", 2], ["tree_palmBend", 1], ["q:Plant_1_Big", 1.5, 0.6], ["q:Plant_7_Big", 1.5, 0.6], ["q:Fern_1", 1, 0.55]], "d": 1.0, "s": 1.2},
	"SABANA": {"m": [["tree_plateau", 4], ["plant_bush", 2], ["q:Grass_Wispy_Tall", 3, 0.5]], "d": 0.18, "s": 1.1},
	"ESTEPA": {"m": [["q:Grass_Wispy_Tall", 3, 0.5], ["q:Grass_Common_Tall", 2, 0.45], ["plant_bushSmall", 1]], "d": 0.14, "s": 0.8},
	"DESIERTO": {"m": [["cactus_tall", 2], ["cactus_short", 3], ["rock_largeB", 1]], "d": 0.05, "s": 0.9},
	"DESIERTO DE ROCA": {"m": [["rock_tallA", 2], ["rock_largeC", 2], ["q:Rock_Medium_1", 1.5, 0.7], ["q:Rock_Medium_3", 1.5, 0.7], ["cactus_short", 1]], "d": 0.12, "s": 0.9, "tint": Color(0.95, 0.85, 0.72)},
	"TIERRAS ROJAS": {"m": [["rock_tallB", 2], ["rock_tallE", 2], ["q:Rock_Medium_2", 1.5, 0.7], ["rock_largeD", 1]], "d": 0.14, "s": 1.0, "tint": Color(1.0, 0.6, 0.45)},
	"TUNDRA": {"m": [["plant_bushSmall", 3], ["rock_largeA", 1], ["tree_pineSmallA", 1], ["q:Grass_Wispy_Tall", 1, 0.4]], "d": 0.08, "s": 0.8, "tint": Color(0.85, 0.9, 0.85)},
	"GLACIAR": {"m": [["rock_tallF", 1], ["rock_largeE", 1]], "d": 0.03, "s": 1.0, "tint": Color(0.92, 0.97, 1.0)},
	"PANTANO": {"m": [["tree_thin_dark", 2], ["y:waterplant_B", 2, 0.6], ["y:waterplant_C", 2, 0.6], ["q:Fern_1", 1, 0.45], ["stump_old", 1], ["log", 1]], "d": 0.38, "s": 0.9},
	"MANGLAR": {"m": [["tree_detailed_dark", 2], ["tree_palmBend", 1], ["y:waterplant_C", 1, 0.6], ["plant_bushLarge", 1]], "d": 0.7, "s": 0.9},
	"BREZAL": {"m": [["plant_bushSmall", 3], ["flower_purpleA", 2], ["flower_purpleC", 2], ["q:Bush_Common_Flowers", 1.5, 0.55]], "d": 0.25, "s": 0.8},
	"MONTE BAJO": {"m": [["plant_bush", 3], ["plant_bushLarge", 2], ["tree_small", 1], ["q:Bush_Common", 2, 0.6], ["q:Bush_Common_Flowers", 1, 0.6]], "d": 0.45, "s": 0.9},
	"CULTIVOS": {"m": [["q:Grass_Common_Tall", 1, 0.4]], "d": 0.0, "s": 1.0},
	"VOLCÁNICO": {"m": [["rock_tallC", 2], ["rock_tallG", 2], ["q:Rock_Medium_2", 1, 0.7], ["rock_largeF", 1]], "d": 0.12, "s": 1.0, "tint": Color(0.35, 0.3, 0.3)},
	"SALAR": {"m": [["rock_largeA", 1]], "d": 0.0, "s": 1.0},
	"BOSQUE EN FLOR": {"m": [["tree_default", 3], ["tree_blocks", 2], ["tree_simple", 2], ["y:tree_single_B", 1], ["q:Flower_4_Group", 1, 0.45]], "d": 0.85, "s": 1.0},
	"BOSQUE OTOÑAL": {"m": [["tree_oak_fall", 3], ["tree_simple_fall", 2], ["tree_detailed_fall", 2], ["tree_fat_fall", 2], ["q:CommonTree_5", 0.5, 1.3], ["q:Fern_1", 0.8, 0.45]], "d": 0.88, "s": 1.05},
	"BOSQUE DE ABEDULES": {"m": [["tree_thin", 3], ["tree_tall", 2], ["tree_cone", 1], ["q:Grass_Common_Tall", 1, 0.4]], "d": 0.8, "s": 1.0},
	"PALMERAL": {"m": [["tree_palm", 2], ["tree_palmTall", 2], ["tree_palmShort", 2], ["tree_palmDetailedShort", 1], ["q:Plant_7_Big", 1, 0.5]], "d": 0.55, "s": 1.0},
	"BOSQUE MIXTO": {"m": [["tree_default", 2], ["tree_pineDefaultB", 2], ["tree_oak", 2], ["tree_pineRoundB", 2], ["y:tree_single_A", 1], ["q:Pine_5", 0.3, 1.2], ["q:Fern_1", 0.8, 0.45]], "d": 0.85, "s": 1.05},
	"BOSQUE ENCANTADO": {"m": [["q:TwistedTree_1", 1, 1.5], ["q:TwistedTree_2", 1, 1.5], ["q:TwistedTree_3", 1, 1.5], ["tree_default", 2], ["tree_detailed", 2], ["mushroom_redTall", 1, 1.6], ["q:Mushroom_Laetiporus", 0.6, 0.5], ["q:Fern_1", 1, 0.5]], "d": 0.9, "s": 1.05},
	"BOSQUE DE CRISTAL": {"m": [["rock_tallH", 2], ["rock_tallI", 2], ["rock_tallJ", 1]], "d": 0.55, "s": 1.0, "tint": Color(0.55, 0.95, 1.1)},
	"BOSQUE DE HONGOS": {"m": [["mushroom_redTall", 2], ["mushroom_tanTall", 2], ["mushroom_redGroup", 1], ["q:Mushroom_Common", 2, 1.4], ["q:Mushroom_Laetiporus", 1, 1.0]], "d": 0.75, "s": 4.5},
	"BOSQUE MUERTO": {"m": [["q:DeadTree_1", 1, 1.4], ["q:DeadTree_2", 1, 1.4], ["q:DeadTree_3", 1, 1.4], ["tree_thin_dark", 2], ["stump_oldTall", 2], ["log", 1]], "d": 0.5, "s": 1.0, "tint": Color(0.7, 0.62, 0.55)},
	"TIERRAS MALDITAS": {"m": [["q:DeadTree_1", 1, 1.2], ["tree_thin_dark", 2], ["stump_oldTall", 1], ["rock_tallC", 1]], "d": 0.3, "s": 1.0, "tint": Color(0.55, 0.4, 0.6)},
}

## El primer MeshInstance3D del .glb con su transformación, y materiales que se tiñen con el color de cada instancia.
func _mesh(model: String) -> Array:
	if _mesh_cache.has(model):
		return _mesh_cache[model]
	var res: Array = []
	var path: String = NATURE + model + ".glb"
	if model.length() > 2 and model[1] == ":":
		path = PACKS[model[0]] % model.substr(2)
	var sc: PackedScene = load(path)
	if sc != null:
		var root: Node = sc.instantiate()
		var found: Array = []
		_find_mesh(root, Transform3D.IDENTITY, found)
		if not found.is_empty():
			var mi: MeshInstance3D = found[0]
			var xf: Transform3D = found[1]
			var mesh: Mesh = mi.mesh.duplicate()
			var tris: int = 0
			for si in mesh.get_surface_count():
				var arr: Array = mesh.surface_get_arrays(si)
				var idx: PackedInt32Array = arr[Mesh.ARRAY_INDEX]
				tris += idx.size() / 3 if idx.size() > 0 else (arr[Mesh.ARRAY_VERTEX] as PackedVector3Array).size() / 3
				var m: Material = mi.get_active_material(si)
				var col: Color = Color(0.5, 0.6, 0.4)
				var mname: String = ""
				var tex: Texture2D = null
				var scissor: float = 0.0
				if m != null:
					mname = m.resource_name
				if m is StandardMaterial3D:
					var std: StandardMaterial3D = m
					col = std.albedo_color
					tex = std.albedo_texture
					if std.transparency == BaseMaterial3D.TRANSPARENCY_ALPHA_SCISSOR:
						scissor = maxf(std.alpha_scissor_threshold, 0.35)
				var sm := ShaderMaterial.new()
				if tex != null:
					sm.shader = _veg_tex_shader
					sm.set_shader_parameter("tex", tex)
					sm.set_shader_parameter("scissor", scissor if scissor > 0.0 else 0.001)
					sm.set_shader_parameter("albedo", col)
					if mname.begins_with("Leaves") or mname.begins_with("Leaf") or mname == "Grass":
						sm.set_shader_parameter("part", 1)
					elif mname.begins_with("Rock"):
						sm.set_shader_parameter("part", 2)
					mesh.surface_set_material(si, sm)
					continue
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
			res = [mesh, xf, mesh.get_aabb(), tris]
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
## [exclude]: zonas sin plantas (las ciudades), Vector3(u, v, radio en uv).
## Fundido entre biomas (píxeles del mapa): cada planta toma el bioma de un punto cercano al azar, así que en
## la linde los bosques se aclaran y las especies de los dos lados se mezclan.
var blend_px: float = 0.0

func build(biome_img: Image, height_img: Image, sea_level: float, world: Vector2, height_scale: float, seed: int, exclude: Array = []) -> int:
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
			var bx: int = x
			var by: int = y
			if blend_px >= 1.0:
				bx = clampi(x + int(rng.randf_range(-1.0, 1.0) * blend_px), 0, w - 1)
				by = clampi(y + int(rng.randf_range(-1.0, 1.0) * blend_px), 0, h - 1)
			var id: int = data[by * w + bx]
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
		var cuv := Vector2((c.x + 0.5) / float(w), (c.y + 0.5) / float(h))
		var blocked: bool = false
		for e in exclude:
			var ev: Vector3 = e
			if cuv.distance_to(Vector2(ev.x, ev.y)) < ev.z:
				blocked = true
				break
		if blocked:
			continue
		var pick: Array = _pick(d["m"], rng)
		var model: String = pick[0]
		if not per_model.has(model):
			per_model[model] = []
		var arr: Array = per_model[model]
		arr.append([c, hv, d, float(pick[2]) if pick.size() > 2 else 1.0])
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
		if int(info[3]) > HEAVY_TRIS and items.size() > HEAVY_CAPS[density_idx]:
			# Al azar (sin esto se quedarían los de arriba del mapa: los candidatos van por filas).
			for i in range(items.size() - 1, 0, -1):
				var j: int = rng.randi_range(0, i)
				var tmp: Variant = items[i]
				items[i] = items[j]
				items[j] = tmp
			items.resize(HEAVY_CAPS[density_idx])
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
			var s: float = step_world * 1.7 / foot * float(d["s"]) * float(it[3]) * rng.randf_range(0.75, 1.25)
			var basis := Basis(Vector3.UP, rng.randf() * TAU).scaled(Vector3(s, s * rng.randf_range(0.9, 1.15), s))
			mm.set_instance_transform(i, Transform3D(basis, pos) * base_xf)
			# Hojas: el color del bioma mezclado con el segundo según su variación (otoño: ocres y rojos), y un poco de
			# luz distinta por árbol. Se pasa a lineal porque el shader lo usa como albedo.
			var bid: int = int(c.z)
			var leaf: Color = leaf_a[bid].lerp(leaf_b[bid], rng.randf() * leaf_var[bid])
			var k: float = rng.randf_range(0.88, 1.12)
			# A lineal y algo más claro: los verdes hondos del 2D (bosque viejo, taiga) en 3D se quedaban casi negros.
			leaf = Color(leaf.r * k, leaf.g * k, leaf.b * k).srgb_to_linear()
			leaf = Color(minf(leaf.r * 1.45 + 0.02, 1.0), minf(leaf.g * 1.45 + 0.02, 1.0), minf(leaf.b * 1.45 + 0.02, 1.0))
			mm.set_instance_color(i, Color(leaf.r, leaf.g, leaf.b, 1.0))
			var tint: Color = d.get("tint", Color.WHITE)
			mm.set_instance_custom_data(i, Color(tint.r, tint.g, tint.b, 1.0))
		var mmi := MultiMeshInstance3D.new()
		mmi.name = model.replace(":", "_")
		mmi.multimesh = mm
		mmi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		add_child(mmi)
		_instances.append(mmi)
		total += items.size()
	return total

## Un modelo al azar según su peso: [archivo, peso, escala propia?].
func _pick(models: Array, rng: RandomNumberGenerator) -> Array:
	var sum: float = 0.0
	for m in models:
		sum += float(m[1])
	var r: float = rng.randf() * sum
	for m in models:
		r -= float(m[1])
		if r <= 0.0:
			return m
	return models[0]
