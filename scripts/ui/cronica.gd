class_name Cronica
extends Panel

## La crónica del mundo: fichas escritas de lo que hay en él. Las de los reinos, imperios, lugares y regiones de cada
## mapa salen solas (con lo que el mapa ya sabe: extensión, imperio, ciudades dentro, alianzas…) y se escriben encima;
## además, fichas libres de personajes, pueblos, historia, religiones… Es del mundo, así que vale para todos sus
## mapas. Se guarda en user://mundos/<mundo>.json.

const CATS = [
	["reino", "Reinos e imperios", ["Gobierno", "Gobernante", "Capital", "Fundación", "Religión", "Lema", "Población"]],
	["lugar", "Ciudades y lugares", ["Señor o gobernante", "Población", "Fundación", "Conocido por"]],
	["region", "Regiones", ["Clima", "Peligros", "Recursos", "Leyendas"]],
	["personaje", "Personajes", ["Cargo", "Lealtad", "Nacimiento", "Rasgos"]],
	["pueblo", "Pueblos y razas", ["Origen", "Lengua", "Costumbres"]],
	["historia", "Historia", ["Cuándo", "Dónde", "Quiénes"]],
	["fe", "Religiones y órdenes", ["Dioses", "Sede", "Seguidores"]],
	["otro", "Otras fichas", []],
]
const LIBRES = ["personaje", "pueblo", "historia", "fe", "otro"]
const DIR := "user://mundos"
const MapOverlay = preload("res://scripts/ui/map_overlay.gd")

var main: Control
var engine: Node
var world_id: String = ""
var data: Dictionary = {"entries": {}}
var sel: String = ""
var _list: VBoxContainer
var _editor: VBoxContainer
var _cols: BoxContainer
var _save_timer: Timer

func _ready() -> void:
	add_theme_stylebox_override("panel", Estilo.caja(Estilo.PAPEL))
	mouse_filter = Control.MOUSE_FILTER_STOP
	_save_timer = Timer.new()
	_save_timer.one_shot = true
	_save_timer.wait_time = 1.0
	_save_timer.timeout.connect(save)
	add_child(_save_timer)

func _file() -> String:
	return "%s/%s.json" % [DIR, world_id]

func load_world(wid: String) -> void:
	if wid == world_id:
		return
	if world_id != "":
		save()
	world_id = wid
	sel = ""
	data = {"entries": {}}
	if FileAccess.file_exists(_file()):
		var v: Variant = JSON.parse_string(FileAccess.get_file_as_string(_file()))
		if typeof(v) == TYPE_DICTIONARY and typeof(v.get("entries")) == TYPE_DICTIONARY:
			data = v

func save() -> void:
	if world_id == "":
		return
	DirAccess.make_dir_recursive_absolute(DIR)
	var f := FileAccess.open(_file() + ".tmp", FileAccess.WRITE)
	if f == null:
		return
	f.store_string(JSON.stringify(data, "\t"))
	f.close()
	DirAccess.rename_absolute(_file() + ".tmp", _file())

func _touch() -> void:
	_save_timer.start()

func _entry(key: String, cat: String) -> Dictionary:
	var e: Dictionary = data["entries"]
	if not e.has(key):
		e[key] = {"cat": cat, "title": "", "fields": {}, "text": "", "u": 0}
	return e[key]

func _has_text(key: String) -> bool:
	var e: Dictionary = data["entries"].get(key, {})
	if e.is_empty():
		return false
	if str(e.get("text", "")).strip_edges() != "":
		return true
	for v in (e.get("fields", {}) as Dictionary).values():
		if str(v).strip_edges() != "":
			return true
	return false

# ---------------------------------------------------------------- qué hay

