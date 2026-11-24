class_name FamiliaNube
extends Node

## La cuenta de la familia (la de Polaris, Nova, Lyra…), por su API REST, con el contrato de Familia-Revista
## (nube/ESQUEMA.md):
##  - Entrar: Firebase Authentication con usuario y contraseña (el usuario se convierte en <usuario>@ronron.example.com;
##    la clave web del proyecto se lee de /config/webKey). Se guarda solo el pase que se renueva, cifrado en el móvil.
##  - Los mapas: una ficha por mapa en /users/<uid>/familia/atlas/<mapa>, campo a campo con su hora en m/<campo>, la hora
##    del servidor en u y el sello de la colección en familia_sello/atlas. Borrar es poner b.
##  - El archivo del mapa (un .atlas: un zip con sus capas) va a tu Google Drive, en <carpeta de la familia>/atlas/, con
##    las claves de /claves/<uid> (gdrive_*), y la ficha apunta a él con nube = {gd, n, s} como los libros de Lyra.
##  - La miniatura va a familia_img/<hash> y la ficha la apunta con portada = hash.
##  - Las novelas son las obras de Nova (familia/obras/<obra>, título en t): la ficha del mapa lleva obra = <obra>.

signal sesion_cambiada(dentro: bool)

const COLECCION := "atlas"
const ARCHIVO_SESION := "user://cuenta.dat"
const CARPETA_DEFECTO := "Familia"
const MIME_CARPETA := "application/vnd.google-apps.folder"

# Dónde está todo (en las pruebas, el emulador de Firebase y un Drive de mentira).
var base_datos: String = "https://app-ratear-default-rtdb.europe-west1.firebasedatabase.app"
var base_auth: String = "https://identitytoolkit.googleapis.com"
var base_token: String = "https://securetoken.googleapis.com"
var base_oauth: String = "https://oauth2.googleapis.com/token"
var base_drive: String = "https://www.googleapis.com"
var extra_consulta: String = ""
var dominio: String = "ronron.example.com"

var uid: String = ""
var usuario: String = ""
var _refresco: String = ""
var _pase: String = ""
var _caduca: int = 0
var _clave_web: String = ""
var _desfase: int = 0
var _drive_pase: String = ""
var _drive_caduca: int = 0
var _drive_raiz: String = ""
var _drive_atlas: String = ""

var dentro: bool:
	get:
		return uid != "" and _refresco != ""

func _ready() -> void:
	_carga()

# ================================================================ HTTP

## [código, cuerpo (bytes), cabeceras]. Código 0 = sin red.
func _http(metodo: int, url: String, cabeceras: PackedStringArray = PackedStringArray(), cuerpo: PackedByteArray = PackedByteArray()) -> Array:
	var h := HTTPRequest.new()
	h.timeout = 120.0
	h.use_threads = true
	add_child(h)
	var err: int = h.request_raw(url, cabeceras, metodo, cuerpo)
	if err != OK:
		h.queue_free()
		return [0, PackedByteArray(), PackedStringArray()]
	var r: Array = await h.request_completed
	h.queue_free()
	return [int(r[1]), r[3], r[2]]

func _json(metodo: int, url: String, datos: Variant = null, cabeceras: PackedStringArray = PackedStringArray()) -> Array:
	var cuerpo := PackedByteArray()
	var cab := cabeceras.duplicate()
	if datos != null:
		cuerpo = JSON.stringify(datos).to_utf8_buffer()
		cab.append("Content-Type: application/json")
	var r: Array = await _http(metodo, url, cab, cuerpo)
	var txt: String = (r[1] as PackedByteArray).get_string_from_utf8()
	var v: Variant = JSON.parse_string(txt) if txt != "" else null
	return [r[0], v, txt]

func _url(ruta: String, consulta: String = "", con_pase: bool = false) -> String:
	var p: PackedStringArray = []
	if extra_consulta != "":
		p.append(extra_consulta)
	if consulta != "":
		p.append(consulta)
	if con_pase:
		p.append("auth=" + _pase.uri_encode())
	return base_datos.trim_suffix("/") + ruta + ".json" + ("" if p.is_empty() else "?" + "&".join(p))

func _ahora() -> int:
	return int(Time.get_unix_time_from_system() * 1000.0) + _desfase

# ================================================================ sesión

func _carga() -> void:
	if not FileAccess.file_exists(ARCHIVO_SESION):
		return
	var f := FileAccess.open_encrypted_with_pass(ARCHIVO_SESION, FileAccess.READ, _llave())
	if f == null:
		return
	var m: Variant = JSON.parse_string(f.get_as_text())
	f.close()
	if typeof(m) == TYPE_DICTIONARY:
		uid = str(m.get("uid", ""))
		usuario = str(m.get("usuario", ""))
		_refresco = str(m.get("refresco", ""))
		_clave_web = str(m.get("claveWeb", ""))

