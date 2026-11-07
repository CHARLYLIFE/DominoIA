# ZEUS AI 1.0 — archivos de integración

Este paquete es un parche: contiene únicamente los archivos nuevos o modificados. No es una APK compilada ni una copia completa del repositorio.

## Reemplazar estos dos archivos existentes

1. `app/src/main/java/com/zeusvision/app/DominoBot.kt`
2. `app/src/main/java/com/zeusvision/app/CaptureService.kt`

## Crear este archivo nuevo

3. `app/src/main/java/com/zeusvision/app/DominoNeuralModel.kt`

## Añadir el entrenador externo

4. `training/zeus_train.py`
5. `training/requirements.txt`
6. `training/README.md`

## Archivos que NO se reemplazan

No modificar `DominoVision_0.9_RECONOCIMIENTO.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `app/build.gradle`, `build.gradle`, `settings.gradle`, `gradle.properties` ni el workflow de GitHub Actions. La carga neuronal utiliza Kotlin puro y no añade dependencias a Gradle.

## Comportamiento antes de entrenar

El paquete no incluye un modelo neuronal de producción. La APK intenta cargar `app/src/main/assets/zeus_domino_policy_value_v1.bin`. Si todavía no existe, el servicio captura la pantalla y el bot continúa con su IS-MCTS actual; la aplicación no se bloquea.

## Activar la red neuronal después del entrenamiento

Desde la carpeta `training`, ejecutar en un ordenador compatible:

```bash
pip install -r requirements.txt
python zeus_train.py --self-test
python zeus_train.py --games 1000000 --batch-matches 128 --workers 4 --save-every 5000 --eval-every 10000 --eval-games 250 --device auto --out runs/zeus_ai_1_0 --apk-assets ../app/src/main/assets
```

El entrenador crea el archivo `app/src/main/assets/zeus_domino_policy_value_v1.bin`. Añade ese archivo al repositorio Android y vuelve a compilar la APK. Para continuar un entrenamiento interrumpido, usa `--resume runs/zeus_ai_1_0/latest.pt` manteniendo los demás parámetros.

## Validaciones realizadas en este entorno

- Simulador: autoprueba de 25 encuentros completos con comprobación de acciones legales.
- Entrenador: ejecución de prueba corta y generación de checkpoint/modelo.
- Paralelismo: generación de partidas probada con dos procesos CPU.
- Kotlin: compilación aislada de `DominoBot.kt` y `DominoNeuralModel.kt`.
- Integración: modelo exportado abierto por Kotlin; salida policy de 84 acciones y sugerencia legal en prueba.

No se ejecutó un millón de encuentros, no se entrenó un modelo competitivo y no se compiló la APK Android completa en este entorno. No hay Android SDK/Gradle instalado aquí. La puntuación exacta de tranca debe compararse con la aplicación observada antes del entrenamiento definitivo; el entrenador permite `loser_pips` o `difference`.

El APK actual tampoco lee el marcador acumulado de la aplicación externa. La política se entrena para encuentros a 100 puntos, pero al inferir no puede condicionar la jugada al marcador exacto.
