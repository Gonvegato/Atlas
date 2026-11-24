extends Control

## ATLAS. — la pantalla principal, con la cara «C» de la familia: cabecera de papel con el nombre en grotesca,
## banda de tinta con las secciones (la elegida en un bloque del acento, con su cuenta) y, a la derecha, el panel
## de la sección. Secciones: Relieve · Biomas · Político · Lugares · Mundo.

const HEADER_H: int = 116
const TABS_H: int = 48
const PANEL_W: int = 340
const MAP_MAT: float = 14.0
const PLACE_HIT_PX: float = 20.0

const MapOverlay = preload("res://scripts/ui/map_overlay.gd")
const Labels3D = preload("res://scripts/ui/labels_3d.gd")

@onready var engine: Node = $HeightmapEngine
@onready var input_ctrl: Node = $TouchInputController
@onready var workspace: Control = $Workspace
@onready var view_2d_root: Control = $Workspace/View2D
@onready var map_canvas: ColorRect = $Workspace/View2D/MapCanvas
@onready var view_3d_container: SubViewportContainer = $Workspace/View3DContainer
@onready var subviewport_3d: SubViewport = $Workspace/View3DContainer/SubViewport3D
@onready var terrain_mesh: MeshInstance3D = $Workspace/View3DContainer/SubViewport3D/TerrainMesh
@onready var sun_light: DirectionalLight3D = $Workspace/View3DContainer/SubViewport3D/DirectionalLight3D
@onready var cam_pivot: Node3D = $Workspace/View3DContainer/SubViewport3D/CameraPivot
@onready var cam_3d: Camera3D = $Workspace/View3DContainer/SubViewport3D/CameraPivot/Camera3D

const SECTIONS = [["relieve", "RELIEVE"], ["biomas", "BIOMAS"], ["regiones", "REGIONES"], ["politico", "POLÍTICO"], ["lugares", "LUGARES"], ["viajes", "VIAJES"], ["mundo", "MUNDO"]]
## [id, nombre, velocidad relativa a ir a pie]
const TRAVEL_MODES = [["pie", "A PIE", 1.0], ["caballo", "A CABALLO", 2.0], ["carro", "EN CARRO", 1.4], ["barco", "EN BARCO", 2.5]]

## [id, nombre, qué hace]
const TOOLS = [
	["land", "TIERRA", "Levanta tierra suave."],
	["mountain", "CORDILLERA", "Levanta montañas con crestas."],
	["sea", "MAR", "Hunde la tierra bajo el mar."],
	["lake", "LAGO", "Hace un lago: fondo bajo el agua y orillas suaves. Con «Contorno», dibuja su orilla."],
	["swamp", "PANTANO", "Deja la tierra a ras del agua, con charcas, y pinta el bioma pantano."],
	["river", "RÍO", "Talla un cauce estrecho."],
	["valley", "VALLE", "Baja la tierra hasta el río: el cauce en medio y las orillas suaves."],
	["canyon", "CAÑÓN", "Corta un cauce hondo con paredes casi verticales."],
	["smooth", "SUAVIZAR", "Redondea lo que toca: costas, cauces, picos. Repasa para más."],
	["cliff", "ACANTILADO", "Recoge la ladera en una sola pared empinada (pásalo por una cuesta o una costa). Elige lo vertical abajo."],
	["plateau", "MESETA", "Aplana a la altura del punto donde empiezas el trazo."],
]

## 0 = infinito (una ventana de 4.096 px que se mueve por un mundo sin bordes guardado en trozos).
const SIZES = [["PEQUEÑO", 1024], ["MEDIANO", 2048], ["GRANDE", 3072], ["ENORME", 4096], ["GIGANTE", 6144], ["COLOSAL", 8192], ["INFINITO", 0]]
const SHAPES = [["CUADRADO", Vector2(1, 1)], ["3:2", Vector2(3, 2)], ["APAISADO 2:1", Vector2(2, 1)], ["VERTICAL 2:3", Vector2(2, 3)]]

const THEMES = [
	{
		"name": "EDITORIAL",
		"deep": Color(0.24, 0.33, 0.43), "shallow": Color(0.42, 0.53, 0.64),
		"low": Color(0.95, 0.92, 0.84), "mid": Color(0.86, 0.82, 0.72), "high": Color(0.66, 0.63, 0.57),
		"snow": Color(0.98, 0.97, 0.94), "ink": Color(0.10, 0.11, 0.13),
		"desat": 0.45, "tint": Color(0.95, 0.92, 0.84), "tint_amt": 0.30
	},
	{
		"name": "PERGAMINO",
		"deep": Color(0.55, 0.48, 0.38), "shallow": Color(0.73, 0.66, 0.53),
		"low": Color(0.93, 0.87, 0.73), "mid": Color(0.82, 0.74, 0.58), "high": Color(0.60, 0.50, 0.38),
		"snow": Color(0.96, 0.93, 0.86), "ink": Color(0.22, 0.15, 0.10),
		"desat": 0.75, "tint": Color(0.86, 0.74, 0.55), "tint_amt": 0.50
	},
	{
		"name": "FANTASÍA",
		"deep": Color(0.09, 0.22, 0.38), "shallow": Color(0.22, 0.52, 0.66),
		"low": Color(0.45, 0.62, 0.38), "mid": Color(0.62, 0.60, 0.38), "high": Color(0.48, 0.44, 0.42),
		"snow": Color(0.96, 0.97, 0.99), "ink": Color(0.08, 0.10, 0.12),
		"desat": 0.0, "tint": Color(1, 1, 1), "tint_amt": 0.0
	}
]

# --- estado
var section: String = "relieve"
var current_tool: String = "mountain"
var current_biome: int = 2
var current_realm: int = -1
var realm_erase: bool = false
var place_kind: String = "ciudad"
## Lugares: "lugares" (tocar pone uno) o "caminos" (el lápiz dibuja un camino).
var lugares_mode: String = "lugares"
var road_kind: String = "camino"
var selected_road: int = -1
var road_pts: Array = []
var _road_last: Vector2 = Vector2.ZERO
var lugares_mode_chips: Array[Button] = []
var road_kind_chips: Array[Button] = []
var places_box: VBoxContainer
var roads_box: VBoxContainer
var road_editor: Control
var lbl_road_desc: Label
var lbl_road_count: Label
var area_kind: String = "bosque"
var current_area: int = -1
var area_erase: bool = false
var trees_3d: bool = true
## Sombras del relieve en 3D (se guarda en atlas.cfg: es cosa del dispositivo, no del mapa).
var shadows_3d: bool = true
var _world3d_dirty: bool = true
var _places3d_dirty: bool = true
var _place_label_pos: Dictionary = {}
var selected_place: int = -1
var brush_radius: float = 38.0
var brush_strength: float = 0.5
var cliff_steps: float = 7.0
## Fundido entre biomas, 0-1 (se guarda con el mapa). El radio en píxeles crece con el tamaño del mapa.
var biome_blend: float = 0.5
var blend_slider: HSlider
var blend_vp: SubViewport
var blend_rect: ColorRect
var mat_blend: ShaderMaterial
var _blend_queued: bool = false
var _last_blend_ms: int = 0
var place_size_box: VBoxContainer
var place_size_slider: HSlider
var place_size_val: Label
var place_size_note: Label
var sea_level: float = 0.5
var sun_azimuth: float = 315.0
var relief_mult: float = 1.0
var map_zoom: float = 1.0
var map_offset: Vector2 = Vector2.ZERO
var yaw: float = 0.45
var pitch: float = -0.62
var cam_dist: float = 13.5
var current_theme_idx: int = 0
var new_size_idx: int = 1
var new_shape_idx: int = 0
var new_mode: int = 0
var show_symbols: bool = true
var show_realms: bool = true
var _stroke_kind: String = ""
var _drag_place: int = -1
var _drag_before: Dictionary = {}
var _drag_moved: bool = false
var _last_pan_pos: Vector2 = Vector2.ZERO
var _armed: String = ""
# Gesto táctil del panel lateral: 0 nada · 1 esperando (aún no se sabe si es toque, desplazamiento o deslizador) ·
# 2 desplazando el panel · 3 se lo pasamos a la interfaz (deslizador en horizontal).
var panel_rect_node: Control
var panel_scroll: ScrollContainer
var _pg_state: int = 0
var _pg_start: Vector2 = Vector2.ZERO
var _pg_last: Vector2 = Vector2.ZERO
var _pg_scroll0: float = 0.0
var _pg_vel: float = 0.0
const PG_SLOP: float = 10.0

var mat_2d: ShaderMaterial
var mat_3d: ShaderMaterial
var overlay: Control
var labels3d: Control
var vegetation: Vegetacion3D
var places3d: Lugares3D
var biome_pal_tex: ImageTexture
var area_list: VBoxContainer
var area_editor: VBoxContainer
var area_name_edit: LineEdit
var area_kind_chips: Array[Button] = []
var area_mode_chips: Array[Button] = []
var area_biome_option: OptionButton
var lbl_area_count: Label
var lbl_area_hint: Label
var lbl_trees: Label
var density_chips: Array[Button] = []
var lbl_msaa_note: Label
# --- relaciones, viajes, mapas guardados y modo vertical
var relation_box: VBoxContainer
var realm_parent_option: OptionButton
var route_pts: Array = []
var route_mode: String = "pie"
var travel_mode_chips: Array[Button] = []
var val_route_dist: Label
var val_route_time: Label
var val_route_legs: Label
var lbl_travel_calib: Label
var calib_amount: LineEdit
var calib_unit: String = "dias"
var calib_unit_chips: Array[Button] = []
var scale_km_edit: LineEdit
var route_list: VBoxContainer
var map_name_edit: LineEdit
var lbl_save_state: Label
var maps_list: VBoxContainer
var sea_slider: HSlider
var sun_slider: HSlider
var autosave_timer: Timer
var panel_node: Panel
var header_name_lbl: Label
var header_dot_lbl: Label
var header_date_lbl: Label
var b_fit_btn: Button
var portrait: bool = false
## "pincel" o "contorno" (se dibuja el borde y se rellena lo de dentro).
var draw_mode: String = "pincel"
var outline_pts: Array = []
var _outline_last: Vector2 = Vector2.ZERO
var _stroke_bounds: Rect2 = Rect2()
## El lienzo crece solo al dibujar tierra junto al borde.
var grow_canvas: bool = true
var draw_mode_chips: Array[Button] = []
var quick_mode_chips: Array[Button] = []
var quick_bar: HBoxContainer
var toast_panel: PanelContainer
var toast_label: Label
var _toast_tween: Tween
var _raw_centroids: Dictionary = {}
# --- cuenta de la familia
var nube: FamiliaNube
var cuenta_box: VBoxContainer
var lbl_nube_estado: Label
var nube_obras: Array = []
var nube_mapas: Array = []
var upload_timer: Timer
var new_obra: String = ""
var new_obra_opt: OptionButton

# --- interfaz
var btn_mode_3d: Button
var btn_undo: Button
var btn_redo: Button
var lbl_world: Label
var lbl_hint: Label
var tab_buttons: Dictionary = {}
var sections_ui: Dictionary = {}
var brush_box: VBoxContainer
var strength_row: Control
var steps_row: Control
var sec_relief_3d: Control
var tool_chips: Array[Button] = []
var lbl_tool_name: Label
var lbl_tool_desc: Label
var val_size: Label
var val_strength: Label
var val_steps: Label
var val_sea: Label
var val_sun: Label
var val_relief: Label
var val_land: Label
var val_water: Label
var val_strokes: Label
var lbl_map_px: Label
var lbl_quality_note: Label
var biome_buttons: Array[Button] = []
var lbl_biome_name: Label
var theme_buttons: Array[Button] = []
var realm_list: VBoxContainer
var realm_editor: VBoxContainer
var realm_name_edit: LineEdit
var realm_kind_chips: Array[Button] = []
var realm_color_buttons: Array[Button] = []
var realm_mode_chips: Array[Button] = []
var lbl_realm_count: Label
var lbl_realm_hint: Label
var place_kind_chips: Array[Button] = []
var place_editor: VBoxContainer
var place_name_edit: LineEdit
var lbl_place_count: Label
var size_chips: Array[Button] = []
var shape_chips: Array[Button] = []
var mode_chips: Array[Button] = []
var btn_create: Button
var extend_buttons: Dictionary = {}
var btn_quality: Button
var chk_symbols: Button
var chk_realms: Button
var chk_names: Button

func _ready() -> void:
	workspace.offset_top = HEADER_H + TABS_H
	workspace.offset_right = -PANEL_W
	biome_pal_tex = Catalogo.textura_biomas()
	_build_ui()
	_setup_materials()
	_connect_signals()
	view_3d_container.visible = false
	subviewport_3d.render_target_update_mode = SubViewport.UPDATE_DISABLED
	_apply_theme(0)
	_apply_sun()
	_update_3d_camera()
	_select_tool(current_tool)
	_select_biome(current_biome)
	_select_section("relieve")
	_set_draw_mode("pincel")
	# El 3D a la resolución real de la pantalla (no a la lógica de 1280×800), con MSAA 4×.
	view_3d_container.stretch = false
	view_3d_container.set_anchors_preset(Control.PRESET_TOP_LEFT)
	# Que crezca hacia abajo y a la derecha (en la escena crecía hacia los dos lados y se descolocaba).
	view_3d_container.grow_horizontal = Control.GROW_DIRECTION_END
	view_3d_container.grow_vertical = Control.GROW_DIRECTION_END
	_msaa_guard_load()
	subviewport_3d.anisotropic_filtering_level = Viewport.ANISOTROPY_16X
	workspace.resized.connect(_fit_3d_viewport)
	get_viewport().size_changed.connect(_fit_3d_viewport)
	get_window().size_changed.connect(_apply_layout)
	_apply_layout()
	await get_tree().process_frame
	_center_map()
	_fit_3d_viewport()
	view_2d_root.resized.connect(_center_map)

# ================================================================ gesto táctil del panel lateral

## En Godot los botones se quedan el arrastre y el ScrollContainer no se mueve si el dedo empieza encima de uno; y
## un arrastre vertical encima de un deslizador le cambia el valor. Aquí se decide antes de que la interfaz vea nada:
## vertical → se desplaza el panel; horizontal → se le pasa a la interfaz (deslizadores); sin moverse → toque normal.
func _input(event: InputEvent) -> void:
	# Los eventos que reenvía este filtro llevan una marca (no se vuelven a interceptar).
	if panel_rect_node == null or event.has_meta("atlas_panel"):
		return
	var emulated: bool = (event is InputEventMouseButton or event is InputEventMouseMotion) and event.device == InputEvent.DEVICE_ID_EMULATION
	var touch: bool = event is InputEventScreenTouch or event is InputEventScreenDrag
	if not (emulated or touch):
		return
	# Solo el primer dedo; los demás no se tocan, salvo que estemos en mitad de un gesto del panel.
	if touch and int(event.get("index")) != 0:
		if _pg_state == 1 or _pg_state == 2:
			get_viewport().set_input_as_handled()
		return
	var pos: Vector2 = event.get("position")
	var is_press: bool = (event is InputEventScreenTouch and event.pressed) or (event is InputEventMouseButton and event.pressed and event.button_index == MOUSE_BUTTON_LEFT)
	var is_release: bool = (event is InputEventScreenTouch and not event.pressed) or (event is InputEventMouseButton and not event.pressed and event.button_index == MOUSE_BUTTON_LEFT)
	var is_move: bool = event is InputEventScreenDrag or event is InputEventMouseMotion
	# Godot entrega el clic emulado y el toque por separado (y el clic, a veces, antes): el gesto empieza con el
	# primero que llegue y el otro se descarta.
	if is_press:
		if _pg_state == 0 and panel_rect_node.get_global_rect().has_point(pos):
			_pg_state = 1
			_pg_start = pos
			_pg_last = pos
			_pg_scroll0 = float(panel_scroll.scroll_vertical)
			_pg_vel = 0.0
			get_viewport().set_input_as_handled()
		elif _pg_state == 1 or _pg_state == 2:
			get_viewport().set_input_as_handled()
		return
	if is_release:
		match _pg_state:
			1:
				# Toque sin moverse: se le da a la interfaz (pulsar y soltar donde empezó).
				_pg_state = 0
				get_viewport().set_input_as_handled()
				_replay([_click_ev(_pg_start, true), _click_ev(_pg_start, false)])
			2:
				_pg_state = 0
				get_viewport().set_input_as_handled()
			3:
				if event is InputEventScreenTouch:
					_pg_state = 0
		return
	if is_move:
		match _pg_state:
			1:
				get_viewport().set_input_as_handled()
				var mv: Vector2 = pos - _pg_start
				if mv.length() >= PG_SLOP:
					if absf(mv.y) >= absf(mv.x):
						_pg_state = 2
					else:
						# Horizontal: para la interfaz (deslizadores). Se le da el clic inicial y desde ahí, lo demás.
						_pg_state = 3
						_replay([_click_ev(_pg_start, true)])
				_pg_last = pos
			2:
				get_viewport().set_input_as_handled()
				if event is InputEventScreenDrag:
					var dy: float = pos.y - _pg_last.y
					_pg_vel = lerpf(_pg_vel, dy * 60.0, 0.5)
				panel_scroll.scroll_vertical = int(_pg_scroll0 - (pos.y - _pg_start.y))
				_pg_last = pos

func _click_ev(p: Vector2, pressed: bool) -> InputEventMouseButton:
	var m := InputEventMouseButton.new()
	m.button_index = MOUSE_BUTTON_LEFT
	m.pressed = pressed
	m.position = p
	m.global_position = p
	m.button_mask = MOUSE_BUTTON_MASK_LEFT if pressed else 0
	return m

## Reenvía eventos a la interfaz, marcados para que este mismo filtro no los vuelva a interceptar.
## Ojo: aquí las posiciones están en unidades de la interfaz (1280×800 u 800×1280), pero Input.parse_input_event las
## espera en píxeles de la pantalla; sin pasarlas, en una tablet (escala 1,5 o 2) el toque caía en otro sitio y los botones
## del panel no respondían.
func _replay(events: Array) -> void:
	var xf: Transform2D = get_tree().root.get_final_transform()
	for e in events:
		var ev: InputEvent = e
		if ev is InputEventMouseButton:
			var mb: InputEventMouseButton = ev
			mb.position = xf * mb.position
			mb.global_position = mb.position
		ev.set_meta("atlas_panel", true)
		Input.parse_input_event(ev)

func _process(delta: float) -> void:
	# Inercia del panel al soltar después de desplazar.
	if _pg_state == 0 and absf(_pg_vel) > 20.0 and panel_scroll != null:
		panel_scroll.scroll_vertical = int(float(panel_scroll.scroll_vertical) - _pg_vel * delta)
		_pg_vel *= pow(0.04, delta)
	elif _pg_state == 0:
		_pg_vel = 0.0

