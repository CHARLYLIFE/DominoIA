/*
 * DOMINÓ PRO — IA probabilística por determinización + Monte Carlo rollouts.
 *
 * La IA NO consulta los valores de las fichas ocultas del estado real.
 * Construye manos rivales hipotéticas a partir de las fichas no vistas y de
 * las restricciones deducidas de los pases. Cada acción se evalúa mediante
 * partidas simuladas con políticas que solo usan información pública y su mano.
 *
 * No garantiza ganar siempre (hay azar e información oculta), pero es una base
 * mucho más fuerte y medible que elegir la ficha con más puntos.
 */
(function (root) {
  "use strict";

  const Engine = root.DominoEngine;
  if (!Engine) throw new Error("Carga js/engine.js antes de js/ai.js");

  const PRESETS = Object.freeze({
    easy:   { simulations: 35,  label: "Fácil" },
    hard:   { simulations: 130, label: "Difícil" },
    expert: { simulations: 240, label: "Experto" }
  });

  function tileHas(tile, number) {
    return tile.a === number || tile.b === number;
  }

  function getUnknownPool(state, observerSide) {
    const known = new Set();
    for (const tile of state.hands[observerSide]) known.add(tile.id);
    for (const tile of state.board) known.add(tile.id);
    return Engine.createSet().filter(tile => !known.has(tile.id));
  }

  function getForbiddenNumbers(state, ownerSide) {
    return Engine.uniqueForbiddenNumbers(state.passHistory[ownerSide]);
  }

  function sampleOpponentHand(state, observerSide, rng = Math.random) {
    const opponent = Engine.otherSide(observerSide);
    const handSize = state.hands[opponent].length; // El número de fichas es información pública.
    const pool = getUnknownPool(state, observerSide);
    const forbidden = getForbiddenNumbers(state, opponent);

    // Si el rival pasó con extremos X e Y, su mano actual no puede contener X ni Y.
    const compatible = pool.filter(tile => !forbidden.has(tile.a) && !forbidden.has(tile.b));
    const source = compatible.length >= handSize ? compatible : pool;
    return Engine.shuffle(source, rng).slice(0, handSize).map(t => ({ ...t }));
  }

  function combination(n, k) {
    if (k < 0 || k > n) return 0;
    k = Math.min(k, n - k);
    let value = 1;
    for (let i = 1; i <= k; i++) value = (value * (n - k + i)) / i;
    return value;
  }

  /* Probabilidad aproximada y condicionada de que el rival pueda jugar en esos extremos. */
  function estimateOpponentCanPlay(state, observerSide, left, right) {
    const opponent = Engine.otherSide(observerSide);
    const handSize = state.hands[opponent].length;
    if (handSize <= 0) return 0;

    const pool = getUnknownPool(state, observerSide);
    const forbidden = getForbiddenNumbers(state, opponent);
    const compatible = pool.filter(tile => !forbidden.has(tile.a) && !forbidden.has(tile.b));
    const candidates = compatible.length >= handSize ? compatible : pool;
    const n = candidates.length;
    if (n < handSize || n === 0) return 0.5;

    const playableCount = candidates.filter(tile =>
      tileHas(tile, left) || tileHas(tile, right)
    ).length;
    if (playableCount === 0) return 0;
    if (playableCount >= n) return 1;

    const allHands = combination(n, handSize);
    const noPlayableHands = combination(n - playableCount, handSize);
    if (allHands <= 0) return 0.5;
    return Math.max(0, Math.min(1, 1 - noPlayableHands / allHands));
  }

  function countSupport(hand, number) {
    let count = 0;
    for (const tile of hand) if (tileHas(tile, number)) count++;
    return count;
  }

  function countDistinctNumbers(hand) {
    const values = new Set();
    for (const tile of hand) {
      values.add(tile.a);
      values.add(tile.b);
    }
    return values.size;
  }

  function quickMoveScore(state, side, action, style = "balanced", rng = Math.random) {
    const tile = state.hands[side].find(t => t.id === action.tileId);
    if (!tile) return -Infinity;

    const after = Engine.cloneState(state);
    if (!Engine.applyMove(after, side, action)) return -Infinity;
    if (after.gameOver) return 10000;

    const ownHand = after.hands[side];
    const ends = Engine.getBoardEnds(after);
    const pipCount = tile.a + tile.b;
    const isDouble = tile.a === tile.b;
    const leftSupport = countSupport(ownHand, ends.left);
    const rightSupport = countSupport(ownHand, ends.right);
    const distinct = countDistinctNumbers(ownHand);
    const opponentProbability = estimateOpponentCanPlay(after, side, ends.left, ends.right);
    const hasFutureMove = ownHand.some(t => tileHas(t, ends.left) || tileHas(t, ends.right));

    let score = 0;
    if (style === "points") {
      score += pipCount * 0.9;
      score += isDouble ? 3.0 : 0;
      score += (leftSupport + rightSupport) * 0.7;
    } else if (style === "control") {
      score += pipCount * 0.15;
      score += (leftSupport + rightSupport) * 1.9;
      score += distinct * 0.35;
      score += isDouble ? 1.8 : 0;
    } else {
      // Mezcla de descarte de puntos, dobles y conservación de salidas útiles.
      score += pipCount * 0.42;
      score += isDouble ? 2.1 : 0;
      score += leftSupport * 1.55 + rightSupport * 1.55;
      score += distinct * 0.22;
    }

    // En una colocación a izquierda/derecha, premiar más el nuevo extremo expuesto.
    if (action.side === "left") score += leftSupport * (style === "control" ? 1.4 : 0.9);
    if (action.side === "right") score += rightSupport * (style === "control" ? 1.4 : 0.9);
    if (action.side === "center") score += (leftSupport + rightSupport) * 0.8;

    // Si nosotros podemos continuar y el rival probablemente no, podemos mantener el control.
    if (hasFutureMove) score += (1 - opponentProbability) * 1.25;
    else {
      const ownPoints = Engine.handPoints(ownHand);
      // Si la mano es pesada, no conviene forzar un bloqueo prematuro.
      score += (1 - opponentProbability) * (ownPoints <= 12 ? 0.45 : -1.0);
    }

    if (ownHand.length === 1) score += 2.5;
    if (ownHand.length === 2 && hasFutureMove) score += 1.0;
    if (style === "random") score += rng() * 4;
    else score += rng() * 0.5; // Desempate pequeño para evitar jugadas siempre idénticas.
    return score;
  }

  function chooseRolloutMove(state, side, style, rng) {
    const legal = Engine.getLegalMoves(state, side);
    if (legal.length <= 1) return legal[0] || null;
    if (style === "random") return legal[Math.floor(rng() * legal.length)];

    let best = legal[0];
    let bestScore = -Infinity;
    for (const action of legal) {
      const score = quickMoveScore(state, side, action, style, rng);
      if (score > bestScore) {
        bestScore = score;
        best = action;
      }
    }
    return best;
  }

  function chooseOpponentStyle(rng) {
    const roll = rng();
    if (roll < 0.48) return "balanced";
    if (roll < 0.73) return "points";
    if (roll < 0.94) return "control";
    return "random";
  }

  function terminalUtility(state, perspective) {
    const result = state.result;
    if (!result) {
      const ownPoints = Engine.handPoints(state.hands[perspective]);
      const oppPoints = Engine.handPoints(state.hands[Engine.otherSide(perspective)]);
      return Math.max(-0.9, Math.min(0.9, (oppPoints - ownPoints) / 42));
    }
    if (result.winner === null) return 0;
    const outcome = result.winner === perspective ? 1 : -1;
    const ownPoints = Engine.handPoints(state.hands[perspective]);
    const oppPoints = Engine.handPoints(state.hands[Engine.otherSide(perspective)]);
    const margin = Math.max(-0.18, Math.min(0.18, (oppPoints - ownPoints) / 100));
    return outcome + margin;
  }

  function rollout(startState, perspective, rng) {
    const state = startState;
    let plies = 0;
    const hardLimit = 40; // En esta variante el juego termina mucho antes; protección ante errores.

    while (!state.gameOver && plies < hardLimit) {
      const side = state.currentTurn;
      const legal = Engine.getLegalMoves(state, side);
      if (legal.length === 0) {
        Engine.passTurn(state, side);
      } else {
        // Política de cada jugador; solo ve su mano, la mesa y el historial público.
        const style = side === perspective ? "control" : chooseOpponentStyle(rng);
        const action = chooseRolloutMove(state, side, style, rng);
        if (!action || !Engine.applyMove(state, side, action)) {
          // Respaldo defensivo para que una simulación defectuosa no quede atrapada.
          if (!Engine.passTurn(state, side)) break;
        }
      }
      plies++;
    }

    return terminalUtility(state, perspective);
  }

  function chooseMove(state, side = "bot", options = {}) {
    const legal = Engine.getLegalMoves(state, side);
    const preset = PRESETS[options.difficulty] || PRESETS.hard;
    const simulations = Math.max(1, Number.isFinite(options.simulations)
      ? Math.floor(options.simulations)
      : preset.simulations);
    const rng = typeof options.rng === "function" ? options.rng : Math.random;
    const start = Date.now();

    if (legal.length === 0) {
      return { action: null, stats: { simulations: 0, candidates: 0, elapsedMs: Date.now() - start, scores: [] } };
    }
    if (legal.length === 1) {
      return {
        action: legal[0],
        stats: { simulations: 0, candidates: 1, elapsedMs: Date.now() - start, scores: [{ ...legal[0], mean: null }] }
      };
    }

    const opponent = Engine.otherSide(side);
    const totals = legal.map(action => ({ action, total: 0, wins: 0, draws: 0, losses: 0 }));

    // Misma mano hipotética para comparar las acciones en cada escenario (reduce varianza).
    for (let i = 0; i < simulations; i++) {
      const imaginedOpponentHand = sampleOpponentHand(state, side, rng);
      for (const entry of totals) {
        const world = Engine.cloneState(state);
        world.hands[opponent] = imaginedOpponentHand.map(t => ({ ...t }));
        const applied = Engine.applyMove(world, side, entry.action);
        const utility = applied && world.gameOver
          ? terminalUtility(world, side)
          : applied
            ? rollout(world, side, rng)
            : -1;
        entry.total += utility;
        if (utility > 0.1) entry.wins++;
        else if (utility < -0.1) entry.losses++;
        else entry.draws++;
      }
    }

    totals.sort((a, b) => (b.total / simulations) - (a.total / simulations));
    const best = totals[0];
    const scores = totals.map(item => ({
      tileId: item.action.tileId,
      side: item.action.side,
      mean: Number((item.total / simulations).toFixed(4)),
      winRate: Number((item.wins / simulations).toFixed(3)),
      drawRate: Number((item.draws / simulations).toFixed(3)),
      lossRate: Number((item.losses / simulations).toFixed(3))
    }));

    return {
      action: best.action,
      stats: {
        simulations,
        candidates: legal.length,
        elapsedMs: Date.now() - start,
        scores
      }
    };
  }

  const api = {
    PRESETS,
    chooseMove,
    sampleOpponentHand,
    estimateOpponentCanPlay,
    quickMoveScore
  };

  root.DominoAI = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : window);
