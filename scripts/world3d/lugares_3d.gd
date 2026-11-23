class_name Lugares3D
extends Node3D

## Los lugares en el relieve 3D: torres y castillos con piezas del Castle Kit de Kenney (CC0), puestos y molino de
## agua del Fantasy Town Kit (CC0) y casas hechas aquí (muros encalados y tejado a dos aguas) para los cascos de
## ciudades, pueblos y aldeas. Son pocos (decenas), así que van como nodos normales.

const CASTLE := "res://assets/kenney/castle/"
const TOWN := "res://assets/kenney/town/"

var _scene_cache: Dictionary = {}
var _house_mesh: ArrayMesh

func _ready() -> void:
	_house_mesh = _make_house()

func clear() -> void:
	for c in get_children():
		c.queue_free()

## [places]: los del motor. Devuelve la posición 3D de cada lugar (id → Vector3) para poner los nombres encima.
func build(places: Array, height_img: Image, sea_level: float, world: Vector2, height_scale: float) -> Dictionary:
	clear()
	var out: Dictionary = {}
	var w: int = height_img.get_width()
	var h: int = height_img.get_height()
	# Tamaño de referencia de un edificio: proporcional al mundo (12 unidades de lado mayor).
	var unit: float = maxf(world.x, world.y) / 12.0 * 0.075
	for p in places:
		var uv: Vector2 = p["uv"]
		var hv: float = height_img.get_pixel(clampi(int(uv.x * w), 0, w - 1), clampi(int(uv.y * h), 0, h - 1)).r
		var pos := Vector3((uv.x - 0.5) * world.x, maxf(hv - sea_level, 0.0) * height_scale, (uv.y - 0.5) * world.y)
		var root := Node3D.new()
		root.position = pos
		add_child(root)
		var rng := RandomNumberGenerator.new()
		rng.seed = int(p["id"]) * 7919
		var kind: String = p["kind"]
		match kind:
			"capital":
				_stack(root, CASTLE, ["tower-square-base", "tower-square-mid-windows", "tower-square-mid", "tower-square-top-roof-high"], unit * 1.1)
				_ring_houses(root, rng, 9, unit * 1.6, unit)
				_piece(root, CASTLE, "flag-banner-long", Vector3(unit * 0.9, 0, 0), unit * 0.9)
			"ciudad":
				_ring_houses(root, rng, 8, unit * 1.2, unit)
				_stack(root, CASTLE, ["tower-hexagon-base", "tower-hexagon-roof"], unit * 0.7)
			"pueblo":
				_ring_houses(root, rng, 4, unit * 0.9, unit * 0.9)
			"aldea":
				_ring_houses(root, rng, 2, unit * 0.6, unit * 0.8)
			"castillo":
				_stack(root, CASTLE, ["tower-square-base", "tower-square-mid", "tower-square-top-roof"], unit)
				for i in 4:
					var a: float = TAU * i / 4.0 + PI / 4.0
					_stack(root, CASTLE, ["tower-hexagon-base", "tower-hexagon-top"], unit * 0.55, Vector3(cos(a), 0, sin(a)) * unit * 0.95)
			"torre":
				_stack(root, CASTLE, ["tower-hexagon-base", "tower-hexagon-mid", "tower-hexagon-mid", "tower-hexagon-roof"], unit * 0.75)
			"templo":
				_stack(root, CASTLE, ["tower-hexagon-base", "tower-hexagon-roof"], unit * 1.0)
				for i in 6:
					var a2: float = TAU * i / 6.0
					_piece(root, TOWN, "pillar-stone", Vector3(cos(a2), 0, sin(a2)) * unit * 0.9, unit * 0.6)
			"puerto":
				_ring_houses(root, rng, 4, unit * 0.9, unit * 0.9)
				_piece(root, CASTLE, "flag-pennant", Vector3(0, 0, unit * 0.6), unit * 0.8)
			"ruinas":
				_piece(root, CASTLE, "siege-tower-demolished", Vector3.ZERO, unit * 0.7)
				_piece(root, TOWN, "wall-broken", Vector3(unit * 0.6, 0, 0), unit * 0.8)
				_piece(root, TOWN, "wall-wood-broken", Vector3(-unit * 0.5, 0, unit * 0.3), unit * 0.8)
			"mina":
				_piece(root, CASTLE, "rocks-large", Vector3.ZERO, unit * 1.2)
				_piece(root, TOWN, "cart", Vector3(unit * 0.6, 0, 0), unit * 0.5)
			"batalla":
				_piece(root, CASTLE, "siege-catapult", Vector3(-unit * 0.4, 0, 0), unit * 0.6)
				_piece(root, CASTLE, "siege-trebuchet", Vector3(unit * 0.5, 0, unit * 0.2), unit * 0.6)
				_piece(root, CASTLE, "flag", Vector3(0, 0, -unit * 0.5), unit * 0.7)
			_:
				_ring_houses(root, rng, 2, unit * 0.6, unit * 0.8)
		out[int(p["id"])] = pos + Vector3(0, unit * 2.2, 0)
	return out

