package com.gonzalo.atlas

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gonzalo.atlas.familia.Acceso
import com.gonzalo.atlas.familia.Actualizador
import com.gonzalo.atlas.familia.Cuenta
import com.gonzalo.atlas.ui.App
import com.gonzalo.atlas.ui.EstadoApp

class Atlas : Application() {
    lateinit var estado: EstadoApp
    override fun onCreate() {
        super.onCreate()
        estado = EstadoApp(this)
        runCatching { Actualizador.init(this) }
        // Sin sesión: si una hermana de la familia ya la tiene, se entra solo
        if (!Cuenta.de(this).dentro) Thread { runCatching { Acceso.pideAHermanas(this) } }.start()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val e = (application as Atlas).estado
        setContent { App(e) }
    }

    override fun onPause() {
        super.onPause()
        val e = (application as Atlas).estado
        e.alSalir()
        e.cuenta.subeYa(e)
    }

    override fun onResume() {
        super.onResume()
        val c = Cuenta.de(this)
        if (!c.dentro) Thread { runCatching { if (Acceso.pideAHermanas(this)) runOnUiThread { (application as Atlas).estado.listas++ } } }.start()
    }
}
