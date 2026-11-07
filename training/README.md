# ZEUS AI 1.0 — entrenamiento neuronal

Este entrenamiento se ejecuta en un ordenador, no dentro de Android. PyTorch y NumPy son las únicas dependencias externas.

## Reglas simuladas

- Dominó doble-seis, 28 fichas.
- Dos jugadores, siete fichas por jugador.
- Sin robo; las otras catorce fichas quedan fuera.
- Se puede jugar por cualquiera de los dos extremos.
- Un jugador pasa cuando no tiene jugada; dos pases consecutivos producen tranca.
- El ganador de la ronda comienza la siguiente.
- El encuentro termina cuando alguien alcanza 100 puntos.
- Al salir, el ganador recibe los puntos de la mano perdedora.
- En tranca, el ganador tiene menos puntos. La opción `loser_pips` asigna los puntos de la mano perdedora; `difference` asigna la diferencia.

**Verificación necesaria:** el ZIP de ZEUS VISION es un asistente visual y no contiene el marcador ni un lector del marcador de la aplicación externa. Además, el ZIP no especifica inequívocamente la regla exacta de puntuación de tranca. Por eso la puntuación de tranca es configurable; confirma que coincide con la aplicación antes de considerar el modelo definitivo. El vector de inferencia no incluye el marcador acumulado, de modo que la política optimiza el resultado medio en encuentros a 100, no una estrategia condicionada al tanteador exacto. El motor IS-MCTS conserva el historial temporal de jugadas y las restricciones de pases para muestrear manos; la red recibe un resumen observable de fichas jugadas, extremos y valores descartados por pases.

## Preparar el entorno

```bash
python -m venv .venv
# Windows: .venv\\Scripts\\activate
# Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt
```

## Comprobar el simulador

```bash
python zeus_train.py --self-test
```

## Entrenar

Ejemplo de prueba corta:

```bash
python zeus_train.py --games 1000 --eval-every 500 --eval-games 100 --out runs/test
```

Entrenamiento largo (objetivo de un millón de encuentros):

```bash
python zeus_train.py --games 1000000 --batch-matches 128 --workers 4 --save-every 5000 --eval-every 10000 --eval-games 250 --device auto --out runs/zeus_ai_1_0
```

Para reanudar desde el último checkpoint:

```bash
python zeus_train.py --games 1000000 --resume runs/zeus_ai_1_0/latest.pt --out runs/zeus_ai_1_0
```

El parámetro `--games` significa encuentros completos hasta 100 puntos, no número de rondas ni posiciones sueltas. El programa imprime el contador real y guarda checkpoints, métricas y modelo exportado. No se debe afirmar que se entrenó un millón de encuentros hasta que el contador final lo confirme.

## Exportar directamente para Android

Desde la carpeta `training`, suponiendo que la raíz del proyecto Android es la carpeta padre:

```bash
python zeus_train.py --games 1000000 --apk-assets ../app/src/main/assets --out runs/zeus_ai_1_0
```

Al guardar el modelo, el entrenador genera:

`app/src/main/assets/zeus_domino_policy_value_v1.bin`

Copia el archivo a la rama/proyecto Android y compila la APK. La aplicación lo carga al iniciar `CaptureService`. Si el archivo falta o no valida, ZEUS continúa con el motor IS-MCTS clásico sin bloquear la captura.

## Arquitectura del modelo

- Entrada: 89 valores observables (mano propia, fichas públicas jugadas, extremos, cantidad conocida de fichas rivales, pases consecutivos, estado de mesa y valores que el adversario ha demostrado no tener al pasar). No contiene las identidades de fichas rivales ocultas.
- POLICY: 84 logits, uno por combinación de 28 fichas y 3 lados/casos de salida. Android enmascara y normaliza únicamente las acciones legales.
- VALUE: cuatro salidas: probabilidad de ganar la ronda, margen esperado normalizado de la ronda, probabilidad de tranca y probabilidad de ganar el encuentro a 100.
- Entrenamiento: policy-gradient con crítico, pérdida supervisada auxiliar contra una política heurística y autojuego mixto contra agentes anteriores/heurísticos.
- Exportación: formato binario ZEUSNN01 de precisión float32, leído por Kotlin sin dependencia de TensorFlow Lite ni conexión a Internet.

El entrenamiento inicial incluye jugadas guiadas por la heurística para evitar comenzar con acciones completamente aleatorias. La autoevaluación alterna asientos y enfrenta el modelo a agentes aleatorios, conservadores, agresivos, de bloqueo y equilibrados. Estos resultados no sustituyen las pruebas contra jugadores humanos.