func _guarda() -> void:
	var f := FileAccess.open_encrypted_with_pass(ARCHIVO_SESION, FileAccess.WRITE, _llave())
	if f == null:
		return
	f.store_string(JSON.stringify({"uid": uid, "usuario": usuario, "refresco": _refresco, "claveWeb": _clave_web}))
	f.close()

## Cifrado con algo de este dispositivo: el archivo copiado a otro móvil no sirve.
func _llave() -> String:
	return "atlas-familia-" + OS.get_unique_id()

func _clave() -> String:
	if _clave_web != "":
		return _clave_web
	var r: Array = await _json(HTTPClient.METHOD_GET, _url("/config/webKey"))
	if r[0] == 200 and typeof(r[1]) == TYPE_STRING and str(r[1]) != "":
		_clave_web = str(r[1])
	return _clave_web

## Entra con el usuario y la contraseña de la familia. Devuelve "" si entra, o el motivo.
func entrar(usuario_escrito: String, contrasena: String) -> String:
	var u: String = usuario_escrito.strip_edges().to_lower()
	if u == "" or contrasena == "":
		return "Escribe tu usuario y tu contraseña."
	var k: String = await _clave()
	if k == "":
		return "No hay conexión con la cuenta."
	var correo: String = u if u.contains("@") else u + "@" + dominio
	var r: Array = await _json(HTTPClient.METHOD_POST, base_auth.trim_suffix("/") + "/v1/accounts:signInWithPassword?key=" + k,
		{"email": correo, "password": contrasena, "returnSecureToken": true})
	if r[0] == 0:
		return "No hay conexión con la cuenta."
	if r[0] != 200 or typeof(r[1]) != TYPE_DICTIONARY:
		var msg: String = ""
		if typeof(r[1]) == TYPE_DICTIONARY:
			msg = str((r[1] as Dictionary).get("error", {}).get("message", ""))
		if msg.begins_with("EMAIL_NOT_FOUND") or msg.begins_with("INVALID_PASSWORD") or msg.begins_with("INVALID_LOGIN_CREDENTIALS"):
			return "Usuario o contraseña incorrectos."
		if msg.begins_with("TOO_MANY_ATTEMPTS"):
			return "Demasiados intentos. Espera un poco y vuelve a probar."
		return "La cuenta no ha dejado entrar (%s)." % msg
	var m: Dictionary = r[1]
	uid = str(m.get("localId", ""))
	usuario = u
	_pase = str(m.get("idToken", ""))
	_refresco = str(m.get("refreshToken", ""))
	_caduca = Time.get_ticks_msec() + int(m.get("expiresIn", "3600")) * 1000 - 60000
	_guarda()
	sesion_cambiada.emit(true)
	return ""

func salir() -> void:
	uid = ""
	usuario = ""
	_refresco = ""
	_pase = ""
	_drive_pase = ""
	_drive_raiz = ""
	_drive_atlas = ""
	if FileAccess.file_exists(ARCHIVO_SESION):
		DirAccess.remove_absolute(ARCHIVO_SESION)
	sesion_cambiada.emit(false)

## El pase de la cuenta, renovado si hace falta. "" = sin sesión o sin red.
func pase() -> String:
	if not dentro:
		return ""
	if _pase != "" and Time.get_ticks_msec() < _caduca:
		return _pase
	var k: String = await _clave()
	var cuerpo: PackedByteArray = ("grant_type=refresh_token&refresh_token=" + _refresco.uri_encode()).to_utf8_buffer()
	var r: Array = await _http(HTTPClient.METHOD_POST, base_token.trim_suffix("/") + "/v1/token?key=" + k,
		PackedStringArray(["Content-Type: application/x-www-form-urlencoded"]), cuerpo)
	if r[0] != 200:
		if r[0] == 400:
			# El pase ya no vale (contraseña cambiada, cuenta borrada): fuera.
			salir()
		return ""
	var m: Variant = JSON.parse_string((r[1] as PackedByteArray).get_string_from_utf8())
	if typeof(m) != TYPE_DICTIONARY:
		return ""
	_pase = str(m.get("id_token", ""))
	_refresco = str(m.get("refresh_token", _refresco))
	_caduca = Time.get_ticks_msec() + int(m.get("expires_in", "3600")) * 1000 - 60000
	_guarda()
	return _pase

