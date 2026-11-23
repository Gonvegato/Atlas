class_name Lugares3D
extends Node3D

## Los lugares en el relieve 3D, con edificios del KayKit Medieval Hexagon Pack (Kay Lousberg, CC0, en
## assets/kaykit): casas, castillo, iglesia, taberna, mercado, herrería, cuartel, molinos, pozo, mina, torres y
## murallas, en cuatro colores (azul, rojo, verde, amarillo). Cada lugar toma el color de su reino (el más
## parecido de los cuatro). Cada edificio se asienta a la altura del terreno en su sitio y no se pisan entre sí.
## Algunas piezas de asedio siguen siendo del Castle Kit de Kenney (CC0).

const KAY := "res://assets/kaykit/buildings/%s/building_%s_%s.gltf"
const KAY_NEUTRAL := "res://assets/kaykit/buildings/neutral/%s.gltf"
const KAY_PROP := "res://assets/kaykit/props/%s.gltf"
const KENNEY_CASTLE := "res://assets/kenney/castle/%s.glb"
const COLORS = {"blue": Color(0.27, 0.45, 0.75), "red": Color(0.75, 0.25, 0.2), "green": Color(0.3, 0.58, 0.3), "yellow": Color(0.85, 0.68, 0.2)}

## Radio (en tamaños de edificio) que ocupa cada tipo de lugar: también es el hueco sin árboles.
const RADIUS = {"capital": 3.4, "ciudad": 2.6, "pueblo": 1.7, "aldea": 1.2, "castillo": 1.8, "torre": 0.8, "templo": 1.2, "puerto": 1.8, "ruinas": 1.2, "mina": 1.2, "batalla": 1.6}

var _scene_cache: Dictionary = {}
var _height: Image
var _sea: float = 0.5
var _world: Vector2 = Vector2(12, 12)
var _hs: float = 2.6
var _rng := RandomNumberGenerator.new()

func clear() -> void:
	for c in get_children():
		c.queue_free()

## Tamaño de un edificio (una pieza de KayKit mide ~1) en unidades del mundo.
func unit_for(world: Vector2) -> float:
	return maxf(world.x, world.y) / 12.0 * 0.07

## Radio en uv que ocupa un lugar (para dejarlo sin árboles).
func radius_uv(kind: String, world: Vector2) -> float:
	return float(RADIUS.get(kind, 1.2)) * unit_for(world) * 1.7 / maxf(world.x, world.y)

