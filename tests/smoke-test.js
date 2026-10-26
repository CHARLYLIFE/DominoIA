"use strict";
const assert = require("node:assert/strict");
const Engine = require("../js/engine.js");
const AI = require("../js/ai.js");

function seeded(seed) {
  let x = seed >>> 0;
  return function () {
    x = (1664525 * x + 1013904223) >>> 0;
    return x / 4294967296;
  };
}

function randomGame(seed) {
  const rng = seeded(seed);
  const state = Engine.startGame(rng);
  assert.equal(state.hands.player.length, 7);
  assert.equal(state.hands.bot.length, 7);
  assert.equal(state.board.length, 0);
  assert.equal(Engine.createSet().length, 28);
  Engine.assertState(state);

  let safety = 0;
  while (!state.gameOver && safety < 40) {
    const side = state.currentTurn;
    const legal = Engine.getLegalMoves(state, side);
    if (!legal.length) {
      assert.equal(Engine.passTurn(state, side), true);
    } else {
      const action = legal[Math.floor(rng() * legal.length)];
      assert.equal(Engine.applyMove(state, side, action), true);
    }
    Engine.assertState(state);
    safety++;
  }
  assert.equal(state.gameOver, true, `La partida ${seed} no acabó dentro del límite`);
  assert.ok(state.result);
  assert.equal(state.hands.player.length + state.hands.bot.length + state.board.length, 14);
  return state;
}

for (let seed = 1; seed <= 250; seed++) randomGame(seed);

// Comprueba que la IA escoge siempre una acción legal y que las manos reales no se modifican.
{
  const rng = seeded(9876);
  const state = Engine.startGame(rng);
  if (state.currentTurn === "player") {
    const opening = Engine.getLegalMoves(state, "player")[0];
    assert.ok(opening);
    Engine.applyMove(state, "player", opening);
  }
  assert.equal(state.currentTurn, "bot");
  const playerHandBefore = state.hands.player.map(t => t.id).sort().join(",");
  const decision = AI.chooseMove(state, "bot", { simulations: 8, rng: seeded(555) });
  assert.ok(decision.action);
  assert.ok(Engine.getLegalMoves(state, "bot").some(a => a.tileId === decision.action.tileId && a.side === decision.action.side));
  assert.equal(state.hands.player.map(t => t.id).sort().join(","), playerHandBefore, "La IA no debe cambiar la mano real del jugador");
  assert.ok(decision.stats.candidates >= 1);
  assert.ok(decision.stats.simulations === 8 || decision.stats.simulations === 0);

  // Cambia la mano oculta manteniendo visibles la mesa, la mano del bot y el número de fichas.
  // Con la misma semilla, la decisión de la IA debe ser la misma: no puede leer esos valores ocultos.
  const alternate = Engine.cloneState(state);
  const visible = new Set([...alternate.hands.bot.map(t => t.id), ...alternate.board.map(t => t.id)]);
  const pool = Engine.createSet().filter(t => !visible.has(t.id));
  const alternateHand = pool.slice().reverse().slice(0, alternate.hands.player.length);
  if (alternateHand.length === alternate.hands.player.length &&
      alternateHand.map(t => t.id).sort().join(",") !== alternate.hands.player.map(t => t.id).sort().join(",")) {
    alternate.hands.player = alternateHand.map(t => ({ ...t }));
    const originalDecision = AI.chooseMove(state, "bot", { simulations: 8, rng: seeded(12345) });
    const alternateDecision = AI.chooseMove(alternate, "bot", { simulations: 8, rng: seeded(12345) });
    assert.deepEqual(alternateDecision.action, originalDecision.action, "La IA no debe aprovechar los valores de la mano oculta real");
  }
}

console.log("OK: 250 partidas aleatorias, reglas, fichas únicas, pases, bloqueos, acción legal y prueba de mano oculta de la IA.");