## Lo del mapa abierto: [clave, categoría, título, id] de reinos (en árbol), lugares y regiones.
func _map_items() -> Array:
	var out: Array = []
	var mid: String = engine.map_id
	for e in main._realm_tree():
		var r: Dictionary = e[0]
		out.append(["%s:r:%d" % [mid, int(r["id"])], "reino", "%s%s" % ["· ".repeat(int(e[1])), str(r["name"])], int(r["id"])])
	for p in engine.places:
		out.append(["%s:p:%d" % [mid, int(p["id"])], "lugar", str(p["name"]), int(p["id"])])
	for a in engine.areas:
		out.append(["%s:a:%d" % [mid, int(a["id"])], "region", str(a["name"]), int(a["id"])])
	return out

func _cat_name(cat: String) -> String:
	for c in CATS:
		if c[0] == cat:
			return c[1]
	return cat

func _cat_fields(cat: String) -> Array:
	for c in CATS:
		if c[0] == cat:
			return c[2]
	return []

## El reino en el que cae un punto del mapa (0 = ninguno).
func _realm_at(uv: Vector2) -> int:
	var px := Vector2i(uv * Vector2(engine.map_size))
	return int(round(engine.realm.sample_mirror(px) * 255.0))

# ---------------------------------------------------------------- dibujo

func refresh() -> void:
	load_world(engine.world_id)
	for c in get_children():
		if c != _save_timer:
			c.queue_free()
	var portrait: bool = size.y > size.x
	_cols = VBoxContainer.new() if portrait else HBoxContainer.new()
	_cols.set_anchors_preset(Control.PRESET_FULL_RECT)
	_cols.add_theme_constant_override("separation", 0)
	add_child(_cols)
	var ls := ScrollContainer.new()
	ls.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	ls.custom_minimum_size = Vector2(0, size.y * 0.38) if portrait else Vector2(360, 0)
	ls.size_flags_vertical = Control.SIZE_EXPAND_FILL
	var lsb := Estilo.caja(Estilo.CRUDO)
	var lpanel := PanelContainer.new()
	lpanel.add_theme_stylebox_override("panel", lsb)
	lpanel.custom_minimum_size = ls.custom_minimum_size
	lpanel.size_flags_vertical = Control.SIZE_EXPAND_FILL if not portrait else Control.SIZE_FILL
	_cols.add_child(lpanel)
	lpanel.add_child(ls)
	var lm := MarginContainer.new()
	lm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for k in ["margin_left", "margin_right", "margin_top", "margin_bottom"]:
		lm.add_theme_constant_override(k, 20)
	ls.add_child(lm)
	_list = VBoxContainer.new()
	_list.add_theme_constant_override("separation", 2)
	lm.add_child(_list)
	var rule := ColorRect.new()
	rule.color = Estilo.TINTA
	rule.custom_minimum_size = Vector2(0, 2) if portrait else Vector2(2, 0)
	_cols.add_child(rule)
	var es := ScrollContainer.new()
	es.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	es.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	es.size_flags_vertical = Control.SIZE_EXPAND_FILL
	_cols.add_child(es)
	var em := MarginContainer.new()
	em.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for k in ["margin_left", "margin_right"]:
		em.add_theme_constant_override(k, 32)
	em.add_theme_constant_override("margin_top", 24)
	em.add_theme_constant_override("margin_bottom", 40)
	es.add_child(em)
	_editor = VBoxContainer.new()
	_editor.add_theme_constant_override("separation", 12)
	_editor.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	em.add_child(_editor)
	_build_list()
	_build_editor()

