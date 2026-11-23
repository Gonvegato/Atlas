class_name PaintLayer
extends Node

## Una capa del mundo que se pinta en la GPU: un SubViewport que no se borra (CLEAR_MODE_NEVER) donde cada
## pincelada es un ColorRect con su shader. Guarda en la CPU una copia de un canal (el espejo) que se pone al
## día leyendo SOLO la zona tocada por cada trazo, con un SubViewport pequeño de recorte: así el historial
## guarda regiones y no el mapa entero, y los pinceles que leen el relieve (suavizar, meseta…) tienen su textura.

## Se pidió leer la capa (trazo, mundo nuevo) y ya está en el espejo.
signal mirror_updated(rect: Rect2i)

var size: Vector2i = Vector2i(1024, 1024)
var hdr: bool = false
## Formato del espejo: FORMAT_RH (16 bits flotante) con hdr, FORMAT_R8 sin él.
var mirror_format: int = Image.FORMAT_R8

var viewport: SubViewport
var mirror: Image
var _grab_vp: SubViewport
var _grab_rect: TextureRect
var _restore_rect: TextureRect
var _init_rect: ColorRect
var _pool: Array[ColorRect] = []
var _pool_used: int = 0
var _snapshot: ImageTexture
var _snapshot_dirty: bool = true
var _restore_mat: ShaderMaterial

const POOL_SIZE: int = 12

func setup(layer_size: Vector2i, use_hdr: bool) -> void:
	size = layer_size
	hdr = use_hdr
	mirror_format = Image.FORMAT_RH if hdr else Image.FORMAT_R8
	if viewport != null:
		viewport.queue_free()
		_grab_vp.queue_free()
	_pool.clear()
	_pool_used = 0

	viewport = SubViewport.new()
	viewport.size = size
	viewport.disable_3d = true
	viewport.use_hdr_2d = hdr
	viewport.transparent_bg = false
	viewport.render_target_clear_mode = SubViewport.CLEAR_MODE_ALWAYS
	viewport.render_target_update_mode = SubViewport.UPDATE_DISABLED
	viewport.canvas_item_default_texture_filter = Viewport.DEFAULT_CANVAS_ITEM_TEXTURE_FILTER_NEAREST
	add_child(viewport)

	_restore_mat = ShaderMaterial.new()
	_restore_mat.shader = preload("res://shaders/layer_restore.gdshader")

	_restore_rect = TextureRect.new()
	_restore_rect.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	_restore_rect.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
	_restore_rect.material = _restore_mat
	_restore_rect.visible = false
	viewport.add_child(_restore_rect)

	_init_rect = ColorRect.new()
	_init_rect.position = Vector2.ZERO
	_init_rect.size = Vector2(size)
	_init_rect.visible = false
	viewport.add_child(_init_rect)

	for i in POOL_SIZE:
		var r := ColorRect.new()
		r.visible = false
		viewport.add_child(r)
		_pool.append(r)

	# El recorte: copia 1:1 (filtro nearest) de una zona de la capa a un viewport pequeño que sí se lee.
	_grab_vp = SubViewport.new()
	_grab_vp.size = Vector2i(16, 16)
	_grab_vp.disable_3d = true
	_grab_vp.use_hdr_2d = hdr
	_grab_vp.render_target_update_mode = SubViewport.UPDATE_DISABLED
	add_child(_grab_vp)
	_grab_rect = TextureRect.new()
	_grab_rect.texture = viewport.get_texture()
	_grab_rect.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
	_grab_rect.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	_grab_rect.size = Vector2(size)
	_grab_vp.add_child(_grab_rect)

	mirror = Image.create(size.x, size.y, false, mirror_format)
	_snapshot = null
	_snapshot_dirty = true

	if not RenderingServer.frame_post_draw.is_connected(_on_frame_post_draw):
		RenderingServer.frame_post_draw.connect(_on_frame_post_draw)

func get_texture() -> Texture2D:
	return viewport.get_texture()

## Pinta la capa entera con un material (generador) o un color. Es asíncrono: espera a que esté dibujada.
func fill(material: Material, color: Color = Color.BLACK) -> void:
	_hide_dabs()
	_restore_rect.visible = false
	_init_rect.material = material
	_init_rect.color = color
	_init_rect.size = Vector2(size)
	_init_rect.visible = true
	viewport.render_target_clear_mode = SubViewport.CLEAR_MODE_ALWAYS
	viewport.render_target_update_mode = SubViewport.UPDATE_ONCE
	await RenderingServer.frame_post_draw
	_init_rect.visible = false
	viewport.render_target_clear_mode = SubViewport.CLEAR_MODE_NEVER

## Carga una imagen completa (del tamaño de la capa) en la GPU y en el espejo.
func load_image(img: Image) -> void:
	var copy: Image = img.duplicate()
	if copy.get_format() != mirror_format:
		copy.convert(mirror_format)
	mirror = copy
	_snapshot_dirty = true
	# Un lienzo nuevo tiene que borrarse una vez antes de escribir en él: si no, en GL Compatibility lo que se pinta
	# después sale con la conversión sRGB aplicada (un id 2 se leía como 26). Comprobado al ampliar el lienzo.
	await fill(null, Color.BLACK)
	await restore_region(Rect2i(Vector2i.ZERO, size), copy)

