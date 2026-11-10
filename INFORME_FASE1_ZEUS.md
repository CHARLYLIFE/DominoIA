# ZEUS AI — Integración de fase 1

## Alcance

Integración sobre el proyecto DominoIA existente, conservando IS-MCTS/PUCT, el modelo neuronal y la visión. No se sustituyen pesos ni checkpoints de entrenamiento.

## Integrado

1. **Inferencia bayesiana de manos ocultas**
   - Enumera repartos compatibles con la mano propia, las fichas jugadas y las fichas desconocidas.
   - Mantiene un prior uniforme sobre repartos legales y utiliza la elección observada del rival como evidencia conductual suave.
   - La legalidad matemática de las jugadas rivales observadas es una restricción dura, incluso cuando se desactiva el likelihood conductual.
   - Los pases se convierten en restricciones: reconstruye la mano del rival en el momento del pase y descarta candidatos que hubieran tenido una ficha legal.

2. **Registro y deduplicación de pases**
   - Registra el jugador que pasó y los extremos de ese momento.
   - Evita registrar dos veces el mismo pase al pasar de turno.
   - No atribuye al rival el pase propio ni viceversa cuando el OCR identifica al actor.

3. **Adaptación del adversario durante el encuentro**
   - Aprende únicamente de jugadas y pases públicos confirmados.
   - Mantiene estadísticas de descarga de tantos, liberación temprana de dobles y frecuencia de pases.
   - Requiere una muestra mínima antes de influir en las heurísticas.
   - Conserva las estadísticas agregadas entre manos, reinicia el contador de primeras jugadas en cada mano y reinicia todo el perfil al detectar marcador 0–0 y Mano 1. No guarda perfiles permanentes ni identidades de jugadores.

4. **Marcador y objetivo**
   - Nuevo lector OCR de marcador para `Tú`, rival, objetivo `a 50` y número de mano.
   - Ignora importes decimales como `180,00 CUP` al buscar los tantos.
   - Alimenta el contexto de marcador de las heurísticas. Si una lectura es parcial, conserva el último valor reconocido en vez de sustituirlo por cero.

5. **Evaluación conjunta de cada jugada legal**
   - Probabilidad de respuesta del rival.
   - Movilidad propia después de jugar.
   - Movilidad esperada del rival.
   - Control de números/extremos.
   - Probabilidad de bloqueo inmediato y resultado esperado del bloqueo.
   - Tantos retenidos y tantos descargados.
   - Frecuencia de victorias observadas en los rollouts MCTS para cada jugada (indicador estimado, no probabilidad calibrada).
   - Para posteriores pequeños usa la esperanza exacta ponderada; para posteriores grandes toma muestras según los pesos bayesianos.

6. **Integración conservadora en la decisión**
   - IS-MCTS/PUCT sigue siendo el motor principal.
   - La evaluación táctica solo aporta un sesgo pequeño al ranking; no legaliza movimientos ni puede invalidar restricciones de fichas.
   - El lookahead existente sigue siendo complementario.

## Límite deliberado

`P(encuentro)` permanece como N/D. Aunque ahora se reconoce el marcador, no se debe fabricar una probabilidad de ganar el encuentro a partir de una red que no está demostrablemente calibrada con los tantos actuales y el objetivo 50. Para habilitarla correctamente hará falta entrenar y validar una cabeza score-aware con las reglas de puntuación de esta aplicación.

La frecuencia de victorias MCTS mostrada para una jugada es una estimación empírica de rollouts, no una probabilidad estadística calibrada ni una garantía.

## Pruebas ejecutadas

Comando: `bash training/run_zeus12_core_smoke.sh`

Resultado: PASS.

- 15 escenarios base.
- 4 regresiones anteriores.
- 7 pruebas específicas de fase 1.
- Total de escenarios/assertions declarados: 26.
- Incluye apertura manual, turnos, OCR de pase, pases contradictorios, legalidad de jugadas rivales, posterior bayesiano, uso de pesos del posterior, evaluación de métricas, aprendizaje solo de eventos confirmados, persistencia del perfil entre manos, reinicio del perfil al detectar nuevo encuentro y lectura del marcador.

Estas son pruebas aisladas del núcleo Kotlin con stubs Android. No equivalen a una compilación de APK ni a una validación en vivo en el teléfono.

## Compilación Android

No se generó APK en este entorno: no hay `gradlew`, Gradle instalado ni Android SDK disponibles. La compilación final debe verificarse en GitHub Actions.

## Archivos incluidos en el parche

### Reemplazar (10)

- `app/src/main/java/com/zeusvision/app/CaptureService.kt` (adaptación visual nueva app ya preparada; se incluye para que el parche acumulado sea autocontenido)
- `app/src/main/java/com/zeusvision/app/DominoVision_0.9_RECONOCIMIENTO.kt` (adaptación visual nueva app ya preparada; se incluye para que el parche acumulado sea autocontenido)
- `app/src/main/java/com/zeusvision/app/DominoBot.kt`
- `app/src/main/java/com/zeusvision/app/DominoGameState.kt`
- `app/src/main/java/com/zeusvision/app/DominoOpponentInference.kt`
- `app/src/main/java/com/zeusvision/app/DominoOpponentProfile.kt`
- `app/src/main/java/com/zeusvision/app/DominoExpertHeuristics.kt`
- `app/src/main/java/com/zeusvision/app/DominoTemporalTracker.kt`
- `training/zeus12_core_smoke.kt`
- `training/run_zeus12_core_smoke.sh`

### Agregar (2)

- `app/src/main/java/com/zeusvision/app/DominoMoveEvaluator.kt`
- `app/src/main/java/com/zeusvision/app/DominoMatchHeaderRecognizer.kt`

También se incluye `INFORME_ADAPTACION_NUEVA_APP.md` como referencia de la adaptación visual. Con los dos informes, el ZIP contiene 14 archivos.

## No modificar

No se reemplazan modelos `.bin`, checkpoints `.pt`, datos de entrenamiento ni workflows de Android. `CaptureService.kt` y `DominoVision_0.9_RECONOCIMIENTO.kt` se incluyen con la adaptación visual ya entregada para que este parche acumulado funcione también si todavía no se había instalado. El patch no altera las 10.168 simulaciones registradas en el checkpoint anterior.
