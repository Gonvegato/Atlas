# ATLAS. — Cartografía fantástica 2D y 3D (Godot 4)

Godot 4.7.2-stable · GL Compatibility (OpenGL ES 3) · Android `arm64-v8a`.

## 0.12.3

- **Arreglo: rayas rectas (surcos) en el relieve generado, en el móvil.** El ruido del terreno elegía el valor de cada
  punto de la rejilla con un hash de coma flotante (`fract(p * 123.34 …)`); el compilador de la GPU del móvil reordena
  esas cuentas y el mismo punto salía con valores distintos según la casilla desde la que se calculaba: escalones
  rectos en los bordes de casilla, en varias direcciones (cada octava va girada), guardados en el mapa (se veían en 2D
  y en 3D). Reproducido simulando ese reordenamiento. Ahora el azar por casilla se calcula con enteros de 32 bits
  (`shaders/ruido.gdshaderinc`), exacto en cualquier GPU, en el generador, el relleno por contorno, los pinceles de
  relieve y biomas, y el ruido del 2D y del 3D. Los mundos generados a partir de ahora son distintos para la misma
  semilla.
- **Mundo → QUITAR RAYAS DEL RELIEVE**, para los mapas ya hechos: busca escalones bruscos (un salto entre dos píxeles
  mucho mayor que la pendiente a los lados) a varias escalas y suaviza solo ahí; se puede deshacer.
- (0.12.2 quitó también los artefactos de las sombras, que no eran la causa de las rayas.)

## 0.12.2

- **Arreglo: cortes rectos y triángulos en el 3D (en el móvil).** Eran artefactos de las sombras del sol: venían con la
  configuración de serie (cuatro tramos en cascada hasta 100 unidades para un mundo de 12, mapa de 16 bits y 2.048 px en
  móvil), y en la GPU del móvil salían como bandas y triángulos en el agua y líneas rectas cruzando el relieve. Ahora hay
  un único mapa de sombras ajustado a lo que se ve (sin cortes entre tramos), de 4.096 px y 32 bits, con más margen y un
  poco de suavizado. Y botón CON SOMBRAS / SIN SOMBRAS en Relieve 3D (se recuerda en el dispositivo).

## 0.12.1

- **La firma de la familia la pone Familia-Revista.** ATLAS compila siempre la versión final (no la de depuración) y la
  publica en `releases/latest/download/ATLAS.apk` (con `ATLAS.json`); Familia-Revista, que guarda la clave, la vuelve a
  firmar y la sube a Drive. Ver `docs/FIRMA-Y-FAMILIA.md`.

## 0.12.0

- **Caminos dibujados, con cuatro texturas.** En Lugares → CAMINOS, el lápiz dibuja un camino de un tirón: SENDERO
  (tierra; a trazos en el mapa), CAMINO (grava; una línea), CALZADA (adoquín; dos líneas con el papel dentro) y CALZADA
  IMPERIAL (losas con juntas; ancha, con su piedra y puntos en medio). En 3D cada uno pinta su suelo y no salen árboles
  encima. Tocar un camino lo elige para cambiarle el tipo o quitarlo; todo se deshace y se guarda con el mapa (y viaja a
  la cuenta). Las calles de las ciudades van en tierra y las plazas en adoquín, con la misma máscara.
- **Los caminos acortan los viajes:** cada paso por un camino cuesta menos (sendero ×0,85, camino ×0,75, calzada ×0,65,
  imperial ×0,55), así que las rutas de Viajes que van por ellos salen más rápidas.

## 0.11.0

- **Ciudades por tamaño, generadas solas.** Aldeas, pueblos, ciudades, capitales y puertos tienen un deslizador de
  TAMAÑO (1-10) en Lugares: de una aldea de 3 casas a una capital de casi 300 edificios. El trazado se genera según tipo y
  tamaño: calles que salen de la plaza (más cuanto más grande), rondas en anillo, casas en fila mirando a la calle, los
  edificios especiales (mercado, taberna, herrería, iglesia, cuartel…) junto a la plaza, muralla irregular con una
  puerta en cada calle y torres (capitales desde el 3, ciudades desde el 4, pueblos muy grandes), arrabales fuera de la
  muralla y molinos y aserraderos en las afueras. Puedes hacer un pueblo enorme o una capital pequeña. Lo mismo en 2D
  (al acercarte se ve la planta: calles, plaza, manzanas y muralla) y en 3D (con las calles de tierra y la plaza
  empedrada pintadas en el suelo). Lo que caería en el agua no se construye. En 3D, cada modelo va en una MultiMesh: una
  capital grande se monta en menos de 0,1 s.
- **Fundido entre biomas.** Los biomas se mezclan en la linde: los colores en 2D, el suelo texturizado en 3D (hasta 7
  muestras, cada bioma distinto se texturiza una vez) y los árboles (en el borde el bosque se aclara y las especies de los
  dos lados se mezclan, en 2D y en 3D). Deslizador FUNDIDO ENTRE BIOMAS en Biomas (se guarda con el mapa; a cero, linde
  nítida).
