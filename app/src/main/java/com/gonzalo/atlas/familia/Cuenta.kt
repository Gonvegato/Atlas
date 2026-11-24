package com.gonzalo.atlas.familia

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Una petición HTTP sencilla. [codigo] 0 = sin red. */
class Respuesta(val codigo: Int, val cuerpo: ByteArray, val cabeceras: Map<String, List<String>>) {
    val texto get() = String(cuerpo, Charsets.UTF_8)
    fun json(): Any? = runCatching { val t = texto.trim(); if (t.startsWith("{")) JSONObject(t) else if (t.startsWith("[")) JSONArray(t) else if (t == "null") null else JSONObject("{\"v\":$t}").opt("v") }.getOrNull()
    fun cabecera(n: String) = cabeceras.entries.firstOrNull { it.key.equals(n, true) }?.value?.firstOrNull()
}

object Red {
    fun pide(metodo: String, url: String, cuerpo: ByteArray? = null, cab: Map<String, String> = emptyMap(), tiempo: Int = 60_000): Respuesta = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = tiempo
        if (metodo == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else c.requestMethod = metodo
        cab.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (cuerpo != null) { c.doOutput = true; c.setFixedLengthStreamingMode(cuerpo.size); c.outputStream.use { it.write(cuerpo) } }
        val cod = c.responseCode
        val s = (if (cod >= 400) c.errorStream else c.inputStream)
        val b = s?.use { it.readBytes() } ?: ByteArray(0)
        Respuesta(cod, b, c.headerFields.filterKeys { it != null })
    } catch (e: Exception) { Respuesta(0, (e.message ?: "").toByteArray(), emptyMap()) }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** Guarda cosas cifradas con una clave del almacén de Android (que no sale del móvil). */
class Caja(ctx: Context, nombre: String) {
    private val f = File(ctx.filesDir, nombre)
    private val alias = "atlas_$nombre"

    private fun clave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun lee(): String? = runCatching {
        val b = f.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }.getOrNull()

    fun escribe(t: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, clave())
        val o = ByteArrayOutputStream(); o.write(c.iv); o.write(c.doFinal(t.toByteArray(Charsets.UTF_8)))
        f.writeBytes(o.toByteArray())
    }

    fun borra() { f.delete() }
}

/**
 * La cuenta de la familia (la de Polaris, Nova, Lyra…), con el contrato de Familia-Revista (nube/ESQUEMA.md):
 *  - Entrar: Firebase Authentication con usuario y contraseña (<usuario>@ronron.example.com; la clave web se lee de /config/webKey).
 *    Si ya has entrado en una hermana, ATLAS entra sola (ver [Acceso]); y si entras aquí, se lo da a las hermanas.
 *  - Los mapas: /users/<uid>/familia/atlas/<mapa>, campo a campo con su hora en m/<campo>, la hora del servidor en u y el
 *    sello en familia_sello/atlas. Borrar es poner b. Los archivos van a tu Google Drive (<carpeta de la familia>/atlas).
 */
class Cuenta private constructor(private val ctx: Context) {
    var base = "https://app-ratear-default-rtdb.europe-west1.firebasedatabase.app"
    var baseAuth = "https://identitytoolkit.googleapis.com"
    var baseToken = "https://securetoken.googleapis.com"
    var baseOauth = "https://oauth2.googleapis.com/token"
    var baseDrive = "https://www.googleapis.com"
    var dominio = "ronron.example.com"

    private val caja = Caja(ctx, "cuenta.bin")
    var uid by mutableStateOf(""); private set
    var usuario by mutableStateOf(""); private set
    private var contrasena = ""
    private var refresco = ""
    private var claveWeb = ""
    private var pase = ""; private var caduca = 0L
    private var desfase = 0L
    var estado by mutableStateOf("")
    var subiendo by mutableStateOf(false)
    var autoSubir by mutableStateOf(true)
    val dentro get() = uid.isNotBlank() && refresco.isNotBlank()
    private val principal = Handler(Looper.getMainLooper())

    init {
        caja.lee()?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { o ->
            uid = o.optString("uid"); usuario = o.optString("u"); contrasena = o.optString("c"); refresco = o.optString("r"); claveWeb = o.optString("k")
        }
        autoSubir = ctx.getSharedPreferences("atlas", Context.MODE_PRIVATE).getBoolean("auto_subir", true)
    }

    fun ponAutoSubir(v: Boolean) { autoSubir = v; ctx.getSharedPreferences("atlas", Context.MODE_PRIVATE).edit().putBoolean("auto_subir", v).apply() }

