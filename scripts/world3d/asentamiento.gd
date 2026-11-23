class_name Asentamiento
extends RefCounted

## Trazado procedural de un asentamiento (aldea, pueblo, ciudad, capital, puerto) según su tipo y su tamaño (1-10).
## Es solo datos, en «unidades de edificio» (una casa mide ~1): el 3D lo convierte en modelos y el 2D en manzanas.
## Mismo lugar, mismo tamaño → mismo trazado (la semilla es el id del lugar).
##
## Cómo se hace: unas calles salen de la plaza hacia fuera (más cuanto más grande), algo curvas; las casas se
## ponen cerca de las calles y mirando a ellas, más apretadas en el centro. Las ciudades grandes y las capitales
## llevan muralla con una puerta en cada calle, una ronda por dentro y arrabales fuera; alrededor, molinos y
## campos. Lo que caiga en el agua se descarta al construirlo.

const KINDS = ["capital", "ciudad", "pueblo", "aldea", "puerto"]
const DEFAULT_SIZE = {"capital": 7, "ciudad": 5, "pueblo": 3, "aldea": 2, "puerto": 4}
const MULT = {"capital": 1.15, "ciudad": 1.0, "pueblo": 0.8, "aldea": 0.55, "puerto": 0.9}
const MAX_BUILDINGS := 300
const ROAD_HW := 0.55

static func has_size(kind: String) -> bool:
	return KINDS.has(kind)

static func size_of(p: Dictionary) -> int:
	var kind: String = str(p.get("kind", ""))
	return clampi(int(p.get("size", DEFAULT_SIZE.get(kind, 3))), 1, 10)

static func has_walls(kind: String, size: int) -> bool:
	return (kind == "capital" and size >= 3) or (kind == "ciudad" and size >= 4) or (kind == "pueblo" and size >= 8)

## Número de edificios para un tipo y tamaño.
static func count_for(kind: String, size: int) -> int:
	var n: float = 3.0 * pow(float(size), 1.9) * float(MULT.get(kind, 1.0))
	return clampi(int(round(n)), 3, MAX_BUILDINGS)

## Cómo se llama ese tamaño (para el panel).
static func size_name(kind: String, size: int) -> String:
	var base: String = {"capital": "capital", "ciudad": "ciudad", "pueblo": "pueblo", "aldea": "aldea", "puerto": "puerto"}.get(kind, kind)
	var adj: String = ""
	if size <= 2:
		adj = "muy pequeña" if kind in ["capital", "ciudad", "aldea"] else "muy pequeño"
	elif size <= 4:
		adj = "pequeña" if kind in ["capital", "ciudad", "aldea"] else "pequeño"
	elif size <= 6:
		adj = "mediana" if kind in ["capital", "ciudad", "aldea"] else "mediano"
	elif size <= 8:
		adj = "grande"
	else:
		adj = "enorme"
	return "%s %s" % [base, adj]

