# ZEUS AI 1.0 — correccion de turnos y modelo neuronal

## Cambios incluidos

- `app/src/main/java/com/zeusvision/app/DominoVision_0.9_RECONOCIMIENTO.kt`: detecta el aro amarillo del avatar activo en coordenadas relativas a la pantalla; estima turno `USER`, `OPPONENT` o `UNKNOWN`; distingue una mesa visualmente vacia de una mesa cuya lectura fallo.
- `app/src/main/java/com/zeusvision/app/DominoBot.kt`: separa el jugador que abre de quien tiene el turno actual; no recomienda durante el turno rival; registra un pase cuando el aro cambia de rival a usuario sin cambio de mesa; permite la salida con mesa vacia.
- `app/src/main/java/com/zeusvision/app/CaptureService.kt`: consume la señal de turno, actualiza la sugerencia automaticamente, permite mesa vacia y muestrea capturas con intervalo minimo de 650 ms mientras la analisis anterior no este ocupado.
- `app/src/main/assets/zeus_domino_policy_value_v1.bin`: candidato neuronal exportado en el formato que ya lee `DominoNeuralModel.kt`.
- `training/zeus_train.py`: al finalizar, exporta tambien el modelo a la carpeta Android indicada por `--apk-assets`.
- `training/checkpoints/latest_7168.pt`: checkpoint para reanudar el entrenamiento.

No se modifican `MainActivity.kt`, `AndroidManifest.xml`, Gradle, el diseño del panel ni las dependencias.

## Validaciones ejecutadas

1. Compilacion aislada de `DominoVision_0.9_RECONOCIMIENTO.kt`, `DominoBot.kt` y `DominoNeuralModel.kt` con un stub JVM de `android.graphics.Bitmap`: correcta.
2. Analisis de las capturas reales del usuario:
   - `1000121206.jpg`: turno detectado `OPPONENT`, confianza 77/100; 4 fichas de mano, 5 fichas de mesa, extremos 3/3. Resultado del motor: esperar al rival.
   - `1000121204.jpg`: turno detectado `USER`, confianza 100/100; 7 fichas de mano, 1 ficha en mesa, extremos 5/5. Resultado del motor: sugerencia legal 3-5 por derecha con el modelo cargado.
3. Mesa vacia simulada conservando la mano y el indicador de turno: detectada como vacia y se genero una salida legal.
4. Transicion simulada de turno rival a usuario sin cambio de mesa: se registro una restriccion de pase. En esa posicion el motor informo que el usuario tampoco tenia jugada legal.
5. Modelo binario: firma `ZEUSNN01`, dimensiones 89/128/84/4, longitud 157560 bytes; carga e inferencia Kotlin correctas.
6. Entrenador Python: `py_compile` y `--self-test` correctos; 25 encuentros de autoprueba, sin acciones ilegales.
7. Torneo exploratorio del motor Kotlin: 100 encuentros a 100 puntos, con el asiento y el turno inicial alternados; híbrido con red 46 victorias, IS-MCTS sin red 47, 7 empates; margen medio de puntos del híbrido -0.8. Con 100 encuentros no se demuestra superioridad de la red.

## Limitaciones que permanecen

- El checkpoint guardado contiene 7,168 encuentros completos; no se ejecutó el objetivo de un millón.
- El modelo se debe considerar candidato entrenado, no IA definitiva ni superior a jugadores expertos.
- La deteccion del turno usa el aro amarillo. No realiza OCR literal del mensaje "Tu oponente debe pasar". El pase se infiere cuando el indicador cambia del rival a ti y las fichas/los extremos de la mesa permanecen iguales.
- La frecuencia es casi en tiempo real, no instantanea: el servicio espera al menos 650 ms entre analisis y no inicia otra busqueda mientras la anterior esta ocupada.
- El vector neuronal de 89 entradas no incluye los puntos acumulados visibles en el marcador; la cabeza de resultado de encuentro no puede condicionar correctamente la jugada a una situacion especifica como 90-40.
- La regla de puntuacion de tranca se entreno como `loser_pips`; confirmar que coincide con la aplicacion antes de continuar el entrenamiento masivo.
- No se pudo compilar la APK Android completa en este entorno porque no hay Android SDK/Gradle instalado. Las pruebas Kotlin fueron aisladas y no sustituyen `assembleDebug` en GitHub Actions.

## Instalacion del parche

Descomprime el ZIP sobre la raiz del proyecto Android, conservando las rutas. Reemplaza los tres Kotlin indicados y añade el archivo `.bin` a `app/src/main/assets/`. El checkpoint y los archivos de `training/` son para reanudar el entrenamiento; no se copian dentro de `app/src/main/assets/`.