    private fun guarda() = caja.escribe(JSONObject().put("uid", uid).put("u", usuario).put("c", contrasena).put("r", refresco).put("k", claveWeb).toString())

    /** Usuario y contraseña (para dárselos a las hermanas de la familia). */
    internal fun credenciales(): Pair<String, String>? = if (dentro && contrasena.isNotBlank()) usuario to contrasena else null

    private fun url(ruta: String, consulta: String = "", conPase: Boolean = false): String {
        val p = listOfNotNull(consulta.takeIf { it.isNotBlank() }, if (conPase) "auth=" + Red.enc(pase) else null)
        return base.trimEnd('/') + ruta + ".json" + if (p.isEmpty()) "" else "?" + p.joinToString("&")
    }

    private fun ahora() = System.currentTimeMillis() + desfase

    private fun clave(): String {
        if (claveWeb.isNotBlank()) return claveWeb
        val r = Red.pide("GET", url("/config/webKey"))
        if (r.codigo == 200) (r.json() as? String)?.let { claveWeb = it }
        return claveWeb
    }

    /** Entra (bloquea: fuera del hilo principal). Devuelve "" si entra, o el motivo. */
    fun entrar(usuarioEscrito: String, pass: String): String {
        val u = usuarioEscrito.trim().lowercase()
        if (u.isEmpty() || pass.isEmpty()) return "Escribe tu usuario y tu contraseña."
        val k = clave()
        if (k.isBlank()) return "No hay conexión con la cuenta."
        val correo = if ("@" in u) u else "$u@$dominio"
        val r = Red.pide("POST", baseAuth.trimEnd('/') + "/v1/accounts:signInWithPassword?key=" + Red.enc(k),
            JSONObject().put("email", correo).put("password", pass).put("returnSecureToken", true).toString().toByteArray(), mapOf("Content-Type" to "application/json"))
        if (r.codigo == 0) return "No hay conexión con la cuenta."
        val o = r.json() as? JSONObject
        if (r.codigo != 200 || o == null) {
            val msg = o?.optJSONObject("error")?.optString("message").orEmpty()
            return when {
                msg.startsWith("EMAIL_NOT_FOUND") || msg.startsWith("INVALID_PASSWORD") || msg.startsWith("INVALID_LOGIN_CREDENTIALS") -> "Usuario o contraseña incorrectos."
                msg.startsWith("TOO_MANY_ATTEMPTS") -> "Demasiados intentos. Espera un poco y vuelve a probar."
                else -> "La cuenta no ha dejado entrar ($msg)."
            }
        }
        val nuevoUid = o.optString("localId")
        pase = o.optString("idToken"); refresco = o.optString("refreshToken")
        caduca = System.currentTimeMillis() + o.optString("expiresIn", "3600").toLong() * 1000 - 60_000
        principal.post { uid = nuevoUid; usuario = u }
        uid = nuevoUid; usuario = u; contrasena = pass
        guarda()
        return ""
    }

    fun salir() {
        uid = ""; usuario = ""; contrasena = ""; refresco = ""; pase = ""; drivePase = ""; driveRaiz = ""; driveAtlas = ""
        caja.borra()
    }