## {buildings: [[tipo, Vector2 pos, giro, escala]], walls: {r, gates: [ángulos]} o {}, roads: [[Vector2...]],
##  plaza: radio, radius: radio total (para dejarlo sin árboles)}
static func generate(kind: String, size: int, seed: int) -> Dictionary:
	var rng := RandomNumberGenerator.new()
	rng.seed = seed * 7919 + 17 + size * 131
	var count: int = count_for(kind, size)
	var big: bool = kind == "capital" or kind == "ciudad"
	var walled: bool = has_walls(kind, size)
	# Edificios del casco (dentro de la muralla) y de los arrabales.
	var n_out: int = int(round(count * 0.22)) if walled and size >= 5 else 0
	var n_core: int = count - n_out
	var plaza: float = 0.0 if kind == "aldea" else 0.9 + float(size) * 0.16 + (0.6 if kind == "capital" else 0.0)
	var core_r: float = maxf(sqrt(float(n_core) * 1.7 / PI) + plaza * 0.6, 1.4)
	var wall_r: float = core_r + 0.7

	# Calles: radiales y algo curvas.
	var n_roads: int = 2 if kind == "aldea" else clampi(3 + size / 2, 3, 8)
	if kind == "aldea" and size <= 1:
		n_roads = 1
	var roads_def: Array = []
	var a0: float = rng.randf() * TAU
	for i in n_roads:
		var ang: float = a0 + TAU * float(i) / float(n_roads) + rng.randf_range(-0.25, 0.25)
		roads_def.append([ang, rng.randf_range(-0.35, 0.35)])
	var outer_r: float = (wall_r if walled else core_r) * (1.45 if n_out > 0 else 1.25) + 1.5
	var roads: Array = []
	for rd in roads_def:
		var pts: Array = []
		for s in 13:
			var r: float = outer_r * float(s) / 12.0
			pts.append(_road_point(rd, r, outer_r))
		roads.append(pts)
	# Ronda por dentro de la muralla.
	var ring_r: float = wall_r - 1.1 if walled and size >= 6 else -1.0

	var out: Array = []
	var grid: Dictionary = {}
	# Plaza: el edificio principal en el centro.
	var center: String = ""
	match kind:
		"capital":
			center = "castle"
		"ciudad":
			center = "church"
		"pueblo":
			center = "well" if size < 5 else "church"
		"puerto":
			center = "market"
		"aldea":
			center = "well" if size >= 3 else ""
	if center != "":
		var sc: float = 1.0
		if center == "castle":
			sc = 1.15 + float(size) * 0.08
		elif center == "church":
			sc = 1.0 + float(size) * 0.02
		out.append([center, Vector2.ZERO, rng.randf() * TAU, sc])
		_mark(grid, Vector2.ZERO, sc * 0.9)

	# Qué edificios: los especiales repartidos, el resto casas.
	var specials: Array = []
	var n_spec: int = int(round(float(n_core) * (0.14 if big else 0.10)))
	var pool: Array = ["market", "tavern", "blacksmith", "church", "tavern", "market"]
	if big:
		pool += ["barracks", "tower_A", "blacksmith", "archeryrange"]
	if kind == "puerto":
		pool += ["market", "tavern", "watermill"]
	for i in n_spec:
		specials.append(pool[i % pool.size()] if i < pool.size() else pool[rng.randi_range(0, pool.size() - 1)])
	var homes: int = maxi(n_core - specials.size() - (1 if center != "" else 0), 0)

	# Rondas (calles en anillo): en las grandes, una a media distancia; con muralla, otra por dentro de ella.
	var rings: Array = []
	if kind != "aldea" and size >= 4:
		rings.append(core_r * 0.55)
	if ring_r > 0.0:
		rings.append(ring_r)
	for rr in rings:
		var pts: Array = []
		for i in 41:
			var a: float = TAU * float(i) / 40.0
			pts.append(Vector2(cos(a), sin(a)) * float(rr))
		roads.append(pts)

	# Solares a los dos lados de cada calle, mirando a ella, de dentro afuera: así se leen las calles.
	var hw: float = ROAD_HW + 0.55
	var lim: float = (wall_r - 0.6) if walled else core_r
	var slots: Array = []
	for rd in roads_def:
		_frontage(rng, slots, rd, plaza + 0.9, lim, outer_r, hw)
	for rr in rings:
		var step: float = 1.02 / float(rr)
		var a: float = rng.randf() * step
		while a < TAU:
			var c := Vector2(cos(a), sin(a)) * float(rr)
			var n: Vector2 = c.normalized()
			for side in [-1.0, 1.0]:
				var q: Vector2 = c + n * side * hw
				slots.append([q, atan2(-n.y * side, -n.x * side), q.length() + rng.randf() * 0.6])
			a += step
	slots.sort_custom(func(x, y): return float(x[2]) < float(y[2]))
	var to_place: Array = specials.duplicate()
	for i in homes:
		to_place.append("home_A" if rng.randf() < 0.5 else "home_B")
	var placed_n: int = 0
	for sl in slots:
		if placed_n >= to_place.size():
			break
		var q: Vector2 = sl[0]
		var rq: float = q.length()
		# Hacia fuera se deja algún solar vacío (huertos): la linde no queda recortada.
		if rng.randf() < clampf(pow(rq / maxf(lim, 0.1), 2.0) * 0.45, 0.0, 0.6):
			continue
		if float(_road_dist(q, roads)[0]) < ROAD_HW + 0.3 or not _free(grid, q, 0.9):
			continue
		var nm: String = to_place[placed_n]
		var sc: float = 1.0 if not nm.begins_with("home") else rng.randf_range(0.88, 1.0)
		out.append([nm, q, float(sl[1]), sc])
		_mark(grid, q, 0.9)
		placed_n += 1
	# Lo que no cupo en las calles, en las traseras (manzanas), más apretado hacia el centro.
	while placed_n < to_place.size():
		var p: Vector2 = _spot(rng, grid, roads, plaza + 0.4, core_r, 0.95, 2.2, -1.0, true)
		if p.x >= 1e8:
			break
		out.append([to_place[placed_n], p, _face_road(p, roads), rng.randf_range(0.85, 1.0)])
		_mark(grid, p, 0.9)
		placed_n += 1
	# Arrabales: fuera de la muralla, a lo largo de los caminos.
	if n_out > 0:
		var sub: Array = []
		for rd in roads_def:
			_frontage(rng, sub, rd, wall_r + 1.2, wall_r + 1.2 + core_r * 0.55, outer_r, hw)
		sub.sort_custom(func(x, y): return float(x[2]) < float(y[2]))
		var k: int = 0
		for sl in sub:
			if k >= n_out:
				break
			var q: Vector2 = sl[0]
			if rng.randf() < 0.3 or not _free(grid, q, 0.85):
				continue
			out.append(["home_A" if rng.randf() < 0.5 else "home_B", q, float(sl[1]), rng.randf_range(0.82, 0.95)])
			_mark(grid, q, 0.85)
			k += 1
	# Afueras: molinos, aserraderos y campos.
	var edge: float = (wall_r + (core_r * 0.45 + 0.9 if n_out > 0 else 0.6)) if walled else core_r + 0.4
	var n_farm: int = clampi(int(round(float(count) * 0.06)) + (2 if kind in ["aldea", "pueblo"] else 0), 1, 14)
	if kind == "puerto":
		n_farm = maxi(1, n_farm / 2)
	for i in n_farm:
		var nm: String = ["windmill", "windmill", "lumbermill"][rng.randi_range(0, 2)]
		var p: Vector2 = _spot(rng, grid, [], edge + 0.4, edge + 2.6, 1.1, 99.0, -1.0, false)
		if p.x < 1e8:
			out.append([nm, p, rng.randf() * TAU, 0.85 if nm == "windmill" else 0.9])
			_mark(grid, p, 1.1)

	var walls: Dictionary = {}
	if walled:
		var gates: Array = []
		for rd in roads_def:
			gates.append(atan2(_road_point(rd, wall_r, outer_r).y, _road_point(rd, wall_r, outer_r).x))
		# Irregularidad del anillo (la misma en 2D y 3D).
		walls = {"r": wall_r, "gates": gates, "amp": rng.randf_range(0.03, 0.07), "ph": rng.randf() * TAU}
	var radius: float = edge + 2.6 if n_farm > 0 else edge
	return {"buildings": out, "walls": walls, "roads": roads, "plaza": plaza, "radius": radius, "core": core_r, "ring": ring_r}

