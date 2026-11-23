# Firma de la familia y ATLAS

Dos pasos que solo puede hacer quien tiene la clave de la familia (está en tu ordenador, en `Familia-Revista/firma/`).

## 1. La clave, como secretos del repositorio Atlas

Así cada APK sale firmado con la misma clave que Polaris, Nova y las demás: se instala encima sin perder los mapas y las
hermanas le pasan la sesión.

1. En tu ordenador, convierte la clave a texto (cambia `familia.keystore` por el nombre de tu archivo):
   - Mac o Linux: `base64 -i firma/familia.keystore | tr -d '\n' > clave.txt`
   - Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("firma\familia.keystore")) > clave.txt`
2. El alias de la clave, si no lo recuerdas: `keytool -list -keystore firma/familia.keystore` (el nombre antes de la coma).
3. En GitHub: repositorio **Atlas** → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**,
   y crea estos cuatro:

| Nombre | Valor |
|---|---|
| `ATLAS_KEYSTORE_BASE64` | El contenido entero de `clave.txt` |
| `ATLAS_KEYSTORE_PASSWORD` | La contraseña del almacén (la de `firma/claves.properties`) |
| `ATLAS_KEY_ALIAS` | El alias del paso 2 |
| `ATLAS_KEY_PASSWORD` | La contraseña de la clave, si es distinta de la del almacén (si es la misma, no hace falta) |

4. Borra `clave.txt`. La próxima compilación sale firmada con la clave de la familia. En el registro de la compilación
   («Exportar ATLAS-android.apk») sale la huella SHA-256: debe ser
   `1d3d3e1a631bbbfdff5e05d449a0fb52f968dae87c596a9bdd4a23fe08671463` (la de `Acceso.CERTIFICADO`).

Los secretos no se ven nunca (ni en el registro); el repositorio es público, así que solo quien pueda escribir en él podría
usarlos en un workflow.

## 2. ATLAS en la lista de la familia (repositorio Familia-Revista)

Para que Polaris y las demás le den la sesión a ATLAS (entrar sin escribir la contraseña), ATLAS tiene que estar en
`LaFamilia.APPS` y en los `<queries>` del manifiesto. El cambio está en `docs/familia-revista-atlas.patch` (tres líneas).
Desde `Familia-Revista`: `git am ../Atlas/docs/familia-revista-atlas.patch` y sacar versión de las apps.

Mientras tanto, en ATLAS se entra una vez con el usuario y la contraseña de la familia (Mundo → Cuenta de la familia).
