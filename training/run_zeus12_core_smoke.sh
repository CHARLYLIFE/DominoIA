#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/zeus-ai-phase1-core-tests.jar"
P="$ROOT/app/src/main/java/com/zeusvision/app"
kotlinc -language-version 2.0 \
  "$ROOT/training/android-stubs/Bitmap.kt" \
  "$P/DominoGameState.kt" \
  "$P/DominoMatchHeaderRecognizer.kt" \
  "$P/DominoOpeningGate.kt" \
  "$P/DominoTemporalTracker.kt" \
  "$P/DominoOpponentInference.kt" \
  "$P/DominoOpponentProfile.kt" \
  "$P/DominoExpertHeuristics.kt" \
  "$P/DominoMoveEvaluator.kt" \
  "$P/DominoSelectiveLookahead.kt" \
  "$P/DominoExactSolver.kt" \
  "$P/DominoNeuralModel.kt" \
  "$P/DominoVision_0.9_RECONOCIMIENTO.kt" \
  "$P/DominoBot.kt" \
  "$ROOT/training/zeus12_core_smoke.kt" \
  -include-runtime -d "$OUT"
java -jar "$OUT"