## Solares a lo largo de una calle radial entre r0 y r1: [posición, giro hacia la calle, orden].
static func _frontage(rng: RandomNumberGenerator, slots: Array, rd: Array, r0: float, r1: float, outer_r: float, hw: float) -> void:
	var r: float = r0
	while r < r1:
		var p0: Vector2 = _road_point(rd, r, outer_r)
		var t: Vector2 = (_road_point(rd, r + 0.05, outer_r) - p0).normalized()
		var n := Vector2(-t.y, t.x)
		for side in [-1.0, 1.0]:
			var q: Vector2 = p0 + n * side * hw + t * rng.randf_range(-0.12, 0.12)
			slots.append([q, atan2(-n.y * side, -n.x * side), r + rng.randf() * 0.6])
		r += 1.02

## Los vértices del anillo de la muralla (en unidades de edificio) y cuántos tramos tiene.
static func wall_ring(walls: Dictionary) -> Array:
	var r: float = float(walls["r"])
	var n: int = maxi(6, int(ceil(TAU * r / 2.0)))
	var amp: float = float(walls.get("amp", 0.0))
	var ph: float = float(walls.get("ph", 0.0))
	var verts: Array = []
	for i in n:
		var th: float = TAU * float(i) / float(n)
		var rr: float = r * (1.0 + amp * sin(3.0 * th + ph) + amp * 0.5 * sin(5.0 * th + ph * 1.7))
		verts.append(Vector2(cos(th), sin(th)) * rr)
	return verts