    /** El pase de la cuenta, renovado si hace falta ("" sin sesión o sin red). */
    @Synchronized fun pase(): String {
        if (!dentro) return ""
        if (pase.isNotBlank() && System.currentTimeMillis() < caduca) return pase
        val k = clave()
        val r = Red.pide("POST", baseToken.trimEnd('/') + "/v1/token?key=" + Red.enc(k), ("grant_type=refresh_token&refresh_token=" + Red.enc(refresco)).toByteArray(),
            mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        if (r.codigo != 200) {
            if (r.codigo == 400) principal.post { salir() }
            return ""
        }
        val o = r.json() as? JSONObject ?: return ""
        pase = o.optString("id_token"); refresco = o.optString("refresh_token", refresco)
        caduca = System.currentTimeMillis() + o.optString("expires_in", "3600").toLong() * 1000 - 60_000
        guarda()
        return pase
    }

    fun lee(ruta: String, consulta: String = ""): Any? {
        if (pase().isBlank()) return null
        val r = Red.pide("GET", url(ruta, consulta, true))
        return if (r.codigo == 200) r.json() else null
    }

    /** Escribe campos de una ficha con el contrato de la familia (un PATCH multi-ruta). "" o el error. */
    fun escribe(coleccion: String, ref: String, campos: Map<String, Any?>): String {
        if (pase().isBlank()) return "Sin sesión en la cuenta."
        val m = ahora()
        val srv = JSONObject().put(".sv", "timestamp")
        val c = JSONObject()
        for ((k, v) in campos) { c.put("familia/$coleccion/$ref/$k", v ?: JSONObject.NULL); c.put("familia/$coleccion/$ref/m/$k", m) }
        c.put("familia/$coleccion/$ref/u", srv); c.put("familia_sello/$coleccion", srv)
        val r = Red.pide("PATCH", url("/users/$uid", "", true), c.toString().toByteArray(), mapOf("Content-Type" to "application/json"))
        if (r.codigo == 200) {
            (r.json() as? JSONObject)?.optLong("familia/$coleccion/$ref/u")?.takeIf { it > 0 }?.let { desfase += it - ahora() }
            return ""
        }
        return "La cuenta rechazó el cambio (${r.codigo}): ${r.texto.take(200)}"
    }

    class Obra(val id: String, val titulo: String)

    /** Las novelas de Nova, sin las borradas, por título. */
    fun obras(): List<Obra> {
        val o = lee("/users/$uid/familia/obras") as? JSONObject ?: return emptyList()
        return o.keys().asSequence().mapNotNull { k -> val e = o.optJSONObject(k) ?: return@mapNotNull null; if (e.optLong("b") > 0) null else Obra(k, e.optString("t", k)) }
            .sortedBy { it.titulo.lowercase() }.toList()
    }

    class MapaNube(val id: String, val titulo: String, val obras: List<String>, val ul: Long, val nubeGd: String, val mundo: String)

    fun mapasNube(): List<MapaNube> {
        val o = lee("/users/$uid/familia/atlas") as? JSONObject ?: return emptyList()
        return o.keys().asSequence().mapNotNull { k ->
            val e = o.optJSONObject(k) ?: return@mapNotNull null
            if (e.optLong("b") > 0) return@mapNotNull null
            val obras = (e.optString("obras").ifBlank { e.optString("obra") }).split(',').map { it.trim() }.filter { it.isNotBlank() }
            MapaNube(k, e.optString("t", k), obras, e.optLong("ul"), e.optJSONObject("nube")?.optString("gd").orEmpty(), e.optString("mundo"))
        }.sortedByDescending { it.ul }.toList()
    }

    // ---------------------------------------------------------------- Google Drive (con las claves de la familia)

    private var drivePase = ""; private var driveCaduca = 0L; private var driveRaiz = ""; private var driveAtlas = ""
    private val driveCarpetas = HashMap<String, String>()

    @Synchronized fun driveToken(): String {
        if (drivePase.isNotBlank() && System.currentTimeMillis() < driveCaduca) return drivePase
        val cl = lee("/claves/$uid") as? JSONObject ?: return ""
        val cid = cl.optString("gdrive_client_id"); val sec = cl.optString("gdrive_client_secret"); val rt = cl.optString("gdrive_refresh_token")
        if (cid.isBlank() || rt.isBlank()) return ""
        val r = Red.pide("POST", baseOauth, "client_id=${Red.enc(cid)}&client_secret=${Red.enc(sec)}&refresh_token=${Red.enc(rt)}&grant_type=refresh_token".toByteArray(),
            mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        if (r.codigo != 200) return ""
        val o = r.json() as? JSONObject ?: return ""
        drivePase = o.optString("access_token"); driveCaduca = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000
        if (driveRaiz.isBlank()) driveRaiz = raiz(cl.optString("gdrive_carpeta"))
        return drivePase
    }

    private fun dcab() = mapOf("Authorization" to "Bearer $drivePase")
    private fun escQ(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    fun busca(padre: String, nombre: String, carpeta: Boolean): String {
        val q = "'${escQ(padre)}' in parents and name = '${escQ(nombre)}' and trashed = false and mimeType ${if (carpeta) "=" else "!="} 'application/vnd.google-apps.folder'"
        val r = Red.pide("GET", "$baseDrive/drive/v3/files?q=${Red.enc(q)}&fields=${Red.enc("files(id,name)")}&pageSize=1", null, dcab())
        return ((r.json() as? JSONObject)?.optJSONArray("files")?.optJSONObject(0))?.optString("id").orEmpty()
    }

    /** Los archivos de una carpeta: [(nombre, id)]. */
    fun dentroDe(carpeta: String): List<Pair<String, String>> {
        val q = "'${escQ(carpeta)}' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'"
        val r = Red.pide("GET", "$baseDrive/drive/v3/files?q=${Red.enc(q)}&fields=${Red.enc("files(id,name)")}&pageSize=1000", null, dcab())
        val a = (r.json() as? JSONObject)?.optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).let { f -> f.optString("name") to f.optString("id") } }
    }

    private fun creaCarpeta(padre: String, nombre: String): String {
        val r = Red.pide("POST", "$baseDrive/drive/v3/files?fields=id", JSONObject().put("name", nombre).put("mimeType", "application/vnd.google-apps.folder").put("parents", JSONArray().put(padre)).toString().toByteArray(),
            dcab() + ("Content-Type" to "application/json"))
        return (r.json() as? JSONObject)?.optString("id").orEmpty()
    }

    /** La carpeta de la familia: la de gdrive_carpeta (id o enlace) o «Familia» en «Mi unidad» (como las hermanas). */
    private fun raiz(config: String): String {
        var c = config.trim()
        if ("/folders/" in c) c = c.substringAfter("/folders/").substringBefore("?").substringBefore("/")
        if (c.isNotBlank()) return c
        return busca("root", "Familia", true).ifBlank { creaCarpeta("root", "Familia") }
    }

    /** Una subcarpeta de la de la familia (sin crearla si [crear] es false). */
    fun carpeta(nombre: String, crear: Boolean): String {
        if (driveToken().isBlank()) return ""
        driveCarpetas[nombre]?.let { return it }
        val id = busca(driveRaiz, nombre, true).ifBlank { if (crear) creaCarpeta(driveRaiz, nombre) else "" }
        if (id.isNotBlank()) driveCarpetas[nombre] = id
        return id
    }

    /** Sube (o reemplaza) un archivo en la carpeta atlas. Devuelve su id ("" si falla). */
    fun sube(nombre: String, datos: ByteArray, tipo: String = "application/octet-stream"): String {
        val dir = carpeta("atlas", true).ifBlank { return "" }
        val existente = busca(dir, nombre, false)
        val meta = if (existente.isNotBlank()) JSONObject().put("name", nombre) else JSONObject().put("name", nombre).put("parents", JSONArray().put(dir))
        val u = baseDrive + if (existente.isNotBlank()) "/upload/drive/v3/files/$existente?uploadType=resumable&fields=id" else "/upload/drive/v3/files?uploadType=resumable&fields=id"
        val r = Red.pide(if (existente.isNotBlank()) "PATCH" else "POST", u, meta.toString().toByteArray(),
            dcab() + mapOf("X-Upload-Content-Type" to tipo, "X-Upload-Content-Length" to datos.size.toString(), "Content-Type" to "application/json; charset=UTF-8"))
        val loc = r.cabecera("Location") ?: return ""
        val up = Red.pide("PUT", loc, datos, mapOf("Content-Type" to tipo), 300_000)
        if (up.codigo != 200 && up.codigo != 201) return ""
        return (up.json() as? JSONObject)?.optString("id")?.ifBlank { existente } ?: existente
    }

    fun baja(id: String, destino: File? = null): ByteArray? {
        if (driveToken().isBlank()) return null
        val r = Red.pide("GET", "$baseDrive/drive/v3/files/${Red.enc(id)}?alt=media&acknowledgeAbuse=true", null, dcab(), 600_000)
        if (r.codigo != 200) return null
        destino?.writeBytes(r.cuerpo)
        return r.cuerpo
    }

    /** Sube una imagen a familia_img (las imágenes nunca cambian: si ya está, da igual). Devuelve su hash. */
    fun subeImagen(png: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }.take(40)
        Red.pide("PUT", url("/users/$uid/familia_img/$h", "", true), JSONObject().put("d", Base64.encodeToString(png, Base64.NO_WRAP)).put("t", "image/png").put("s", png.size).toString().toByteArray(),
            mapOf("Content-Type" to "application/json"))
        return h
    }

    // ---------------------------------------------------------------- subir sola

    private var pendiente: Runnable? = null

    /** Tras guardar: si estás dentro y «subir sola» está encendido, sube el mapa al cabo de un rato. */
    fun programaSubida(e: com.gonzalo.atlas.ui.EstadoApp) {
        if (!dentro || !autoSubir) return
        pendiente?.let { principal.removeCallbacks(it) }
        val r = Runnable { Nube.subeEnSegundoPlano(e, manual = false) }
        pendiente = r
        principal.postDelayed(r, 90_000)
    }

    fun subeYa(e: com.gonzalo.atlas.ui.EstadoApp) { pendiente?.let { principal.removeCallbacks(it) }; pendiente = null; if (dentro && autoSubir) Nube.subeEnSegundoPlano(e, manual = false) }

    companion object {
        @Volatile private var una: Cuenta? = null
        fun de(ctx: Context): Cuenta = una ?: synchronized(this) { una ?: Cuenta(ctx.applicationContext).also { una = it } }
    }
}