# ---------------------------------------------------------------- modo vertical

## En vertical el panel pasa abajo (a lo ancho) y el mapa arriba; la interfaz se calcula sobre 800×1280 en vez de
## 1280×800 para que el texto mida lo mismo que en horizontal.
func _apply_layout() -> void:
	var win: Vector2i = get_window().size
	var now_portrait: bool = win.y > win.x
	get_tree().root.content_scale_size = Vector2i(800, 1280) if now_portrait else Vector2i(1280, 800)
	if now_portrait == portrait and panel_node != null and panel_node.has_meta("laid"):
		return
	portrait = now_portrait
	if panel_node == null:
		return
	panel_node.set_meta("laid", true)
	var sb: StyleBoxFlat = Estilo.caja(Estilo.PAPEL)
	sb.border_color = Estilo.TINTA
	if portrait:
		var ph: int = 520
		panel_node.anchor_left = 0.0
		panel_node.anchor_right = 1.0
		panel_node.anchor_top = 1.0
		panel_node.anchor_bottom = 1.0
		panel_node.offset_left = 0
		panel_node.offset_right = 0
		panel_node.offset_top = -ph
		panel_node.offset_bottom = 0
		sb.border_width_top = 3
		panel_scroll.offset_left = 0
		panel_scroll.offset_top = 3
		workspace.offset_right = 0
		workspace.offset_bottom = -ph
	else:
		panel_node.anchor_left = 1.0
		panel_node.anchor_right = 1.0
		panel_node.anchor_top = 0.0
		panel_node.anchor_bottom = 1.0
		panel_node.offset_left = -PANEL_W
		panel_node.offset_right = 0
		panel_node.offset_top = HEADER_H + TABS_H
		panel_node.offset_bottom = 0
		sb.border_width_left = 3
		panel_scroll.offset_left = 3
		panel_scroll.offset_top = 0
		workspace.offset_right = -PANEL_W
		workspace.offset_bottom = 0
	panel_node.add_theme_stylebox_override("panel", sb)
	# Cabecera más compacta en vertical.
	var fs: int = 52 if portrait else 64
	header_name_lbl.add_theme_font_size_override("font_size", fs)
	header_dot_lbl.add_theme_font_size_override("font_size", fs)
	lbl_world.visible = not portrait
	header_date_lbl.text = (Estilo.fecha_corta()) if portrait else ("  ·  " + Estilo.fecha_corta())
	b_fit_btn.visible = not portrait
	lbl_hint.visible = not portrait
	for k in tab_buttons.keys():
		var tb: Button = tab_buttons[k]
		(tb.get_meta("name_l") as Label).add_theme_font_size_override("font_size", 11 if portrait else 12)
		_fit_tab(tb)
	_select_section(section)
	_center_map.call_deferred()
	_fit_3d_viewport.call_deferred()

## Píxeles reales de pantalla por unidad de la interfaz (2 en una tablet de 2560×1600 con la base de 1280×800).
func _screen_scale() -> float:
	var logical: Vector2 = get_viewport().get_visible_rect().size
	if logical.y <= 0.0:
		return 1.0
	return clampf(float(DisplayServer.window_get_size().y) / logical.y, 1.0, 4.0)

## Antes el SubViewport del 3D tomaba el tamaño lógico del contenedor y se estiraba al doble en pantallas densas
## (borroso y con parpadeo al alejarse). Ahora mide lo mismo que la pantalla y el contenedor se encoge a la mitad.
func _fit_3d_viewport() -> void:
	var f: float = _screen_scale()
	var sz: Vector2 = workspace.size
	if sz.x <= 0.0 or sz.y <= 0.0:
		return
	var px := Vector2i((sz * f).round())
	subviewport_3d.size = px
	view_3d_container.size = Vector2(px)
	view_3d_container.scale = Vector2.ONE / f
	view_3d_container.position = Vector2.ZERO
	if labels3d != null:
		labels3d.px_scale = f
		labels3d.queue_redraw()

# ================================================================ mapa y 3D

func _setup_materials() -> void:
	mat_2d = ShaderMaterial.new()
	mat_2d.shader = load("res://shaders/map_2d.gdshader")
	mat_2d.set_shader_parameter("biome_pal", biome_pal_tex)
	map_canvas.material = mat_2d
	# El fundido entre biomas se precalcula aquí (ver _bake_blend).
	blend_vp = SubViewport.new()
	blend_vp.transparent_bg = true
	blend_vp.disable_3d = true
	blend_vp.render_target_update_mode = SubViewport.UPDATE_DISABLED
	blend_vp.size = Vector2i(512, 512)
	add_child(blend_vp)
	blend_rect = ColorRect.new()
	blend_rect.position = Vector2.ZERO
	blend_rect.size = Vector2(512, 512)
	mat_blend = ShaderMaterial.new()
	mat_blend.shader = load("res://shaders/biome_blend.gdshader")
	mat_blend.set_shader_parameter("biome_pal", biome_pal_tex)
	blend_rect.material = mat_blend
	blend_vp.add_child(blend_rect)

	mat_3d = ShaderMaterial.new()
	mat_3d.shader = load("res://shaders/terrain_3d.gdshader")
	mat_3d.set_shader_parameter("biome_pal", biome_pal_tex)
	mat_3d.set_shader_parameter("biome_data", biome_pal_tex)
	mat_3d.set_shader_parameter("ground_tex", load("res://assets/terrain/suelos.png"))
	terrain_mesh.material_override = mat_3d
	_rebuild_realm_palette()

func _on_layers_ready(size: Vector2i) -> void:
	for m in [mat_2d, mat_3d]:
		m.set_shader_parameter("height_tex", engine.height.get_texture())
		m.set_shader_parameter("biome_tex", engine.biome.get_texture())
		m.set_shader_parameter("realm_tex", engine.realm.get_texture())
	mat_2d.set_shader_parameter("area_tex", engine.area.get_texture())
	if map_frame != null:
		# En un mundo infinito no hay marco: el mapa no acaba.
		map_frame.visible = not engine.infinite
	_apply_blend()
	_world3d_dirty = true
	_places3d_dirty = true
	var long_side: float = float(maxi(size.x, size.y))
	var world := Vector2(size) / long_side * 12.0
	var plane := PlaneMesh.new()
	plane.size = world
	# Un vértice cada ~3 píxeles del mapa (683 en uno de 2.048), hasta 900 por lado: con menos, las paredes de
	# cañones y acantilados salían en dientes de sierra.
	var subdiv: float = clampf(long_side / 3.0, 256.0, 900.0)
	plane.subdivide_width = int(round(subdiv * world.x / 12.0))
	plane.subdivide_depth = int(round(subdiv * world.y / 12.0))
	terrain_mesh.mesh = plane
	mat_3d.set_shader_parameter("terrain_size", world)
	mat_3d.set_shader_parameter("vertex_step", 1.0 / subdiv)
	lbl_map_px.text = "%s × %s PX" % [Estilo.miles(size.x), Estilo.miles(size.y)]
	lbl_world.text = engine.map_name.to_upper()
	if map_name_edit != null and not map_name_edit.has_focus():
		map_name_edit.text = engine.map_name
	_center_map()
	_refresh_quality()

func _connect_signals() -> void:
	input_ctrl.stroke_started.connect(_on_stroke_started)
	input_ctrl.stroke_moved.connect(_on_stroke_moved)
	input_ctrl.stroke_ended.connect(_on_stroke_ended)
	input_ctrl.pan_changed.connect(func(d: Vector2):
		map_offset += d
		_apply_2d_transform()
	)
	input_ctrl.zoom_changed.connect(func(factor: float, center: Vector2):
		if input_ctrl.is_3d_mode:
			cam_dist = clampf(cam_dist / factor, 4.5, 24.0)
			_update_3d_camera()
		else:
			# center ya viene en coordenadas locales del Workspace (las de gui_input).
			var old_zoom: float = map_zoom
			map_zoom = clampf(map_zoom * factor, 0.5, 16.0)
			map_offset = center - (center - map_offset) * (map_zoom / old_zoom)
			_apply_2d_transform()
	)
	input_ctrl.orbit_changed.connect(func(d: Vector2):
		yaw -= d.x * 0.007
		pitch = clampf(pitch - d.y * 0.007, -1.45, -0.15)
		_update_3d_camera()
	)
	engine.layers_ready.connect(_on_layers_ready)
	autosave_timer = Timer.new()
	autosave_timer.one_shot = true
	autosave_timer.wait_time = 4.0
	autosave_timer.timeout.connect(_do_save)
	add_child(autosave_timer)
	_recenter_timer = Timer.new()
	_recenter_timer.one_shot = true
	_recenter_timer.wait_time = 0.35
	_recenter_timer.timeout.connect(_try_recenter)
	add_child(_recenter_timer)
	engine.dirty_changed.connect(_schedule_save)
	engine.saved.connect(_on_saved)
	engine.map_loaded.connect(_on_map_loaded)
	nube = FamiliaNube.new()
	add_child(nube)
	nube.sesion_cambiada.connect(func(_d: bool): _refresh_cuenta(true))
	upload_timer = Timer.new()
	upload_timer.one_shot = true
	upload_timer.wait_time = 20.0
	upload_timer.timeout.connect(_upload_now)
	add_child(upload_timer)
	engine.realms_changed.connect(func(): _refresh_route.call_deferred())
	engine.history_changed.connect(func(can_undo: bool, can_redo: bool):
		btn_undo.disabled = not can_undo
		btn_redo.disabled = not can_redo
		val_strokes.text = Estilo.miles(engine.stroke_count)
	)
	engine.stats_changed.connect(func(land_frac: float, _max_h: float):
		var land_pct: int = int(round(land_frac * 100.0))
		val_land.text = "%d %%" % land_pct
		val_water.text = "%d %%" % (100 - land_pct)
		val_strokes.text = Estilo.miles(engine.stroke_count)
	)
	engine.realms_changed.connect(_on_realms_changed)
	engine.areas_changed.connect(_on_areas_changed)
	engine.area_shapes_changed.connect(func(sh: Dictionary):
		overlay.area_shapes = sh
		overlay.queue_redraw()
		_refresh_labels_3d()
	)
	engine.layer_changed.connect(func(layer_name: String):
		if layer_name == "realm":
			_places3d_dirty = true
		if layer_name == "biome":
			_queue_blend_bake()
		if layer_name == "biome" or layer_name == "height":
			_world3d_dirty = true
			_places3d_dirty = true
			if input_ctrl.is_3d_mode:
				_rebuild_3d.call_deferred()
	)
	engine.places_changed.connect(_on_places_changed)
	engine.roads_changed.connect(_on_roads_changed)
	engine.realm_centroids_changed.connect(func(c: Dictionary):
		_raw_centroids = c
		overlay.centroids = _realm_aggregate(c)
		overlay.queue_redraw()
	)

func _on_workspace_gui_input(event: InputEvent) -> void:
	input_ctrl.handle_input(event)

func _pos_to_uv(pos: Vector2) -> Vector2:
	return (pos - map_offset) / (map_canvas.size * map_zoom)

func _brush_px_on_screen(r: float) -> float:
	return r / float(engine.map_size.x) * map_canvas.size.x * map_zoom

## Radio real de la herramienta (en píxeles del mapa) según el deslizador.
func _tool_radius() -> float:
	var r: float = brush_radius * float(maxi(engine.map_size.x, engine.map_size.y)) / 1024.0
	match current_tool:
		"river":
			return r * 0.6
		"valley":
			return r * 1.8
		"canyon":
			return r * 1.6
		"lake":
			return r * 1.2
	return r

func _on_stroke_started(pos: Vector2, pressure: float) -> void:
	if not engine.world_ready:
		return
	_stroke_kind = ""
	var uv: Vector2 = _pos_to_uv(pos)
	_stroke_bounds = Rect2(uv, Vector2.ZERO)
	# Contorno: se va guardando el borde y al soltar se rellena (ver _on_stroke_ended).
	if _outline_target() != "":
		_stroke_kind = "outline"
		outline_pts = [uv]
		_outline_last = pos
		overlay.outline = outline_pts
		overlay.queue_redraw()
		return
	match section:
		"relieve":
			_stroke_kind = "paint"
			engine.begin_stroke(current_tool, 0, uv)
		"biomas":
			_stroke_kind = "paint"
			engine.begin_stroke("biome", current_biome, uv)
		"regiones":
			if current_area <= 0 and not area_erase:
				_flash(lbl_area_hint)
				return
			_stroke_kind = "paint"
			engine.begin_stroke("area", 0 if area_erase else current_area, uv)
		"politico":
			if current_realm <= 0 and not realm_erase:
				_flash_realm_hint()
				return
			_stroke_kind = "paint"
			engine.begin_stroke("realm", 0 if realm_erase else current_realm, uv)
		"lugares":
			if lugares_mode == "caminos":
				_stroke_kind = "road"
				road_pts = [uv.clamp(Vector2.ZERO, Vector2.ONE)]
				_road_last = pos
				overlay.road_draft = road_pts
				overlay.road_draft_kind = road_kind
				overlay.queue_redraw()
				return
			_place_down(pos, uv)
			return
		"viajes":
			_stroke_kind = "route"
			if uv.x < 0.0 or uv.y < 0.0 or uv.x > 1.0 or uv.y > 1.0:
				return
			# Si se toca un lugar, la ruta pasa justo por él.
			var hit: int = _hit_place(pos)
			if hit > 0:
				uv = engine.place_by_id(hit)["uv"]
			route_pts.append(uv)
			_refresh_route()
			return
		_:
			_stroke_kind = "pan"
			_last_pan_pos = pos
			return
	_on_stroke_moved(pos, pressure)

func _on_stroke_moved(pos: Vector2, pressure: float) -> void:
	match _stroke_kind:
		"outline":
			if pos.distance_to(_outline_last) >= 3.0:
				var ouv: Vector2 = _pos_to_uv(pos)
				outline_pts.append(ouv)
				_stroke_bounds = _stroke_bounds.expand(ouv)
				_outline_last = pos
				overlay.queue_redraw()
		"road":
			if pos.distance_to(_road_last) >= 4.0:
				road_pts.append(_pos_to_uv(pos).clamp(Vector2.ZERO, Vector2.ONE))
				_road_last = pos
				overlay.queue_redraw()
		"paint":
			var uv: Vector2 = _pos_to_uv(pos)
			_stroke_bounds = _stroke_bounds.expand(uv)
			var r: float = _tool_radius() if section == "relieve" else brush_radius * float(maxi(engine.map_size.x, engine.map_size.y)) / 1024.0
			var s: float = _tool_strength(pressure)
			engine.paint_uv(uv, r, s, cliff_steps)
			# El fundido de biomas se recalcula mientras pintas (como mucho cada 0,1 s).
			if section in ["biomas", "regiones"] and Time.get_ticks_msec() - _last_blend_ms > 100:
				_last_blend_ms = Time.get_ticks_msec()
				_queue_blend_bake()
			var subtracts: bool = section == "relieve" and current_tool in ["sea", "river", "valley", "canyon"]
			overlay.show_ring(pos, _brush_px_on_screen(r), subtracts)
		"place":
			if _drag_place > 0:
				var uv2: Vector2 = _pos_to_uv(pos).clamp(Vector2.ZERO, Vector2.ONE)
				_drag_moved = true
				engine.move_place_live(_drag_place, uv2)
		"pan":
			map_offset += pos - _last_pan_pos
			_last_pan_pos = pos
			_apply_2d_transform()

func _on_stroke_ended() -> void:
	match _stroke_kind:
		"outline":
			var pts: Array = outline_pts.duplicate()
			var target: String = _outline_target()
			var value: int = _outline_value()
			outline_pts = []
			overlay.outline = []
			if pts.size() >= 3:
				_finish_outline(target, value, pts)
			else:
				overlay.queue_redraw()
		"paint":
			overlay.hide_ring()
			engine.end_stroke()
			if section == "relieve" and current_tool in ["land", "mountain"]:
				_maybe_grow(_stroke_bounds)
		"place":
			if _drag_place > 0 and _drag_moved:
				engine.commit_place_move(_drag_before)
		"road":
			var pts: Array = road_pts.duplicate()
			road_pts = []
			overlay.road_draft = []
			var map_px: Vector2 = Vector2(engine.map_size)
			var len_px: float = 0.0
			for i in range(pts.size() - 1):
				len_px += (Vector2(pts[i + 1]) * map_px).distance_to(Vector2(pts[i]) * map_px)
			# Un toque (o un trazo mínimo) elige el camino que haya debajo; un trazo lo dibuja.
			var screen_per_px: float = map_canvas.size.x * map_zoom / map_px.x
			if len_px * screen_per_px < 12.0:
				var hit: int = engine.road_at(Vector2(pts[0]), 14.0 / maxf(screen_per_px, 0.0001))
				_select_road(hit)
			else:
				var simple: Array = _simplify(pts, 0.6 / maxf(map_px.x, map_px.y))
				var id: int = engine.add_road(road_kind, simple)
				_select_road(-1)
				if id > 0:
					_toast("%s de %s." % [str(Catalogo.tipo_camino(road_kind)[1]).capitalize(), _fmt_len(len_px)])
			overlay.queue_redraw()
	_stroke_kind = ""
	_drag_place = -1
	_drag_moved = false

## Qué rellena un contorno en esta sección y herramienta ("" = se pinta con pincel).
func _outline_target() -> String:
	if draw_mode != "contorno":
		return ""
	match section:
		"relieve":
			return current_tool if current_tool in ["land", "sea", "lake", "swamp"] else ""
		"biomas":
			return "biome"
		"regiones":
			return "area" if (current_area > 0 or area_erase) else ""
		"politico":
			return "realm" if (current_realm > 0 or realm_erase) else ""
	return ""

func _outline_value() -> int:
	match section:
		"biomas":
			return current_biome
		"regiones":
			return 0 if area_erase else current_area
		"politico":
			return 0 if realm_erase else current_realm
	return 0

func _finish_outline(target: String, value: int, pts: Array) -> void:
	overlay.queue_redraw()
	var r: Rect2i = await engine.fill_outline(target, value, pts)
	if r.size.x <= 0:
		_toast("No se pudo rellenar ese contorno: dibújalo más grande.")
		return
	if target == "land":
		var b := Rect2(pts[0], Vector2.ZERO)
		for p in pts:
			b = b.expand(p)
		_maybe_grow(b)

