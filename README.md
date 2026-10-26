🁫 Dominó Pro — 1 vs 1

Juego de dominó doble-seis para Android y navegadores web. Juega contra una inteligencia artificial con estrategia, probabilidades y simulaciones de partidas.

🎮 Características

- 28 fichas únicas: conjunto completo de dominó doble-seis, desde "0-0" hasta "6-6".
- Un jugador contra la IA: 7 fichas para cada jugador.
- 14 fichas fuera de juego: modalidad sin robo de fichas.
- Tres niveles de dificultad: Fácil, Difícil y Experto.
- IA estratégica: evaluación de jugadas, simulaciones Monte Carlo y análisis de información conocida.
- Memoria de pases: utiliza los extremos de la mesa cuando un jugador no puede jugar para deducir posibles restricciones en su mano.
- Detección de victoria: reconoce cuándo un jugador se queda sin fichas.
- Partidas bloqueadas: compara los puntos restantes para determinar el resultado.
- Interfaz adaptable: diseñada para pantallas de móviles y ordenadores.
- Sin conexión a servicios de IA: la lógica del bot se ejecuta localmente.
- Preparado para Android: el proyecto web puede empaquetarse como APK utilizando Capacitor.

«La IA estima las mejores jugadas según la información disponible. No garantiza la victoria en todas las partidas.»

📂 Estructura del proyecto

domino-pro/
├── index.html
├── README.md
├── css/
│   └── game.css
├── js/
│   ├── engine.js
│   ├── ai.js
│   └── domino.js
└── tests/
    └── smoke-test.js

Descripción de archivos

Archivo| Función
"index.html"| Estructura de la interfaz del juego.
"css/game.css"| Estilos visuales, fichas y diseño adaptable.
"js/engine.js"| Reglas, estado de la partida y movimientos legales.
"js/ai.js"| Estrategia, estimación de probabilidades y simulaciones.
"js/domino.js"| Turnos, controles e interacción con la interfaz.
"tests/smoke-test.js"| Pruebas automáticas del motor y la IA.

📜 Reglas del juego

1. Se crea el conjunto completo de 28 fichas.
2. Cada jugador recibe 7 fichas aleatorias.
3. Las otras 14 fichas quedan fuera de la partida.
4. No se roban fichas durante el juego.
5. Cada ficha debe coincidir con uno de los extremos abiertos de la cadena.
6. Si un jugador no tiene una jugada legal, debe pasar.
7. Gana quien coloca todas sus fichas primero.
8. Si la partida queda bloqueada, gana quien tenga menos puntos en la mano.
9. Si ambos jugadores tienen los mismos puntos al bloquearse la partida, se declara empate.

La regla de salida debe coincidir con la implementación del motor: si se utiliza el doble más alto, el jugador que lo posee comienza con esa ficha. Si ninguna mano contiene dobles, se aplica el criterio alternativo definido en el código.

🧠 Inteligencia artificial

La IA utiliza varias técnicas complementarias:

Evaluación estratégica

Analiza las fichas disponibles, los números repetidos, los puntos restantes y las posibles continuaciones de cada jugada.

Conteo de fichas

Mantiene información sobre las fichas conocidas y las jugadas observadas para estimar qué fichas podrían permanecer ocultas.

Historial de pases

Registra los extremos de la mesa cuando un jugador pasa. En la modalidad sin robo, esto permite descartar manos rivales incompatibles con los pases observados.

Simulaciones Monte Carlo

Evalúa posibles repartos ocultos y continuaciones de la partida para estimar el resultado esperado de diferentes decisiones.

Limitación: las simulaciones son estimaciones estadísticas. Su calidad depende del modelo de manos ocultas, las reglas implementadas y el número de simulaciones realizadas.

🚀 Ejecutar el juego localmente

Puedes ejecutar el juego directamente en un navegador si los archivos no utilizan módulos que requieran un servidor local.

También puedes abrir el proyecto mediante un servidor HTTP local. Si tienes Python instalado, ejecuta desde la raíz:

python -m http.server 8000

Después abre:

http://localhost:8000

🧪 Ejecutar las pruebas

Necesitas tener "Node.js" (https://nodejs.org/) instalado.

Desde la raíz del proyecto, ejecuta:

node tests/smoke-test.js

Las pruebas deben comprobar, según los casos incluidos:

- Creación del conjunto de 28 fichas.
- Ausencia de fichas duplicadas.
- Legalidad de los movimientos.
- Reglas de los pases y bloqueos.
- Funcionamiento básico de la IA.
- Finalización correcta de las partidas simuladas.

Si una prueba falla, revisa el mensaje de error antes de publicar una nueva versión.

🌐 Publicar en GitHub Pages

1. Sube los archivos a la raíz de tu repositorio.
2. Abre Settings → Pages.
3. En la sección de compilación, selecciona Deploy from a branch.
4. Elige la rama "main" y la carpeta "/(root)".
5. Guarda los cambios.
6. Espera a que termine la publicación y abre la URL que proporciona GitHub Pages.

Asegúrate de que las rutas de CSS y JavaScript en "index.html" coincidan con la estructura del repositorio. GitHub Pages permite publicar la versión web, pero no genera por sí solo una APK.

📱 Preparar una APK para Android

El proyecto puede integrarse con "Capacitor" (https://capacitorjs.com/docs), que permite empaquetar una aplicación web como aplicación Android.

El proceso general es:

1. Preparar el proyecto npm.
2. Configurar la carpeta de archivos web, por ejemplo "www".
3. Instalar Capacitor y la plataforma Android.
4. Sincronizar los archivos web.
5. Abrir el proyecto Android con Android Studio.
6. Compilar y probar la APK en un dispositivo.

Consulta la documentación oficial de Capacitor para conocer los requisitos de desarrollo y compilación de Android.

🔒 Privacidad y funcionamiento

La lógica del juego puede ejecutarse localmente en el dispositivo. No se necesita una API de IA externa para calcular las jugadas.

Si se utiliza almacenamiento local para estadísticas o preferencias, esos datos permanecen en el navegador o dispositivo, salvo que una futura versión añada servicios externos.

🛠️ Próximas mejoras

- Animaciones de fichas y efectos de sonido.
- Fichas con puntos visuales tradicionales.
- Historial completo de partidas.
- Estadísticas de victorias y derrotas.
- Mejoras de accesibilidad.
- Optimización del algoritmo de búsqueda.
- Pruebas masivas para medir el rendimiento real de la IA.
- Generación automatizada de APK mediante GitHub Actions.

📄 Licencia

Este proyecto puede distribuirse bajo la licencia que el autor elija. Si deseas permitir modificaciones y redistribución, considera añadir una licencia como MIT mediante un archivo "LICENSE".

---

Dominó Pro — 1 vs 1

Un proyecto de dominó para mejorar la estrategia, la experiencia móvil y la inteligencia artificial mediante pruebas y simulaciones.
