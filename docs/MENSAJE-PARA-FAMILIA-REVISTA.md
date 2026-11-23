Hay una app nueva en la familia: **ATLAS** (mapas de fantasía hechos en Godot, repositorio público `Gonvegato/Atlas`, paquete `com.gonzalo.atlas`). Guarda los mapas en la cuenta de la familia (`familia/atlas` y la carpeta «Familia» de Drive) y cada mapa va ligado a una novela de `familia/obras`.

Necesito dos cosas en este repositorio:

**1. ATLAS en la lista de apps de la familia.** En el módulo `familia`:

- En `familia/src/main/java/com/gonzalo/familia/LaFamilia.kt`, añade `"com.gonzalo.atlas"` al final de `APPS`.
- En `familia/src/main/AndroidManifest.xml`, dentro de `<queries>`, añade `<package android:name="com.gonzalo.atlas" />` junto a los demás.

**2. Que este repositorio firme ATLAS con la clave de la familia y lo suba a Drive.** La clave solo está aquí (secreto `FIRMA_B64`) y no hay forma de copiarla a ATLAS, así que la firma tiene que hacerse aquí:

- ATLAS publica cada versión en una dirección fija: `https://github.com/Gonvegato/Atlas/releases/latest/download/ATLAS.apk`, y al lado `ATLAS.json` con `{"version": "0.12.1", "code": 17, "sha": "..."}`. El APK es una versión final, ya alineada, firmada con una clave provisional.
- Haz un workflow (o un trabajo dentro de `publicar.yml`, como encaje mejor) que, a mano (`workflow_dispatch`) y de forma periódica, descargue ese APK, lo vuelva a firmar con `familia.keystore` (alias `familia`, sin linaje: como las apps que nacen en la familia) usando `apksigner sign`, que sustituye la firma provisional, y compruebe con `apksigner verify --print-certs` que la huella SHA-256 es la de `Acceso.CERTIFICADO`.
- Después tiene que subirlo a Drive como las demás, con `herramientas/gdrive/subir-version.py` y con el nombre `Atlas-<versión>.apk` (o el que siga vuestra convención), sin volver a subir una versión que ya esté.
- Prepara y borra las claves igual que en `publicar.yml`. Si el sistema de versiones necesita registrar la app (nombre, paquete), regístrala como «Atlas», con el paquete `com.gonzalo.atlas`.
- Ya está comprobado con apksigner que el APK de Godot se deja volver a firmar y queda con un único firmante. Aun así, verifícalo en la primera ejecución.

ATLAS no es una app de Gradle de este repositorio: no hay que compilarla aquí, solo descargarla y firmarla.

Comprueba que compila, que pasan las pruebas del módulo `familia` y que el workflow nuevo funciona (lánzalo a mano una vez). Después súbelo y saca versión de las apps como lo hagas normalmente.