## Si se ha dibujado tierra pegada al borde, el lienzo crece por ese lado (hasta 8.192 px).
func _maybe_grow(b: Rect2) -> void:
	if not grow_canvas:
		return
	var edge: float = 0.03
	var dirs: Array = []
	if b.position.x < edge:
		dirs.append(["O", "al oeste"])
	if b.end.x > 1.0 - edge:
		dirs.append(["E", "al este"])
	if b.position.y < edge:
		dirs.append(["N", "al norte"])
	if b.end.y > 1.0 - edge:
		dirs.append(["S", "al sur"])
	var grown: Array = []
	for d in dirs:
		if await engine.extend(str(d[0])):
			grown.append(d[1])
	if not grown.is_empty():
		_toast("El lienzo ha crecido %s (%s × %s px)." % [", ".join(grown), Estilo.miles(engine.map_size.x), Estilo.miles(engine.map_size.y)])
	elif not dirs.is_empty() and maxi(engine.map_size.x, engine.map_size.y) >= engine.MAX_SIDE:
		_toast("El lienzo ya está en el máximo (8.192 px).")

func _set_draw_mode(m: String) -> void:
	draw_mode = m
	for i in draw_mode_chips.size():
		_paint_filter(draw_mode_chips[i], (i == 1) == (m == "contorno"))
	# La barra rápida flota sobre la mesa oscura: el apagado lleva fondo de papel para que se lea.
	for i in quick_mode_chips.size():
		var on: bool = (i == 1) == (m == "contorno")
		if on:
			Estilo.pintar_boton(quick_mode_chips[i], Estilo.SOBRE_ACENTO, Estilo.ACENTO, Estilo.ACENTO.darkened(0.15), 16, 10, Estilo.TINTA, 2)
		else:
			Estilo.pintar_boton(quick_mode_chips[i], Estilo.TINTA, Estilo.PAPEL, Estilo.CRUDO, 16, 10, Estilo.TINTA, 2)
	_update_hint()

## Un aviso breve abajo del mapa.
func _toast(text: String) -> void:
	toast_label.text = text
	toast_panel.visible = true
	toast_panel.modulate.a = 1.0
	if _toast_tween != null:
		_toast_tween.kill()
	_toast_tween = create_tween()
	_toast_tween.tween_interval(2.6)
	_toast_tween.tween_property(toast_panel, "modulate:a", 0.0, 0.5)
	_toast_tween.tween_callback(func(): toast_panel.visible = false)

## Fuerza según la herramienta: los pinceles que suman usan incrementos pequeños; los que mezclan, alfa.
func _tool_strength(pressure: float) -> float:
	var f: float = brush_strength
	match current_tool:
		"land", "mountain", "sea":
			return lerpf(0.006, 0.05, f) * pressure
		"river":
			return lerpf(0.006, 0.05, f) * pressure * 1.35
		"valley", "canyon", "lake":
			return lerpf(0.15, 0.7, f)
		_:
			return lerpf(0.05, 0.5, f) * pressure

func _place_down(pos: Vector2, uv: Vector2) -> void:
	_stroke_kind = "place"
	var hit: int = _hit_place(pos)
	if hit > 0:
		_select_place(hit)
		_drag_place = hit
		_drag_before = engine.meta_snapshot()
		_drag_moved = false
		return
	if uv.x < 0.0 or uv.y < 0.0 or uv.x > 1.0 or uv.y > 1.0:
		return
	var id: int = engine.add_place(place_kind, uv)
	_select_place(id)

func _hit_place(pos: Vector2) -> int:
	var best: int = -1
	var best_d: float = PLACE_HIT_PX
	for p in engine.places:
		var uv: Vector2 = p["uv"]
		var d: float = overlay.uv_to_screen(uv).distance_to(pos)
		if d < best_d:
			best_d = d
			best = int(p["id"])
	return best

func _center_map() -> void:
	var ws_size: Vector2 = view_2d_root.size
	if ws_size.x <= 0.0 or ws_size.y <= 0.0 or engine == null:
		return
	var aspect: float = float(engine.map_size.x) / float(engine.map_size.y)
	var avail: Vector2 = ws_size - Vector2.ONE * ((MAP_MAT + 3.0) * 2.0 + 48.0)
	var w: float = avail.x
	var h: float = w / aspect
	if h > avail.y:
		h = avail.y
		w = h * aspect
	map_canvas.size = Vector2(w, h)
	map_zoom = 1.0
	map_offset = (ws_size - map_canvas.size) * 0.5
	if engine.infinite:
		# Centrado en el medio de la ventana, con el zoom mínimo del mundo infinito.
		map_zoom = 0.0001
		map_offset = (ws_size - map_canvas.size * map_zoom) * 0.5
	_apply_2d_transform()
	if engine.infinite:
		map_offset = (ws_size - map_canvas.size * map_zoom) * 0.5
		_apply_2d_transform()

## Mundo infinito: no se puede alejar tanto como para ver más de media ventana, ni salir de ella (la ventana se
## recoloca antes de llegar al borde, así que nunca se ve dónde acaba).
func _clamp_infinite_view() -> void:
	var ws: Vector2 = view_2d_root.size
	if ws.x <= 0.0 or map_canvas.size.x <= 0.0:
		return
	var fill: float = maxf(ws.x / map_canvas.size.x, ws.y / map_canvas.size.y)
	var min_zoom: float = fill * 2.0
	if map_zoom < min_zoom:
		var c: Vector2 = ws * 0.5
		map_offset = c - (c - map_offset) * (min_zoom / map_zoom)
		map_zoom = min_zoom
	var cs: Vector2 = map_canvas.size * map_zoom
	map_offset.x = clampf(map_offset.x, ws.x - cs.x, 0.0)
	map_offset.y = clampf(map_offset.y, ws.y - cs.y, 0.0)

func _apply_2d_transform() -> void:
	if engine != null and engine.infinite:
		_clamp_infinite_view()
	map_canvas.scale = Vector2.ONE * map_zoom
	map_canvas.position = map_offset
	if engine != null and engine.infinite and _recenter_timer != null:
		_recenter_timer.start()
	overlay.set_view(map_offset, map_canvas.size * map_zoom, map_zoom)
	# Celdas de los árboles: entre ~6 y ~12 px de pantalla, en potencias de 2 de píxeles del mapa (más árboles al acercarse).
	if map_canvas.size.x > 0.0 and mat_2d != null:
		var tpp: float = float(engine.map_size.x) / (map_canvas.size.x * map_zoom)
		var cell: float = pow(2.0, floor(log(maxf(12.0 * tpp, 1.0)) / log(2.0)))
		cell = clampf(cell, 4.0, 64.0)
		mat_2d.set_shader_parameter("tree_cell", cell)
		mat_2d.set_shader_parameter("symbol_cell", clampf(cell * 1.6, 8.0, 96.0))

var _recenter_timer: Timer
var map_frame: Panel
var _told_infinite: bool = false

## Mundo infinito: si el centro de la vista se acerca al borde de la ventana, la ventana se recoloca (trozos
## enteros) y la vista se queda donde estaba.
func _try_recenter() -> void:
	if not engine.infinite or not engine.world_ready or input_ctrl.is_3d_mode or _stroke_kind != "":
		return
	var ws: Vector2 = map_canvas.get_parent().size
	var scale_px: float = map_canvas.size.x * map_zoom / float(engine.map_size.x)
	if scale_px <= 0.0:
		return
	var center_px: Vector2 = (ws * 0.5 - map_offset) / scale_px
	var w := Vector2(engine.map_size)
	var dc := Vector2i.ZERO
	if center_px.x < w.x * 0.3 or center_px.x > w.x * 0.7:
		dc.x = int(round((center_px.x - w.x * 0.5) / float(engine.CHUNK)))
	if center_px.y < w.y * 0.3 or center_px.y > w.y * 0.7:
		dc.y = int(round((center_px.y - w.y * 0.5) / float(engine.CHUNK)))
	if dc == Vector2i.ZERO:
		return
	lbl_save_state.text = "CARGANDO EL MUNDO…"
	await engine.recenter(dc)
	map_offset += Vector2(dc * engine.CHUNK) * scale_px
	_apply_2d_transform()
	if not _told_infinite:
		_told_infinite = true
		_toast("El mundo sigue: se ha cargado lo de ese lado. (Al moverte así, DESHACER empieza de nuevo.)")
	_schedule_save()

func _update_3d_camera() -> void:
	cam_pivot.rotation = Vector3(pitch, yaw, 0.0)
	cam_3d.position = Vector3(0.0, 0.0, cam_dist)
	_fit_shadows()
	if labels3d != null:
		labels3d.queue_redraw()

## Sombras del sol hechas a la medida del mundo: un solo mapa (sin los cortes rectos entre tramos de la sombra en
## cascada) que llega justo hasta donde hay terreno, y margen de sobra. Con la configuración de serie (cuatro tramos
## hasta 100 unidades para un mundo de 12) en el móvil salían bandas y triángulos de «acné» de sombra en el agua y
## líneas rectas cruzando el relieve.
func _fit_shadows() -> void:
	if sun_light == null:
		return
	sun_light.shadow_enabled = shadows_3d
	sun_light.directional_shadow_mode = DirectionalLight3D.SHADOW_ORTHOGONAL
	sun_light.directional_shadow_max_distance = cam_dist + 10.0
	sun_light.shadow_bias = 0.12
	sun_light.shadow_normal_bias = 2.5
	sun_light.shadow_blur = 1.0

func _toggle_3d() -> void:
	input_ctrl.is_3d_mode = not input_ctrl.is_3d_mode
	var in_3d: bool = input_ctrl.is_3d_mode
	view_3d_container.visible = in_3d
	view_2d_root.visible = not in_3d
	subviewport_3d.render_target_update_mode = (
		SubViewport.UPDATE_ALWAYS if in_3d else SubViewport.UPDATE_DISABLED
	)
	btn_mode_3d.text = "VOLVER AL MAPA  ←" if in_3d else "VER RELIEVE 3D  →"
	sec_relief_3d.visible = in_3d
	labels3d.visible = in_3d
	if quick_bar != null:
		quick_bar.visible = not in_3d and section in ["relieve", "biomas", "regiones", "politico"]
	if in_3d:
		_msaa_enter_3d()
		_rebuild_3d()
	_update_hint()

# ---------------------------------------------------------------- MSAA del 3D con protector
## El MSAA ya colgó el arranque con Vulkan: aquí solo se activa al entrar en 3D, y si una entrada en 3D con MSAA no
## llega a durar 3 s (cuelgue), el siguiente arranque lo deja apagado y lo avisa en Mundo → Calidad.
var msaa_on: bool = true
var msaa_fallback: bool = false

func _msaa_guard_load() -> void:
	var cfg := ConfigFile.new()
	cfg.load(engine.CONFIG_PATH)
	msaa_on = bool(cfg.get_value("calidad", "msaa_3d", true))
	if bool(cfg.get_value("arranque", "pendiente_msaa", false)):
		msaa_on = false
		msaa_fallback = true
		cfg.set_value("calidad", "msaa_3d", false)
		cfg.set_value("arranque", "pendiente_msaa", false)
		cfg.save(engine.CONFIG_PATH)
	subviewport_3d.msaa_3d = Viewport.MSAA_DISABLED

func _msaa_enter_3d() -> void:
	if not msaa_on:
		subviewport_3d.msaa_3d = Viewport.MSAA_DISABLED
		return
	var cfg := ConfigFile.new()
	cfg.load(engine.CONFIG_PATH)
	cfg.set_value("arranque", "pendiente_msaa", true)
	cfg.save(engine.CONFIG_PATH)
	subviewport_3d.msaa_3d = Viewport.MSAA_4X
	get_tree().create_timer(3.0).timeout.connect(func():
		var c2 := ConfigFile.new()
		c2.load(engine.CONFIG_PATH)
		c2.set_value("arranque", "pendiente_msaa", false)
		c2.save(engine.CONFIG_PATH)
	)

func _set_msaa(on: bool) -> void:
	msaa_on = on
	msaa_fallback = false
	var cfg := ConfigFile.new()
	cfg.load(engine.CONFIG_PATH)
	cfg.set_value("calidad", "msaa_3d", on)
	cfg.save(engine.CONFIG_PATH)
	if input_ctrl.is_3d_mode:
		_msaa_enter_3d()

## Árboles, edificios y nombres del 3D: solo si algo cambió desde la última vez.
func _rebuild_3d() -> void:
	if not engine.world_ready:
		return
	var long_side: float = float(maxi(engine.map_size.x, engine.map_size.y))
	var world := Vector2(engine.map_size) / long_side * 12.0
	var hs: float = 2.6 * relief_mult
	if _world3d_dirty or _places3d_dirty:
		# Calles, plazas y caminos: antes que la vegetación (que no planta encima) y que los edificios.
		places3d.build_mask(engine.places, engine.roads, engine.height.mirror, world)
		mat_3d.set_shader_parameter("has_roads", places3d.road_count > 0)
		if places3d.road_count > 0:
			mat_3d.set_shader_parameter("road_tex", ImageTexture.create_from_image(places3d.road_img))
	if _world3d_dirty:
		_world3d_dirty = false
		var n: int = 0
		if trees_3d:
			# Sin árboles dentro de las ciudades y demás lugares.
			var exclude: Array = []
			for p in engine.places:
				var puv: Vector2 = p["uv"]
				exclude.append(Vector3(puv.x, puv.y, places3d.radius_uv_place(p, world)))
			n = vegetation.build(engine.biome.mirror, engine.height.mirror, sea_level, world, hs, engine.world_seed, exclude, places3d.road_img)
		else:
			vegetation.clear()
		lbl_trees.text = "%s PLANTAS" % Estilo.miles(n)
	if _places3d_dirty:
		_places3d_dirty = false
		_place_label_pos = places3d.build(engine.places, engine.height.mirror, engine.realm.mirror, engine.realms, sea_level, world, hs)
	_refresh_labels_3d()

func _uv_to_3d(uv: Vector2, lift: float) -> Vector3:
	var long_side: float = float(maxi(engine.map_size.x, engine.map_size.y))
	var world := Vector2(engine.map_size) / long_side * 12.0
	var px := Vector2i(clampi(int(uv.x * engine.map_size.x), 0, engine.map_size.x - 1), clampi(int(uv.y * engine.map_size.y), 0, engine.map_size.y - 1))
	var hv: float = engine.height.mirror.get_pixelv(px).r
	return Vector3((uv.x - 0.5) * world.x, maxf(hv - sea_level, 0.0) * 2.6 * relief_mult + lift, (uv.y - 0.5) * world.y)

func _refresh_labels_3d() -> void:
	if labels3d == null or not input_ctrl.is_3d_mode:
		return
	var out: Array = []
	for a in engine.areas:
		var id: int = int(a["id"])
		if overlay.area_shapes.has(id):
			var t: Array = Catalogo.tipo_region(str(a["kind"]))
			var sh: Dictionary = overlay.area_shapes[id]
			out.append([str(a["name"]).to_upper(), _uv_to_3d(sh["uv"], 0.35), "agua" if bool(t[2]) else "region"])
	if show_realms:
		for r in engine.realms:
			var id: int = int(r["id"])
			if overlay.centroids.has(id):
				var c: Dictionary = overlay.centroids[id]
				var col: Color = r["color"]
				out.append([(str(r["kind"]) + " DE " + str(r["name"])).to_upper(), _uv_to_3d(c["uv"], 1.1), "reino", col])
	for p in engine.places:
		var id: int = int(p["id"])
		var pos: Vector3 = _place_label_pos.get(id, _uv_to_3d(p["uv"], 0.2))
		var kind: String = p["kind"]
		out.append([str(p["name"]).to_upper() if kind == "capital" else str(p["name"]), pos, "capital" if kind == "capital" else "lugar"])
	labels3d.labels = out
	labels3d.queue_redraw()

func _update_hint() -> void:
	if input_ctrl.is_3d_mode:
		lbl_hint.text = "UN DEDO GIRA · DOS DEDOS ACERCAN"
		return
	match section:
		"lugares":
			lbl_hint.text = "LÁPIZ DIBUJA EL CAMINO · TOCA UNO PARA CAMBIARLO" if lugares_mode == "caminos" else "TOCA PARA PONER · ARRASTRA PARA MOVER"
		"viajes":
			lbl_hint.text = "TOCA PARA AÑADIR ETAPAS · LOS LUGARES ATRAEN"
		"mundo":
			lbl_hint.text = "UN DEDO MUEVE · DOS DEDOS ACERCAN"
		_:
			lbl_hint.text = "DIBUJA EL BORDE · SE RELLENA SOLO" if draw_mode == "contorno" else "LÁPIZ DIBUJA · DOS DEDOS MUEVEN Y ACERCAN"

func _apply_theme(idx: int) -> void:
	current_theme_idx = idx
	var t: Dictionary = THEMES[idx]
	for k in [["col_deep_sea", "deep"], ["col_shallow_sea", "shallow"], ["col_land_low", "low"], ["col_land_mid", "mid"], ["col_snow", "snow"], ["biome_tint", "tint"]]:
		var c: Color = t[k[1]]
		mat_2d.set_shader_parameter(k[0], c)
	for k in [["biome_desat", "desat"], ["biome_tint_amt", "tint_amt"]]:
		var v: float = t[k[1]]
		mat_2d.set_shader_parameter(k[0], v)
	_apply_3d_palette()
	var high: Color = t["high"]
	var ink: Color = t["ink"]
	mat_2d.set_shader_parameter("col_land_high", high)
	mat_2d.set_shader_parameter("col_ink", ink)
	_queue_blend_bake()
	overlay.ink = ink
	overlay.queue_redraw()
	for i in theme_buttons.size():
		_paint_filter(theme_buttons[i], i == idx)
	_refresh_biome_swatches()
	_world3d_dirty = true
	if input_ctrl.is_3d_mode:
		_rebuild_3d.call_deferred()

## El 3D va siempre con la paleta de fantasía (colores vivos, como los árboles y los edificios), sea cual sea el
## estilo del mapa 2D: agua turquesa y azul hondo, hierba verde viva y los colores puros de los biomas.
func _apply_3d_palette() -> void:
	mat_3d.set_shader_parameter("col_shallow_sea", Color(0.18, 0.62, 0.74))
	mat_3d.set_shader_parameter("col_deep_sea", Color(0.05, 0.20, 0.42))
	mat_3d.set_shader_parameter("col_land_low", Color(0.30, 0.64, 0.30))
	mat_3d.set_shader_parameter("col_land_mid", Color(0.44, 0.60, 0.32))
	mat_3d.set_shader_parameter("col_snow", Color(0.97, 0.98, 1.0))
	mat_3d.set_shader_parameter("biome_tint", Color(1, 1, 1))
	mat_3d.set_shader_parameter("biome_desat", 0.0)
	mat_3d.set_shader_parameter("biome_tint_amt", 0.0)

## El color de un bioma tal y como lo pinta el estilo elegido (la misma cuenta que el shader).
func _styled_biome_color(c: Color) -> Color:
	var t: Dictionary = THEMES[current_theme_idx]
	var l: float = c.r * 0.299 + c.g * 0.587 + c.b * 0.114
	var desat: float = t["desat"]
	var tint: Color = t["tint"]
	var amt: float = t["tint_amt"]
	var out: Color = c.lerp(Color(l, l, l), desat)
	var tinted := Color(tint.r * (0.55 + 0.55 * l), tint.g * (0.55 + 0.55 * l), tint.b * (0.55 + 0.55 * l))
	return out.lerp(tinted, amt)

