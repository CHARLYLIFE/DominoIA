# ZEUS AI 1.2 — Informe de integración y validación

## Alcance

Esta entrega es un parche sobre el proyecto ZEUS VISION del ZIP auditado. No crea otra aplicación ni sustituye el motor actual. El modelo neuronal, el reconocimiento visual original y el entrenador se conservan.

## Archivos existentes modificados

1. `app/build.gradle`
   - Versión de aplicación `1.2.0` / `versionCode 4`.
   - Añade el OCR latino empaquetado `com.google.mlkit:text-recognition:16.0.1`.
2. `app/src/main/java/com/zeusvision/app/DominoBot.kt`
   - Conserva IS-MCTS, UCT/PUCT, la política/value neuronal, heurísticas y API previas.
   - Consume el estado temporal común y su historial de pases/movimientos.
   - Rechaza sugerencias con turno no confirmado y nunca sugiere cuando el rival tiene el turno.
   - Permite la salida manual `SALGO YO` sobre mesa vacía cuando el indicador está desconocido; respeta una señal rival explícita y suficientemente confiable.
   - Integra la inferencia probabilística y deriva al solver exacto solo cuando la mano rival queda deducida de forma única; con más de una distribución posible permanece IS-MCTS.
   - Añade cancelación de búsquedas obsoletas.
3. `app/src/main/java/com/zeusvision/app/CaptureService.kt`
   - Panel de 140 dp, minimizable y arrastrable.
   - Procesamiento en un executor de fondo.
   - OCR por captura, control de captura pendiente y número de generación para no presentar resultados antiguos.
   - Cierra imágenes/bitmaps y recursos al detener el servicio.
   - Conserva los controles de salida y sugerencia automática.

## Archivos nuevos

Todos los Kotlin se colocan en `app/src/main/java/com/zeusvision/app/`:

- `DominoGameState.kt` — estado común inmutable, confianza global y confianza independiente del turno.
- `DominoTemporalTracker.kt` — estabilización temporal, firma espacial cuantizada, reconstrucción de pases/jugadas, OCR de mensajes y protección ante contradicciones.
- `DominoOpponentInference.kt` — enumeración de manos ocultas compatibles, filtros por pases/jugadas observadas, probabilidades por ficha, ponderación conductual y caché LRU de dos estados.
- `DominoExactSolver.kt` — minimax con poda alfa-beta, memoización y tabla de transposición. Solo se activa si queda exactamente una mano rival compatible. Con varias manos ocultas devuelve `null` para que IS-MCTS continúe; así evita la fuga de información/strategy fusion de resolver cada reparto como si el jugador conociera la mano rival.
- `DominoPassTextRecognizer.kt` — OCR latino offline con modelo incluido en la APK.

Pruebas auxiliares en `training/`:

- `zeus12_core_smoke.kt`
- `run_zeus12_core_smoke.sh`
- `android-stubs/Bitmap.kt` (stub solo para ejecutar pruebas JVM; no entra en la APK).

## Cómo trabaja la integración

1. La captura produce una lectura de `DominoVision`.
2. El OCR extrae texto y el rastreador temporal exige estabilidad de capturas consecutivas, incluyendo la posición de las fichas de mesa cuantizada a 12 px.
3. Si mano/mesa son inconsistentes o una transición contradice las fichas observadas, el estado queda en espera. Una contradicción persistente obliga a establecer una nueva línea base y descartar el historial dudoso.
4. `DominoGameState` entrega mano, mesa, extremos, turno, líder e historial al motor. La confianza de turno debe alcanzar 65/100, excepto la salida manual con mesa vacía.
5. `DominoOpponentInference` comprueba la partición completa de las 28 fichas y enumera todas las combinaciones compatibles cuando el espacio es manejable. Una contradicción de pase o una ficha imposible hace que la distribución se rechace.
6. `DominoBot` alimenta las determinaciones ponderadas al IS-MCTS original. POLICY/VALUE se mantienen en su formato binario actual.
7. Si quedan como máximo 8 fichas activas y la inferencia deja exactamente una mano rival posible, el solver minimax exacto tiene prioridad y un presupuesto predeterminado de 180 ms. Si quedan dos o más manos posibles, se agota el tiempo o no es elegible, el motor vuelve a IS-MCTS. No se etiqueta como información perfecta una posición con fichas rivales aún desconocidas.
8. Una captura posterior invalida la generación de búsqueda anterior. El servicio no publica su sugerencia.

## Reglas de pase y OCR

- `Tu oponente debe pasar` se interpreta como turno del rival, no del usuario.
- `Debes pasar` se interpreta como turno del usuario.
- El texto ambiguo no reemplaza al indicador visual.
- Un pase solo se registra como evento cuando la transición de turno y la ausencia de cambios en fichas/extremos permiten confirmarlo.
- El pase rival añade una restricción en los extremos de ese momento. No se infiere una ausencia permanente de números sin reconstruir la mano histórica y las jugadas posteriores.

