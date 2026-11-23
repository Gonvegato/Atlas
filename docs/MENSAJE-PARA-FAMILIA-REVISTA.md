Copia desde la línea de abajo hasta el final y pégalo en el chat de Claude que trabaja en el repositorio **Familia-Revista**.

---

Hay una app nueva en la familia: **ATLAS** (mapas de fantasía hechos en Godot, repositorio público `Gonvegato/Atlas`,
paquete `com.gonzalo.atlas`). Guarda los mapas en la cuenta de la familia, en `familia/atlas` y en la carpeta «Familia»
de Drive, y cada mapa va ligado a una novela de `familia/obras`.

Necesito que la añadas a la lista de apps de la familia, para que cuando ATLAS lleve la firma de la familia las hermanas
puedan pasarle la sesión. Son dos cambios en el módulo `familia`:

1. En `familia/src/main/java/com/gonzalo/familia/LaFamilia.kt`, añade `"com.gonzalo.atlas"` al final de `APPS`:

   ```kotlin
   val APPS = listOf("com.gonzalo.hiedra", SESHAT, SESKOBO, "com.gonzalo.cobalto", "com.gonzalo.gamerate", "com.gonzalo.mostaza", "com.gonzalo.cobre", "com.gonzalo.ammit", "com.gonzalo.atlas")
   ```

2. En `familia/src/main/AndroidManifest.xml`, dentro de `<queries>`, junto a los demás paquetes:

   ```xml
   <!-- ATLAS: los mapas de las novelas (repositorio Gonvegato/Atlas, en Godot) -->
   <package android:name="com.gonzalo.atlas" />
   ```

Si `APPS` o `<queries>` ya no son como arriba (puede que hayan cambiado desde el 23-11-2026), adapta el cambio a como
estén ahora, con el mismo objetivo. Comprueba que compila y que pasan las pruebas del módulo `familia`, y súbelo y saca
versión de las apps como lo hagas normalmente.

No hace falta tocar nada de la firma: ATLAS se firmará con `familia.keystore` (alias `familia`) desde su propio
repositorio; la huella es la de `Acceso.CERTIFICADO`. ATLAS no es una app de Gradle de este repositorio, así que no la
añadas a `publicar.yml` ni a `firmar.py`.