# ================================================================ base de datos

func _lee(ruta: String, consulta: String = "") -> Variant:
	if await pase() == "":
		return null
	var r: Array = await _json(HTTPClient.METHOD_GET, _url(ruta, consulta, true))
	return r[1] if r[0] == 200 else null

## Escribe campos de una ficha con el contrato de la familia (un PATCH multi-ruta). Devuelve "" o el error.
func _escribe(coleccion: String, ref: String, campos: Dictionary) -> String:
	if await pase() == "":
		return "Sin sesión en la cuenta."
	var m: int = _ahora()
	var cuerpo: Dictionary = {}
	var srv := {".sv": "timestamp"}
	for c in campos.keys():
		cuerpo["familia/%s/%s/%s" % [coleccion, ref, c]] = campos[c]
		cuerpo["familia/%s/%s/m/%s" % [coleccion, ref, c]] = m
	cuerpo["familia/%s/%s/u" % [coleccion, ref]] = srv
	cuerpo["familia_sello/" + coleccion] = srv
	var r: Array = await _json(HTTPClient.METHOD_PATCH, _url("/users/" + uid, "", true), cuerpo)
	if r[0] == 200:
		# Ajusta el reloj con la hora del servidor que se acaba de poner.
		if typeof(r[1]) == TYPE_DICTIONARY:
			var u: Variant = (r[1] as Dictionary).get("familia/%s/%s/u" % [coleccion, ref])
			if typeof(u) in [TYPE_INT, TYPE_FLOAT]:
				_desfase += int(u) - _ahora()
		return ""
	return "La cuenta rechazó el cambio (%d): %s" % [r[0], str(r[2]).left(200)]

## Las novelas de Nova: [{id, t}], sin las borradas, por título.
func obras() -> Array:
	var v: Variant = await _lee("/users/%s/familia/obras" % uid)
	var out: Array = []
	if typeof(v) == TYPE_DICTIONARY:
		for k in (v as Dictionary).keys():
			var o: Variant = v[k]
			if typeof(o) != TYPE_DICTIONARY or int(o.get("b", 0)) > 0:
				continue
			out.append({"id": str(k), "t": str(o.get("t", k)), "estado": str(o.get("estado", ""))})
	out.sort_custom(func(a, b): return str(a["t"]).naturalnocasecmp_to(str(b["t"])) < 0)
	return out

## Los mapas de la cuenta: [{id, t, obra, ul, w, h, nube}], sin los borrados, el más reciente primero.
func mapas() -> Array:
	var v: Variant = await _lee("/users/%s/familia/%s" % [uid, COLECCION])
	var out: Array = []
	if typeof(v) == TYPE_DICTIONARY:
		for k in (v as Dictionary).keys():
			var o: Variant = v[k]
			if typeof(o) != TYPE_DICTIONARY or int(o.get("b", 0)) > 0:
				continue
			var d: Dictionary = o
			out.append({"id": str(k), "t": str(d.get("t", k)), "obra": str(d.get("obra", "")), "obras": str(d.get("obras", "")), "ul": int(d.get("ul", 0)),
				"w": int(d.get("w", 0)), "h": int(d.get("h", 0)), "nube": d.get("nube", {}), "lam": d.get("lam", {})})
	out.sort_custom(func(a, b): return int(a["ul"]) > int(b["ul"]))
	return out

func borra_mapa(id: String) -> String:
	return await _escribe(COLECCION, id, {"b": _ahora()})

# ================================================================ Google Drive

func _drive_token() -> String:
	if _drive_pase != "" and Time.get_ticks_msec() < _drive_caduca:
		return _drive_pase
	var cl: Variant = await _lee("/claves/" + uid)
	if typeof(cl) != TYPE_DICTIONARY:
		return ""
	var cid: String = str(cl.get("gdrive_client_id", ""))
	var sec: String = str(cl.get("gdrive_client_secret", ""))
	var rt: String = str(cl.get("gdrive_refresh_token", ""))
	if cid == "" or rt == "":
		return ""
	var cuerpo: String = "client_id=%s&client_secret=%s&refresh_token=%s&grant_type=refresh_token" % [cid.uri_encode(), sec.uri_encode(), rt.uri_encode()]
	var r: Array = await _http(HTTPClient.METHOD_POST, base_oauth, PackedStringArray(["Content-Type: application/x-www-form-urlencoded"]), cuerpo.to_utf8_buffer())
	if r[0] != 200:
		return ""
	var m: Variant = JSON.parse_string((r[1] as PackedByteArray).get_string_from_utf8())
	if typeof(m) != TYPE_DICTIONARY:
		return ""
	_drive_pase = str(m.get("access_token", ""))
	_drive_caduca = Time.get_ticks_msec() + int(m.get("expires_in", 3600)) * 1000 - 60000
	if _drive_raiz == "":
		_drive_raiz = await _raiz(str(cl.get("gdrive_carpeta", "")))
	return _drive_pase