func _apply_sun() -> void:
	var az: float = deg_to_rad(sun_azimuth)
	# En el mapa el norte está arriba (-Y en pantalla); la luz viene DESDE el acimut.
	mat_2d.set_shader_parameter("sun_dir", Vector3(sin(az) * 0.78, -cos(az) * 0.78, 0.62))
	mat_2d.set_shader_parameter("light2d", Vector2(sin(az), -cos(az)))
	var elev: float = deg_to_rad(42.0)
	# En 3D el norte es -Z (la V de la textura crece hacia +Z). La luz viaja hacia el lado opuesto.
	var travel := Vector3(-sin(az) * cos(elev), -sin(elev), cos(az) * cos(elev))
	sun_light.basis = Basis.looking_at(travel, Vector3.UP)
	val_sun.text = "%s · %d°" % [_cardinal(sun_azimuth), int(round(sun_azimuth)) % 360]

func _cardinal(deg: float) -> String:
	var names: Array[String] = ["N", "NE", "E", "SE", "S", "SO", "O", "NO"]
	var i: int = int(round(fposmod(deg, 360.0) / 45.0)) % 8
	return names[i]

## Un mundo nuevo con el tamaño y la forma elegidos en Mundo (o los actuales).
func _new_world() -> void:
	var long_side: int = SIZES[new_size_idx][1]
	var inf: bool = long_side == 0
	if inf:
		long_side = 4096
	var shape: Vector2 = SHAPES[new_shape_idx][1]
	var size := Vector2i(long_side, long_side)
	if shape.x > shape.y:
		size.y = int(round(long_side * shape.y / shape.x))
	elif shape.y > shape.x:
		size.x = int(round(long_side * shape.x / shape.y))
	var s: int = randi_range(1, 9999)
	selected_place = -1
	current_realm = -1
	route_pts.clear()
	await engine.save_map(_view_extra())
	await engine.create_world(size, s, 1 if new_mode == 2 else new_mode, inf)
	if new_obra != "":
		_set_obra(new_obra, false)
	map_name_edit.text = engine.map_name
	_refresh_route()
	if inf:
		_toast("Mundo infinito: muévete en cualquier dirección y el mundo sigue. Lo que dibujes se guarda por trozos.")
	if new_mode == 2:
		# Lienzo en blanco (mar) para dibujar el mundo a mano: tierra por contorno y lienzo que crece.
		grow_canvas = not inf
		_select_section("relieve")
		_select_tool("land")
		_set_draw_mode("contorno")
		_toast("Dibuja la costa de tu tierra con el lápiz: al soltar se rellena. Si llegas al borde, el lienzo crece.")

## Botones que borran trabajo sin vuelta atrás: el primer toque pregunta, el segundo hace.
func _confirm(key: String, b: Button, text_ready: String, action: Callable) -> void:
	if _armed == key:
		_armed = ""
		b.text = text_ready
		action.call()
		return
	_armed = key
	b.text = "¿SEGURO? TOCA OTRA VEZ"
	get_tree().create_timer(3.0).timeout.connect(func():
		if _armed == key:
			_armed = ""
			b.text = text_ready
	)

# ================================================================ selección

func _select_section(id: String) -> void:
	section = id
	for k in tab_buttons.keys():
		_paint_tab(tab_buttons[k], str(k) == id)
	for k in sections_ui.keys():
		var c: Control = sections_ui[k]
		c.visible = (str(k) == id)
	brush_box.visible = id in ["relieve", "biomas", "politico", "regiones"]
	overlay.show_route = id == "viajes"
	if quick_bar != null:
		quick_bar.visible = id in ["relieve", "biomas", "regiones", "politico"]
	overlay.queue_redraw()
	if id == "mundo":
		_refresh_maps_list()
		_refresh_cuenta(false)
	if id == "lugares":
		_refresh_roads_ui()
	mat_2d.set_shader_parameter("show_area_edges", id == "regiones")
	_refresh_brush_rows()
	_update_hint()

func _refresh_brush_rows() -> void:
	strength_row.visible = section == "relieve"
	steps_row.visible = section == "relieve" and current_tool == "cliff"

func _select_tool(tool_id: String) -> void:
	current_tool = tool_id
	for i in TOOLS.size():
		var item: Array = TOOLS[i]
		var on: bool = item[0] == tool_id
		_paint_filter(tool_chips[i], on)
		if on:
			lbl_tool_name.text = item[1]
			lbl_tool_desc.text = item[2]
	_refresh_brush_rows()

func _select_biome(id: int) -> void:
	current_biome = id
	for i in biome_buttons.size():
		_paint_row(biome_buttons[i], i == id)
	lbl_biome_name.text = "AUTOMÁTICO" if id == 0 else str(Catalogo.BIOMAS[id - 1][0])

## Fila de lista de la familia (nombres en serif, como los de los ajustes y las fichas): raya fina debajo y, la
## elegida, una barra del acento a la izquierda y la letra en seminegra.
func _paint_row(b: Button, on: bool) -> void:
	var normal := Estilo.caja(Color(Estilo.ACENTO, 0.10) if on else Color(0, 0, 0, 0), Color(0, 0, 0, 0), 0, 12, 8)
	normal.border_color = Estilo.ACENTO if on else Color(Estilo.TINTA, 0.18)
	normal.border_width_left = 4 if on else 0
	normal.border_width_bottom = 0 if on else 1
	normal.content_margin_left = 10 if on else 14
	var pressed := normal.duplicate() as StyleBoxFlat
	pressed.bg_color = Color(Estilo.TINTA, 0.08)
	for k in ["normal", "hover", "disabled"]:
		b.add_theme_stylebox_override(k, normal)
	b.add_theme_stylebox_override("pressed", pressed)
	b.add_theme_stylebox_override("hover_pressed", pressed)
	b.add_theme_stylebox_override("focus", StyleBoxEmpty.new())
	b.add_theme_font_override("font", Estilo.F_SERIF_SEMI if on else Estilo.F_SERIF)
	for k in ["font_color", "font_hover_color", "font_pressed_color", "font_hover_pressed_color", "font_focus_color"]:
		b.add_theme_color_override(k, Estilo.TINTA if on else Color(Estilo.TINTA, 0.82))

## «BOSQUE VIEJO» → «Bosque viejo» (los nombres van en serif con mayúscula inicial).
static func _frase(t: String) -> String:
	return t.substr(0, 1).to_upper() + t.substr(1).to_lower()

func _paint_filter(b: Button, on: bool) -> void:
	if on:
		Estilo.pintar_boton(b, Estilo.PAPEL, Estilo.TINTA, Estilo.TINTA, 12, 9, Estilo.TINTA, 2)
	else:
		Estilo.pintar_boton(b, Estilo.TINTA, Color(0, 0, 0, 0), Color(Estilo.TINTA, 0.08), 12, 9, Estilo.TINTA, 2)

# ================================================================ reinos y lugares

func _rebuild_realm_palette() -> void:
	# Fila 0: el color de cada reino · fila 1: su imperio (el de más arriba), r = id / 255.
	var img := Image.create(64, 2, false, Image.FORMAT_RGBA8)
	img.fill(Color(0, 0, 0, 0))
	for r in engine.realms:
		var c: Color = r["color"]
		var rid: int = int(r["id"])
		img.set_pixel(rid, 0, c)
		img.set_pixel(rid, 1, Color(float(engine.realm_top(rid)) / 255.0, 0, 0, 1))
	var tex := ImageTexture.create_from_image(img)
	var rimg := Image.create(64, 64, false, Image.FORMAT_R8)
	for rel in engine.relations:
		var k: float = float(Catalogo.relacion_idx(str(rel["kind"]))) / 255.0
		var a: int = int(rel["a"])
		var b: int = int(rel["b"])
		if a < 64 and b < 64:
			rimg.set_pixel(a, b, Color(k, 0, 0))
			rimg.set_pixel(b, a, Color(k, 0, 0))
	if mat_2d != null:
		mat_2d.set_shader_parameter("realm_pal", tex)
		mat_3d.set_shader_parameter("realm_pal", tex)
		mat_2d.set_shader_parameter("relation_tex", ImageTexture.create_from_image(rimg))

func _on_realms_changed() -> void:
	_rebuild_realm_palette()
	overlay.centroids = _realm_aggregate(_raw_centroids)
	overlay.realm_parent = func(id: int) -> int: return engine.realm_parent(id)
	_places3d_dirty = true
	if engine.realm_by_id(current_realm).is_empty():
		current_realm = -1
	overlay.realms = engine.realms
	overlay.queue_redraw()
	_set_tab_count("politico", engine.realms.size())
	lbl_realm_count.text = "%d REINOS" % engine.realms.size() if engine.realms.size() != 1 else "1 REINO"
	for c in realm_list.get_children():
		c.queue_free()
	for e in _realm_tree():
		var r: Dictionary = e[0]
		var depth: int = e[1]
		var id: int = int(r["id"])
		var col: Color = r["color"]
		var b := Estilo.boton("%s%s · %s" % ["   ".repeat(depth) + ("└ " if depth > 0 else ""), str(r["name"]).to_upper(), str(r["kind"])], 12, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 12, 9)
		b.custom_minimum_size = Vector2(0, 40)
		b.icon = _swatch(col)
		b.alignment = HORIZONTAL_ALIGNMENT_LEFT
		b.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
		b.clip_text = true
		b.pressed.connect(func(): _select_realm(id))
		realm_list.add_child(b)
		_paint_filter(b, id == current_realm and not realm_erase)
	_refresh_realm_editor()

## Los reinos en orden de árbol: [reino, profundidad].
func _realm_tree() -> Array:
	var out: Array = []
	var add_level := func(f: Callable, parent: int, depth: int) -> void:
		for r in engine.realms:
			var p: int = int(r.get("parent", 0))
			if p == parent or (parent == 0 and engine.realm_by_id(p).is_empty() and p != 0):
				out.append([r, depth])
				if depth < 6:
					f.call(f, int(r["id"]), depth + 1)
	add_level.call(add_level, 0, 0)
	return out

## Centro y área de cada reino contando los suyos (un imperio sin territorio propio también tiene centro).
func _realm_aggregate(c: Dictionary) -> Dictionary:
	var out: Dictionary = {}
	for r in engine.realms:
		var id: int = int(r["id"])
		var sum := Vector2.ZERO
		var area: float = 0.0
		for o in engine.realms:
			var oid: int = int(o["id"])
			if c.has(oid) and engine.realm_is_within(oid, id):
				var cc: Dictionary = c[oid]
				sum += Vector2(cc["uv"]) * float(cc["area"])
				area += float(cc["area"])
		if area > 0.0:
			out[id] = {"uv": sum / area, "area": area}
	return out

func _select_realm(id: int) -> void:
	current_realm = id
	realm_erase = false
	_on_realms_changed()

func _refresh_realm_editor() -> void:
	var r: Dictionary = engine.realm_by_id(current_realm)
	realm_editor.visible = not r.is_empty()
	for i in realm_mode_chips.size():
		_paint_filter(realm_mode_chips[i], (i == 1) == realm_erase)
	if r.is_empty():
		return
	if not realm_name_edit.has_focus():
		realm_name_edit.text = str(r["name"])
	var kind: String = r["kind"]
	for i in realm_kind_chips.size():
		_paint_filter(realm_kind_chips[i], Catalogo.TIPOS_REINO[i] == kind)
	_refresh_relations()
	realm_parent_option.clear()
	realm_parent_option.add_item("NINGUNO (INDEPENDIENTE)", 0)
	for o in engine.realms:
		var oid: int = int(o["id"])
		if oid != current_realm and not engine.realm_is_within(oid, current_realm):
			realm_parent_option.add_item(("%s DE %s" % [str(o["kind"]), str(o["name"])]).to_upper(), oid)
	var cur_parent: int = int(r.get("parent", 0))
	for i in realm_parent_option.item_count:
		if realm_parent_option.get_item_id(i) == cur_parent:
			realm_parent_option.select(i)
	var col: Color = r["color"]
	for i in realm_color_buttons.size():
		var c: Color = Catalogo.COLORES_REINO[i]
		var on: bool = c.is_equal_approx(col)
		var sb := Estilo.caja(c, Estilo.TINTA if on else Color(c, 0.0), 3 if on else 0)
		var b: Button = realm_color_buttons[i]
		b.add_theme_stylebox_override("normal", sb)
		b.add_theme_stylebox_override("hover", sb)
		b.add_theme_stylebox_override("pressed", sb)

func _refresh_relations() -> void:
	for c in relation_box.get_children():
		c.queue_free()
	var others: int = 0
	for o in engine.realms:
		var oid: int = int(o["id"])
		if oid == current_realm:
			continue
		others += 1
		var row := HBoxContainer.new()
		row.add_theme_constant_override("separation", 8)
		relation_box.add_child(row)
		var nm := Estilo.etiqueta(str(o["name"]).to_upper(), 11, Estilo.TINTA)
		nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		nm.clip_text = true
		var oc: Color = o["color"]
		var sw := TextureRect.new()
		sw.texture = _swatch(oc)
		sw.stretch_mode = TextureRect.STRETCH_KEEP_CENTERED
		row.add_child(sw)
		row.add_child(nm)
		var ob := _option_button()
		for rk in Catalogo.RELACIONES:
			ob.add_item(str(rk[1]))
		ob.select(Catalogo.relacion_idx(engine.relation(current_realm, oid)))
		ob.item_selected.connect(func(i: int): engine.set_relation(current_realm, oid, str(Catalogo.RELACIONES[i][0])))
		row.add_child(ob)
	if others == 0:
		relation_box.add_child(_note_label("Crea otro reino para poder relacionarlos."))

## Rótulo pequeño de un grupo del panel (versalitas gris) que parte línea si no cabe.
func _caption(text: String) -> Label:
	var l := Estilo.etiqueta(text, 11, Estilo.GRIS)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	l.custom_minimum_size = Vector2(120, 0)
	return l

func _option_button() -> OptionButton:
	var ob := OptionButton.new()
	ob.focus_mode = Control.FOCUS_NONE
	# Sin esto el desplegable mide lo que su opción más larga y ensancha el panel entero.
	ob.fit_to_longest_item = false
	ob.clip_text = true
	ob.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	ob.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	ob.custom_minimum_size = Vector2(120, 40)
	ob.add_theme_font_override("font", Estilo.versalitas())
	ob.add_theme_font_size_override("font_size", 11)
	for k in ["normal", "hover", "pressed", "focus"]:
		ob.add_theme_stylebox_override(k, Estilo.caja(Color(0, 0, 0, 0), Estilo.TINTA, 2, 10, 8))
	for k in ["font_color", "font_hover_color", "font_pressed_color", "font_focus_color"]:
		ob.add_theme_color_override(k, Estilo.TINTA)
	return ob

func _note_label(text: String) -> Label:
	var l := Estilo.etiqueta(text, 13, Estilo.GRIS, Estilo.F_TEXTO)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	return l

func _flash(l: Label) -> void:
	l.add_theme_color_override("font_color", Estilo.ACENTO)
	get_tree().create_timer(1.2).timeout.connect(func():
		l.add_theme_color_override("font_color", Estilo.GRIS)
	)

func _flash_realm_hint() -> void:
	lbl_realm_hint.add_theme_color_override("font_color", Estilo.ACENTO)
	get_tree().create_timer(1.2).timeout.connect(func():
		lbl_realm_hint.add_theme_color_override("font_color", Estilo.GRIS)
	)

func _on_places_changed() -> void:
	_places3d_dirty = true
	_world3d_dirty = true
	if input_ctrl.is_3d_mode:
		_rebuild_3d.call_deferred()
	overlay.places = engine.places
	if engine.place_by_id(selected_place).is_empty():
		selected_place = -1
	overlay.selected_place = selected_place
	overlay.queue_redraw()
	_set_tab_count("lugares", engine.places.size())
	lbl_place_count.text = "%d LUGARES" % engine.places.size() if engine.places.size() != 1 else "1 LUGAR"
	_refresh_place_editor()

func _select_place(id: int) -> void:
	selected_place = id
	overlay.selected_place = id
	overlay.queue_redraw()
	_refresh_place_editor()

func _refresh_place_editor() -> void:
	var p: Dictionary = engine.place_by_id(selected_place)
	place_editor.visible = not p.is_empty()
	var kind: String = place_kind if p.is_empty() else str(p["kind"])
	for i in place_kind_chips.size():
		_paint_filter(place_kind_chips[i], Catalogo.TIPOS_LUGAR[i][0] == kind)
	if not p.is_empty() and not place_name_edit.has_focus():
		place_name_edit.text = str(p["name"])
	var sized: bool = not p.is_empty() and Asentamiento.has_size(kind)
	place_size_box.visible = sized
	if sized:
		var sz: int = Asentamiento.size_of(p)
		place_size_slider.set_value_no_signal(sz)
		place_size_val.text = "%d / 10" % sz
		var nm: String = Asentamiento.size_name(kind, sz)
		place_size_note.text = "%s · unos %s edificios. Se genera solo: calles, plaza%s y afueras." % [
			nm.substr(0, 1).to_upper() + nm.substr(1), Estilo.miles(Asentamiento.count_for(kind, sz)),
			", murallas, arrabales" if Asentamiento.has_walls(kind, sz) else ""]

func _set_tab_count(id: String, n: int) -> void:
	var b: Button = tab_buttons[id]
	var count_l: Label = b.get_meta("count_l")
	count_l.text = str(n)
	count_l.visible = n > 0
	_fit_tab(b)

func _swatch(c: Color) -> ImageTexture:
	var img := Image.create(16, 16, false, Image.FORMAT_RGBA8)
	img.fill(Estilo.TINTA)
	img.fill_rect(Rect2i(2, 2, 12, 12), c)
	return ImageTexture.create_from_image(img)

func _refresh_biome_swatches() -> void:
	for i in biome_buttons.size():
		if i == 0:
			continue
		var c: Color = Catalogo.BIOMAS[i - 1][1]
		biome_buttons[i].icon = _swatch(_styled_biome_color(c))

func _refresh_quality() -> void:
	if btn_quality == null:
		return
	if lbl_msaa_note != null:
		lbl_msaa_note.text = "La última vez el 3D con MSAA no respondió: se ha apagado. Puedes volver a probar." if msaa_fallback else "Bordes suaves en árboles, edificios y montañas del 3D."
	_paint_filter(btn_quality, engine.hdr)
	btn_quality.text = "ALTA PRECISIÓN · 16 BITS" if engine.hdr else "PRECISIÓN NORMAL · 8 BITS"
	if engine.hdr_fallback:
		lbl_quality_note.text = "El último arranque con alta precisión no terminó: se ha vuelto a 8 bits. Puedes volver a probar."
	else:
		lbl_quality_note.text = "Relieve más fino al ampliar (sin escalones). Si el móvil no la aguanta, ATLAS vuelve solo a 8 bits."