func _scene(path: String) -> PackedScene:
	if not _scene_cache.has(path):
		_scene_cache[path] = load(path)
	return _scene_cache[path]

func _piece(parent: Node3D, dir: String, name: String, offset: Vector3, scale: float, y: float = 0.0) -> float:
	var sc: PackedScene = _scene(dir + name + ".glb")
	if sc == null:
		return 0.0
	var n: Node3D = sc.instantiate()
	n.position = offset + Vector3(0, y, 0)
	n.scale = Vector3.ONE * scale
	parent.add_child(n)
	for c in n.find_children("*", "GeometryInstance3D", true, false):
		(c as GeometryInstance3D).cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	return scale

## Piezas de torre apiladas (cada pieza del Castle Kit mide 1 de alto).
func _stack(parent: Node3D, dir: String, names: Array, scale: float, offset: Vector3 = Vector3.ZERO) -> void:
	for i in names.size():
		_piece(parent, dir, str(names[i]), offset, scale, i * scale)

func _ring_houses(parent: Node3D, rng: RandomNumberGenerator, n: int, radius: float, size: float) -> void:
	for i in n:
		var a: float = rng.randf() * TAU
		var r: float = radius * sqrt(rng.randf()) if n > 1 else 0.0
		var mi := MeshInstance3D.new()
		mi.mesh = _house_mesh
		mi.position = Vector3(cos(a) * r, 0, sin(a) * r)
		mi.rotation.y = rng.randf() * TAU
		var s: float = size * rng.randf_range(0.35, 0.55)
		mi.scale = Vector3(s * rng.randf_range(0.9, 1.4), s * rng.randf_range(0.9, 1.3), s)
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		parent.add_child(mi)

## Una casa de 1×1 de planta: muros encalados (0..0,6 de alto) y tejado a dos aguas (hasta 1,05).
func _make_house() -> ArrayMesh:
	var walls := StandardMaterial3D.new()
	walls.albedo_color = Color(0.93, 0.89, 0.80)
	walls.roughness = 0.95
	var roof := StandardMaterial3D.new()
	roof.albedo_color = Color(0.66, 0.30, 0.22)
	roof.roughness = 0.9
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var hw: float = 0.5
	var hd: float = 0.38
	var wh: float = 0.6
	# Cuatro muros y las dos puntas del hastial.
	_quad(st, Vector3(-hw, 0, hd), Vector3(hw, 0, hd), Vector3(hw, wh, hd), Vector3(-hw, wh, hd))
	_quad(st, Vector3(hw, 0, -hd), Vector3(-hw, 0, -hd), Vector3(-hw, wh, -hd), Vector3(hw, wh, -hd))
	_quad(st, Vector3(hw, 0, hd), Vector3(hw, 0, -hd), Vector3(hw, wh, -hd), Vector3(hw, wh, hd))
	_quad(st, Vector3(-hw, 0, -hd), Vector3(-hw, 0, hd), Vector3(-hw, wh, hd), Vector3(-hw, wh, -hd))
	_tri(st, Vector3(hw, wh, hd), Vector3(hw, wh, -hd), Vector3(hw, 1.05, 0))
	_tri(st, Vector3(-hw, wh, -hd), Vector3(-hw, wh, hd), Vector3(-hw, 1.05, 0))
	st.set_material(walls)
	var mesh: ArrayMesh = st.commit()
	var st2 := SurfaceTool.new()
	st2.begin(Mesh.PRIMITIVE_TRIANGLES)
	var o: float = 0.08
	_quad(st2, Vector3(-hw - o, wh - 0.04, hd + o), Vector3(hw + o, wh - 0.04, hd + o), Vector3(hw + o, 1.07, 0), Vector3(-hw - o, 1.07, 0))
	_quad(st2, Vector3(hw + o, wh - 0.04, -hd - o), Vector3(-hw - o, wh - 0.04, -hd - o), Vector3(-hw - o, 1.07, 0), Vector3(hw + o, 1.07, 0))
	st2.set_material(roof)
	st2.commit(mesh)
	return mesh

func _quad(st: SurfaceTool, a: Vector3, b: Vector3, c: Vector3, d: Vector3) -> void:
	_tri(st, a, b, c)
	_tri(st, a, c, d)

func _tri(st: SurfaceTool, a: Vector3, b: Vector3, c: Vector3) -> void:
	var n: Vector3 = (b - a).cross(c - a).normalized()
	for v in [a, b, c]:
		st.set_normal(n)
		st.add_vertex(v)