## Devuelve la posición 3D de cada lugar (id → Vector3) para poner su nombre encima.
func build(places: Array, height_img: Image, realm_img: Image, realms: Array, sea_level: float, world: Vector2, height_scale: float) -> Dictionary:
	clear()
	_height = height_img
	_sea = sea_level
	_world = world
	_hs = height_scale
	var out: Dictionary = {}
	var u: float = unit_for(world)
	for p in places:
		var uv: Vector2 = p["uv"]
		var kind: String = p["kind"]
		_rng.seed = int(p["id"]) * 7919 + 17
		var col: String = _color_for(uv, realm_img, realms, int(p["id"]))
		var root := Node3D.new()
		var base: Vector3 = _ground(uv)
		root.position = base
		add_child(root)
		var placed: Array = []
		match kind:
			"capital":
				_put(root, uv, _kay(col, "castle"), Vector2.ZERO, u * 1.25, placed, 1.1)
				_walls(root, uv, u * 3.1, u, col)
				_cluster(root, uv, col, ["church", "market", "tavern", "blacksmith", "barracks", "well"], u * 1.3, u * 2.6, u, placed)
				_cluster(root, uv, col, ["home_A", "home_B", "home_A", "home_B", "home_A", "home_B", "home_A", "home_B", "home_A", "home_B"], u * 1.3, u * 2.7, u * 0.95, placed)
			"ciudad":
				_put(root, uv, _kay(col, "church"), Vector2.ZERO, u, placed, 0.8)
				_cluster(root, uv, col, ["market", "tavern", "blacksmith", "well", "tower_A"], u * 0.9, u * 2.2, u, placed)
				_cluster(root, uv, col, ["home_A", "home_B", "home_A", "home_B", "home_A", "home_B", "home_A", "home_B"], u * 0.9, u * 2.4, u * 0.95, placed)
			"pueblo":
				_put(root, uv, _kay(col, "well"), Vector2.ZERO, u * 0.8, placed, 0.5)
				_cluster(root, uv, col, ["tavern" if _rng.randf() < 0.5 else "lumbermill", "home_A", "home_B", "home_A", "home_B"], u * 0.6, u * 1.6, u * 0.95, placed)
			"aldea":
				_cluster(root, uv, col, ["home_A", "home_B"], u * 0.3, u * 0.9, u * 0.9, placed)
				_put(root, uv, _kay(col, "windmill"), _free_spot(placed, u * 0.9, u * 1.3, u * 0.7), u * 0.85, placed, 0.7, u * 0.85 * 0.5)
				_put(root, uv, KAY_NEUTRAL % "building_grain", _free_spot(placed, u * 1.0, u * 1.4, u * 0.9), u * 0.7, placed, 0.9)
			"castillo":
				_put(root, uv, _kay(col, "castle"), Vector2.ZERO, u * 1.1, placed, 1.0)
				_walls(root, uv, u * 1.9, u * 0.9, col)
				_put(root, uv, _kay(col, "tower_catapult"), Vector2(u * 1.3, 0), u * 0.8, placed, 0.5)
			"torre":
				_put(root, uv, _kay(col, "tower_B"), Vector2.ZERO, u * 0.9, placed, 0.6)
				_put(root, uv, KAY_PROP % ("flag_" + col), Vector2(u * 0.6, 0), u * 1.2, placed, 0.1)
			"templo":
				_put(root, uv, _kay(col, "church"), Vector2.ZERO, u * 1.1, placed, 0.8)
				_cluster(root, uv, col, ["well", "home_A"], u * 0.9, u * 1.3, u * 0.9, placed)
			"puerto":
				_put(root, uv, _kay(col, "market"), Vector2.ZERO, u, placed, 0.9)
				_cluster(root, uv, col, ["home_A", "home_B", "home_A", "tavern"], u * 0.9, u * 1.7, u * 0.95, placed)
				for prop in ["crate_A_big", "barrel", "crate_long_A", "barrel", "sack"]:
					_put(root, uv, KAY_PROP % prop, _free_spot(placed, u * 0.6, u * 1.3, u * 0.25), u * 1.2, placed, 0.15)
			"ruinas":
				_put(root, uv, KAY_NEUTRAL % "building_destroyed", Vector2.ZERO, u, placed, 0.8)
				_put(root, uv, KAY_NEUTRAL % "building_stage_B", _free_spot(placed, u * 0.8, u * 1.2, u * 0.6), u * 0.8, placed, 0.5)
				_put(root, uv, KAY_NEUTRAL % "building_dirt", Vector2(u * 0.3, u * 0.3), u * 0.8, placed, 0.0)
			"mina":
				_put(root, uv, _kay(col, "mine"), Vector2.ZERO, u, placed, 0.9)
				for prop in ["resource_stone", "wheelbarrow", "resource_stone"]:
					_put(root, uv, KAY_PROP % prop, _free_spot(placed, u * 0.8, u * 1.3, u * 0.3), u * 1.2, placed, 0.2)
			"batalla":
				for i in 4:
					_put(root, uv, KAY_PROP % "tent", _free_spot(placed, u * 0.4, u * 1.4, u * 0.45), u * 1.3, placed, 0.4)
				for prop in ["weaponrack", "target", "flag_" + col, "flag_red" if col != "red" else "flag_blue"]:
					_put(root, uv, KAY_PROP % prop, _free_spot(placed, u * 0.4, u * 1.5, u * 0.2), u * 1.4, placed, 0.1)
				_put(root, uv, KENNEY_CASTLE % "siege-catapult", _free_spot(placed, u * 1.0, u * 1.6, u * 0.5), u * 0.5, placed, 0.4)
			_:
				_cluster(root, uv, col, ["home_A", "home_B"], u * 0.3, u * 0.9, u * 0.9, placed)
		out[int(p["id"])] = base + Vector3(0, u * (3.4 if kind == "capital" else 2.0), 0)
	return out

func _kay(col: String, name: String) -> String:
	return KAY % [col, name, col]

## El color del reino del lugar (el más parecido de los cuatro de KayKit); sin reino, uno fijo según el lugar.
func _color_for(uv: Vector2, realm_img: Image, realms: Array, pid: int) -> String:
	var keys: Array = COLORS.keys()
	if realm_img != null:
		var px := Vector2i(clampi(int(uv.x * realm_img.get_width()), 0, realm_img.get_width() - 1), clampi(int(uv.y * realm_img.get_height()), 0, realm_img.get_height() - 1))
		var rid: int = int(round(realm_img.get_pixelv(px).r * 255.0))
		for r in realms:
			if int(r["id"]) == rid:
				var rc: Color = r["color"]
				var best: String = "blue"
				var best_d: float = 1e9
				for k in keys:
					var c: Color = COLORS[k]
					var dh: float = absf(fposmod(rc.h - c.h + 0.5, 1.0) - 0.5)
					var d: float = dh * 3.0 + absf(rc.s - c.s) * 0.5 + absf(rc.v - c.v) * 0.3
					if d < best_d:
						best_d = d
						best = k
				return best
	return keys[pid % keys.size()]

