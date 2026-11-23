# Firma de la familia y ATLAS

## 1. La firma (esto solo puedes hacerlo tú, en la web de GitHub)

Ningún Claude puede crear secretos de GitHub ni leer los que ya hay: hay que pegarlo a mano una vez.

ATLAS acepta el **mismo secreto `FIRMA_B64`** que ya usa Familia-Revista (un .zip en base64 con `familia.keystore` y
`claves.properties`). GitHub no deja ver el valor de un secreto ya guardado, así que hay que volver a generarlo en el
ordenador donde tienes la carpeta `Familia-Revista/firma/`:

1. Haz un `claves.properties` aparte con **solo** la línea de la familia (así no viajan las contraseñas de las claves
   viejas): `familia.pass=LA_CONTRASEÑA`.
2. Comprímelo junto a `familia.keystore` y pásalo a texto:
   - Mac o Linux:
     `zip -j firma.zip firma/familia.keystore claves.properties && base64 -i firma.zip | tr -d '\n' > firma.txt`
   - Windows (PowerShell):
     `Compress-Archive firma\familia.keystore, claves.properties firma.zip; [Convert]::ToBase64String([IO.File]::ReadAllBytes("firma.zip")) > firma.txt`
3. En GitHub: repositorio **Atlas** → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.
   Nombre: `FIRMA_B64`. Valor: el contenido entero de `firma.txt`.
4. Borra `firma.zip`, `firma.txt` y el `claves.properties` de apoyo.

La próxima compilación sale firmada con la clave de la familia (alias `familia`). En el registro de la compilación
(«Exportar ATLAS-android.apk») sale la huella SHA-256: debe ser
`1d3d3e1a631bbbfdff5e05d449a0fb52f968dae87c596a9bdd4a23fe08671463` (la de `Acceso.CERTIFICADO`).

**Ojo:** la primera versión con la firma de la familia no se instala encima de las de ahora (cada una llevaba una clave
de depuración distinta). Antes de desinstalar, sube tus mapas a la cuenta (Mundo → Cuenta de la familia → SUBIR AHORA).
A partir de ahí, cada versión se instala encima sin perder nada.

(Sigue valiendo la forma antigua con cuatro secretos `ATLAS_KEYSTORE_BASE64`, `ATLAS_KEYSTORE_PASSWORD`,
`ATLAS_KEY_ALIAS` y `ATLAS_KEY_PASSWORD`; si está `FIRMA_B64`, manda ese.)

## 2. ATLAS en la lista de la familia (repositorio Familia-Revista)

Esto sí lo puede hacer otro Claude que trabaje en Familia-Revista: el mensaje para pegarle está en
`docs/MENSAJE-PARA-FAMILIA-REVISTA.md`. El cambio (tres líneas) también está como parche en
`docs/familia-revista-atlas.patch`.

Con eso, ATLAS queda en `LaFamilia.APPS` y en los `<queries>`. **Todavía no basta para entrar sin contraseña**: ATLAS
(Godot) aún no le pide la sesión a Polaris; esa parte está pendiente en ATLAS. Mientras tanto se entra una vez con el
usuario y la contraseña de la familia (Mundo → Cuenta de la familia).
