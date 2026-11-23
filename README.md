# ATLAS. — Cartografía fantástica 2D y 3D (Godot 4)

Godot 4.7.2-stable · GL Compatibility (OpenGL ES 3) · Android `arm64-v8a`.

## 0.5.0

- **Nombres con placa detrás**, en el estilo de la familia (rectángulos duros, papel y tinta):
  - Capitales: placa de tinta con letra de papel.
  - Resto de lugares: placa de papel con filete de tinta, y un halo de papel detrás del símbolo.
  - Reinos y regiones: banda de papel translúcida (rotada con el texto en las regiones).
  - En 3D, las mismas placas con una línea guía hasta el sitio.
- **Ciudades nuevas en 3D** con el [KayKit Medieval Hexagon Pack](https://github.com/KayKit-Game-Assets/KayKit-Medieval-Hexagon-Pack-1.0)
  (Kay Lousberg, CC0):
  - Castillo, iglesia, taberna, mercado, herrería, cuartel, molinos, pozo, aserradero, mina, torres, murallas con puerta, ruinas,
    tiendas de campaña, banderas, cajas y barriles.
  - Cada lugar lleva los colores de su reino (el más parecido de los cuatro del pack) y cada tipo tiene su composición. La capital
    tiene castillo, iglesia, mercado, taberna, herrería, cuartel, diez casas, pozo y muralla con puerta y torres; el castillo,
    muralla y torre de catapulta; la aldea, casas y molino; la batalla, tiendas, banderas y una catapulta…
  - Cada edificio se asienta en la altura del terreno de su sitio, sin pisarse con los demás, y alrededor de cada lugar no hay
    árboles.
- **Más variedad de vegetación**:
  - Árboles sueltos de KayKit.
  - Del [Stylized Nature MegaKit](https://opengameart.org/content/stylized-nature-megakit) de Quaternius (CC0): helechos,
    flores, hierba alta, tréboles, arbustos con flor, setas, rocas y árboles protagonistas (retorcidos en el bosque encantado,
    muertos en el bosque muerto, pinos y árboles grandes repartidos). Estos son 20-50 veces más pesados que los de Kenney y van
    con tope por modelo. Sus texturas se han reducido a 512 px, sin mapas de normales, y con los bordes transparentes
    rellenados para que no salgan halos blancos.
- Arreglos en 3D:
  - Árboles y edificios ya no reciben la sombra direccional, que a esa escala dejaba motas.
  - Fuera `tree_default_fall` de Kenney, que tiene caras solapadas (rayado al alejarse).
  - Fuera los campos de trigo hexagonales, que parecían monedas.
  - Las copas oscuras salen más claras.
- El APK crece unos 18 MB por los modelos nuevos. Licencias en `assets/*/License.txt`.


## 0.4.0

- **Bosques de verdad en 2D**: cada bosque con su árbol, a tinta y con luz y sombra en la copa (del lado del sol):
  - caducifolio de varios lóbulos, conífera de dos pisos, selva (con alguna palmera), palmera, acacia, abedul (tronco blanco),
    arbusto, árbol en flor, manglar (con raíces), seta gigante, cristales y árbol seco;
  - bosque mixto (coníferas y caducifolios) y bosque otoñal (cada árbol entre ocre y rojo).
  - Los árboles se solapan como en un mapa dibujado (los de delante tapan a los de detrás) y hay muchos más.
  - El número se adapta al zoom: de lejos se ve la masa del bosque y, al acercarte, aparecen más árboles, más pequeños.
- **29 biomas** (cuatro nuevos: bosque otoñal, bosque de abedules, palmeral y bosque mixto).
- **Árboles y edificios en el relieve 3D**, con modelos CC0 de Kenney ([Nature Kit](https://kenney.nl/assets/nature-kit),
  [Castle Kit](https://kenney.nl/assets/castle-kit) y [Fantasy Town Kit](https://kenney.nl/assets/fantasy-town-kit), en
  `assets/kenney/`, con su `License.txt`):
  - Pinos, robles, palmeras, abedules, arbustos, cactus, setas y rocas según el bioma. Las hojas llevan el color del bioma y del
    estilo del mapa (no el turquesa del kit).
  - Castillos y torres del Castle Kit; cascos de ciudades, pueblos y aldeas con casas; templos, puertos, ruinas, minas y batallas.
  - Densidad: sin árboles, pocos, normal o muchos (hasta 16.000). Un `MultiMeshInstance3D` por modelo.
- **Nombres en 3D**: lugares, regiones y reinos rotulados encima del relieve, siguiendo a la cámara.
- **Regiones con nombre** (sección nueva): «+ Nueva región», tipo, nombre (p. ej., «Bosque del Cosmos») y pintas dónde está.
  - 21 tipos: bosque, selva, montes, sierra, colinas, desierto, llanura, pantano, valle, meseta, tierras, isla, península, cabo,
    mar, océano, lago, bahía, golfo, estrecho y río.
  - Al pintarla puede pintar también su bioma: un bosque pinta bosque de serie; se elige cualquiera o ninguno.
  - El nombre se rotula en cursiva a lo largo de la zona (azul si es agua) y el contorno discontinuo solo se ve al editar.
  - Un trazo que pinta región y bioma se deshace de una vez.


## 0.3.0

Cinco secciones en la banda de tinta, cada una con su panel: **Relieve · Biomas · Político · Lugares · Mundo** (con la cuenta de
reinos y lugares al lado, como las hermanas).

- **Relieve**: además de Tierra, Cordillera, Mar y Río,
  - **Valle**: baja la tierra hasta el río; el cauce queda bajo el agua y las orillas, secas justo por encima, suben suaves hasta
    el terreno. Repasar ensancha la llanura del río sin inundarla.
  - **Cañón**: cauce hondo con paredes casi verticales.
  - **Suavizar**: redondea costas, cauces y picos.
  - **Acantilado**: rellanos planos y paredes (eliges cuántos escalones).
  - **Meseta**: aplana a la altura del punto donde empiezas.
  - Cada herramienta dice qué hace debajo de los botones.
- **Biomas** (25, cada uno con su color y su símbolo): pradera, bosque, bosque viejo, taiga, selva, sabana, estepa, desierto,
  desierto de roca, tierras rojas, tundra, glaciar, pantano, manglar, brezal, monte bajo, cultivos, volcánico, salar, bosque en
  flor, bosque encantado, bosque de cristal, bosque de hongos, bosque muerto y tierras malditas. Se dibujan a tinta: árboles,
  coníferas, selva, acacias, hierba, dunas, pantano, grietas, parcelas, cristales, hongos, hielo y árboles secos. Las lindes
  son orgánicas y los colores cambian con el estilo (Editorial, Pergamino, Fantasía). «Automático» los borra.
- **Político**: reinos con nombre (inventado, o el tuyo), tipo (reino, imperio, república, ducado, condado, principado, califato,
  teocracia, clanes, liga, tribus, marca) y color heráldico. Pintas su territorio; las fronteras salen solas donde se tocan, con
  una banda del color del reino. El nombre («IMPERIO DE KARTHMOR») va en el centro del territorio sin tapar las capitales.
  Borrar un reino borra su territorio (y se puede deshacer).
- **Lugares**: capital, ciudad, pueblo, aldea, castillo, puerto, templo, torre, ruinas, mina y batalla, cada uno con su símbolo y
  un nombre inventado que se puede cambiar. Se tocan para editarlos y se arrastran para moverlos. Los nombres pequeños aparecen al
  acercarte, como en un atlas.
- **Mundo**:
  - Mundo nuevo de 1.024, 2.048, 3.072 o 4.096 px de lado mayor, cuadrado, 3:2, apaisado 2:1 o vertical 2:3, empezando con un
    continente o con océano vacío.
  - **Ampliar lienzo** hacia cualquier lado (la mitad más de mar, hasta 4.096 px).
  - Mar y sol, estilo, cifras y calidad.
  - Lo que borra trabajo pide un segundo toque.
- **Calidad**:
  - El mapa de 2.048 px por defecto (antes 1.024).
  - **Alta precisión**: el relieve se guarda en coma flotante de 16 bits (`use_hdr_2d` en el SubViewport), sin escalones al
    ampliar.
  - Si un arranque con alta precisión no llega a completarse (por ejemplo, porque la GPU se cuelga), el siguiente vuelve solo a
    8 bits y lo avisa en Mundo → Calidad. Se puede volver a probar.
  - Zoom hasta ×16.
- **Historial único** para todo (trazos de relieve, biomas y reinos, reinos y lugares). Guarda solo la zona tocada de cada trazo
  (40 pasos, hasta 96 MB).
- **3D**: biomas y reinos también en el relieve, y mapas no cuadrados.

### Arreglos

- Si el lápiz mandaba varios movimientos en el mismo fotograma, solo se pintaba el último y quedaban huecos. Ahora se pintan
  todos.
- El último trozo de cada trazo se perdía (se escondía el pincel antes de dibujarse).
- Al terminar un trazo ya no se lee el mapa entero de la GPU: solo la zona tocada.


## 0.2.0

- **La cara de la familia («C»)**: las mismas letras que las hermanas (Bricolage Grotesque ExtraBold para el nombre, los títulos y las
  cifras; Inter para lo demás, ambas OFL, en `fonts/`), papel `#F8F0D4` y tinta negra (los de `Familia-Revista`, noviembre de 2026).
  Cabecera de papel con «ATLAS.» y su punto del acento, enlaces en versalitas (+ NUEVO MUNDO · DESHACER · REHACER · ENCUADRAR), el número
  del mundo y la fecha («MUNDO Nº 0042 · 23.11.26») y «VER RELIEVE 3D →» en un bloque del acento. Debajo, la banda de tinta con la
  herramienta elegida en un bloque del acento.
- **Panel a la derecha** con la rejilla dura de la familia: bloques de cifras con borde de 3 px (el primero en el acento, el tercero en el
  azul de la familia), títulos en grotesca y deslizadores con tirador cuadrado.
  - **Pincel**: tamaño y fuerza (la fuerza antes no se podía cambiar).
  - **Relieve** (en 3D): exageración vertical.
  - **Mar y sol**: nivel del mar en vivo (2D y 3D) y de dónde viene la luz (NO, SE…), que mueve el sombreado del mapa y el sol del 3D.
  - **Estilo del mapa**: Editorial · Pergamino · Fantasía, como filtros (antes era un botón que rotaba sin enseñar las opciones).
  - **Este mundo**: % de tierra, % de mar y trazos. Se cuentan al terminar cada trazo sobre una copia de 128 × 128 del heightmap.
- **Rehacer**, y Deshacer / Rehacer se apagan cuando no hay nada que hacer.
- **El mapa en un paspartú** de papel con filete de tinta, la flecha del norte, la etiqueta de zoom y el **anillo del pincel** mientras
  dibujas (con «+» si suma y «−» si talla).
- Cada «Nuevo mundo» tiene su número (la semilla) y deja deshacer, rehacer y trazos a cero.

### Arreglos

- **El trazo caía por encima del dedo.** Las posiciones de `gui_input` son locales al `Workspace` y se trataban como globales: pintaba
  desplazado la altura de las barras (al tocar el centro del mapa pintaba en `y = 321` de 1024 en vez de `512`). Lo mismo con el centro
  del pellizco.
- **El antipalma no funcionaba en Android.** Godot emula ratón desde el táctil (`emulate_mouse_from_touch`, activo por defecto) y esos
  clics empezaban a pintar al instante y seguían pintando durante el pellizco de dos dedos. Ahora se ignoran los eventos con
  `device == InputEvent.DEVICE_ID_EMULATION`; el trazo empieza tras los 40 ms donde se apoyó el lápiz.
- **El 3D salía quemado a blanco.** Tres causas, medidas una a una: tonemap Filmic con `white = 1` (aclaraba los medios: un gris 0,5 sin luz
  salía a 0,64), difuso Burley (a vista rasante multiplica la luz) y la luz con sombra, que en Compatibility se pinta en otra pasada en
  espacio sRGB y hay que bajarle la energía (lo dice la documentación de Godot 4.7, «3D lights and shadows»). Ahora: tonemap lineal,
  Lambert, especular bajo, luz 0,4 y ambiente 0,25, con las sombras puestas. Fondo del 3D del color de la mesa.
- **Costa y curvas de nivel con ruido**: el heightmap es de 8 bits; el mapa usa ahora la altura suavizada (núcleo 1-2-1 con las mismas 9
  muestras del Sobel) para la costa, los colores y las curvas.
- **Mundo inicial menos nevado**: las cordilleras solo donde una máscara de baja frecuencia lo permite (cadenas, no un macizo blanco).

## Cómo actualizar el APK en GitHub

1. Haz push a `main` (o lanza el flujo a mano).
2. **Actions → Compilar ATLAS. Android APK** y descarga `ATLAS-android-apk`.
3. Desinstala la versión anterior en Android e instala el nuevo `.apk`.