func _dcab() -> PackedStringArray:
	return PackedStringArray(["Authorization: Bearer " + _drive_pase])

static func _esc_q(s: String) -> String:
	return s.replace("\\", "\\\\").replace("'", "\\'")

func _busca(padre: String, nombre: String, carpeta: bool) -> String:
	var q: String = "'%s' in parents and name = '%s' and trashed = false and mimeType %s '%s'" % [_esc_q(padre), _esc_q(nombre), "=" if carpeta else "!=", MIME_CARPETA]
	var r: Array = await _json(HTTPClient.METHOD_GET, base_drive + "/drive/v3/files?q=" + q.uri_encode() + "&fields=" + "files(id,name)".uri_encode() + "&pageSize=1", null, _dcab())
	if r[0] == 200 and typeof(r[1]) == TYPE_DICTIONARY:
		var fs: Array = r[1].get("files", [])
		if not fs.is_empty():
			return str(fs[0]["id"])
	return ""

func _crea_carpeta(padre: String, nombre: String) -> String:
	var r: Array = await _json(HTTPClient.METHOD_POST, base_drive + "/drive/v3/files?fields=id", {"name": nombre, "mimeType": MIME_CARPETA, "parents": [padre]}, _dcab())
	return str(r[1].get("id", "")) if r[0] == 200 and typeof(r[1]) == TYPE_DICTIONARY else ""

## La carpeta de la familia: la de gdrive_carpeta (id o enlace) o «Familia» en «Mi unidad» (como las hermanas).
func _raiz(config: String) -> String:
	var c: String = config.strip_edges()
	if c.contains("/folders/"):
		c = c.get_slice("/folders/", 1).get_slice("?", 0).get_slice("/", 0)
	if c != "":
		return c
	var id: String = await _busca("root", CARPETA_DEFECTO, true)
	return id if id != "" else await _crea_carpeta("root", CARPETA_DEFECTO)

func _carpeta_atlas() -> String:
	if _drive_atlas != "":
		return _drive_atlas
	var id: String = await _busca(_drive_raiz, "atlas", true)
	if id == "":
		id = await _crea_carpeta(_drive_raiz, "atlas")
	_drive_atlas = id
	return id

## Sube (o reemplaza) un archivo en la carpeta atlas. Devuelve su id ("" si falla).
func _sube(nombre: String, datos: PackedByteArray) -> String:
	var dir: String = await _carpeta_atlas()
	if dir == "":
		return ""
	var existente: String = await _busca(dir, nombre, false)
	var meta: Dictionary = {"name": nombre} if existente != "" else {"name": nombre, "parents": [dir]}
	var url: String = base_drive + ("/upload/drive/v3/files/%s?uploadType=resumable&fields=id" % existente if existente != "" else "/upload/drive/v3/files?uploadType=resumable&fields=id")
	var cab := _dcab()
	cab.append("X-Upload-Content-Type: application/octet-stream")
	cab.append("X-Upload-Content-Length: %d" % datos.size())
	cab.append("Content-Type: application/json; charset=UTF-8")
	var r: Array = await _http(HTTPClient.METHOD_PATCH if existente != "" else HTTPClient.METHOD_POST, url, cab, JSON.stringify(meta).to_utf8_buffer())
	if r[0] != 200:
		return ""
	# La dirección de la subida viene en la cabecera Location.
	var loc: String = ""
	for hline in (r[2] as PackedStringArray):
		if hline.to_lower().begins_with("location:"):
			loc = hline.substr(9).strip_edges()
	if loc == "":
		return ""
	var up: Array = await _http(HTTPClient.METHOD_PUT, loc, PackedStringArray(["Content-Type: application/octet-stream"]), datos)
	if up[0] != 200 and up[0] != 201:
		return ""
	var m: Variant = JSON.parse_string((up[1] as PackedByteArray).get_string_from_utf8())
	return str(m.get("id", existente)) if typeof(m) == TYPE_DICTIONARY else existente

func _baja(file_id: String) -> PackedByteArray:
	if await _drive_token() == "":
		return PackedByteArray()
	var r: Array = await _http(HTTPClient.METHOD_GET, base_drive + "/drive/v3/files/%s?alt=media&acknowledgeAbuse=true" % file_id.uri_encode(), _dcab())
	return r[1] if r[0] == 200 else PackedByteArray()