func _build_list() -> void:
	var head := HBoxContainer.new()
	_list.add_child(head)
	var t := Estilo.etiqueta("Crónica", 30, Estilo.TINTA, Estilo.grotesca())
	t.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	head.add_child(t)
	var n_written: int = 0
	for k in data["entries"].keys():
		if _has_text(str(k)):
			n_written += 1
	head.add_child(Estilo.etiqueta("%d ESCRITAS" % n_written if n_written != 1 else "1 ESCRITA", 11, Estilo.ACENTO))
	var sub := Estilo.etiqueta("DE " + str(engine.world_name).to_upper(), 11, Estilo.GRIS)
	_list.add_child(sub)
	_list.add_child(_gap(8))
	_list.add_child(Estilo.etiqueta("+ NUEVA FICHA", 11, Estilo.GRIS))
	var flow := HFlowContainer.new()
	flow.add_theme_constant_override("h_separation", 6)
	flow.add_theme_constant_override("v_separation", 6)
	_list.add_child(flow)
	for cat in LIBRES:
		var b := Estilo.boton(str(_cat_name(cat)).to_upper(), 10, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 10, 8, Estilo.TINTA, 2)
		var c: String = cat
		b.pressed.connect(func(): _new_free(c))
		flow.add_child(b)
	var items: Array = _map_items()
	for cat_def in CATS:
		var cat: String = cat_def[0]
		var rows: Array = []
		for it in items:
			if it[1] == cat:
				rows.append([it[0], it[2]])
		for k in data["entries"].keys():
			var e: Dictionary = data["entries"][k]
			if str(k).begins_with("e_") and str(e.get("cat", "")) == cat:
				rows.append([str(k), str(e.get("title", "Sin título"))])
		if rows.is_empty():
			continue
		_list.add_child(_gap(14))
		var cap := HBoxContainer.new()
		_list.add_child(cap)
		var cl := Estilo.etiqueta(str(cat_def[1]).to_upper(), 11, Estilo.TINTA)
		cl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		cap.add_child(cl)
		cap.add_child(Estilo.etiqueta(str(rows.size()), 13, Estilo.ACENTO, Estilo.grotesca()))
		_list.add_child(Estilo.filete(2))
		for r in rows:
			var key: String = r[0]
			var b := Estilo.boton(("● " if _has_text(key) else "") + str(r[1]), 16, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 10, 8)
			b.alignment = HORIZONTAL_ALIGNMENT_LEFT
			b.clip_text = true
			main._paint_row(b, key == sel)
			b.add_theme_font_size_override("font_size", 16)
			b.pressed.connect(func(): select(key))
			_list.add_child(b)

func _gap(h: int) -> Control:
	var c := Control.new()
	c.custom_minimum_size = Vector2(0, h)
	return c

func select(key: String) -> void:
	sel = key
	refresh()

func _new_free(cat: String) -> void:
	var key: String = "e_%d" % Time.get_unix_time_from_system() + str(randi() % 1000)
	var e: Dictionary = _entry(key, cat)
	e["title"] = "Nueva ficha de " + _cat_name(cat).to_lower()
	e["u"] = int(Time.get_unix_time_from_system())
	_touch()
	select(key)

# ---------------------------------------------------------------- la ficha

