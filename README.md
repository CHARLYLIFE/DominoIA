# Dominó Pro — 1 contra 1

Juego de dominó doble-seis para navegador y móvil: 28 fichas, 7 para cada jugador, 14 fuera y sin robar.

## Estructura de archivos

```text
index.html
css/game.css
js/engine.js
js/ai.js
js/domino.js
tests/smoke-test.js
README.md
```

## Probarlo en GitHub Pages

1. Sube todos estos archivos y carpetas a la raíz del repositorio, conservando exactamente los nombres y las mayúsculas/minúsculas.
2. En GitHub abre **Settings → Pages**.
3. En **Build and deployment**, selecciona **Deploy from a branch**, elige tu rama (normalmente `main`) y la carpeta `/ (root)`.
4. Guarda y abre la URL que GitHub Pages muestre. La versión web no necesita npm, servidor ni API externa.

También puedes abrir `index.html` directamente para una prueba rápida; un servidor local o GitHub Pages suele ser más fiable si el navegador restringe archivos locales.

## IA

`js/ai.js` usa determinización Monte Carlo: construye manos rivales compatibles con las fichas no vistas y el historial público de pases, y compara cada acción con partidas simuladas. Las simulaciones no consultan los valores de la mano real oculta del jugador. El selector ofrece Fácil, Difícil y Experto.

La IA no es literalmente invencible: las fichas ocultas y el azar impiden garantizar una victoria. La dificultad es una cantidad de simulaciones por acción, no una promesa estadística de tasa de victoria. El rendimiento depende del teléfono y del número de jugadas legales.

## Reglas implementadas

- Salida con el doble más alto entre las dos manos; si no hay dobles, la ficha de mayor suma. La ficha de salida es obligatoria.
- La mesa conserva una cadena lógica de izquierda a derecha; la interfaz serpentea las filas sin alterar esa cadena.
- Solo se puede pasar cuando no hay jugadas legales.
- Dos pases consecutivos bloquean la partida; gana quien conserve menos puntos, o hay empate.
- Las estadísticas se guardan en el almacenamiento local del navegador.

## Pruebas automáticas

Necesitas Node.js instalado. Desde la raíz del proyecto ejecuta:

```bash
node tests/smoke-test.js
```

No hay dependencias externas.

## APK más adelante

Este repositorio es primero una web estática. Cuando el juego esté validado puedes empaquetarlo con Capacitor, configurando `webDir` como la carpeta que contenga `index.html` (por ejemplo `www`). No copies los archivos en `www` a menos que ya hayas configurado Capacitor para usar esa carpeta.
