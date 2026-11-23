# ATLAS. — Cartografía Fantástica 2D y 3D (Godot 4)

Proyecto listo para compilar automáticamente en Android mediante **GitHub Actions** sin instalar nada en tu ordenador.

## Cómo conseguir el `.apk` en 3 pasos

1. Crea un repositorio nuevo en GitHub (puede ser **privado**).
2. Sube el contenido de esta carpeta a la rama `main` (asegúrate de incluir la carpeta oculta `.github/`).
3. Entra en la pestaña **Actions** de tu repositorio en GitHub:
   - Se lanzará automáticamente el flujo **Compilar ATLAS. Android APK** (tarda ~2-3 minutos).
   - Al terminar en verde, haz clic en la ejecución y descarga el archivo **ATLAS-android-apk** en la sección **Artifacts**.
   - Descomprime e instala `ATLAS-android.apk` en tu Android.

## Controles en Android (optimizados para lápiz capacitivo)
- **1 toque / lápiz:** Pintar relieve (con búfer antipalma de 40 ms y presión simulada por velocidad).
- **2 dedos:** Zoom y desplazamiento del lienzo 2D.
- **Modo 3D:** 1 dedo para orbitar la cámara, 2 dedos para acercar/alejar.