# ================================================================ construcción de la interfaz

func _build_ui() -> void:
	var bg := ColorRect.new()
	bg.color = Estilo.MESA
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	bg.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(bg)
	move_child(bg, 0)

	_build_header()
	_build_tabs()
	_build_panel()
	_build_map_frame()

	overlay = MapOverlay.new()
	overlay.set_anchors_preset(Control.PRESET_FULL_RECT)
	view_2d_root.add_child(overlay)

	# Barra rápida sobre el mapa (a mano del pulgar): pincel o contorno.
	quick_bar = HBoxContainer.new()
	quick_bar.add_theme_constant_override("separation", 0)
	quick_bar.position = Vector2(16, 14)
	workspace.add_child(quick_bar)
	for i in 2:
		var qb := Estilo.boton(["PINCEL", "CONTORNO"][i], 12, Estilo.TINTA, Estilo.PAPEL, Estilo.CRUDO, 14, 10, Estilo.TINTA, 2)
		qb.custom_minimum_size = Vector2(0, 44)
		var qi: int = i
		qb.pressed.connect(func(): _set_draw_mode("contorno" if qi == 1 else "pincel"))
		quick_bar.add_child(qb)
		quick_mode_chips.append(qb)
	toast_panel = PanelContainer.new()
	toast_panel.add_theme_stylebox_override("panel", Estilo.caja(Estilo.TINTA, Estilo.ACENTO, 0, 16, 10))
	toast_panel.mouse_filter = Control.MOUSE_FILTER_IGNORE
	toast_panel.visible = false
	toast_panel.set_anchors_preset(Control.PRESET_CENTER_BOTTOM)
	toast_panel.grow_horizontal = Control.GROW_DIRECTION_BOTH
	toast_panel.grow_vertical = Control.GROW_DIRECTION_BEGIN
	toast_panel.offset_bottom = -64
	workspace.add_child(toast_panel)
	toast_label = Estilo.etiqueta("", 13, Estilo.PAPEL, Estilo.F_SEMI)
	toast_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	toast_label.custom_minimum_size = Vector2(360, 0)
	toast_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	toast_panel.add_child(toast_label)

	vegetation = Vegetacion3D.new()
	# Hojas con los colores puros del bioma (paleta de fantasía), como el suelo 3D.
	subviewport_3d.add_child(vegetation)
	places3d = Lugares3D.new()
	subviewport_3d.add_child(places3d)
	labels3d = Labels3D.new()
	labels3d.set_anchors_preset(Control.PRESET_FULL_RECT)
	labels3d.visible = false
	labels3d.camera = cam_3d
	workspace.add_child(labels3d)

## La cabecera de la familia (dirección C): banda negra con la fila pequeña arriba (+ NUEVO MUNDO en el acento,
## deshacer, rehacer, encuadrar · el mapa y la fecha) y el nombre grande en papel con su punto del acento; a la
## derecha, la acción principal (el relieve 3D) en bloque del acento.
func _build_header() -> void:
	var header := Panel.new()
	header.add_theme_stylebox_override("panel", Estilo.caja(Estilo.TINTA_CABECERA))
	header.set_anchors_preset(Control.PRESET_TOP_WIDE)
	header.offset_bottom = HEADER_H
	add_child(header)

	var transparent := Color(0, 0, 0, 0)
	var pressed_bg := Color(1, 1, 1, 0.08)
	var small := HBoxContainer.new()
	small.set_anchors_preset(Control.PRESET_TOP_WIDE)
	small.offset_left = 18
	small.offset_right = -24
	small.offset_top = 6
	small.offset_bottom = 44
	small.add_theme_constant_override("separation", 2)
	header.add_child(small)

	var b_new := Estilo.boton("+ NUEVO MUNDO", 12, Estilo.acento_sobre_negro(), transparent, pressed_bg, 10, 10)
	b_new.pressed.connect(func(): _select_section("mundo"))
	small.add_child(b_new)
	btn_undo = Estilo.boton("DESHACER", 12, Estilo.SOBRE_CABECERA, transparent, pressed_bg, 10, 10)
	btn_undo.disabled = true
	btn_undo.pressed.connect(func(): engine.undo())
	small.add_child(btn_undo)
	btn_redo = Estilo.boton("REHACER", 12, Estilo.SOBRE_CABECERA, transparent, pressed_bg, 10, 10)
	btn_redo.disabled = true
	btn_redo.pressed.connect(func(): engine.redo())
	small.add_child(btn_redo)
	var b_fit := Estilo.boton("ENCUADRAR", 12, Estilo.SOBRE_CABECERA, transparent, pressed_bg, 10, 10)
	b_fit_btn = b_fit
	b_fit.pressed.connect(_center_map)
	small.add_child(b_fit)
	var spacer := Control.new()
	spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	small.add_child(spacer)
	lbl_world = Estilo.etiqueta("MUNDO Nº %04d" % engine.world_seed, 12, Estilo.SOBRE_CABECERA)
	lbl_world.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	small.add_child(lbl_world)
	var date_lbl := Estilo.etiqueta("  ·  " + Estilo.fecha_corta(), 12, Estilo.SOBRE_CABECERA)
	date_lbl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	small.add_child(date_lbl)
	header_date_lbl = date_lbl

	var big := HBoxContainer.new()
	big.set_anchors_preset(Control.PRESET_FULL_RECT)
	big.offset_left = 28
	big.offset_right = -24
	big.offset_top = 40
	big.offset_bottom = -6
	big.add_theme_constant_override("separation", 0)
	header.add_child(big)
	var name_box := HBoxContainer.new()
	name_box.add_theme_constant_override("separation", 0)
	name_box.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	header_name_lbl = Estilo.etiqueta("ATLAS", 64, Estilo.PAPEL, Estilo.grotesca_prieta())
	header_dot_lbl = Estilo.etiqueta(".", 64, Estilo.ACENTO, Estilo.grotesca())
	name_box.add_child(header_name_lbl)
	name_box.add_child(header_dot_lbl)
	big.add_child(name_box)
	var spacer2 := Control.new()
	spacer2.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	big.add_child(spacer2)
	btn_mode_3d = Estilo.boton("VER RELIEVE 3D  →", 14, Estilo.SOBRE_ACENTO, Estilo.ACENTO, Estilo.ACENTO.darkened(0.15), 22, 14)
	var bold_grotesk := FontVariation.new()
	bold_grotesk.base_font = Estilo.F_GROTESCA
	bold_grotesk.spacing_glyph = 1
	Estilo._opsz(bold_grotesk)
	btn_mode_3d.add_theme_font_override("font", bold_grotesk)
	btn_mode_3d.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	btn_mode_3d.pressed.connect(_toggle_3d)
	big.add_child(btn_mode_3d)

## Las pestañas de la familia: sobre papel, la elegida en un bloque del acento, su número al lado en el acento
## (en papel si está elegida) y un filete de tinta debajo de toda la fila.
func _build_tabs() -> void:
	var band := Panel.new()
	band.add_theme_stylebox_override("panel", Estilo.caja(Estilo.PAPEL))
	band.set_anchors_preset(Control.PRESET_TOP_WIDE)
	band.offset_top = HEADER_H
	band.offset_bottom = HEADER_H + TABS_H
	add_child(band)
	var rule := Estilo.filete(2)
	rule.set_anchors_preset(Control.PRESET_BOTTOM_WIDE)
	rule.offset_top = -2
	band.add_child(rule)

	var row := HBoxContainer.new()
	row.set_anchors_preset(Control.PRESET_FULL_RECT)
	row.offset_bottom = -2
	row.offset_right = -24
	row.add_theme_constant_override("separation", 0)
	band.add_child(row)

	for item in SECTIONS:
		var id: String = item[0]
		var b := Estilo.boton("", 12, Estilo.TINTA, Color(0, 0, 0, 0), Color(Estilo.TINTA, 0.06), 0, 0)
		b.size_flags_vertical = Control.SIZE_EXPAND_FILL
		b.pressed.connect(func(): _select_section(id))
		var inner := HBoxContainer.new()
		inner.set_anchors_preset(Control.PRESET_FULL_RECT)
		inner.alignment = BoxContainer.ALIGNMENT_CENTER
		inner.add_theme_constant_override("separation", 5)
		inner.mouse_filter = Control.MOUSE_FILTER_IGNORE
		b.add_child(inner)
		var name_l := Estilo.etiqueta(str(item[1]), 12, Estilo.TINTA, _tab_font())
		name_l.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		inner.add_child(name_l)
		var count_l := Estilo.etiqueta("", 13, Estilo.ACENTO, Estilo.grotesca())
		count_l.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		count_l.visible = false
		inner.add_child(count_l)
		b.set_meta("name_l", name_l)
		b.set_meta("count_l", count_l)
		row.add_child(b)
		tab_buttons[id] = b
		_fit_tab(b)

	var spacer := Control.new()
	spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(spacer)

	lbl_hint = Estilo.etiqueta("", 11, Estilo.GRIS_CLARO)
	lbl_hint.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(lbl_hint)

static var _tab_font_cache: FontVariation = null

## Inter negra con 1 de espaciado, como PestanasPaginas de la familia.
static func _tab_font() -> FontVariation:
	if _tab_font_cache == null:
		_tab_font_cache = FontVariation.new()
		_tab_font_cache.base_font = Estilo.F_NEGRITA
		_tab_font_cache.spacing_glyph = 1
	return _tab_font_cache

## Ancho de la pestaña según su nombre y su número (12 px de margen a cada lado).
func _fit_tab(b: Button) -> void:
	var name_l: Label = b.get_meta("name_l")
	var count_l: Label = b.get_meta("count_l")
	var w: float = name_l.get_minimum_size().x
	if count_l.visible:
		w += 5.0 + count_l.get_minimum_size().x
	b.custom_minimum_size = Vector2(w + 28.0, 0)

func _paint_tab(b: Button, on: bool) -> void:
	var name_l: Label = b.get_meta("name_l")
	var count_l: Label = b.get_meta("count_l")
	Estilo.pintar_boton(b, Estilo.TINTA, Estilo.ACENTO if on else Color(0, 0, 0, 0), Estilo.ACENTO.darkened(0.12) if on else Color(Estilo.TINTA, 0.06), 0, 0)
	name_l.add_theme_color_override("font_color", Estilo.SOBRE_ACENTO if on else Estilo.TINTA)
	count_l.add_theme_color_override("font_color", Estilo.SOBRE_ACENTO if on else Estilo.ACENTO)

func _build_map_frame() -> void:
	var frame := Panel.new()
	frame.show_behind_parent = true
	frame.mouse_filter = Control.MOUSE_FILTER_IGNORE
	frame.set_anchors_preset(Control.PRESET_FULL_RECT)
	frame.offset_left = -MAP_MAT
	frame.offset_top = -MAP_MAT
	frame.offset_right = MAP_MAT
	frame.offset_bottom = MAP_MAT
	frame.add_theme_stylebox_override("panel", Estilo.caja(Estilo.PAPEL, Estilo.TINTA, 3))
	map_canvas.add_child(frame)
	map_frame = frame

# ---------------------------------------------------------------- panel

func _build_panel() -> void:
	var panel := Panel.new()
	var sb := Estilo.caja(Estilo.PAPEL)
	sb.border_color = Estilo.TINTA
	sb.border_width_left = 3
	panel.add_theme_stylebox_override("panel", sb)
	panel.set_anchors_preset(Control.PRESET_RIGHT_WIDE)
	panel.offset_left = -PANEL_W
	panel.offset_top = HEADER_H + TABS_H
	add_child(panel)
	panel_node = panel

	var scroll := ScrollContainer.new()
	scroll.set_anchors_preset(Control.PRESET_FULL_RECT)
	scroll.offset_left = 3
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	# Barra de desplazamiento visible y gruesa (de tinta), por si se prefiere arrastrarla.
	var bar: VScrollBar = scroll.get_v_scroll_bar()
	bar.custom_minimum_size = Vector2(8, 0)
	bar.add_theme_stylebox_override("scroll", Estilo.caja(Color(Estilo.TINTA, 0.06)))
	bar.add_theme_stylebox_override("grabber", Estilo.caja(Color(Estilo.TINTA, 0.55)))
	bar.add_theme_stylebox_override("grabber_highlight", Estilo.caja(Estilo.TINTA))
	bar.add_theme_stylebox_override("grabber_pressed", Estilo.caja(Estilo.ACENTO))
	panel.add_child(scroll)
	panel_rect_node = panel
	panel_scroll = scroll

	var margin := MarginContainer.new()
	margin.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for side in ["margin_left", "margin_right"]:
		margin.add_theme_constant_override(side, 22)
	margin.add_theme_constant_override("margin_top", 18)
	margin.add_theme_constant_override("margin_bottom", 24)
	scroll.add_child(margin)

	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 20)
	margin.add_child(col)

	_build_relief_3d(col)
	_build_section_relieve(col)
	_build_section_biomas(col)
	_build_section_regiones(col)
	_build_section_politico(col)
	_build_section_lugares(col)
	_build_section_viajes(col)
	_build_section_mundo(col)
	_build_brush(col)

	var foot := VBoxContainer.new()
	foot.add_theme_constant_override("separation", 2)
	col.add_child(foot)
	foot.add_child(Estilo.filete(1, Color(Estilo.TINTA, 0.2)))
	foot.add_child(Estilo.etiqueta("ATLAS · hecho en casa", 13, Estilo.GRIS, Estilo.F_SEMI))
	var ver: String = str(ProjectSettings.get_setting("application/config/version", ""))
	foot.add_child(Estilo.etiqueta(ver, 12, Estilo.GRIS_CLARO, Estilo.F_TEXTO))

func _section_box(parent: Control, id: String) -> VBoxContainer:
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 12)
	parent.add_child(v)
	sections_ui[id] = v
	return v

func _note(parent: Control, text: String) -> Label:
	var l := Estilo.etiqueta(text, 13, Estilo.GRIS, Estilo.F_TEXTO)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	l.custom_minimum_size = Vector2(PANEL_W - 70, 0)
	parent.add_child(l)
	return l

func _chips(parent: Control, labels: Array, on_pick: Callable, size: int = 12) -> Array[Button]:
	var flow := HFlowContainer.new()
	flow.add_theme_constant_override("h_separation", 6)
	flow.add_theme_constant_override("v_separation", 6)
	parent.add_child(flow)
	var out: Array[Button] = []
	for i in labels.size():
		var b := Estilo.boton(str(labels[i]), size, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 12, 9)
		b.custom_minimum_size.y = 40
		var idx: int = i
		b.pressed.connect(func(): on_pick.call(idx))
		flow.add_child(b)
		out.append(b)
	return out

func _toggle_chip(parent: Control, text: String, on: bool, on_toggle: Callable) -> Button:
	var b := Estilo.boton(text, 11, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 12, 9)
	_paint_filter(b, on)
	b.pressed.connect(func():
		var now: bool = not bool(b.get_meta("on", on))
		b.set_meta("on", now)
		_paint_filter(b, now)
		on_toggle.call(now)
	)
	b.set_meta("on", on)
	parent.add_child(b)
	return b

func _line_edit(parent: Control, on_submit: Callable) -> LineEdit:
	var e := LineEdit.new()
	e.add_theme_font_override("font", Estilo.F_SEMI)
	e.add_theme_font_size_override("font_size", 17)
	e.add_theme_color_override("font_color", Estilo.TINTA)
	e.add_theme_color_override("caret_color", Estilo.ACENTO)
	var sb := Estilo.caja(Estilo.CRUDO, Estilo.TINTA, 0, 10, 10)
	sb.border_width_bottom = 2
	e.add_theme_stylebox_override("normal", sb)
	var sbf := sb.duplicate() as StyleBoxFlat
	sbf.border_color = Estilo.ACENTO
	e.add_theme_stylebox_override("focus", sbf)
	e.text_submitted.connect(func(t: String):
		on_submit.call(t)
		e.release_focus()
	)
	e.focus_exited.connect(func(): on_submit.call(e.text))
	parent.add_child(e)
	return e

func _accent_button(parent: Control, text: String) -> Button:
	var b := Estilo.boton(text, 12, Estilo.ACENTO, Color(0, 0, 0, 0), Color(0, 0, 0, 0.06), 0, 8)
	b.alignment = HORIZONTAL_ALIGNMENT_LEFT
	parent.add_child(b)
	return b

func _accent_link(parent: Control, text: String, action: Callable) -> Button:
	var b := _accent_button(parent, text)
	b.pressed.connect(action)
	return b

func _build_relief_3d(col: Control) -> void:
	var relief := VBoxContainer.new()
	relief.add_theme_constant_override("separation", 10)
	relief.visible = false
	sec_relief_3d = relief
	col.add_child(relief)
	val_relief = _title(relief, "Relieve 3D", "× 1,0")
	_slider_row(relief, "EXAGERACIÓN", 0.4, 2.2, 0.1, relief_mult, func(v: float):
		relief_mult = v
		mat_3d.set_shader_parameter("height_scale", 2.6 * v)
		val_relief.text = "× " + ("%.1f" % v).replace(".", ",")
		_world3d_dirty = true
		_places3d_dirty = true
		if input_ctrl.is_3d_mode:
			_rebuild_3d()
	)
	var trow := HBoxContainer.new()
	relief.add_child(trow)
	var tl := Estilo.etiqueta("ÁRBOLES Y EDIFICIOS", 11, Estilo.GRIS)
	tl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	trow.add_child(tl)
	lbl_trees = Estilo.etiqueta("", 11, Estilo.TINTA)
	trow.add_child(lbl_trees)
	density_chips = _chips(relief, ["SIN ÁRBOLES", "POCOS", "NORMAL", "MUCHOS"], func(i: int):
		trees_3d = i > 0
		if i > 0:
			vegetation.density_idx = i - 1
		for k in density_chips.size():
			_paint_filter(density_chips[k], k == i)
		_world3d_dirty = true
		if input_ctrl.is_3d_mode:
			_rebuild_3d()
	, 11)
	for k in density_chips.size():
		_paint_filter(density_chips[k], k == 2)
	relief.add_child(Estilo.etiqueta("SUELO", 11, Estilo.GRIS))
	var ground_chips: Array[Button] = []
	ground_chips = _chips(relief, ["TEXTURAS", "LISO"], func(i: int):
		mat_3d.set_shader_parameter("use_ground", i == 0)
		for k in ground_chips.size():
			_paint_filter(ground_chips[k], k == i)
	, 11)
	_paint_filter(ground_chips[0], true)
	_paint_filter(ground_chips[1], false)
	relief.add_child(Estilo.etiqueta("SOMBRAS", 11, Estilo.GRIS))
	var cfg := ConfigFile.new()
	cfg.load("user://atlas.cfg")
	shadows_3d = bool(cfg.get_value("vista", "sombras", true))
	var shadow_chips: Array[Button] = []
	shadow_chips = _chips(relief, ["CON SOMBRAS", "SIN SOMBRAS"], func(i: int):
		shadows_3d = i == 0
		_fit_shadows()
		for k in shadow_chips.size():
			_paint_filter(shadow_chips[k], k == i)
		var c2 := ConfigFile.new()
		c2.load("user://atlas.cfg")
		c2.set_value("vista", "sombras", shadows_3d)
		c2.save("user://atlas.cfg")
	, 11)
	_paint_filter(shadow_chips[0], shadows_3d)
	_paint_filter(shadow_chips[1], not shadows_3d)
	_fit_shadows()
	_note(relief, "Modelos CC0 de Kenney, KayKit (Kay Lousberg) y Quaternius; suelos CC0 de Poly Haven. Si el 3D va a tirones, baja a «Pocos», pon el suelo liso o quita las sombras.")
	relief.add_child(Estilo.filete(2))

