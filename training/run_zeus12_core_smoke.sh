#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/zeus-ai-1.2-core-tests.jar"
PACKAGE="$ROOT/app/src/main/java/com/zeusvision/app"
kotlinc \
  "$ROOT/training/android-stubs/Bitmap.kt" \
  "$PACKAGE/DominoGameState.kt" \
  "$PACKAGE/DominoTemporalTracker.kt" \
  "$PACKAGE/DominoOpponentInference.kt" \
  "$PACKAGE/DominoExactSolver.kt" \
  "$PACKAGE/DominoNeuralModel.kt" \
  "$PACKAGE/DominoVision_0.9_RECONOCIMIENTO.kt" \
  "$PACKAGE/DominoBot.kt" \
  "$ROOT/training/zeus12_core_smoke.kt" \
  -include-runtime -d "$OUT"
java -jar "$OUT"
