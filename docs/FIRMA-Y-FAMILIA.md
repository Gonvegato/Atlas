# Firma de la familia y ATLAS

## Cómo se firma ATLAS (sin que tengas que tocar claves)

La clave de la familia (`familia.keystore`) solo está guardada como secreto `FIRMA_B64` en Familia-Revista, y de un
secreto de GitHub no la puede sacar nadie. Por eso el reparto es este:

1. Cada vez que algo entra en `main`, ATLAS compila su APK (versión final, firmada con una clave provisional) y lo
   publica en una dirección fija:
   - `https://github.com/Gonvegato/Atlas/releases/latest/download/ATLAS.apk`
   - `https://github.com/Gonvegato/Atlas/releases/latest/download/ATLAS.json` (`version`, `code`, `sha` = el commit, `apk_sha256` = la huella del APK)
2. Familia-Revista (que sí tiene la clave) lo descarga, lo vuelve a firmar con `familia.keystore` (alias `familia`,
   sin linaje) y lo sube a Drive con las demás apps. Comprobado con apksigner: el APK de Godot se deja volver a firmar
   y queda con un único firmante, el nuevo.
3. Lo instalas desde Drive como las demás. La huella debe ser
   `1d3d3e1a631bbbfdff5e05d449a0fb52f968dae87c596a9bdd4a23fe08671463` (la de `Acceso.CERTIFICADO`).

El mensaje para el Claude de Familia-Revista está en `docs/MENSAJE-PARA-FAMILIA-REVISTA.md`.

**Ojo:** la primera versión con la firma de la familia no se instala encima de las de ahora. Antes de desinstalar,
sube tus mapas a la cuenta (Mundo → Cuenta de la familia → SUBIR AHORA). A partir de ahí, cada versión se instala
encima sin perder nada.

## Otra forma (solo si algún día tienes la clave en tu ordenador)

ATLAS también puede firmarse él mismo si en su repositorio hay un secreto `FIRMA_B64` (un .zip en base64 con
`familia.keystore` y un `claves.properties` con la línea `familia.pass=...`), o los cuatro antiguos
`ATLAS_KEYSTORE_BASE64`, `ATLAS_KEYSTORE_PASSWORD`, `ATLAS_KEY_ALIAS` y `ATLAS_KEY_PASSWORD`.

## Entrar sin contraseña y actualizarse sola (desde la 1.0.0)

ATLAS es ya una app nativa con el mismo contrato que las hermanas:

- **Entrar una vez.** Si no tiene sesión, pregunta a las hermanas instaladas (`content://<paquete>.familia.acceso/sesion`,
  solo a las que llevan la firma de la familia) y entra con la cuenta de la primera que la tenga. Y da la suya por
  `content://com.gonzalo.atlas.familia.acceso/sesion`, con las mismas comprobaciones.
- **Actualizarse sola.** Mira la carpeta «versiones» de tu Google Drive (dentro de la carpeta de la familia: la de
  `gdrive_carpeta` o «Familia»), coge el `Atlas-<versión>.apk` más nuevo, comprueba que es ATLAS y que lleva la misma
  firma, y lo instala (sin preguntar cuando no la estás usando, si Android lo deja).