func _build_section_relieve(col: Control) -> void:
	var v := _section_box(col, "relieve")
	lbl_tool_name = _title(v, "Relieve", "")
	var labels: Array = []
	for t in TOOLS:
		labels.append(t[1])
	tool_chips = _chips(v, labels, func(i: int): _select_tool(TOOLS[i][0]))
	lbl_tool_desc = _note(v, "")

func _build_section_biomas(col: Control) -> void:
	var v := _section_box(col, "biomas")
	lbl_biome_name = _title(v, "Biomas", "")
	var opts := HFlowContainer.new()
	opts.add_theme_constant_override("h_separation", 6)
	v.add_child(opts)
	chk_symbols = _toggle_chip(opts, "SÍMBOLOS", show_symbols, func(on: bool):
		show_symbols = on
		mat_2d.set_shader_parameter("show_symbols", on)
	)
	var grid := GridContainer.new()
	grid.columns = 2
	grid.add_theme_constant_override("h_separation", 6)
	grid.add_theme_constant_override("v_separation", 6)
	v.add_child(grid)
	var names: Array = ["AUTOMÁTICO"]
	for b in Catalogo.BIOMAS:
		names.append(b[0])
	for i in names.size():
		var b := Estilo.boton(_frase(str(names[i])), 15, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 8, 8)
		b.alignment = HORIZONTAL_ALIGNMENT_LEFT
		b.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		b.custom_minimum_size = Vector2((PANEL_W - 3 - 44 - 6) / 2, 42)
		_paint_row(b, false)
		b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		b.add_theme_constant_override("h_separation", 6)
		if i == 0:
			b.icon = _swatch(Estilo.PAPEL)
		var idx: int = i
		b.pressed.connect(func(): _select_biome(idx))
		grid.add_child(b)
		biome_buttons.append(b)
	_note(v, "«Automático» borra el bioma: el color vuelve a salir de la altura.")
	var bl: Label
	bl = _slider_row(v, "FUNDIDO ENTRE BIOMAS", 0.0, 1.0, 0.05, biome_blend, func(x: float):
		biome_blend = x
		bl.text = "%d %%" % int(round(x * 100.0))
		_apply_blend()
		_schedule_save()
	)
	blend_slider = bl.get_parent().get_parent().get_child(1) as HSlider
	bl.text = "%d %%" % int(round(biome_blend * 100.0))
	_note(v, "Mezcla los colores, los árboles y el suelo en la linde de dos biomas. A cero, la linde es nítida.")

func _build_section_politico(col: Control) -> void:
	var v := _section_box(col, "politico")
	lbl_realm_count = _title(v, "Política", "0 REINOS")
	_accent_link(v, "+ NUEVO REINO", func():
		var id: int = engine.add_realm()
		if id > 0:
			_select_realm(id)
	)
	realm_list = VBoxContainer.new()
	realm_list.add_theme_constant_override("separation", 6)
	v.add_child(realm_list)
	lbl_realm_hint = _note(v, "Elige un reino y pinta su territorio. Las fronteras salen solas donde se tocan dos reinos.")

	var opts := HFlowContainer.new()
	opts.add_theme_constant_override("h_separation", 6)
	v.add_child(opts)
	chk_realms = _toggle_chip(opts, "FRONTERAS", show_realms, func(on: bool):
		show_realms = on
		mat_2d.set_shader_parameter("show_realms", on)
		mat_3d.set_shader_parameter("show_realms", on)
		overlay.show_realm_names = on
		overlay.queue_redraw()
	)

	realm_editor = VBoxContainer.new()
	realm_editor.add_theme_constant_override("separation", 10)
	v.add_child(realm_editor)
	realm_editor.add_child(Estilo.filete(2))
	realm_editor.add_child(Estilo.etiqueta("NOMBRE", 11, Estilo.GRIS))
	realm_name_edit = _line_edit(realm_editor, func(t: String):
		if t.strip_edges() != "":
			engine.update_realm(current_realm, "name", t.strip_edges())
	)
	_accent_link(realm_editor, "OTRO NOMBRE →", func():
		engine.update_realm(current_realm, "name", Nombres.reino(engine.rng))
	)
	realm_mode_chips = _chips(realm_editor, ["PINTAR TERRITORIO", "BORRAR TERRITORIO"], func(i: int):
		realm_erase = (i == 1)
		_on_realms_changed()
	, 11)
	realm_editor.add_child(Estilo.etiqueta("TIPO", 11, Estilo.GRIS))
	realm_kind_chips = _chips(realm_editor, Catalogo.TIPOS_REINO, func(i: int):
		engine.update_realm(current_realm, "kind", Catalogo.TIPOS_REINO[i])
	, 11)
	realm_editor.add_child(Estilo.etiqueta("COLOR", 11, Estilo.GRIS))
	var colors := HFlowContainer.new()
	colors.add_theme_constant_override("h_separation", 6)
	colors.add_theme_constant_override("v_separation", 6)
	realm_editor.add_child(colors)
	for i in Catalogo.COLORES_REINO.size():
		var b := Button.new()
		b.focus_mode = Control.FOCUS_NONE
		b.custom_minimum_size = Vector2(38, 38)
		var idx: int = i
		b.pressed.connect(func(): engine.update_realm(current_realm, "color", Catalogo.COLORES_REINO[idx]))
		colors.add_child(b)
		realm_color_buttons.append(b)
	realm_editor.add_child(_caption("PERTENECE A · UN IMPERIO CONTIENE REINOS"))
	realm_parent_option = _option_button()
	realm_parent_option.item_selected.connect(func(i: int):
		engine.set_realm_parent(current_realm, realm_parent_option.get_item_id(i))
	)
	realm_editor.add_child(realm_parent_option)
	realm_editor.add_child(Estilo.filete(1, Color(Estilo.TINTA, 0.25)))
	realm_editor.add_child(_caption("RELACIONES · SE VEN EN LA FRONTERA"))
	relation_box = VBoxContainer.new()
	relation_box.add_theme_constant_override("separation", 6)
	realm_editor.add_child(relation_box)
	var legend := VBoxContainer.new()
	legend.add_theme_constant_override("separation", 2)
	realm_editor.add_child(legend)
	for rk in Catalogo.RELACIONES:
		if str(rk[0]) == "":
			continue
		var lr := HBoxContainer.new()
		lr.add_theme_constant_override("separation", 8)
		var sw := TextureRect.new()
		sw.texture = _swatch(rk[2])
		sw.stretch_mode = TextureRect.STRETCH_KEEP_CENTERED
		lr.add_child(sw)
		lr.add_child(Estilo.etiqueta("%s · %s" % [rk[1], str(rk[3]).to_lower()], 11, Estilo.GRIS, Estilo.F_TEXTO))
		legend.add_child(lr)
	var b_del := _accent_button(realm_editor, "ELIMINAR ESTE REINO")
	b_del.pressed.connect(func():
		_confirm("realm_del", b_del, "ELIMINAR ESTE REINO", func(): engine.delete_realm(current_realm))
	)

# ---------------------------------------------------------------- regiones con nombre

func _build_section_regiones(col: Control) -> void:
	var v := _section_box(col, "regiones")
	lbl_area_count = _title(v, "Regiones", "0 REGIONES")
	_note(v, "Bosques, montes, mares, lagos… con su nombre. Elige el tipo, crea la región, ponle nombre y pinta dónde está.")
	var labels: Array = []
	for t in Catalogo.TIPOS_REGION:
		labels.append(t[1])
	area_kind_chips = _chips(v, labels, func(i: int):
		area_kind = Catalogo.TIPOS_REGION[i][0]
		if current_area > 0:
			engine.update_area(current_area, "kind", area_kind)
		_refresh_area_editor()
	, 10)
	_accent_link(v, "+ NUEVA REGIÓN", func():
		var id: int = engine.add_area(area_kind)
		if id > 0:
			current_area = id
			area_erase = false
			_on_areas_changed()
			area_name_edit.grab_focus()
			area_name_edit.select_all()
	)
	area_list = VBoxContainer.new()
	area_list.add_theme_constant_override("separation", 6)
	v.add_child(area_list)
	lbl_area_hint = _note(v, "Con una región elegida, el lápiz pinta su contorno (discontinuo; solo se ve aquí).")
	var opts := HFlowContainer.new()
	v.add_child(opts)
	_toggle_chip(opts, "NOMBRES", true, func(on: bool):
		overlay.show_area_names = on
		overlay.queue_redraw()
	)

	area_editor = VBoxContainer.new()
	area_editor.add_theme_constant_override("separation", 10)
	v.add_child(area_editor)
	area_editor.add_child(Estilo.filete(2))
	area_editor.add_child(Estilo.etiqueta("NOMBRE", 11, Estilo.GRIS))
	area_name_edit = _line_edit(area_editor, func(t: String):
		if t.strip_edges() != "":
			engine.update_area(current_area, "name", t.strip_edges())
	)
	_accent_link(area_editor, "OTRO NOMBRE →", func():
		var a: Dictionary = engine.area_by_id(current_area)
		if not a.is_empty():
			engine.update_area(current_area, "name", Nombres.region(str(a["kind"]), engine.rng))
	)
	area_mode_chips = _chips(area_editor, ["PINTAR", "BORRAR CONTORNO"], func(i: int):
		area_erase = (i == 1)
		_on_areas_changed()
	, 11)
	area_editor.add_child(Estilo.etiqueta("AL PINTARLA, PINTA TAMBIÉN EL BIOMA", 11, Estilo.GRIS))
	area_biome_option = OptionButton.new()
	area_biome_option.focus_mode = Control.FOCUS_NONE
	area_biome_option.add_theme_font_override("font", Estilo.versalitas())
	area_biome_option.add_theme_font_size_override("font_size", 12)
	for k in ["normal", "hover", "pressed", "focus"]:
		area_biome_option.add_theme_stylebox_override(k, Estilo.caja(Color(0, 0, 0, 0), Estilo.TINTA, 2, 12, 9))
	for k in ["font_color", "font_hover_color", "font_pressed_color", "font_focus_color"]:
		area_biome_option.add_theme_color_override(k, Estilo.TINTA)
	area_biome_option.add_item("NINGUNO (SOLO EL NOMBRE)", 0)
	for i in Catalogo.BIOMAS.size():
		area_biome_option.add_item(str(Catalogo.BIOMAS[i][0]), i + 1)
	area_biome_option.item_selected.connect(func(idx: int):
		engine.update_area(current_area, "biome", area_biome_option.get_item_id(idx))
	)
	area_editor.add_child(area_biome_option)
	var b_del := _accent_button(area_editor, "ELIMINAR ESTA REGIÓN")
	b_del.pressed.connect(func():
		_confirm("area_del", b_del, "ELIMINAR ESTA REGIÓN", func(): engine.delete_area(current_area))
	)

func _on_areas_changed() -> void:
	if engine.area_by_id(current_area).is_empty():
		current_area = -1
	overlay.areas = engine.areas
	overlay.queue_redraw()
	_set_tab_count("regiones", engine.areas.size())
	var simg := Image.create(256, 1, false, Image.FORMAT_R8)
	for a in engine.areas:
		if Catalogo.es_conflicto(str(a["kind"])):
			simg.set_pixel(int(a["id"]), 0, Color(1, 0, 0))
	mat_2d.set_shader_parameter("area_style", ImageTexture.create_from_image(simg))
	lbl_area_count.text = "%d REGIONES" % engine.areas.size() if engine.areas.size() != 1 else "1 REGIÓN"
	mat_2d.set_shader_parameter("selected_area", current_area)
	for c in area_list.get_children():
		c.queue_free()
	for a in engine.areas:
		var id: int = int(a["id"])
		var t: Array = Catalogo.tipo_region(str(a["kind"]))
		var b := Estilo.boton("%s · %s" % [str(a["name"]).to_upper(), str(t[1])], 11, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 12, 9)
		b.alignment = HORIZONTAL_ALIGNMENT_LEFT
		b.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
		b.clip_text = true
		b.pressed.connect(func():
			current_area = id
			area_erase = false
			_on_areas_changed()
		)
		area_list.add_child(b)
		_paint_filter(b, id == current_area and not area_erase)
	_refresh_area_editor()
	_refresh_labels_3d()

func _refresh_area_editor() -> void:
	var a: Dictionary = engine.area_by_id(current_area)
	area_editor.visible = not a.is_empty()
	for i in area_mode_chips.size():
		_paint_filter(area_mode_chips[i], (i == 1) == area_erase)
	var kind: String = area_kind if a.is_empty() else str(a["kind"])
	for i in area_kind_chips.size():
		_paint_filter(area_kind_chips[i], Catalogo.TIPOS_REGION[i][0] == kind)
	if a.is_empty():
		return
	if not area_name_edit.has_focus():
		area_name_edit.text = str(a["name"])
	var bid: int = int(a["biome"])
	for i in area_biome_option.item_count:
		if area_biome_option.get_item_id(i) == bid:
			area_biome_option.select(i)

func _build_section_lugares(col: Control) -> void:
	var v := _section_box(col, "lugares")
	lbl_place_count = _title(v, "Lugares", "0 LUGARES")
	lugares_mode_chips = _chips(v, ["LUGARES", "CAMINOS"], func(i: int):
		_set_lugares_mode("caminos" if i == 1 else "lugares")
	, 11)
	places_box = VBoxContainer.new()
	places_box.add_theme_constant_override("separation", 10)
	v.add_child(places_box)
	_build_roads_box(v)
	var pv: VBoxContainer = places_box
	var labels: Array = []
	for t in Catalogo.TIPOS_LUGAR:
		labels.append(t[1])
	place_kind_chips = _chips(pv, labels, func(i: int):
		var kind: String = Catalogo.TIPOS_LUGAR[i][0]
		if selected_place > 0:
			engine.update_place(selected_place, "kind", kind)
		place_kind = kind
		_refresh_place_editor()
	, 11)
	_note(pv, "Toca el mapa para poner uno del tipo elegido; toca uno para editarlo y arrástralo para moverlo. Los nombres pequeños aparecen al acercarte.")
	var opts := HFlowContainer.new()
	pv.add_child(opts)
	chk_names = _toggle_chip(opts, "NOMBRES", true, func(on: bool):
		overlay.show_place_names = on
		overlay.queue_redraw()
	)

	place_editor = VBoxContainer.new()
	place_editor.add_theme_constant_override("separation", 10)
	pv.add_child(place_editor)
	place_editor.add_child(Estilo.filete(2))
	place_editor.add_child(Estilo.etiqueta("NOMBRE", 11, Estilo.GRIS))
	place_name_edit = _line_edit(place_editor, func(t: String):
		if t.strip_edges() != "":
			engine.update_place(selected_place, "name", t.strip_edges())
	)
	# Tamaño de aldeas, pueblos, ciudades, capitales y puertos: el trazado se genera según él.
	place_size_box = VBoxContainer.new()
	place_size_box.add_theme_constant_override("separation", 4)
	place_editor.add_child(place_size_box)
	place_size_val = _slider_row(place_size_box, "TAMAÑO", 1.0, 10.0, 1.0, 5.0, func(x: float):
		var p: Dictionary = engine.place_by_id(selected_place)
		if p.is_empty():
			return
		engine.update_place(selected_place, "size", int(x))
	)
	place_size_slider = place_size_val.get_parent().get_parent().get_child(1) as HSlider
	place_size_note = _note(place_size_box, "")
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 16)
	place_editor.add_child(row)
	_accent_link(row, "OTRO NOMBRE →", func():
		var p: Dictionary = engine.place_by_id(selected_place)
		if not p.is_empty():
			engine.update_place(selected_place, "name", Nombres.lugar(str(p["kind"]), engine.rng))
	)
	var b_del := _accent_button(row, "ELIMINAR")
	b_del.pressed.connect(func():
		engine.delete_place(selected_place)
		selected_place = -1
	)
	var b_done := _accent_link(row, "LISTO", func(): _select_place(-1))
	b_done.add_theme_color_override("font_color", Estilo.TINTA)

func _build_roads_box(v: Control) -> void:
	roads_box = VBoxContainer.new()
	roads_box.add_theme_constant_override("separation", 10)
	roads_box.visible = false
	v.add_child(roads_box)
	lbl_road_count = _caption("TIPO DE CAMINO")
	roads_box.add_child(lbl_road_count)
	var names: Array = []
	for t in Catalogo.TIPOS_CAMINO:
		names.append(t[1])
	road_kind_chips = _chips(roads_box, names, func(i: int):
		road_kind = str(Catalogo.TIPOS_CAMINO[i][0])
		if selected_road > 0:
			engine.set_road_kind(selected_road, road_kind)
		_refresh_roads_ui()
	, 11)
	lbl_road_desc = _note(roads_box, "")
	_note(roads_box, "Dibuja el camino con el lápiz, de un tirón. Toca uno para cambiarle el tipo o quitarlo. Por un camino se viaja más deprisa (Viajes lo tiene en cuenta).")
	road_editor = HBoxContainer.new()
	road_editor.add_theme_constant_override("separation", 16)
	roads_box.add_child(road_editor)
	var b_del := _accent_button(road_editor, "QUITAR ESTE CAMINO")
	b_del.pressed.connect(func():
		engine.delete_road(selected_road)
		_select_road(-1)
	)
	var b_done := _accent_link(road_editor, "LISTO", func(): _select_road(-1))
	b_done.add_theme_color_override("font_color", Estilo.TINTA)

func _set_lugares_mode(mode: String) -> void:
	lugares_mode = mode
	if mode == "caminos":
		_select_place(-1)
	else:
		_select_road(-1)
	_refresh_roads_ui()
	_update_hint()

func _select_road(id: int) -> void:
	selected_road = id
	overlay.selected_road = id
	var r: Dictionary = engine.road_by_id(id)
	if not r.is_empty():
		road_kind = str(r["kind"])
	overlay.queue_redraw()
	_refresh_roads_ui()