func _build_editor() -> void:
	if sel == "":
		_editor.add_child(Estilo.etiqueta("ESCRIBE TU MUNDO", 12, Estilo.ACENTO))
		var t := Estilo.etiqueta("Elige un reino, un lugar o una región de la lista, o empieza una ficha nueva.", 26, Estilo.TINTA, Estilo.F_SERIF)
		t.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		_editor.add_child(t)
		var n: Label = main._note_label("Los reinos, imperios, lugares y regiones de este mapa ya tienen su ficha: se rellenan solas con lo que dice el mapa (extensión, a qué imperio pertenecen, qué ciudades tienen dentro, con quién están aliados o en guerra) y tú escribes el resto. Las fichas son del mundo: valen para todos sus mapas.")
		_editor.add_child(n)
		return
	var parts: PackedStringArray = sel.split(":")
	var linked: bool = parts.size() == 3
	var cat: String = ""
	var elem: Dictionary = {}
	if linked:
		var id: int = int(parts[2])
		match parts[1]:
			"r":
				cat = "reino"
				elem = engine.realm_by_id(id)
			"p":
				cat = "lugar"
				elem = engine.place_by_id(id)
			"a":
				cat = "region"
				elem = engine.area_by_id(id)
		if elem.is_empty():
			sel = ""
			_build_editor()
			return
	var e: Dictionary = _entry(sel, cat if linked else str(data["entries"][sel].get("cat", "otro")))
	cat = str(e["cat"])
	_editor.add_child(Estilo.etiqueta(_cat_name(cat).to_upper() + (" · DEL MAPA «%s»" % str(engine.map_name).to_upper() if linked else ""), 12, Estilo.ACENTO))
	# El nombre: en lo del mapa, es el del mapa (cambiarlo aquí lo cambia allí).
	var title := LineEdit.new()
	title.add_theme_font_override("font", Estilo.F_SERIF_SEMI)
	title.add_theme_font_size_override("font_size", 34)
	title.add_theme_color_override("font_color", Estilo.TINTA)
	title.add_theme_stylebox_override("normal", _line_box(false))
	title.add_theme_stylebox_override("focus", _line_box(true))
	title.text = str(elem["name"]) if linked else str(e.get("title", ""))
	title.text_changed.connect(func(t: String):
		if linked:
			return
		e["title"] = t
		e["u"] = int(Time.get_unix_time_from_system())
		_touch()
	)
	title.text_submitted.connect(func(t: String):
		if t.strip_edges() == "":
			return
		if linked:
			match parts[1]:
				"r":
					engine.update_realm(int(parts[2]), "name", t.strip_edges())
				"p":
					engine.update_place(int(parts[2]), "name", t.strip_edges())
				"a":
					engine.update_area(int(parts[2]), "name", t.strip_edges())
		refresh()
	)
	_editor.add_child(title)
	# Lo que dice el mapa.
	if linked:
		var facts: Array = _facts(parts[1], int(parts[2]), elem)
		if not facts.is_empty():
			var fb := VBoxContainer.new()
			fb.add_theme_constant_override("separation", 4)
			_editor.add_child(fb)
			for f in facts:
				var l := Estilo.etiqueta(str(f), 12, Estilo.GRIS)
				l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
				fb.add_child(l)
		var go := Estilo.boton("VER EN EL MAPA  →", 12, Estilo.SOBRE_ACENTO, Estilo.ACENTO, Estilo.ACENTO.darkened(0.15), 16, 10)
		go.size_flags_horizontal = Control.SIZE_SHRINK_BEGIN
		go.pressed.connect(func(): main._show_on_map(parts[1], int(parts[2])))
		_editor.add_child(go)
	_editor.add_child(Estilo.filete(2))
	# Los datos de su tipo.
	var fields: Dictionary = e["fields"]
	var grid := GridContainer.new()
	grid.columns = 2
	grid.add_theme_constant_override("h_separation", 16)
	grid.add_theme_constant_override("v_separation", 8)
	_editor.add_child(grid)
	for fname in _cat_fields(cat):
		var l := Estilo.etiqueta(str(fname).to_upper(), 11, Estilo.GRIS)
		l.custom_minimum_size = Vector2(150, 0)
		grid.add_child(l)
		var le := LineEdit.new()
		le.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		le.add_theme_font_override("font", Estilo.F_SERIF)
		le.add_theme_font_size_override("font_size", 18)
		le.add_theme_color_override("font_color", Estilo.TINTA)
		le.add_theme_stylebox_override("normal", _line_box(false))
		le.add_theme_stylebox_override("focus", _line_box(true))
		le.text = str(fields.get(fname, ""))
		var fk: String = fname
		le.text_changed.connect(func(t: String):
			fields[fk] = t
			e["u"] = int(Time.get_unix_time_from_system())
			_touch()
		)
		grid.add_child(le)
	_editor.add_child(Estilo.etiqueta("SU HISTORIA", 11, Estilo.GRIS))
	var te := TextEdit.new()
	te.wrap_mode = TextEdit.LINE_WRAPPING_BOUNDARY
	te.custom_minimum_size = Vector2(0, 360)
	te.add_theme_font_override("font", Estilo.F_SERIF)
	te.add_theme_font_size_override("font_size", 19)
	te.add_theme_color_override("font_color", Estilo.TINTA)
	te.add_theme_color_override("caret_color", Estilo.ACENTO)
	te.add_theme_constant_override("line_spacing", 6)
	te.add_theme_stylebox_override("normal", Estilo.caja(Estilo.CRUDO, Color(0, 0, 0, 0), 0, 16, 14))
	te.add_theme_stylebox_override("focus", Estilo.caja(Estilo.CRUDO, Estilo.ACENTO, 0, 16, 14))
	te.placeholder_text = "Qué es, de dónde viene, quién manda, qué se cuenta de él…"
	te.text = str(e.get("text", ""))
	te.text_changed.connect(func():
		e["text"] = te.text
		e["u"] = int(Time.get_unix_time_from_system())
		_touch()
	)
	_editor.add_child(te)
	if not linked:
		var del := Estilo.boton("BORRAR ESTA FICHA", 11, Estilo.ACENTO, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 0, 8)
		del.size_flags_horizontal = Control.SIZE_SHRINK_BEGIN
		del.pressed.connect(func():
			main._confirm("cron_" + sel, del, "BORRAR ESTA FICHA", func():
				(data["entries"] as Dictionary).erase(sel)
				sel = ""
				save()
				refresh()
			)
		)
		_editor.add_child(del)