## Una pincelada: un ColorRect con [material] en [rect] (píxeles de la capa). Varias en el mismo fotograma
## se dibujan todas (antes solo se veía la última).
func dab(material: Material, rect: Rect2) -> void:
	if _pool_used >= _pool.size():
		var extra := ColorRect.new()
		viewport.add_child(extra)
		_pool.append(extra)
	var r: ColorRect = _pool[_pool_used]
	_pool_used += 1
	r.material = material
	r.position = rect.position
	r.size = rect.size
	r.visible = true
	viewport.render_target_update_mode = SubViewport.UPDATE_ONCE

func _hide_dabs() -> void:
	for i in _pool_used:
		_pool[i].visible = false
	_pool_used = 0

func _on_frame_post_draw() -> void:
	# Lo pintado ya está en la textura (CLEAR_MODE_NEVER): se esconden para que no se repitan en el siguiente render.
	if _pool_used > 0:
		_hide_dabs()

## Escribe [img] (del formato del espejo) en [rect] de la capa y del espejo.
func restore_region(rect: Rect2i, img: Image) -> void:
	_hide_dabs()
	if img.get_format() != mirror_format:
		img = img.duplicate()
		img.convert(mirror_format)
	var tex := ImageTexture.create_from_image(img)
	_restore_rect.texture = tex
	_restore_rect.position = Vector2(rect.position)
	_restore_rect.size = Vector2(rect.size)
	_restore_rect.visible = true
	viewport.render_target_clear_mode = SubViewport.CLEAR_MODE_NEVER
	viewport.render_target_update_mode = SubViewport.UPDATE_ONCE
	if mirror != img:
		mirror.blit_rect(img, Rect2i(Vector2i.ZERO, rect.size), rect.position)
	_snapshot_dirty = true
	await RenderingServer.frame_post_draw
	_restore_rect.visible = false
	_restore_rect.texture = null
	mirror_updated.emit(rect)

## Lee [rect] de la GPU (tras dibujarse lo pendiente), lo devuelve en el formato del espejo y pone el espejo al día.
func read_region(rect: Rect2i) -> Image:
	rect = rect.intersection(Rect2i(Vector2i.ZERO, size))
	if rect.size.x <= 0 or rect.size.y <= 0:
		return null
	# Primero que se dibuje lo pendiente en la capa; luego el recorte.
	await RenderingServer.frame_post_draw
	_grab_vp.size = rect.size
	_grab_rect.position = -Vector2(rect.position)
	_grab_vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	await RenderingServer.frame_post_draw
	var img: Image = _grab_vp.get_texture().get_image()
	if img == null:
		return null
	img.convert(mirror_format)
	mirror.blit_rect(img, Rect2i(Vector2i.ZERO, rect.size), rect.position)
	_snapshot_dirty = true
	mirror_updated.emit(rect)
	return img

## Dibuja un nodo (Polygon2D…) una vez sobre la capa y lo quita. Asíncrono: espera a que esté dibujado.
func draw_once(node: CanvasItem) -> void:
	_hide_dabs()
	viewport.add_child(node)
	viewport.render_target_clear_mode = SubViewport.CLEAR_MODE_NEVER
	viewport.render_target_update_mode = SubViewport.UPDATE_ONCE
	await RenderingServer.frame_post_draw
	node.queue_free()

## Pasa toda la capa por un material (que lee snapshot_texture()) y la vuelve a leer: para cambios globales
## como borrar un reino entero. Devuelve la imagen nueva completa.
func apply_full(material: Material) -> Image:
	_hide_dabs()
	_init_rect.material = material
	_init_rect.size = Vector2(size)
	_init_rect.visible = true
	viewport.render_target_clear_mode = SubViewport.CLEAR_MODE_NEVER
	viewport.render_target_update_mode = SubViewport.UPDATE_ONCE
	await RenderingServer.frame_post_draw
	_init_rect.visible = false
	return await read_region(Rect2i(Vector2i.ZERO, size))

func crop_mirror(rect: Rect2i) -> Image:
	return mirror.get_region(rect)

## La capa como textura para leerla desde un pincel (el estado al empezar el trazo).
func snapshot_texture() -> ImageTexture:
	if _snapshot == null or _snapshot.get_size() != Vector2(size):
		_snapshot = ImageTexture.create_from_image(mirror)
	elif _snapshot_dirty:
		_snapshot.update(mirror)
	_snapshot_dirty = false
	return _snapshot

func sample_mirror(px: Vector2i) -> float:
	var p := Vector2i(clampi(px.x, 0, size.x - 1), clampi(px.y, 0, size.y - 1))
	return mirror.get_pixelv(p).r