func _refresh_roads_ui() -> void:
	if places_box == null:
		return
	var roads_on: bool = lugares_mode == "caminos"
	places_box.visible = not roads_on
	roads_box.visible = roads_on
	for i in lugares_mode_chips.size():
		_paint_filter(lugares_mode_chips[i], (i == 1) == roads_on)
	for i in road_kind_chips.size():
		_paint_filter(road_kind_chips[i], str(Catalogo.TIPOS_CAMINO[i][0]) == road_kind)
	lbl_road_desc.text = str(Catalogo.tipo_camino(road_kind)[5])
	road_editor.visible = selected_road > 0 and not engine.road_by_id(selected_road).is_empty()
	var n: int = engine.roads.size()
	lbl_road_count.text = "TIPO DE CAMINO · %d %s" % [n, "CAMINO" if n == 1 else "CAMINOS"]

## Una longitud en píxeles del mapa, en km si el viaje está calibrado.
func _fmt_len(px: float) -> String:
	var km_pp: float = float(engine.travel.get("km_per_px", 0.0))
	return (Estilo.miles(int(round(px * km_pp))) + " km") if km_pp > 0.0 else (Estilo.miles(int(round(px))) + " px")

func _on_roads_changed() -> void:
	overlay.roads = engine.roads
	overlay.map_px = Vector2(engine.map_size)
	if val_route_dist != null:
		_refresh_route()
	if engine.road_by_id(selected_road).is_empty():
		selected_road = -1
		overlay.selected_road = -1
	_places3d_dirty = true
	_world3d_dirty = true
	if input_ctrl.is_3d_mode:
		_rebuild_3d.call_deferred()
	overlay.queue_redraw()
	_refresh_roads_ui()

## Quita puntos casi alineados (Ramer-Douglas-Peucker) con tolerancia [eps] en uv.
func _simplify(pts: Array, eps: float) -> Array:
	if pts.size() < 3:
		return pts.duplicate()
	var a: Vector2 = pts[0]
	var b: Vector2 = pts[pts.size() - 1]
	var best: float = -1.0
	var idx: int = -1
	for i in range(1, pts.size() - 1):
		var p: Vector2 = pts[i]
		var ab: Vector2 = b - a
		var t: float = clampf((p - a).dot(ab) / maxf(ab.length_squared(), 1e-12), 0.0, 1.0)
		var d: float = p.distance_to(a + ab * t)
		if d > best:
			best = d
			idx = i
	if best <= eps:
		return [a, b]
	var left: Array = _simplify(pts.slice(0, idx + 1), eps)
	var right: Array = _simplify(pts.slice(idx), eps)
	left.pop_back()
	return left + right

# ---------------------------------------------------------------- viajes

func _build_section_viajes(col: Control) -> void:
	var v := _section_box(col, "viajes")
	_title(v, "Viajes", "")
	_note(v, "Toca el mapa para marcar etapas (si tocas un lugar, la ruta pasa por él). La ruta cuenta las cuestas, la montaña y el agua.")
	travel_mode_chips = _chips(v, TRAVEL_MODES.map(func(m): return m[1]), func(i: int):
		route_mode = str(TRAVEL_MODES[i][0])
		_refresh_route()
	, 11)
	var bl := _blocks(v, [["—", "DISTANCIA"], ["—", "TIEMPO"], ["0", "ETAPAS"]])
	val_route_dist = bl[0]
	val_route_time = bl[1]
	val_route_legs = bl[2]
	var row := HFlowContainer.new()
	row.add_theme_constant_override("h_separation", 14)
	v.add_child(row)
	_accent_link(row, "QUITAR ÚLTIMA", func():
		if not route_pts.is_empty():
			route_pts.pop_back()
			_refresh_route()
	)
	_accent_link(row, "BORRAR RUTA", func():
		route_pts.clear()
		_refresh_route()
	)
	_accent_link(row, "GUARDAR RUTA", func():
		if route_pts.size() >= 2:
			engine.add_route(route_pts, route_mode, "Ruta %d" % (engine.routes.size() + 1))
	)
	v.add_child(Estilo.filete(2))
	_title(v, "Calibrar", "")
	lbl_travel_calib = _note(v, "")
	_note(v, "Marca un viaje que conozcas y di cuánto se tarda (con el medio elegido arriba). Todas las rutas se calculan a partir de él.")
	var crow := HBoxContainer.new()
	crow.add_theme_constant_override("separation", 8)
	v.add_child(crow)
	calib_amount = _line_edit(crow, func(_t: String): pass)
	calib_amount.placeholder_text = "3"
	calib_amount.custom_minimum_size = Vector2(70, 0)
	calib_amount.virtual_keyboard_type = LineEdit.KEYBOARD_TYPE_NUMBER_DECIMAL
	var unit_box := HBoxContainer.new()
	crow.add_child(unit_box)
	calib_unit_chips = _chips(unit_box, ["DÍAS", "HORAS"], func(i: int):
		calib_unit = "dias" if i == 0 else "horas"
		for k in calib_unit_chips.size():
			_paint_filter(calib_unit_chips[k], k == i)
	, 11)
	_paint_filter(calib_unit_chips[0], true)
	_paint_filter(calib_unit_chips[1], false)
	_accent_link(v, "ESTE VIAJE DURA ESO →", _calibrate_time)
	var srow := HBoxContainer.new()
	srow.add_theme_constant_override("separation", 8)
	v.add_child(srow)
	scale_km_edit = _line_edit(srow, func(_t: String): pass)
	scale_km_edit.placeholder_text = "120"
	scale_km_edit.custom_minimum_size = Vector2(90, 0)
	scale_km_edit.virtual_keyboard_type = LineEdit.KEYBOARD_TYPE_NUMBER_DECIMAL
	srow.add_child(Estilo.etiqueta("KM", 11, Estilo.GRIS))
	_accent_link(v, "ESTA RUTA MIDE ESOS KM →", _calibrate_km)
	v.add_child(Estilo.filete(2))
	_title(v, "Rutas guardadas", "")
	route_list = VBoxContainer.new()
	route_list.add_theme_constant_override("separation", 6)
	v.add_child(route_list)
	for k in travel_mode_chips.size():
		_paint_filter(travel_mode_chips[k], k == 0)

func _mode_speed(mode: String) -> float:
	for m in TRAVEL_MODES:
		if m[0] == mode:
			return float(m[2])
	return 1.0

func _route_cost(points: Array, mode: String) -> Dictionary:
	return engine.route_cost(points, "barco" if mode == "barco" else "tierra")

static func _fmt_hours(h: float) -> String:
	# Se redondea una sola vez (a minutos o a horas) y luego se reparte: si no, salían «3 D 24 H».
	if h < 24.0:
		var mins: int = int(round(h * 60.0))
		if mins < 60:
			return "%d MIN" % mins
		return "%d H %02d" % [mins / 60, mins % 60] if mins % 60 != 0 else "%d H" % (mins / 60)
	var hours: int = int(round(h))
	var d: int = hours / 24
	var rest: int = hours % 24
	return "%d D %d H" % [d, rest] if rest > 0 else ("%d DÍAS" % d if d != 1 else "1 DÍA")

func _route_texts(points: Array, mode: String) -> Array:
	if points.size() < 2:
		return ["—", "—"]
	var c: Dictionary = _route_cost(points, mode)
	var km_pp: float = float(engine.travel.get("km_per_px", 0.0))
	var hpp: float = float(engine.travel.get("hours_per_px", 0.0))
	var dist: String = (Estilo.miles(int(round(float(c["px"]) * km_pp))) + " KM") if km_pp > 0.0 else (Estilo.miles(int(round(float(c["px"])))) + " PX")
	var time: String = _fmt_hours(float(c["weighted"]) * hpp / _mode_speed(mode)) if hpp > 0.0 else "SIN CALIBRAR"
	return [dist, time]

func _refresh_route() -> void:
	for k in travel_mode_chips.size():
		_paint_filter(travel_mode_chips[k], TRAVEL_MODES[k][0] == route_mode)
	var t: Array = _route_texts(route_pts, route_mode)
	val_route_dist.text = t[0]
	val_route_time.text = t[1]
	val_route_legs.text = str(maxi(route_pts.size() - 1, 0))
	var hpp: float = float(engine.travel.get("hours_per_px", 0.0))
	lbl_travel_calib.text = ("Referencia: " + str(engine.travel.get("sample", ""))) if hpp > 0.0 else "Aún sin referencia: el tiempo saldrá cuando calibres un viaje."
	overlay.route = route_pts
	overlay.route_label = t[1] if t[1] != "SIN CALIBRAR" else t[0]
	overlay.routes = engine.routes
	overlay.queue_redraw()
	for c in route_list.get_children():
		c.queue_free()
	for r in engine.routes:
		var rid: int = int(r["id"])
		var rt: Array = _route_texts(r["points"], str(r["mode"]))
		var line := HBoxContainer.new()
		route_list.add_child(line)
		var b := Estilo.boton("%s · %s · %s" % [str(r["name"]).to_upper(), rt[0], rt[1]], 11, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 10, 8)
		b.alignment = HORIZONTAL_ALIGNMENT_LEFT
		b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		b.clip_text = true
		var pts: Array = r["points"]
		var md: String = r["mode"]
		b.pressed.connect(func():
			route_pts = pts.duplicate()
			route_mode = md
			_refresh_route()
		)
		_paint_filter(b, false)
		line.add_child(b)
		var x := _accent_button(line, "BORRAR")
		x.pressed.connect(func(): engine.delete_route(rid))
	if engine.routes.is_empty():
		route_list.add_child(_note_label("Ninguna todavía."))

func _calibrate_time() -> void:
	var amount: float = calib_amount.text.replace(",", ".").to_float()
	if route_pts.size() < 2 or amount <= 0.0:
		_flash(lbl_travel_calib)
		return
	var hours: float = amount * (24.0 if calib_unit == "dias" else 1.0)
	var c: Dictionary = _route_cost(route_pts, route_mode)
	var tr: Dictionary = engine.travel.duplicate()
	# Horas por píxel ponderado yendo a pie: así vale para cualquier medio.
	tr["hours_per_px"] = hours * _mode_speed(route_mode) / maxf(float(c["weighted"]), 1.0)
	var mname: String = "a pie"
	for m in TRAVEL_MODES:
		if m[0] == route_mode:
			mname = str(m[1]).to_lower()
	tr["sample"] = "%s %s %s, %d etapas." % [calib_amount.text, "días" if calib_unit == "dias" else "horas", mname, route_pts.size() - 1]
	engine.set_travel(tr)

func _calibrate_km() -> void:
	var km: float = scale_km_edit.text.replace(",", ".").to_float()
	if route_pts.size() < 2 or km <= 0.0:
		_flash(lbl_travel_calib)
		return
	var c: Dictionary = _route_cost(route_pts, route_mode)
	var tr: Dictionary = engine.travel.duplicate()
	tr["km_per_px"] = km / maxf(float(c["px"]), 1.0)
	engine.set_travel(tr)

# ---------------------------------------------------------------- mapas guardados

func _view_extra() -> Dictionary:
	return {"theme": current_theme_idx, "sun": sun_azimuth, "relief": relief_mult, "blend": biome_blend}

## Radio del fundido en píxeles del mapa: hasta ~80 px en un mapa de 2048 (proporcional en los demás).
func _blend_px() -> float:
	return biome_blend * 80.0 * float(maxi(engine.map_size.x, engine.map_size.y)) / 2048.0

## Pinta (una vez) el fundido entre biomas en blend_vp: a 1/4 del mapa (como mucho 2.048 px); el mapa lo lee con una
## muestra. Se pide al cambiar los biomas, el estilo, la paleta o el radio, y se hace al final del fotograma.
func _queue_blend_bake() -> void:
	if _blend_queued:
		return
	_blend_queued = true
	_bake_blend.call_deferred()

func _bake_blend() -> void:
	_blend_queued = false
	if engine == null or not engine.world_ready or blend_vp == null:
		return
	var px: float = _blend_px()
	if px < 1.0:
		mat_2d.set_shader_parameter("use_blend_tex", false)
		return
	var ms: Vector2i = engine.map_size
	var sz := Vector2i(clampi(ms.x / 4, 64, 2048), clampi(ms.y / 4, 64, 2048))
	if blend_vp.size != sz:
		blend_vp.size = sz
	blend_rect.size = Vector2(sz)
	mat_blend.set_shader_parameter("biome_tex", engine.biome.get_texture())
	mat_blend.set_shader_parameter("biome_pal", biome_pal_tex)
	mat_blend.set_shader_parameter("tex_size", Vector2(ms))
	mat_blend.set_shader_parameter("biome_blend", px)
	for k in ["biome_desat", "biome_tint", "biome_tint_amt"]:
		mat_blend.set_shader_parameter(k, mat_2d.get_shader_parameter(k))
	blend_vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	mat_2d.set_shader_parameter("blend_tex", blend_vp.get_texture())
	mat_2d.set_shader_parameter("use_blend_tex", true)

func _apply_blend() -> void:
	var px: float = _blend_px()
	_queue_blend_bake()
	mat_2d.set_shader_parameter("biome_blend", px)
	mat_3d.set_shader_parameter("biome_blend", px)
	if vegetation != null:
		vegetation.blend_px = px
	_world3d_dirty = true

func _schedule_save() -> void:
	if engine.world_ready:
		lbl_save_state.text = "CAMBIOS SIN GUARDAR…"
		autosave_timer.start()

func _do_save() -> void:
	lbl_save_state.text = "GUARDANDO…"
	await engine.save_map(_view_extra())

func _on_saved(ok: bool) -> void:
	var t: Dictionary = Time.get_time_dict_from_system()
	lbl_save_state.text = ("GUARDADO · %02d:%02d" % [t["hour"], t["minute"]]) if ok else "NO SE PUDO GUARDAR"
	if not ok:
		_toast("No se pudo guardar el mapa. Se volverá a intentar con el próximo cambio.")
	elif nube != null and nube.dentro:
		# A la cuenta, un poco después (si sigues pintando, se espera a que pares).
		upload_timer.start()
	if section == "mundo":
		_refresh_maps_list()

func _notification(what: int) -> void:
	if engine == null or not engine.world_ready:
		return
	if what == NOTIFICATION_APPLICATION_PAUSED or what == NOTIFICATION_APPLICATION_FOCUS_OUT:
		await engine.save_map(_view_extra())
		if nube != null and nube.dentro and not upload_timer.is_stopped():
			upload_timer.stop()
			_upload_now()
	elif what == NOTIFICATION_WM_CLOSE_REQUEST:
		await engine.save_map(_view_extra())
		get_tree().quit()

func _on_map_loaded() -> void:
	var ex: Dictionary = engine.loaded_extra
	selected_place = -1
	current_realm = -1
	current_area = -1
	route_pts.clear()
	_apply_theme(int(ex.get("theme", current_theme_idx)))
	sun_azimuth = float(ex.get("sun", sun_azimuth))
	sun_slider.set_value_no_signal(sun_azimuth)
	_apply_sun()
	relief_mult = float(ex.get("relief", relief_mult))
	mat_3d.set_shader_parameter("height_scale", 2.6 * relief_mult)
	biome_blend = float(ex.get("blend", 0.5))
	if blend_slider != null:
		blend_slider.set_value_no_signal(biome_blend)
		var bl: Label = blend_slider.get_parent().get_child(0).get_child(1) as Label
		bl.text = "%d %%" % int(round(biome_blend * 100.0))
	_apply_blend()
	_set_sea_level(engine.sea_level)
	map_name_edit.text = engine.map_name
	_refresh_route()
	# Abrir no es un cambio: sin autoguardado.
	autosave_timer.stop()
	lbl_save_state.text = "ABIERTO"

func _set_sea_level(x: float) -> void:
	sea_level = x
	mat_2d.set_shader_parameter("sea_level", x)
	mat_3d.set_shader_parameter("sea_level", x)
	engine.sea_level = x
	engine.set_sea_level(x)
	val_sea.text = "%d %%" % int(round(x * 100.0))
	if sea_slider != null:
		sea_slider.set_value_no_signal(x)
	_world3d_dirty = true
	_places3d_dirty = true

# ---------------------------------------------------------------- cuenta de la familia

func _obra_titulo(id: String) -> String:
	for o in nube_obras:
		if o["id"] == id:
			return str(o["t"])
	return ""

## Enlaza el mapa abierto con una novela de Nova. Si el mapa aún tiene su nombre de serie, toma el de la novela.
func _set_obra(id: String, save: bool = true) -> void:
	engine.map_obra = id
	engine.map_obra_t = _obra_titulo(id)
	if id != "" and engine.map_name.begins_with("Mundo nº"):
		engine.map_name = "Mapa de " + engine.map_obra_t
		map_name_edit.text = engine.map_name
		lbl_world.text = engine.map_name.to_upper()
	if save:
		_do_save()

func _upload_now() -> void:
	if not nube.dentro or not engine.world_ready:
		return
	lbl_nube_estado.text = "SUBIENDO A LA CUENTA…"
	var e: String = await nube.sube_mapa(engine.map_id, {"t": engine.map_name, "obra": engine.map_obra,
		"w": engine.map_size.x, "h": engine.map_size.y})
	var t: Dictionary = Time.get_time_dict_from_system()
	lbl_nube_estado.text = ("EN LA CUENTA · %02d:%02d" % [t["hour"], t["minute"]]) if e == "" else e.to_upper()
	if e != "":
		_toast(e)