- **Valles, cañones y acantilados sin escalones.** El cañón ya no talla cuatro terrazas: fondo con río, talud, pared casi
  vertical de una vez y cornisa redondeada, con barrancos (el pie de la pared entra y sale). El valle sube en curva suave
  sin arista ni al pie ni en el borde, y su anchura ondula. El ACANTILADO ya no hace rellanos: donde hay desnivel recoge
  la ladera en una sola pared (deslizador PARED VERTICAL), decidida con la altura suavizada para no abrir hoyos. En 3D, el
  sombreado por caras (low-poly) queda solo para las cumbres (en laderas suaves sus filas parecían peldaños), los estratos
  de las paredes son bandas suaves y la malla tiene más vértices (uno cada ~3 px del mapa, hasta 900 por lado).
- **Firma con el secreto de la familia.** El workflow acepta el mismo secreto `FIRMA_B64` que Familia-Revista (ver
  `docs/FIRMA-Y-FAMILIA.md`). Mensaje para el Claude de Familia-Revista en `docs/MENSAJE-PARA-FAMILIA-REVISTA.md`.

## 0.10.1

- **Arreglo: los botones del panel no respondían en la tablet** (en vertical y en horizontal). El filtro táctil del panel
  (desde la 0.7.0) reenviaba el toque en unidades de la interfaz y no en píxeles de la pantalla; con escala 1,5 o 2 caía en
  otro sitio. Ahora se comprueba con pantallas escaladas (1200×1920 y 2560×1600): botones, desplazamiento y deslizadores.


## 0.10.0

- **Los mapas de las novelas, en la cuenta de la familia**: Mundo → «Cuenta de la familia».
  - Se entra con el usuario y la contraseña de Polaris y Nova (una vez; la sesión se guarda cifrada en el dispositivo).
  - Cada mapa se enlaza con una de tus novelas de Nova («Novela de este mapa» y «Para la novela» al crear uno) y, si aún tenía
    su nombre de serie, toma el de la novela («Mapa de …»).
  - Al guardar, unos segundos después, el mapa sube solo: el archivo `.atlas` a tu Google Drive (`Familia/atlas/`, con las
    claves de Drive que ya pusiste en Polaris), la miniatura a `familia_img` y la ficha a `familia/atlas/<mapa>` con el
    contrato de la familia (campo a campo con `m/<campo>`, `u` y `familia_sello/atlas`; borrar es poner `b`).
  - «Mapas en la cuenta»: los mapas de tus otros dispositivos, con su novela; se bajan y se abren desde ahí.
  - Probado contra el emulador oficial de Firebase con las reglas de `Familia-Revista/nube/reglas.json` y un Google Drive de
    pruebas.
- **Entrar sola desde Polaris**, pendiente de dos pasos que están en `docs/FIRMA-Y-FAMILIA.md`: la clave de la familia como
  secretos del repositorio y el parche de `Familia-Revista` (`docs/familia-revista-atlas.patch`).


## 0.9.0

- **Contorno que se rellena**: con «Contorno» (en el panel o en la barra rápida sobre el mapa) dibujas el borde con el lápiz
  y al soltar se rellena lo de dentro.
  - Vale para tierra, mar, lago, pantano, biomas, regiones y reinos.
  - La costa pasa exactamente por donde dibujas (comprobado: ±1 px) y se suaviza a lo largo del trazo.
  - Si el contorno se cruza consigo mismo, se rellena igual.
  - Se deshace de una vez.
- **Dibujar el mundo a mano**: Mundo → Nuevo mundo → «Dibujar a mano». Empieza con un mar vacío, la herramienta Tierra en
  modo Contorno, y el lienzo **crece solo** (hasta 8.192 px) cuando dibujas tierra junto a un borde. Se puede apagar.
- **Imperios que contienen reinos**: «Pertenece a» en cada reino (sin ciclos).
  - La frontera exterior lleva el color del imperio y las de dentro, una línea fina a trazos.
  - El nombre del imperio va más grande sobre todo su territorio.
  - La lista de reinos se ve en árbol.
  - Las relaciones de un imperio valen para sus reinos.
- **Lagos y pantanos** como herramientas de relieve (a pincel o por contorno). El lago deja el fondo bajo el agua con orillas
  suaves; el pantano, la tierra a ras del agua con charcas, y pinta el bioma pantano.
- Interfaz:
  - Barra rápida Pincel/Contorno sobre el mapa y avisos breves (lienzo ampliado, errores de guardado…).
  - Botones de al menos 40 px de alto.
  - El panel ya no se desborda con nombres largos.
- **Firma fija**: si el repositorio tiene los secretos `ATLAS_KEYSTORE_BASE64`, `ATLAS_KEYSTORE_PASSWORD`,
  `ATLAS_KEY_ALIAS` y (si es distinta) `ATLAS_KEY_PASSWORD`, el APK sale en release firmado con esa clave. Si no, con una de
  depuración nueva, como hasta ahora. El paquete pasa a ser `com.gonzalo.atlas`, como el resto de la familia.
