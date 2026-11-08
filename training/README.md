# ZEUS AI 1.0 — entrenamiento neuronal

El entrenamiento se ejecuta fuera de Android. La APK solo carga el modelo binario local `app/src/main/assets/zeus_domino_policy_value_v1.bin` y realiza inferencia sin Internet.

## Reanudar el entrenamiento

Requisitos: Python 3.10 o superior, PyTorch y NumPy. Instala dependencias desde esta carpeta:

```bash
python -m pip install -r requirements.txt
```

El checkpoint incluido está en `training/checkpoints/latest_7168.pt`. Desde la carpeta `training/`, continuar hasta un millón de encuentros completos con:

```bash
python zeus_train.py --games 1000000 --match-target 100 --blocked-scoring loser_pips --workers 4 --batch-matches 256 --eval-every 10000 --eval-games 250 --resume checkpoints/latest_7168.pt --out runs/zeus_ai_1_0 --apk-assets ../app/src/main/assets
```

Ajusta `--workers` según los núcleos de CPU disponibles. CUDA solo se usa si PyTorch la detecta; de lo contrario se entrena en CPU.

El programa conserva checkpoints, métricas y exporta el archivo de Android al completar el objetivo. No apagues el dispositivo durante el entrenamiento. Para reanudar tras una interrupción utiliza `--resume runs/zeus_ai_1_0/latest.pt`.

## Limitaciones conocidas del modelo incluido

El modelo que acompaña esta versión tiene 89 entradas, 128 neuronas ocultas, 84 salidas de política y 4 valores auxiliares. Está entrenado con 7,168 encuentros completos guardados en el último checkpoint. No equivale a un millón de encuentros ni demuestra superioridad competitiva. El vector actual no recibe el marcador acumulado; la lectura del marcador y su incorporación al estado son una etapa posterior necesaria para optimizar plenamente las partidas a 100 puntos.

El parámetro `--blocked-scoring loser_pips` supone que, al producirse una tranca, el ganador recibe los puntos de la mano perdedora. Confirma esa regla en la aplicación antes de continuar un entrenamiento extenso.