static func _road_point(rd: Array, r: float, outer_r: float) -> Vector2:
	var ang: float = float(rd[0]) + float(rd[1]) * sin(r / maxf(outer_r, 0.1) * PI)
	return Vector2(cos(ang), sin(ang)) * r

## Distancia a la calle más cercana, su dirección en ese punto y el punto.
static func _road_dist(p: Vector2, roads: Array) -> Array:
	var best: float = 1e9
	var dir := Vector2.RIGHT
	var at := Vector2.ZERO
	for pts in roads:
		for i in range((pts as Array).size() - 1):
			var a: Vector2 = pts[i]
			var b: Vector2 = pts[i + 1]
			var ab: Vector2 = b - a
			var t: float = clampf((p - a).dot(ab) / maxf(ab.length_squared(), 1e-6), 0.0, 1.0)
			var d: float = p.distance_to(a + ab * t)
			if d < best:
				best = d
				dir = ab.normalized()
				at = a + ab * t
	return [best, dir, at]

## El giro para que el edificio dé la cara a la calle más cercana.
static func _face_road(p: Vector2, roads: Array) -> float:
	if roads.is_empty() or p.length() < 0.01:
		return 0.0
	var rd: Array = _road_dist(p, roads)
	var at: Vector2 = rd[2]
	var to: Vector2 = at - p
	return atan2(to.y, to.x)

## Un hueco libre entre r0 y r1: lejos de la calzada, sin pisar a nadie, y (si [near] es pequeño) cerca de una calle.
static func _spot(rng: RandomNumberGenerator, grid: Dictionary, roads: Array, r0: float, r1: float, need: float, near: float, ring_r: float, dense_center: bool) -> Vector2:
	for attempt in 60:
		var t: float = rng.randf()
		var r: float = lerpf(r0, maxf(r1, r0 + 0.5), pow(t, 0.75) if dense_center else sqrt(t))
		var a: float = rng.randf() * TAU
		var p := Vector2(cos(a), sin(a)) * r
		if not roads.is_empty():
			var rd: Array = _road_dist(p, roads)
			var d: float = rd[0]
			if d < ROAD_HW + need * 0.45:
				continue
			if d > near and rng.randf() > exp(-(d - near) / 1.2):
				continue
		if ring_r > 0.0 and absf(p.length() - ring_r) < ROAD_HW + need * 0.4:
			continue
		if _free(grid, p, need):
			return p
	return Vector2(1e9, 1e9)

static func _key(p: Vector2) -> Vector2i:
	return Vector2i(int(floor(p.x / 2.0)), int(floor(p.y / 2.0)))

static func _mark(grid: Dictionary, p: Vector2, r: float) -> void:
	var k: Vector2i = _key(p)
	if not grid.has(k):
		grid[k] = []
	(grid[k] as Array).append(Vector3(p.x, p.y, r))

static func _free(grid: Dictionary, p: Vector2, r: float) -> bool:
	var k: Vector2i = _key(p)
	for dy in range(-1, 2):
		for dx in range(-1, 2):
			var kk := Vector2i(k.x + dx, k.y + dy)
			if not grid.has(kk):
				continue
			for q in grid[kk]:
				var qv: Vector3 = q
				if p.distance_to(Vector2(qv.x, qv.y)) < (qv.z + r) * 0.55:
					return false
	return true