## [reload]: volver a pedir novelas y mapas a la cuenta.
func _refresh_cuenta(reload: bool = false) -> void:
	if cuenta_box == null:
		return
	for c in cuenta_box.get_children():
		c.queue_free()
	lbl_nube_estado = Estilo.etiqueta("", 11, Estilo.GRIS)
	if not nube.dentro:
		cuenta_box.add_child(_note_label("Entra con tu usuario y contraseña de la familia (los de Polaris y Nova) y tus mapas irán a la cuenta, con su novela, y a tu Google Drive."))
		var u := _line_edit(cuenta_box, func(_t: String): pass)
		u.placeholder_text = "Usuario"
		var pw := _line_edit(cuenta_box, func(_t: String): pass)
		pw.placeholder_text = "Contraseña"
		pw.secret = true
		var b := Estilo.boton("ENTRAR  →", 14, Estilo.SOBRE_ACENTO, Estilo.ACENTO, Estilo.ACENTO.darkened(0.15), 18, 12)
		b.add_theme_font_override("font", Estilo.F_GROTESCA)
		b.alignment = HORIZONTAL_ALIGNMENT_LEFT
		cuenta_box.add_child(b)
		cuenta_box.add_child(lbl_nube_estado)
		b.pressed.connect(func():
			lbl_nube_estado.text = "ENTRANDO…"
			var e: String = await nube.entrar(u.text, pw.text)
			if e != "":
				lbl_nube_estado.text = e.to_upper()
		)
		_refresh_new_obra()
		return
	var row := HBoxContainer.new()
	cuenta_box.add_child(row)
	var who := Estilo.etiqueta("DENTRO COMO " + nube.usuario.to_upper(), 11, Estilo.TINTA)
	who.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(who)
	var out := _accent_button(row, "SALIR")
	out.pressed.connect(func(): _confirm("salir", out, "SALIR", func(): nube.salir()))
	cuenta_box.add_child(_caption("NOVELA DE ESTE MAPA"))
	var ob := _option_button()
	cuenta_box.add_child(ob)
	cuenta_box.add_child(lbl_nube_estado)
	var sync := HFlowContainer.new()
	sync.add_theme_constant_override("h_separation", 14)
	cuenta_box.add_child(sync)
	_accent_link(sync, "SUBIR AHORA", _upload_now)
	_accent_link(sync, "ACTUALIZAR", func(): _refresh_cuenta(true))
	cuenta_box.add_child(_caption("MAPAS EN LA CUENTA"))
	var cloud := VBoxContainer.new()
	cloud.add_theme_constant_override("separation", 6)
	cuenta_box.add_child(cloud)
	if reload or nube_obras.is_empty():
		lbl_nube_estado.text = "LEYENDO LA CUENTA…"
		nube_obras = await nube.obras()
		nube_mapas = await nube.mapas()
		if not is_instance_valid(lbl_nube_estado):
			return
		lbl_nube_estado.text = "%d NOVELAS · %d MAPAS EN LA CUENTA" % [nube_obras.size(), nube_mapas.size()]
	ob.add_item("NINGUNA")
	ob.set_item_metadata(0, "")
	for o in nube_obras:
		ob.add_item(str(o["t"]).to_upper())
		ob.set_item_metadata(ob.item_count - 1, str(o["id"]))
		if str(o["id"]) == engine.map_obra:
			ob.select(ob.item_count - 1)
	ob.item_selected.connect(func(i: int): _set_obra(str(ob.get_item_metadata(i))))
	var local: Dictionary = {}
	for m in engine.list_maps():
		local[m["id"]] = float(m["saved_at"])
	for m in nube_mapas:
		var mid: String = m["id"]
		var line := HBoxContainer.new()
		line.add_theme_constant_override("separation", 6)
		cloud.add_child(line)
		var nov: String = _obra_titulo(str(m["obra"]))
		var lb := Estilo.etiqueta("%s%s" % [str(m["t"]).to_upper(), (" · " + nov.to_upper()) if nov != "" else ""], 11, Estilo.TINTA)
		lb.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		lb.clip_text = true
		lb.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
		line.add_child(lb)
		# Bajar si no está en este dispositivo o si el de la cuenta es más nuevo.
		var newer: bool = not local.has(mid) or float(m["ul"]) / 1000.0 > float(local[mid]) + 5.0
		if mid == engine.map_id:
			line.add_child(Estilo.etiqueta("ABIERTO", 11, Estilo.GRIS))
		elif newer:
			var bb := _accent_button(line, "BAJAR")
			bb.pressed.connect(func():
				bb.text = "BAJANDO…"
				var e: String = await nube.baja_mapa(m)
				if e != "":
					_toast(e)
					bb.text = "BAJAR"
					return
				await engine.save_map(_view_extra())
				await engine.load_map(mid)
				_refresh_maps_list()
			)
		else:
			var ab := _accent_button(line, "ABRIR")
			ab.pressed.connect(func():
				await engine.save_map(_view_extra())
				await engine.load_map(mid)
			)
	if nube_mapas.is_empty():
		cloud.add_child(_note_label("Ninguno todavía: se suben solos al guardar."))
	_refresh_new_obra()

func _refresh_new_obra() -> void:
	if new_obra_opt == null:
		return
	new_obra_opt.clear()
	new_obra_opt.add_item("NINGUNA")
	new_obra_opt.set_item_metadata(0, "")
	for o in nube_obras:
		new_obra_opt.add_item(str(o["t"]).to_upper())
		new_obra_opt.set_item_metadata(new_obra_opt.item_count - 1, str(o["id"]))
		if str(o["id"]) == new_obra:
			new_obra_opt.select(new_obra_opt.item_count - 1)
	new_obra_opt.get_parent().get_child(new_obra_opt.get_index() - 1).visible = nube.dentro
	new_obra_opt.visible = nube.dentro

func _refresh_maps_list() -> void:
	if maps_list == null:
		return
	for c in maps_list.get_children():
		c.queue_free()
	for m in engine.list_maps():
		var mid: String = m["id"]
		var sz: Vector2i = m["size"]
		var d: Dictionary = Time.get_datetime_dict_from_unix_time(int(m["saved_at"]))
		var line := HBoxContainer.new()
		line.add_theme_constant_override("separation", 6)
		maps_list.add_child(line)
		var nov: String = str(m.get("obra_t", ""))
		var size_txt: String = "infinito" if bool(m.get("infinite", false)) else (Estilo.miles(maxi(sz.x, sz.y)) + " px")
		var txt: String = "%s%s · %s · %02d.%02d.%02d" % [str(m["name"]), (" · " + nov) if nov != "" else "", size_txt, d["day"], d["month"], int(d["year"]) % 100]
		var b := Estilo.boton(txt, 15, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 10, 8)
		b.alignment = HORIZONTAL_ALIGNMENT_LEFT
		b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		b.clip_text = true
		b.add_theme_font_size_override("font_size", 15)
		_paint_row(b, mid == engine.map_id)
		b.pressed.connect(func():
			if mid == engine.map_id:
				return
			await engine.save_map(_view_extra())
			await engine.load_map(mid)
		)
		line.add_child(b)
		if mid != engine.map_id:
			var x := _accent_button(line, "BORRAR")
			x.pressed.connect(func():
				_confirm("del_" + mid, x, "BORRAR", func():
					engine.delete_map_files(mid)
					_refresh_maps_list()
				)
			)

func _build_section_mundo(col: Control) -> void:
	var v := _section_box(col, "mundo")
	_title(v, "Cuenta de la familia", "")
	cuenta_box = VBoxContainer.new()
	cuenta_box.add_theme_constant_override("separation", 10)
	v.add_child(cuenta_box)
	v.add_child(Estilo.filete(2))
	_title(v, "Este mapa", "")
	map_name_edit = _line_edit(v, func(t: String):
		if t.strip_edges() != "" and t.strip_edges() != engine.map_name:
			engine.map_name = t.strip_edges()
			lbl_world.text = engine.map_name.to_upper()
			_do_save()
	)
	lbl_save_state = Estilo.etiqueta("", 11, Estilo.GRIS)
	v.add_child(lbl_save_state)
	var srow := HFlowContainer.new()
	srow.add_theme_constant_override("h_separation", 14)
	v.add_child(srow)
	_accent_link(srow, "GUARDAR AHORA", _do_save)
	_accent_link(srow, "DUPLICAR", func():
		await engine.duplicate_map(engine.map_name + " (copia)", _view_extra())
		map_name_edit.text = engine.map_name
		lbl_world.text = engine.map_name.to_upper()
		_refresh_maps_list()
	)
	_note(v, "Se guarda solo unos segundos después de cada cambio y al salir de la app.")
	var rrow := HFlowContainer.new()
	v.add_child(rrow)
	_accent_link(rrow, "QUITAR RAYAS DEL RELIEVE", func():
		lbl_save_state.text = "QUITANDO RAYAS…"
		var n: int = await engine.repair_seams()
		_world3d_dirty = true
		_places3d_dirty = true
		if input_ctrl.is_3d_mode:
			_rebuild_3d.call_deferred()
		_toast("Rayas quitadas (%s px retocados). Si no te gusta, DESHACER." % Estilo.miles(n))
		_schedule_save()
	)
	_note(v, "Para mapas creados con versiones anteriores a la 0.12.3: en algunos móviles el relieve generado salía con rayas rectas (surcos). Esto las suaviza sin tocar lo demás; los mundos nuevos ya salen bien.")
	v.add_child(Estilo.etiqueta("MIS MAPAS", 11, Estilo.GRIS))
	maps_list = VBoxContainer.new()
	maps_list.add_theme_constant_override("separation", 6)
	v.add_child(maps_list)
	v.add_child(Estilo.filete(2))
	_title(v, "Nuevo mundo", "")
	v.add_child(Estilo.etiqueta("TAMAÑO (LADO MAYOR)", 11, Estilo.GRIS))
	var size_labels: Array = []
	for s in SIZES:
		size_labels.append(("%s · %s" % [s[0], Estilo.miles(s[1])]) if int(s[1]) > 0 else "INFINITO · SIN BORDES")
	size_chips = _chips(v, size_labels, func(i: int):
		new_size_idx = i
		for k in size_chips.size():
			_paint_filter(size_chips[k], k == i)
	, 11)
	v.add_child(Estilo.etiqueta("FORMA", 11, Estilo.GRIS))
	var shape_labels: Array = []
	for s in SHAPES:
		shape_labels.append(s[0])
	shape_chips = _chips(v, shape_labels, func(i: int):
		new_shape_idx = i
		for k in shape_chips.size():
			_paint_filter(shape_chips[k], k == i)
	, 11)
	v.add_child(_caption("PARA LA NOVELA"))
	new_obra_opt = _option_button()
	new_obra_opt.item_selected.connect(func(i: int): new_obra = str(new_obra_opt.get_item_metadata(i)))
	v.add_child(new_obra_opt)
	v.add_child(Estilo.etiqueta("EMPEZAR CON", 11, Estilo.GRIS))
	mode_chips = _chips(v, ["UN CONTINENTE", "OCÉANO VACÍO", "DIBUJAR A MANO"], func(i: int):
		new_mode = i
		for k in mode_chips.size():
			_paint_filter(mode_chips[k], k == i)
	, 11)
	for k in size_chips.size():
		_paint_filter(size_chips[k], k == new_size_idx)
	for k in shape_chips.size():
		_paint_filter(shape_chips[k], k == new_shape_idx)
	for k in mode_chips.size():
		_paint_filter(mode_chips[k], k == new_mode)
	btn_create = Estilo.boton("CREAR MUNDO  →", 15, Estilo.SOBRE_ACENTO, Estilo.ACENTO, Estilo.ACENTO.darkened(0.15), 18, 14)
	btn_create.add_theme_font_override("font", Estilo.F_GROTESCA)
	btn_create.alignment = HORIZONTAL_ALIGNMENT_LEFT
	btn_create.pressed.connect(func(): _confirm("create", btn_create, "CREAR MUNDO  →", _new_world))
	v.add_child(btn_create)
	var gr := HFlowContainer.new()
	v.add_child(gr)
	_toggle_chip(gr, "LIENZO QUE CRECE", grow_canvas, func(on: bool): grow_canvas = on)
	_note(v, "Con «Lienzo que crece», si dibujas tierra junto al borde el mapa se amplía solo por ese lado.")
	_note(v, "El mapa actual queda guardado en «Mis mapas». «Gigante» y «Colosal» usan mucha memoria (hasta ~1 GB) y tardan más en guardarse.")

	v.add_child(Estilo.filete(2))
	_title(v, "Ampliar lienzo", "")
	lbl_map_px = Estilo.etiqueta("", 11, Estilo.GRIS)
	v.add_child(lbl_map_px)
	var dirs := GridContainer.new()
	dirs.columns = 2
	dirs.add_theme_constant_override("h_separation", 6)
	dirs.add_theme_constant_override("v_separation", 6)
	v.add_child(dirs)
	for d in [["N", "↑ AL NORTE"], ["S", "↓ AL SUR"], ["O", "← AL OESTE"], ["E", "→ AL ESTE"]]:
		var key: String = d[0]
		var label: String = d[1]
		var b := Estilo.boton(label, 11, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 12, 10, Estilo.TINTA, 2)
		b.custom_minimum_size = Vector2((PANEL_W - 3 - 44 - 6) / 2, 0)
		b.pressed.connect(func(): _confirm("ext_" + key, b, label, func(): engine.extend(key)))
		dirs.add_child(b)
		extend_buttons[key] = b
	_note(v, "Añade la mitad del tamaño en mar por ese lado (hasta 8.192 px). Lo pintado se queda igual; el historial se vacía.")

	v.add_child(Estilo.filete(2))
	_title(v, "Mar y sol", "")
	val_sea = _slider_row(v, "NIVEL DEL MAR", 0.30, 0.70, 0.01, sea_level, func(x: float):
		_set_sea_level(x)
		_schedule_save()
		val_sea.text = "%d %%" % int(round(x * 100.0))
	)
	val_sea.text = "%d %%" % int(round(sea_level * 100.0))
	sea_slider = val_sea.get_parent().get_parent().get_child(1)
	val_sun = _slider_row(v, "LUZ DESDE", 0.0, 359.0, 1.0, sun_azimuth, func(x: float):
		sun_azimuth = x
		_apply_sun()
	)
	sun_slider = val_sun.get_parent().get_parent().get_child(1)

	v.add_child(Estilo.filete(2))
	_title(v, "Estilo del mapa", "")
	var names: Array = []
	for t in THEMES:
		names.append(t["name"])
	theme_buttons = _chips(v, names, func(i: int): _apply_theme(i))

	v.add_child(Estilo.filete(2))
	_title(v, "Este mundo", "")
	var wb := _blocks(v, [["—", "TIERRA"], ["—", "MAR"], ["0", "TRAZOS"]])
	val_land = wb[0]
	val_water = wb[1]
	val_strokes = wb[2]

	v.add_child(Estilo.filete(2))
	_title(v, "Calidad", "")
	var q := HFlowContainer.new()
	v.add_child(q)
	btn_quality = Estilo.boton("", 11, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 12, 9)
	btn_quality.pressed.connect(func():
		await engine.set_high_precision(not engine.hdr)
		_refresh_quality()
	)
	q.add_child(btn_quality)
	lbl_quality_note = _note(v, "")
	var q2 := HFlowContainer.new()
	v.add_child(q2)
	var btn_msaa := Estilo.boton("", 11, Estilo.TINTA, Color(0, 0, 0, 0), Color(0, 0, 0, 0.08), 12, 9)
	var refresh_msaa := func():
		btn_msaa.text = "3D SIN DIENTES (MSAA 4×)" if msaa_on else "3D CON DIENTES (SIN MSAA)"
		_paint_filter(btn_msaa, msaa_on)
	btn_msaa.pressed.connect(func():
		_set_msaa(not msaa_on)
		refresh_msaa.call()
	)
	q2.add_child(btn_msaa)
	refresh_msaa.call()
	lbl_msaa_note = _note(v, "")

func _build_brush(col: Control) -> void:
	brush_box = VBoxContainer.new()
	brush_box.add_theme_constant_override("separation", 10)
	col.add_child(brush_box)
	brush_box.add_child(Estilo.filete(2))
	_title(brush_box, "Pincel", "")
	draw_mode_chips = _chips(brush_box, ["PINCEL", "CONTORNO (SE RELLENA)"], func(i: int):
		_set_draw_mode("contorno" if i == 1 else "pincel")
	, 11)
	_note(brush_box, "Contorno: dibuja el borde con el lápiz y se rellena lo de dentro (tierra, mar, lago, pantano, biomas, regiones y reinos).")
	var blocks := _blocks(brush_box, [["38", "TAMAÑO"], ["50 %", "FUERZA"]])
	val_size = blocks[0]
	val_strength = blocks[1]
	_slider_row(brush_box, "TAMAÑO", 6.0, 160.0, 1.0, brush_radius, func(x: float):
		brush_radius = x
		val_size.text = str(int(x))
	)
	var str_val: Label = _slider_row(brush_box, "FUERZA", 0.0, 1.0, 0.01, brush_strength, func(x: float):
		brush_strength = x
		val_strength.text = "%d %%" % int(round(x * 100.0))
	)
	strength_row = str_val.get_parent().get_parent() as Control
	val_steps = _slider_row(brush_box, "PARED VERTICAL", 1.0, 10.0, 1.0, cliff_steps, func(x: float):
		cliff_steps = x
		val_steps.text = str(int(x))
	)
	steps_row = val_steps.get_parent().get_parent() as Control
	val_steps.text = str(int(cliff_steps))
	val_size.text = str(int(brush_radius))
	val_strength.text = "%d %%" % int(round(brush_strength * 100.0))

## El título de un bloque (TituloC): grotesca a la izquierda y, a la derecha, su dato en el acento.
func _title(parent: Control, text: String, right: String) -> Label:
	var row := HBoxContainer.new()
	parent.add_child(row)
	var t := Estilo.etiqueta(text, 26, Estilo.TINTA, Estilo.F_GROTESCA)
	t.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(t)
	var r := Estilo.etiqueta(right, 12, Estilo.ACENTO)
	r.size_flags_vertical = Control.SIZE_SHRINK_END
	row.add_child(r)
	return r

## Bloques de cifras (BloquesCifras): borde de 3 px; el primero en el acento, el tercero en el azul de la familia.
func _blocks(parent: Control, items: Array) -> Array[Label]:
	var outer := PanelContainer.new()
	outer.add_theme_stylebox_override("panel", Estilo.caja(Estilo.TINTA, Estilo.TINTA, 3, 3, 3))
	parent.add_child(outer)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 3)
	outer.add_child(row)
	var values: Array[Label] = []
	for i in items.size():
		var bg: Color = Estilo.PAPEL
		var fg: Color = Estilo.TINTA
		if i == 0:
			bg = Estilo.ACENTO
			fg = Estilo.SOBRE_ACENTO
		elif i == 2:
			bg = Estilo.BLOQUE2
			fg = Color.WHITE
		var cell := PanelContainer.new()
		cell.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		cell.add_theme_stylebox_override("panel", Estilo.caja(bg, Color(0, 0, 0, 0), 0, 10, 10))
		row.add_child(cell)
		var v := VBoxContainer.new()
		v.add_theme_constant_override("separation", 0)
		cell.add_child(v)
		var item: Array = items[i]
		var val := Estilo.etiqueta(str(item[0]), 28, fg, Estilo.F_GROTESCA)
		v.add_child(val)
		v.add_child(Estilo.etiqueta(str(item[1]), 10, fg))
		values.append(val)
	return values

## Etiqueta, valor a la derecha y deslizador debajo. Devuelve la etiqueta del valor.
func _slider_row(parent: Control, label: String, min_v: float, max_v: float, step: float, value: float, on_change: Callable) -> Label:
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 0)
	parent.add_child(box)
	var head := HBoxContainer.new()
	box.add_child(head)
	var l := Estilo.etiqueta(label, 11, Estilo.GRIS)
	l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	head.add_child(l)
	var val := Estilo.etiqueta("", 11, Estilo.TINTA)
	head.add_child(val)
	var s := Estilo.deslizador(min_v, max_v, step, value)
	s.value_changed.connect(on_change)
	box.add_child(s)
	return val