func _line_box(focus: bool) -> StyleBoxFlat:
	var sb := Estilo.caja(Color(0, 0, 0, 0), Color(0, 0, 0, 0), 0, 4, 6)
	sb.border_width_bottom = 2
	sb.border_color = Estilo.ACENTO if focus else Color(Estilo.TINTA, 0.25)
	return sb

## Lo que el mapa sabe de un elemento, en líneas cortas.
func _facts(t: String, id: int, elem: Dictionary) -> Array:
	var out: Array = []
	match t:
		"r":
			out.append(str(elem.get("kind", "REINO")).to_upper())
			var agg: Dictionary = main.overlay.centroids.get(id, {})
			if agg.has("area"):
				out.append("EXTENSIÓN · " + MapOverlay.fmt_km2(main._area_km2(float(agg["area"]))).to_upper())
			var par: int = int(elem.get("parent", 0))
			if par > 0 and not engine.realm_by_id(par).is_empty():
				out.append("PERTENECE A · " + str(engine.realm_by_id(par)["name"]).to_upper())
			var kids: Array = []
			for c in engine.realm_children(id):
				var cr: Dictionary = engine.realm_by_id(int(c))
				if not cr.is_empty():
					kids.append(str(cr["name"]))
			if not kids.is_empty():
				out.append("DENTRO · " + ", ".join(kids).to_upper())
			var towns: Array = []
			for p in engine.places:
				var rid: int = _realm_at(p["uv"])
				if rid == id or (rid > 0 and engine.realm_is_within(rid, id)):
					towns.append("%s (%s)" % [str(p["name"]), str(p["kind"])])
			if not towns.is_empty():
				out.append("LUGARES · " + ", ".join(towns).to_upper())
			for o in engine.realms:
				var oid: int = int(o["id"])
				if oid == id:
					continue
				var rel: String = engine.relation(id, oid)
				if rel != "":
					var ri: int = Catalogo.relacion_idx(rel)
					var rel_name: String = str(Catalogo.RELACIONES[ri][1]) if ri >= 0 else rel
					out.append(("%s CON %s" % [rel_name, str(o["name"])]).to_upper())
		"p":
			var kind: String = str(elem.get("kind", ""))
			var line: String = kind.to_upper()
			if Asentamiento.has_size(kind):
				line += " · " + Asentamiento.size_name(kind, Asentamiento.size_of(elem)).to_upper()
			out.append(line)
			var rid: int = _realm_at(elem["uv"])
			if rid > 0 and not engine.realm_by_id(rid).is_empty():
				out.append("EN · " + str(engine.realm_by_id(rid)["name"]).to_upper())
		"a":
			out.append(str(elem.get("kind", "")).to_upper())
	return out
