# ATLAS. — Cartografía fantástica 2D y 3D (Godot 4)

Godot 4.7.2-stable · GL Compatibility (OpenGL ES 3) · Android `arm64-v8a`.

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