# ================================================================ mapas

## Empaqueta la carpeta de un mapa (user://mapas/<id>) en un .atlas (zip).
static func empaqueta(dir: String) -> PackedByteArray:
	var tmp: String = "user://subida.atlas"
	var z := ZIPPacker.new()
	if z.open(tmp) != OK:
		return PackedByteArray()
	for f in DirAccess.get_files_at(dir):
		if f.ends_with(".tmp"):
			continue
		z.start_file(f)
		z.write_file(FileAccess.get_file_as_bytes(dir + "/" + f))
		z.close_file()
	z.close()
	var out: PackedByteArray = FileAccess.get_file_as_bytes(tmp)
	DirAccess.remove_absolute(tmp)
	return out

static func desempaqueta(datos: PackedByteArray, dir: String) -> bool:
	var tmp: String = "user://bajada.atlas"
	var f := FileAccess.open(tmp, FileAccess.WRITE)
	if f == null:
		return false
	f.store_buffer(datos)
	f.close()
	var z := ZIPReader.new()
	if z.open(tmp) != OK:
		return false
	DirAccess.make_dir_recursive_absolute(dir)
	var ok: bool = false
	for n in z.get_files():
		var nombre: String = n.get_file()
		if nombre == "" or nombre.contains(".."):
			continue
		var w := FileAccess.open(dir + "/" + nombre, FileAccess.WRITE)
		if w != null:
			w.store_buffer(z.read_file(n))
			w.close()
			ok = ok or nombre == "mundo.bin"
	z.close()
	DirAccess.remove_absolute(tmp)
	return ok

## Sube un mapa guardado: el archivo a Drive, la miniatura a familia_img y la ficha a familia/atlas. "" o el error.
## [lamina]: la imagen del mapa para el visor de los escritos (WebP); se sube a Drive al lado del mapa y su
## referencia va en el campo «lam» ({gd, n, s, w, h}).
func sube_mapa(id: String, ficha: Dictionary, lamina: PackedByteArray = PackedByteArray(), lam_size: Vector2i = Vector2i.ZERO) -> String:
	if not dentro:
		return "Sin sesión en la cuenta."
	if await _drive_token() == "":
		return "Tu Google Drive no está configurado en la cuenta (se pone una vez en Polaris)."
	var dir: String = "user://mapas/" + id
	var datos: PackedByteArray = empaqueta(dir)
	if datos.is_empty():
		return "No se pudo empaquetar el mapa."
	var nombre: String = id + ".atlas"
	var gd: String = await _sube(nombre, datos)
	if gd == "":
		return "No se pudo subir el mapa a Google Drive."
	var campos: Dictionary = ficha.duplicate()
	campos["nube"] = {"gd": gd, "n": nombre, "s": datos.size()}
	if not lamina.is_empty():
		var nl: String = id + ".lamina.webp"
		var gl: String = await _sube(nl, lamina)
		if gl != "":
			campos["lam"] = {"gd": gl, "n": nl, "s": lamina.size(), "w": lam_size.x, "h": lam_size.y}
	campos["ul"] = _ahora()
	var mini: String = dir + "/miniatura.png"
	if FileAccess.file_exists(mini):
		var png: PackedByteArray = FileAccess.get_file_as_bytes(mini)
		var hash: String = _sha256(png).left(40)
		# Las imágenes nunca cambian: si ya existe, el servidor no deja reescribirla (y da igual).
		await _json(HTTPClient.METHOD_PUT, _url("/users/%s/familia_img/%s" % [uid, hash], "", true), {"d": Marshalls.raw_to_base64(png), "t": "image/png", "s": png.size()})
		campos["portada"] = hash
	return await _escribe(COLECCION, id, campos)

## Baja un mapa de la cuenta a user://mapas/<id>. "" o el error.
func baja_mapa(m: Dictionary) -> String:
	var nube: Variant = m.get("nube", {})
	var gd: String = str(nube.get("gd", "")) if typeof(nube) == TYPE_DICTIONARY else ""
	if gd == "":
		return "Ese mapa no tiene archivo en Google Drive."
	var datos: PackedByteArray = await _baja(gd)
	if datos.is_empty():
		return "No se pudo bajar el mapa de Google Drive."
	if not desempaqueta(datos, "user://mapas/" + str(m["id"])):
		return "El archivo del mapa está dañado."
	return ""

static func _sha256(b: PackedByteArray) -> String:
	var h := HashingContext.new()
	h.start(HashingContext.HASH_SHA256)
	h.update(b)
	return h.finish().hex_encode()
