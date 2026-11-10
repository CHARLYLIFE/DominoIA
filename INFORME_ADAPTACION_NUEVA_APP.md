# ZEUS VISION — adaptación visual para la nueva aplicación de dominó

## Archivos incluidos

Descomprimir este ZIP sobre la raíz del repositorio. Incluye tres reemplazos completos:

1. `app/src/main/java/com/zeusvision/app/CaptureService.kt`
2. `app/src/main/java/com/zeusvision/app/DominoVision_0.9_RECONOCIMIENTO.kt`
3. `app/src/main/java/com/zeusvision/app/DominoTemporalTracker.kt`

`CaptureService.kt` conserva la exclusión dinámica del panel ZEUS introducida en el parche anterior. El rectángulo se vuelve a medir después de mover, minimizar o actualizar el panel; no depende de que el panel esté siempre en una esquina.

## Cambios

- Detección del aro azul alrededor del avatar superior del rival y del avatar inferior del usuario.
- Lectura complementaria de los textos `Tu turno`, `Juega…`, `Paso` y `Pasas: no tienes ficha`.
- Detección de fichas del tablero en la zona central de esta nueva aplicación.
- Reconocimiento de fichas horizontales y verticales, separación de fichas próximas y reconstrucción de la cadena en disposición serpenteante.
- Cálculo de los dos extremos de la cadena.
- Exclusión de la ventana flotante ZEUS de la lectura de fichas y puntos. Si el panel tapa una parte relevante de la zona del tablero, no se confirma la mesa vacía.
- La iluminación azul de las fichas no se usa como única regla de legalidad. Se reconoce que el 6-6 iluminado corresponde a la salida especial inicial observada; las aperturas posteriores deben calcularse con el motor de dominó y no depender de esa iluminación.
- Se mantiene intacto el modelo neuronal, IS-MCTS/PUCT, los datos de entrenamiento y las reglas estratégicas.

## Pruebas realizadas

Se ejecutó el detector Kotlin sobre los píxeles de siete capturas reales suministradas en esta conversación:

| Captura | Turno reconocido | Fichas en mano | Fichas en mesa | Extremos |
|---|---|---:|---:|---|
| `1000125118.jpg` | Usuario | 7 | 0 | Mesa vacía |
| `1000125150.jpg` | Usuario | 3 | 9 | 3 / 5 |
| `1000125162.jpg` | Usuario | 5 | 4 | 2 / 1 |
| `1000125165.jpg` | Rival | 1 | 9 | 2 / 0 |
| `1000124973.jpg` | Rival | 6 | 2 | 5 / 6 |
| `1000124974.jpg` | Usuario | 6 | 3 | 5 / 1 |
| `1000124980.jpg` | Rival | 5 | 5 | 3 / 3 |

También se comprobó que:

- La captura `1000124980.jpg`, con el panel ZEUS abajo a la izquierda, se analiza usando su rectángulo de exclusión y conserva la lectura de las fichas visibles.
- Si el panel tapa una parte relevante del área central de una mesa vacía, el detector no confirma falsamente que la mesa está vacía.
- El pase propio `Paso / Pasas: no tienes ficha` se clasifica como `USER_PASS` y el siguiente turno es del rival.
- El pase del rival seguido de `Tu turno` se clasifica como `OPPONENT_PASS` y el siguiente turno es del usuario.

Los tests visuales y de seguimiento temporal se compilaron y ejecutaron con `kotlinc` y finalizaron correctamente.

## Limitación de validación

No se compiló la APK completa en este entorno: no están instalados Android SDK ni Gradle. Por tanto, el ZIP contiene los reemplazos de código y pruebas aisladas de los módulos Kotlin, pero la compilación Android final debe verificarse con GitHub Actions. La prueba real en el Samsung sigue siendo necesaria para comprobar la posición del panel, los cambios de turno y el OCR en directo.
