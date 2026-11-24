package com.gonzalo.atlas.familia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actualizarse sola, como las hermanas: Familia-Revista firma cada versión con la clave de la familia y la deja en la carpeta
 * «versiones» de tu Google Drive (Atlas-<versión>.apk). Se mira al abrir la app, cada pocos minutos mientras se ve y, cerrada,
 * cada 4 horas. Si hay una más nueva, se baja, se comprueba que es ATLAS y que lleva la misma firma, y se instala:
 *  - sin preguntar cuando no la estás usando (Android 12 o más, con el permiso de instalar apps);
 *  - si Android no lo deja, con un aviso para actualizar ahora.
 */
object Actualizador {
    class Nueva(val nombre: String, val versionCode: Long, val archivo: File, val silenciosa: Boolean)

    var nueva by mutableStateOf<Nueva?>(null)
    var estado by mutableStateOf("")
        private set
    private val ocupado = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bucle: Job? = null
    @Volatile private var primerPlano = false
    @Volatile private var iniciado = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("atlas_actualizador", Context.MODE_PRIVATE)
    fun activo(ctx: Context) = prefs(ctx).getBoolean("on", true)
    fun ponActivo(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("on", v).apply()
    private fun anota(ctx: Context, t: String) {
        val v = java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) } + " · " + t
        estado = v; prefs(ctx).edit().putString("estado", v).apply()
    }

    /** «Atlas-1.2.0.apk» → [1, 2, 0]. */
    fun versionDe(nombre: String): List<Int>? = Regex("""^Atlas-(\d+(?:\.\d+)*)\.apk$""").find(nombre)?.groupValues?.get(1)?.split('.')?.map { it.toInt() }

    fun mayor(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    private fun hayInternet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val c = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Suppress("DEPRECATION")
    private fun instalada(ctx: Context) = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    /** Que el archivo sea de verdad una versión más nueva de ATLAS, con la misma firma que la instalada. */
    @Suppress("DEPRECATION")
    fun valida(ctx: Context, f: File): Long? {
        val pm = ctx.packageManager
        val n = pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        val a = instalada(ctx)
        if (n.packageName != ctx.packageName || n.longVersionCode <= a.longVersionCode) return null
        val fa = a.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val fn = n.signingInfo?.let { it.signingCertificateHistory ?: it.apkContentsSigners }?.map { it.toCharsString() }?.toSet().orEmpty()
        return if (fn.isEmpty() || (fa.isNotEmpty() && fn.containsAll(fa))) n.longVersionCode else null
    }

    fun puedeSilenciosa(ctx: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < 31 || !ctx.packageManager.canRequestPackageInstalls()) return false
        val minimo = when { sdk >= 37 -> 35; sdk >= 36 -> 34; sdk >= 35 -> 33; sdk >= 34 -> 31; sdk >= 33 -> 30; else -> 29 }
        return ctx.applicationInfo.targetSdkVersion >= minimo
    }

    fun init(ctx: Context) {
        if (iniciado) return
        iniciado = true
        val app = ctx.applicationContext
        estado = prefs(app).getString("estado", "").orEmpty()
        runCatching {
            WorkManager.getInstance(app).enqueueUniquePeriodicWork("actualizar", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ActualizadorWorker>(4, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_START -> {
                    primerPlano = true
                    runCatching { WorkManager.getInstance(app).cancelUniqueWork("actualizar-al-salir") }
                    bucle?.cancel()
                    bucle = scope.launch { while (true) { runCatching { comprueba(app) }; delay(5 * 60_000) } }
                }
                Lifecycle.Event.ON_STOP -> {
                    primerPlano = false
                    bucle?.cancel(); bucle = null
                    if (nueva?.silenciosa == true) programaAlSalir(app)
                }
                else -> {}
            }
        })
    }

    private fun programaAlSalir(ctx: Context) = runCatching {
        WorkManager.getInstance(ctx).enqueueUniqueWork("actualizar-al-salir", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ActualizadorWorker>().setInitialDelay(45, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    /** Mira la carpeta «versiones» y baja la más nueva si la hay (bloquea: fuera del hilo principal). */
    fun comprueba(ctx: Context, manual: Boolean = false) {
        if (!activo(ctx) && !manual) return
        val c = Cuenta.de(ctx)
        if (!c.dentro) { anota(ctx, "sin cuenta de la familia: entra en Mundo → Cuenta para actualizarte sola"); return }
        if (!hayInternet(ctx)) { anota(ctx, "sin internet"); return }
        if (!ocupado.compareAndSet(false, true)) return
        try {
            val dir = c.carpeta("versiones", false)
            if (dir.isBlank()) { anota(ctx, "no encuentro la carpeta «versiones» en tu Google Drive"); return }
            val ult = c.dentroDe(dir).mapNotNull { (n, id) -> versionDe(n)?.let { Triple(n, id, it) } }.fold(null as Triple<String, String, List<Int>>?) { m, x -> if (m == null || mayor(x.third, m.third)) x else m }
                ?: run { anota(ctx, "en «versiones» aún no hay ningún Atlas-<versión>.apk"); return }
            val actual = instalada(ctx).versionName?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return
            val cache = File(ctx.cacheDir, "actualizaciones").apply { mkdirs() }
            cache.listFiles()?.filter { it.name != ult.first }?.forEach { it.delete() }
            if (!mayor(ult.third, actual)) { anota(ctx, "al día (la última es la ${ult.third.joinToString(".")})"); return }
            if (nueva?.nombre == ult.first) return
            val f = File(cache, ult.first)
            var code = if (f.exists()) valida(ctx, f) else null
            if (code == null) {
                f.delete()
                c.baja(ult.second, f) ?: run { anota(ctx, "no he podido bajar la ${ult.first}"); return }
                code = valida(ctx, f)
                if (code == null) { f.delete(); anota(ctx, "bajada la ${ult.first}, pero Android no la da por buena (otra firma o archivo roto)"); return }
            }
            val sil = puedeSilenciosa(ctx)
            nueva = Nueva(ult.first, code, f, sil)
            if (sil && !primerPlano) programaAlSalir(ctx)
            anota(ctx, "lista la ${ult.third.joinToString(".")}" + if (sil) " (se instala al salir de la app)" else " (te pregunto para instalarla)")
        } catch (e: Exception) { anota(ctx, "error: ${e.message ?: e.javaClass.simpleName}") } finally { ocupado.set(false) }
    }

    fun fallo(ctx: Context, msg: String) { anota(ctx, "la instalación no se completó: $msg"); nueva?.let { nueva = Nueva(it.nombre, it.versionCode, it.archivo, false) } }

    fun alSalir(ctx: Context) {
        val n = nueva?.takeIf { it.silenciosa } ?: return
        if (primerPlano) return
        instala(ctx, n)
    }

    fun instala(ctx: Context, n: Nueva) {
        ctx.getSharedPreferences("atlas_act", Context.MODE_PRIVATE).edit().putLong("reabrir", if (primerPlano) System.currentTimeMillis() else 0L).apply()
        val pi = ctx.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(p)
        pi.openSession(id).use { s ->
            n.archivo.inputStream().use { i -> s.openWrite("atlas.apk", 0, n.archivo.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val i = Intent(ctx, ActualizadorReceiver::class.java).setPackage(ctx.packageName)
            s.commit(PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
        }
    }

    /** «Actualizar ahora» (con la app delante). Bloquea. Devuelve qué ha pasado. */
    fun ahora(ctx: Context): String {
        if (nueva == null) comprueba(ctx, manual = true)
        val n = nueva ?: return estado.substringAfter(" · ").ifBlank { "No hay versión nueva" }.replaceFirstChar { it.uppercase() }
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return "Permite a ATLAS instalar apps y vuelve a pulsar"
        }
        return try { instala(ctx, n); "Instalando la ${n.nombre.removeSuffix(".apk")}…" } catch (e: Exception) { "No se pudo instalar: ${e.message}" }
    }

    fun reabre(ctx: Context) {
        val sp = ctx.getSharedPreferences("atlas_act", Context.MODE_PRIVATE)
        val t = sp.getLong("reabrir", 0L); sp.edit().putLong("reabrir", 0L).apply()
        if (t == 0L || System.currentTimeMillis() - t > 15 * 60_000L) return
        val abrir = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reabrir", "ATLAS actualizada", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 3006, abrir, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(3006, Notification.Builder(ctx, "reabrir").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ATLAS se ha actualizado").setContentText("Toca para abrirla").setContentIntent(pi).setAutoCancel(true).build())
    }
}

class ActualizadorReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (st == PackageInstaller.STATUS_SUCCESS) return
        if (st != PackageInstaller.STATUS_PENDING_USER_ACTION) { Actualizador.fallo(ctx, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "código $st"); return }
        @Suppress("DEPRECATION") val conf = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(conf) }.onFailure {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("actualizar", "Actualizaciones de ATLAS", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(ctx, 3005, conf, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(3005, Notification.Builder(ctx, "actualizar").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("ATLAS tiene una versión nueva").setContentText("Toca para actualizar").setContentIntent(pi).setAutoCancel(true).build())
        }
    }
}

class ActualizadorReabrir : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Actualizador.reabre(ctx) }
}

class ActualizadorWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (!Actualizador.activo(c)) return Result.success()
        withContext(Dispatchers.IO) {
            if (!Cuenta.de(c).dentro) runCatching { Acceso.pideAHermanas(c) }
            if (Actualizador.nueva == null) runCatching { Actualizador.comprueba(c) }
        }
        runCatching { Actualizador.alSalir(c) }
        return Result.success()
    }
}