## Archivos que permanecen intactos

- `DominoVision_0.9_RECONOCIMIENTO.kt` — no se reemplaza ni se elimina ningún detector actual.
- `DominoNeuralModel.kt` — formato de inferencia y API sin cambios.
- `app/src/main/assets/zeus_domino_policy_value_v1.bin` — pesos originales sin cambios.
- `training/zeus_train.py`, `training/requirements.txt`, checkpoints y métricas — sin cambios.
- `MainActivity.kt`, `AndroidManifest.xml`, `styles.xml`, `build.gradle` raíz, `settings.gradle` y `gradle.properties` — sin cambios.

El vector neuronal sigue siendo de 89 entradas (`89 → 128 → 84` POLICY y 4 VALUE). No se reentrena ni se altera la puntuación. El marcador acumulado todavía no se extrae de la pantalla: los campos de puntuación del estado común permanecen nulos hasta que exista un reconocimiento validado.

## Validaciones ejecutadas realmente

### Compilación Kotlin aislada

Compilación correcta de estos componentes en JVM con un stub mínimo de `android.graphics.Bitmap`:

- `DominoGameState.kt`
- `DominoTemporalTracker.kt`
- `DominoOpponentInference.kt`
- `DominoExactSolver.kt`
- `DominoNeuralModel.kt`
- `DominoVision_0.9_RECONOCIMIENTO.kt`
- `DominoBot.kt`

Esto no es una compilación de la APK ni valida las clases Android de `CaptureService` o el enlace real de ML Kit.

### Pruebas de núcleo

`training/run_zeus12_core_smoke.sh` terminó con:

`ZEUS AI 1.2 CORE TESTS: PASS (15/15 escenarios principales)`

Casos cubiertos: salida usuario/rival; turno normal usuario/rival; pase rival/usuario y dos pases; cambio de extremos; animación estabilizada; lectura contradictoria; tranca; finales de 2 y 4 fichas; particiones y restricciones imposibles; reinicio de ronda/aplicación; OCR de los dos mensajes de pase; confianza de turno; integración del historial temporal en el motor.

Resultados de muestras controladas:

- Reparto inicial: 116.280 manos posibles.
- Pase en extremos 0–0 con la mano propia fijada: 3.432 manos compatibles.
- En el conjunto de prueba, el solver rechaza finales con 15 o 120 manos rivales compatibles y no intenta convertirlos en información perfecta.
- Con una única mano rival compatible, el solver resolvió el final de 2 fichas en 1 nodo, el de 4 fichas en 7 nodos y una tranca en 4 nodos.
- La inferencia continúa enumerando las 116.280 manos iniciales y las 3.432 compatibles tras el pase 0–0 del escenario de prueba; el IS-MCTS recibe la incertidumbre restante.
- Partición con bit 28 inexistente: rechazada.
- Historial que incluye pase 0–0 y posterior jugada 0–1: rechazado como imposible.

### Entrenador Python

`python3 training/zeus_train.py --self-test` terminó correctamente: 25 encuentros completos y acciones legales, con el vector actual de 89 entradas.

## Validaciones que NO se han ejecutado

- No se ha compilado `assembleDebug` ni la APK completa en este entorno: el proyecto original no incluye Gradle Wrapper y aquí no están instalados Gradle ni Android SDK.
- No se ha ejecutado el OCR sobre una captura real dentro de Android.
- No se ha probado el overlay en el Samsung ni en otro dispositivo.
- No se ha realizado un torneo comparativo de ZEUS 1.2 contra ZEUS anterior/humanos, ni se han medido victorias, latencia o RAM reales en dispositivo.
- No se ha entrenado otra red ni se afirma que el modelo sea superior o imbatible.

## Instalación

1. Descarga el ZIP de parche ZEUS AI 1.2.
2. Descomprime su contenido directamente sobre la raíz del repositorio Android, conservando las carpetas. Acepta reemplazar los cuatro archivos existentes que aparecen en el ZIP (`app/build.gradle`, `CaptureService.kt`, `DominoBot.kt` y el workflow de GitHub Actions).
3. Comprueba que los cinco Kotlin nuevos quedaron en `app/src/main/java/com/zeusvision/app/`.
4. Sube los cambios a la rama `main` o `master` (o inicia manualmente el workflow `Build ZEUS AI 1.2 APK`).
5. En GitHub abre **Actions → Build ZEUS AI 1.2 APK** y descarga el artefacto `ZEUS-AI-1.2-APK` cuando termine correctamente.

Para probar el núcleo en un PC con `kotlinc` y Java instalados, desde la raíz del repositorio ejecuta:

```bash
bash training/run_zeus12_core_smoke.sh
```

La carpeta `training/android-stubs/` es solo para esa prueba JVM; no se copia a fuentes Android ni altera la APK.
