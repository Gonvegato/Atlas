extends Node

signal stroke_started(screen_pos: Vector2, pressure: float)
signal stroke_moved(screen_pos: Vector2, pressure: float)
signal stroke_ended()
signal pan_changed(delta: Vector2)
signal zoom_changed(factor: float, center: Vector2)
signal orbit_changed(delta: Vector2)

@export var velocity_pressure_enabled: bool = true
@export var palm_buffer_ms: int = 40

var is_3d_mode: bool = false
var touches: Dictionary = {}
var pending_stroke: bool = false
var stroke_active: bool = false
var pending_start_ms: int = 0
var pending_pos: Vector2 = Vector2.ZERO
var pending_down_pos: Vector2 = Vector2.ZERO
var last_pinch_dist: float = 0.0
var mouse_painting: bool = false
var mouse_panning: bool = false

func _process(_delta: float) -> void:
	if pending_stroke and touches.size() == 1:
		if Time.get_ticks_msec() - pending_start_ms >= palm_buffer_ms:
			pending_stroke = false
			stroke_active = true
			# Empieza donde se apoyó el lápiz y recupera lo recorrido durante el búfer antipalma.
			stroke_started.emit(pending_down_pos, 1.0)
			if pending_pos.distance_to(pending_down_pos) > 0.5:
				stroke_moved.emit(pending_pos, 1.0)

## Las posiciones llegan en coordenadas locales del Workspace (las de gui_input) y salen igual.
func handle_input(event: InputEvent) -> void:
	# Godot emula ratón desde el táctil (input_devices/pointing/emulate_mouse_from_touch, activo por defecto):
	# esos eventos llegan con device == DEVICE_ID_EMULATION y, si se atendieran, pintarían sin búfer antipalma
	# y seguirían pintando durante el pellizco. El táctil ya se trata con ScreenTouch/ScreenDrag.
	if event.device == InputEvent.DEVICE_ID_EMULATION and (event is InputEventMouseButton or event is InputEventMouseMotion):
		return
	if event is InputEventScreenTouch:
		_handle_touch(event)
	elif event is InputEventScreenDrag:
		_handle_drag(event)
	elif event is InputEventMouseButton:
		_handle_mouse_button(event)
	elif event is InputEventMouseMotion:
		_handle_mouse_motion(event)

func _handle_touch(e: InputEventScreenTouch) -> void:
	if e.pressed:
		touches[e.index] = e.position
		if touches.size() == 1 and not is_3d_mode:
			pending_stroke = true
			pending_start_ms = Time.get_ticks_msec()
			pending_pos = e.position
			pending_down_pos = e.position
		else:
			pending_stroke = false
			if stroke_active:
				stroke_active = false
				stroke_ended.emit()
			if touches.size() == 2:
				var pts: Array = touches.values()
				var p0: Vector2 = pts[0]
				var p1: Vector2 = pts[1]
				last_pinch_dist = p0.distance_to(p1)
	else:
		touches.erase(e.index)
		if touches.is_empty():
			pending_stroke = false
			if stroke_active:
				stroke_active = false
				stroke_ended.emit()

func _handle_drag(e: InputEventScreenDrag) -> void:
	touches[e.index] = e.position
	if is_3d_mode:
		if touches.size() == 1:
			orbit_changed.emit(e.relative)
		elif touches.size() == 2:
			_process_pinch()
		return

	if touches.size() == 1:
		if pending_stroke:
			pending_pos = e.position
		elif stroke_active:
			var speed := e.velocity.length()
			var pressure := 1.0
			if velocity_pressure_enabled:
				pressure = clampf(1.0 - (speed / 1800.0) * 0.65, 0.30, 1.0)
			stroke_moved.emit(e.position, pressure)
	elif touches.size() == 2:
		pan_changed.emit(e.relative * 0.5)
		_process_pinch()

func _process_pinch() -> void:
	var pts: Array = touches.values()
	var p0: Vector2 = pts[0]
	var p1: Vector2 = pts[1]
	var dist: float = p0.distance_to(p1)
	if last_pinch_dist > 10.0:
		var factor: float = dist / last_pinch_dist
		var center: Vector2 = (p0 + p1) * 0.5
		zoom_changed.emit(factor, center)
	last_pinch_dist = dist

func _handle_mouse_button(e: InputEventMouseButton) -> void:
	if e.button_index == MOUSE_BUTTON_WHEEL_UP and e.pressed:
		zoom_changed.emit(1.12, e.position)
	elif e.button_index == MOUSE_BUTTON_WHEEL_DOWN and e.pressed:
		zoom_changed.emit(0.89, e.position)
	elif e.button_index == MOUSE_BUTTON_LEFT:
		if is_3d_mode:
			mouse_panning = e.pressed
		else:
			mouse_painting = e.pressed
			if e.pressed:
				stroke_started.emit(e.position, 1.0)
			else:
				stroke_ended.emit()
	elif e.button_index == MOUSE_BUTTON_RIGHT or e.button_index == MOUSE_BUTTON_MIDDLE:
		mouse_panning = e.pressed

func _handle_mouse_motion(e: InputEventMouseMotion) -> void:
	if is_3d_mode and mouse_panning:
		orbit_changed.emit(e.relative)
	elif mouse_panning:
		pan_changed.emit(e.relative)
	elif mouse_painting:
		var speed := e.velocity.length()
		var pressure := clampf(1.0 - (speed / 1800.0) * 0.65, 0.30, 1.0) if velocity_pressure_enabled else 1.0
		stroke_moved.emit(e.position, pressure)