## Punto del terreno para un uv (la misma cuenta que el vertex shader del relieve).
func _ground(uv: Vector2) -> Vector3:
	var w: int = _height.get_width()
	var h: int = _height.get_height()
	var px := Vector2i(clampi(int(uv.x * w), 0, w - 1), clampi(int(uv.y * h), 0, h - 1))
	var hv: float = _height.get_pixelv(px).r
	return Vector3((uv.x - 0.5) * _world.x, maxf(hv - _sea, 0.0) * _hs, (uv.y - 0.5) * _world.y)

func _scene(path: String) -> PackedScene:
	if not _scene_cache.has(path):
		_scene_cache[path] = load(path) if ResourceLoader.exists(path) else null
	return _scene_cache[path]

## Pone una pieza en [offset] (unidades del mundo, desde el centro del lugar) a la altura del terreno de su sitio.
## [footprint]: radio que ocupa (en edificios) para que los demás no la pisen. [lift]: para piezas con base hundida.
func _put(root: Node3D, uv: Vector2, path: String, offset: Vector2, scale: float, placed: Array, footprint: float, lift: float = 0.0) -> void:
	var sc: PackedScene = _scene(path)
	if sc == null:
		return
	var n: Node3D = sc.instantiate()
	var g: Vector3 = _ground(uv + offset / _world)
	n.position = Vector3(offset.x, g.y - root.position.y + lift, offset.y)
	n.rotation.y = _rng.randi_range(0, 5) * PI / 3.0
	n.scale = Vector3.ONE * scale
	root.add_child(n)
	_no_shadows(n)
	placed.append(Vector3(offset.x, offset.y, footprint * scale / 0.07 * 0.07))

## Sin sombras propias ni recibidas: a esta escala la sombra direccional deja motas (acné) en los edificios.
func _no_shadows(n: Node) -> void:
	for c in n.find_children("*", "GeometryInstance3D", true, false):
		var g: GeometryInstance3D = c
		g.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		if g is MeshInstance3D:
			var mi: MeshInstance3D = g
			for si in mi.get_surface_override_material_count():
				var m: Material = mi.get_active_material(si)
				if m is BaseMaterial3D:
					(m as BaseMaterial3D).disable_receive_shadows = true

## Un sitio libre a una distancia entre [r0] y [r1] del centro, sin pisar lo ya puesto.
func _free_spot(placed: Array, r0: float, r1: float, need: float) -> Vector2:
	var best := Vector2(r1, 0)
	for attempt in 40:
		var a: float = _rng.randf() * TAU
		var r: float = lerpf(r0, r1, sqrt(_rng.randf()))
		var p := Vector2(cos(a), sin(a)) * r
		var ok: bool = true
		for q in placed:
			var qv: Vector3 = q
			if p.distance_to(Vector2(qv.x, qv.y)) < (qv.z + need) * 0.55:
				ok = false
				break
		if ok:
			return p
		best = p
	return best

func _cluster(root: Node3D, uv: Vector2, col: String, names: Array, r0: float, r1: float, scale: float, placed: Array) -> void:
	for nm in names:
		var spot: Vector2 = _free_spot(placed, r0, r1, scale)
		var lift: float = scale * 0.5 if nm == "windmill" or nm == "watermill" else (scale * 0.24 if nm == "lumbermill" else 0.0)
		_put(root, uv, _kay(col, str(nm)), spot, scale, placed, 0.8, lift)

## Muralla en anillo de radio [r] con una puerta y torres en algunos tramos.
func _walls(root: Node3D, uv: Vector2, r: float, scale: float, col: String) -> void:
	var seg: float = 2.0 * scale
	var n: int = maxi(6, int(ceil(TAU * r / seg)))
	var rr: float = n * seg / TAU
	var gate: int = _rng.randi_range(0, n - 1)
	for i in n:
		var th: float = TAU * (float(i) + 0.5) / float(n)
		var off := Vector2(cos(th), sin(th)) * rr
		var path: String = KAY_NEUTRAL % ("wall_straight_gate" if i == gate else "wall_straight")
		var sc: PackedScene = _scene(path)
		if sc == null:
			continue
		var w: Node3D = sc.instantiate()
		var g: Vector3 = _ground(uv + off / _world)
		w.position = Vector3(off.x, g.y - root.position.y, off.y)
		w.rotation.y = -th - PI / 2.0
		w.scale = Vector3.ONE * scale
		root.add_child(w)
		_no_shadows(w)
		if i % 3 == 1 and i != gate:
			var tsc: PackedScene = _scene(_kay(col, "tower_A"))
			if tsc != null:
				var t: Node3D = tsc.instantiate()
				var off2 := Vector2(cos(th + PI / n), sin(th + PI / n)) * rr
				var g2: Vector3 = _ground(uv + off2 / _world)
				t.position = Vector3(off2.x, g2.y - root.position.y, off2.y)
				t.scale = Vector3.ONE * scale * 0.7
				root.add_child(t)
				_no_shadows(t)