- **Arreglo importante** (ya venía en la 0.8.0): tras abrir un mapa guardado, ampliar el lienzo o cambiar la precisión, lo que
  se pintaba se guardaba con valores erróneos (un lienzo nuevo que no se había borrado nunca aplicaba la conversión sRGB).


## 0.8.0

- **Se guarda solo**: unos segundos después de cada cambio, al salir de la app y antes de abrir otro mapa o crear uno
  nuevo. Al abrir ATLAS vuelve el último mapa.
  - Cada mapa va en su carpeta de `user://mapas/` (relieve y capas comprimidas con zstd, en otro hilo para no congelar la
    app; se escribe en `.tmp` y se cambia al final, así que un cierre a medias no rompe el mapa).
  - Mundo → «Este mapa» (nombre, guardar ahora, duplicar) y «Mis mapas» (abrir y borrar, con miniatura del relieve).
- **Relaciones entre reinos, en sus fronteras comunes**: alianza (dorada), comercio (verde azulado a trazos), tregua (blanca a
  trazos), guerra (rayada en rojo y negro) y vasallaje (morada). Se eligen en el editor de cada reino, con leyenda, y se deshacen.
- **Zonas en conflicto**: cuatro tipos de región nuevos (zona en conflicto, frente, asedio, tierra de nadie). Llevan rayado rojo,
  borde rojo siempre visible y el nombre en rojo con espadas cruzadas.
- **Viajes** (sección nueva):
  - Tocas etapas en el mapa (los lugares atraen la ruta) y da distancia y tiempo a pie, a caballo, en carro o en barco.
  - Cuenta las cuestas, la alta montaña y el agua (y al revés en barco).
  - Calibrado: marcas un viaje que conozcas y dices cuánto dura («3 días a pie»); opcionalmente, cuántos km mide.
  - Las rutas se guardan con el mapa.
- **Modo vertical**: la app gira con el dispositivo. En vertical el panel va abajo a lo ancho, la interfaz se calcula sobre
  800×1280 (el texto mide lo mismo) y la cabecera se compacta.
- **Mapas más grandes**: nuevos tamaños «Gigante» (6.144 px) y «Colosal» (8.192 px), y ampliar lienzo hasta 8.192 px. Medido
  aquí, un mapa de 8.192 tarda ~6 s en crearse, ~2,5 s en guardarse y ocupa ~36 MB.


## 0.7.0

- El 3D se dibuja a la resolución real de la pantalla (antes a la mitad y estirado en tablets): era el «titileo» al alejarse.
  MSAA 4× con protector (si una entrada en 3D con MSAA se cuelga, el siguiente arranque lo apaga), filtrado anisótropo y malla
  de hasta 768 divisiones.
- Mapa 2D sin parpadeo al alejarse: el sombreado mide la pendiente a la escala del zoom y las curvas de nivel se desvanecen
  cuando se juntan.
- Rótulos 2D que se adaptan al zoom, con prioridad y sin solapes (cada nombre prueba cuatro posiciones). Los reinos llevan una
  pestaña de su color, también en 3D.
- Panel lateral táctil: el arrastre vertical desplaza siempre (también empezando sobre un botón) y ya no mueve los deslizadores.
- Cañón con cauce de agua, fondo seco y paredes escalonadas; estratos de colores en las paredes 3D y hachures a tinta en 2D.
- 20 texturas de suelo a 1.024 px (4 nuevas de Poly Haven: hierba alpina, hierba rala, guijarros y pared de roca).

## 0.6.0

- **Suelos con textura en el 3D**: 16 texturas CC0 de [Poly Haven](https://polyhaven.com) en un `Texture2DArray` (comprimido
  en GPU: ETC2 en Android), en `assets/terrain/`:
  - hierba, hierba seca, hojarasca, agujas de pino, suelo de bosque, arena, roca con musgo, nieve, barro, tierra roja, salar
    agrietado, suelo volcánico, tierra de labor, grava y roca.
  - Cada bioma tiene su suelo y un segundo que se mezcla con ruido; arena en las orillas y lindes irregulares entre biomas.
  - Cada textura se muestrea a dos escalas para que no se note la repetición.
  - Selector «Suelo: texturas / liso» en el panel 3D, por si el móvil no da para más.
- **Fantasía en el 3D**, siempre con colores vivos aunque el 2D vaya en Editorial o Pergamino: agua turquesa y azul hondo, hierba
  verde, más saturación y contraste en el suelo y las hojas con los colores puros del bioma.
- **Montañas low-poly**, como los modelos:
  - Sombreado por caras (sin las vetas de antes).
  - Altura suavizada en el vértice para que las crestas no tiemblen; malla de 300 divisiones.
  - Roca cálida con paredes más oscuras y casquetes de nieve de borde nítido.
- **Nombres en 3D sin amontonarse**: prioridad (capitales, reinos, regiones, lugares) y fuera los que se solapan. De lejos se ven
  los importantes y al acercarte, todos.


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
