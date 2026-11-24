Hay que hacer en este repositorio un **visor de mapas de solo lectura** dentro de los escritos. Los mapas los hace **ATLAS** (`Gonvegato/Atlas`, paquete `com.gonzalo.atlas`) y los sube a la cuenta de la familia desde la versión 0.14.0.

**Dónde están (contrato de ATLAS, campo a campo como las demás colecciones):** `/users/<uid>/familia/atlas/<mapa>`, con `m/<campo>`, `u` y `familia_sello/atlas`, como siempre. Borrado = `b`. Campos:

- `t`: nombre del mapa.
- `obra`: id de la obra principal (el mismo id de `familia/obras/<obra>` y de los escritos de Cobalto).
- `obras`: todas las obras en las que sale el mapa, ids separados por comas, por ejemplo `"o_ithria,o_cosmos"`. Si no está, vale `obra`.
- `w`, `h`: tamaño del mapa en píxeles.
- `portada`: hash de la miniatura en `familia_img/<hash>` (PNG de 256 px).
- `lam`: la **lámina**, `{gd, n, s, w, h}`. Es el mapa 2D entero con sus rótulos, una imagen WebP de 3.072 px de lado mayor en el Drive de la familia (carpeta `atlas`). Se baja con las claves de `/claves/<uid>` como los demás `nube`.
- `lug`: texto JSON con los lugares del mapa, `[{"n": nombre, "k": tipo, "x": 0-1, "y": 0-1}]`. La `x` y la `y` son la posición sobre la lámina (0,0 arriba a la izquierda).
- `nube`: el archivo del mapa (`.atlas`); el visor no lo necesita.
- `ul`: ms de la última subida.

**Lo que necesito:**

1. Documenta esta colección en `nube/ESQUEMA.md`, en una sección `atlas/<mapa>` (ATLAS).
2. En el módulo `familia` (para que lo usen todas las apps que enseñan escritos), un visor compartido:
   - Una función que lea `familia/atlas` y devuelva los mapas de una obra: los que tienen esa obra en `obra` o en `obras`, sin los borrados.
   - Un visor a pantalla completa con la lámina: pellizcar para acercar (hasta unas 6×), arrastrar, doble toque para acercar o volver, y **nada de editar**.
   - Cabecera de la dirección C con el nombre del mapa y ← VOLVER. Si la obra tiene varios mapas, pestañas o lista para elegir.
   - Un BUSCAR que use `lug`: al elegir un lugar, centra la lámina en él y lo marca.
   - La lámina se guarda en el dispositivo y solo se vuelve a bajar si cambia `lam.gd` o `ul`, así se puede ver sin conexión.
   - Sin `lam` (un mapa subido con una versión anterior de ATLAS), enseña la miniatura de `portada` y un aviso: «Abre el mapa en ATLAS y súbelo para ver la lámina».
3. Entradas al visor: en **Cobalto/Thot**, en la ficha de la obra, una sección «MAPAS» (numerada en el acento, como las demás) con la miniatura de cada mapa; al tocarla se abre el visor. Lo mismo donde **Seshat** y **Jonsu** enseñan los escritos (su página Escritos).
4. Solo lectura: nadie más que ATLAS escribe en `familia/atlas`.

Comprueba que compila y que pasan las pruebas (añade alguna del filtro por obra y del visor sin `lam`). Después súbelo y saca versión de las apps como lo hagas normalmente.
