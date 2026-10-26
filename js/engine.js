/*
 * DOMINÓ DOBLE-SEIS — MOTOR DE REGLAS
 * Variante: 1 contra 1, 7 fichas por jugador, 14 fuera, sin robo.
 * No depende del DOM; puede usarse en navegador y Node.js.
 */
(function (root) {
  "use strict";

  const SIDES = Object.freeze(["player", "bot"]);

  function otherSide(side) {
    return side === "player" ? "bot" : "player";
  }

  function createSet() {
    const tiles = [];
    for (let a = 0; a <= 6; a++) {
      for (let b = a; b <= 6; b++) {
        tiles.push({ a, b, id: `${a}-${b}` });
      }
    }
    return tiles;
  }

  function shuffle(items, rng = Math.random) {
    const result = items.slice();
    for (let i = result.length - 1; i > 0; i--) {
      const j = Math.floor(rng() * (i + 1));
      [result[i], result[j]] = [result[j], result[i]];
    }
    return result;
  }

  function tilePoints(tile) {
    return tile.a + tile.b;
  }

  function highestDouble(hand) {
    let best = null;
    for (const tile of hand) {
      if (tile.a === tile.b && (best === null || tile.a > best.a)) best = tile;
    }
    return best;
  }

  function highestTile(hand) {
    return hand.reduce((best, tile) => {
      if (!best) return tile;
      const p = tilePoints(tile);
      const bp = tilePoints(best);
      if (p > bp || (p === bp && tile.a > best.a)) return tile;
      return best;
    }, null);
  }

  /*
   * Salida: comienza el poseedor del doble más alto disponible y debe jugarlo.
   * Si no hay dobles en las dos manos, sale la ficha de mayor suma de puntos.
   */
  function chooseOpening(playerHand, botHand, rng = Math.random) {
    const playerDouble = highestDouble(playerHand);
    const botDouble = highestDouble(botHand);

    if (playerDouble || botDouble) {
      if (playerDouble && botDouble) {
        if (playerDouble.a > botDouble.a) return { side: "player", tile: playerDouble };
        if (botDouble.a > playerDouble.a) return { side: "bot", tile: botDouble };
        return rng() < 0.5
          ? { side: "player", tile: playerDouble }
          : { side: "bot", tile: botDouble };
      }
      return playerDouble
        ? { side: "player", tile: playerDouble }
        : { side: "bot", tile: botDouble };
    }

    const playerHigh = highestTile(playerHand);
    const botHigh = highestTile(botHand);
    if (tilePoints(playerHigh) > tilePoints(botHigh)) return { side: "player", tile: playerHigh };
    if (tilePoints(botHigh) > tilePoints(playerHigh)) return { side: "bot", tile: botHigh };
    return rng() < 0.5
      ? { side: "player", tile: playerHigh }
      : { side: "bot", tile: botHigh };
  }

  function startGame(rng = Math.random) {
    const deck = shuffle(createSet(), rng);
    const playerHand = deck.slice(0, 7);
    const botHand = deck.slice(7, 14);
    const opening = chooseOpening(playerHand, botHand, rng);

    return {
      hands: { player: playerHand, bot: botHand },
      board: [],
      currentTurn: opening.side,
      openingTileId: opening.tile.id,
      consecutivePasses: 0,
      passHistory: { player: [], bot: [] },
      events: [],
      lastMove: null,
      gameOver: false,
      result: null,
      moveCount: 0,
      createdAt: Date.now()
    };
  }

  function cloneTile(tile) {
    return { a: tile.a, b: tile.b, id: tile.id };
  }

  function cloneState(state) {
    return {
      hands: {
        player: state.hands.player.map(cloneTile),
        bot: state.hands.bot.map(cloneTile)
      },
      board: state.board.map(cloneTile),
      currentTurn: state.currentTurn,
      openingTileId: state.openingTileId,
      consecutivePasses: state.consecutivePasses,
      passHistory: {
        player: state.passHistory.player.map(p => ({ left: p.left, right: p.right, turn: p.turn })),
        bot: state.passHistory.bot.map(p => ({ left: p.left, right: p.right, turn: p.turn }))
      },
      events: state.events.map(e => ({ ...e })),
      lastMove: state.lastMove ? { ...state.lastMove } : null,
      gameOver: state.gameOver,
      result: state.result ? { ...state.result } : null,
      moveCount: state.moveCount,
      createdAt: state.createdAt
    };
  }

  function getBoardEnds(state) {
    if (!state.board.length) return null;
    return {
      left: state.board[0].a,
      right: state.board[state.board.length - 1].b
    };
  }

  function containsNumber(tile, n) {
    return tile.a === n || tile.b === n;
  }

  function getLegalMoves(state, side = state.currentTurn) {
    if (!state || state.gameOver || !SIDES.includes(side)) return [];
    const hand = state.hands[side];
    if (!hand || hand.length === 0) return [];

    if (state.board.length === 0) {
      if (state.openingTileId) {
        return hand.some(t => t.id === state.openingTileId)
          ? [{ tileId: state.openingTileId, side: "center" }]
          : [];
      }
      return hand.map(t => ({ tileId: t.id, side: "center" }));
    }

    const ends = getBoardEnds(state);
    const actions = [];
    for (const tile of hand) {
      const canLeft = containsNumber(tile, ends.left);
      const canRight = containsNumber(tile, ends.right);
      if (canLeft) actions.push({ tileId: tile.id, side: "left" });
      if (canRight) actions.push({ tileId: tile.id, side: "right" });
    }
    return actions;
  }

  function orientForSide(tile, side, ends) {
    if (side === "center" || !ends) return cloneTile(tile);
    if (side === "left") {
      if (tile.b === ends.left) return cloneTile(tile);
      if (tile.a === ends.left) return { a: tile.b, b: tile.a, id: tile.id };
    }
    if (side === "right") {
      if (tile.a === ends.right) return cloneTile(tile);
      if (tile.b === ends.right) return { a: tile.b, b: tile.a, id: tile.id };
    }
    return null;
  }

  function handPoints(hand) {
    return hand.reduce((sum, tile) => sum + tilePoints(tile), 0);
  }

  function finalizeBlocked(state) {
    const playerPoints = handPoints(state.hands.player);
    const botPoints = handPoints(state.hands.bot);
    let winner = null;
    if (playerPoints < botPoints) winner = "player";
    else if (botPoints < playerPoints) winner = "bot";
    state.gameOver = true;
    state.currentTurn = null;
    state.result = {
      type: "blocked",
      winner,
      playerPoints,
      botPoints,
      message: winner === "player"
        ? `🔒 Partida bloqueada. Ganaste ${playerPoints} a ${botPoints}.`
        : winner === "bot"
          ? `🔒 Partida bloqueada. El bot gana ${botPoints} a ${playerPoints}.`
          : `🔒 Partida bloqueada. Empate ${playerPoints} a ${botPoints}.`
    };
  }

  function applyMove(state, side, action) {
    if (!state || state.gameOver || state.currentTurn !== side) return false;
    const legal = getLegalMoves(state, side);
    if (!legal.some(m => m.tileId === action.tileId && m.side === action.side)) return false;

    const hand = state.hands[side];
    const handIndex = hand.findIndex(t => t.id === action.tileId);
    if (handIndex < 0) return false;
    const tile = hand[handIndex];
    const ends = getBoardEnds(state);
    const oriented = orientForSide(tile, action.side, ends);
    if (!oriented) return false;

    hand.splice(handIndex, 1);
    if (action.side === "center" || state.board.length === 0) state.board.push(oriented);
    else if (action.side === "left") state.board.unshift(oriented);
    else state.board.push(oriented);

    state.openingTileId = null;
    state.consecutivePasses = 0;
    state.moveCount += 1;
    state.lastMove = { tileId: tile.id, side, boardSide: action.side, moveNumber: state.moveCount };
    state.events.push({ type: "play", player: side, tileId: tile.id, boardSide: action.side, turn: state.moveCount });

    if (hand.length === 0) {
      const playerPoints = handPoints(state.hands.player);
      const botPoints = handPoints(state.hands.bot);
      state.gameOver = true;
      state.currentTurn = null;
      state.result = {
        type: "domino",
        winner: side,
        playerPoints,
        botPoints,
        message: side === "player" ? "🎉 ¡Ganaste la partida! ¡Dominó!" : "🤖 El bot ganó la partida. ¡Dominó!"
      };
      return true;
    }

    state.currentTurn = otherSide(side);
    return true;
  }

  function passTurn(state, side) {
    if (!state || state.gameOver || state.currentTurn !== side) return false;
    if (getLegalMoves(state, side).length !== 0) return false;

    const ends = getBoardEnds(state) || { left: null, right: null };
    state.passHistory[side].push({ left: ends.left, right: ends.right, turn: state.moveCount });
    state.events.push({ type: "pass", player: side, left: ends.left, right: ends.right, turn: state.moveCount });
    state.consecutivePasses += 1;

    if (state.consecutivePasses >= 2) {
      finalizeBlocked(state);
      return true;
    }

    state.currentTurn = otherSide(side);
    return true;
  }

  function uniqueForbiddenNumbers(passRecords) {
    const values = new Set();
    for (const record of passRecords || []) {
      if (Number.isInteger(record.left)) values.add(record.left);
      if (Number.isInteger(record.right)) values.add(record.right);
    }
    return values;
  }

  function getHandNumberSupport(hand, number) {
    let count = 0;
    for (const tile of hand) if (containsNumber(tile, number)) count++;
    return count;
  }

  function assertState(state) {
    const ids = new Set();
    for (const side of SIDES) {
      for (const tile of state.hands[side]) {
        if (ids.has(tile.id)) throw new Error(`Ficha duplicada en el estado: ${tile.id}`);
        ids.add(tile.id);
      }
    }
    for (const tile of state.board) {
      if (ids.has(tile.id)) throw new Error(`Ficha duplicada en el estado: ${tile.id}`);
      ids.add(tile.id);
    }
    if (ids.size !== 14) throw new Error(`Estado inválido: entre manos y mesa deben aparecer las 14 fichas repartidas; hay ${ids.size}.`);
    return true;
  }

  const api = {
    SIDES,
    otherSide,
    createSet,
    shuffle,
    tilePoints,
    startGame,
    cloneState,
    getBoardEnds,
    getLegalMoves,
    applyMove,
    passTurn,
    handPoints,
    uniqueForbiddenNumbers,
    getHandNumberSupport,
    assertState
  };

  root.DominoEngine = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : window);
