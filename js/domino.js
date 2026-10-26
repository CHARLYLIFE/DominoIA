/* DOMINÓ PRO — pegamento entre interfaz, motor e IA. */
(function () {
  "use strict";

  const Engine = window.DominoEngine;
  const AI = window.DominoAI;

  if (!Engine || !AI) {
    throw new Error("Falta cargar js/engine.js y js/ai.js antes de js/domino.js");
  }

  const $ = (id) => document.getElementById(id);

  const el = {
    status: $("status"),
    aiInfo: $("aiInfo"),
    playerHand: $("playerHand"),
    botHand: $("botHand"),
    board: $("board"),
    playerCount: $("playerCount"),
    botCount: $("botCount"),
    playerSubtext: $("playerSubtext"),
    botSubtext: $("botSubtext"),
    passButton: $("passButton"),
    newGame: $("newGame"),
    hintButton: $("hintButton"),
    difficulty: $("difficulty"),
    sidePicker: $("sidePicker"),
    playLeft: $("playLeft"),
    playRight: $("playRight"),
    leftEnd: $("leftEnd"),
    rightEnd: $("rightEnd"),
    moveCounter: $("moveCounter"),
    winsPlayer: $("winsPlayer"),
    winsBot: $("winsBot"),
    draws: $("draws"),
    winRate: $("winRate")
  };

  const STATS_KEY = "dominoProStatsV1";

  let state = null;
  let generation = 0;
  let botTimer = null;
  let pendingTileId = null;
  let hintAction = null;
  let lastAnimatedMoveNumber = 0;
  let resultRecordedForGeneration = -1;

  function readStats() {
    const fallback = {
      games: 0,
      winsPlayer: 0,
      winsBot: 0,
      draws: 0
    };

    try {
      const saved = JSON.parse(localStorage.getItem(STATS_KEY) || "null");

      if (!saved || typeof saved !== "object") {
        return fallback;
      }

      return {
        games: Number(saved.games) || 0,
        winsPlayer: Number(saved.winsPlayer) || 0,
        winsBot: Number(saved.winsBot) || 0,
        draws: Number(saved.draws) || 0
      };
    } catch (_) {
      return fallback;
    }
  }

  let stats = readStats();

  function saveStats() {
    try {
      localStorage.setItem(STATS_KEY, JSON.stringify(stats));
    } catch (_) {
      /* El almacenamiento es opcional. */
    }

    renderStats();
  }

  function renderStats() {
    el.winsPlayer.textContent = stats.winsPlayer;
    el.winsBot.textContent = stats.winsBot;
    el.draws.textContent = stats.draws;

    const decided = stats.winsPlayer + stats.winsBot + stats.draws;

    el.winRate.textContent = decided
      ? `${Math.round((stats.winsPlayer / decided) * 100)}%`
      : "—";
  }

  function recordResultIfNeeded() {
    if (
      !state ||
      !state.gameOver ||
      !state.result ||
      resultRecordedForGeneration === generation
    ) {
      return;
    }

    resultRecordedForGeneration = generation;
    stats.games += 1;

    if (state.result.winner === "player") {
      stats.winsPlayer += 1;
    } else if (state.result.winner === "bot") {
      stats.winsBot += 1;
    } else {
      stats.draws += 1;
    }

    saveStats();
  }

  function setStatus(message, detail) {
    el.status.textContent = message;

    if (typeof detail === "string") {
      el.aiInfo.textContent = detail;
    }
  }

  /*
   * Puntos de dominó con una cuadrícula fija de 3 × 3.
   *
   * Posiciones:
   * 1 2 3
   * 4 5 6
   * 7 8 9
   *
   * 0: sin puntos
   * 1: centro
   * 2: diagonal
   * 3: diagonal y centro
   * 4: cuatro esquinas
   * 5: cuatro esquinas y centro
   * 6: dos columnas de tres puntos
   *
   * Los nueve elementos se conservan en el DOM.
   * La clase pip-on solo hace visibles los puntos activos.
   */
  function pipMarkup(value) {
    const patterns = {
      0: [],
      1: [5],
      2: [1, 9],
      3: [1, 5, 9],
      4: [1, 3, 7, 9],
      5: [1, 3, 5, 7, 9],
      6: [1, 3, 4, 6, 7, 9]
    };

    const numericValue = Number(value);
    const positions = patterns[numericValue] || [];

    let dots = "";

    for (let i = 1; i <= 9; i++) {
      const activeClass = positions.includes(i) ? " pip-on" : "";

      dots += `<i class="pip${activeClass}" aria-hidden="true"></i>`;
    }

    return `
      <div class="pips pips-${numericValue}" aria-hidden="true">
        ${dots}
      </div>
    `;
  }

  function tileMarkup(tile, className, label) {
    return `
      <div class="tile-half">
        ${pipMarkup(tile.a)}
      </div>
      <div class="tile-half">
        ${pipMarkup(tile.b)}
      </div>
    `;
  }

  function makeHandButton(tile, legalMoves, isTurn) {
    const button = document.createElement("button");

    const validMoves = legalMoves.filter(
      move => move.tileId === tile.id
    );

    button.type = "button";
    button.className = "tile hand-tile";

    if (validMoves.length) {
      button.classList.add("is-legal");
    } else {
      button.classList.add("is-illegal");
    }

    if (pendingTileId === tile.id) {
      button.classList.add("is-selected");
    }

    button.disabled = !isTurn || validMoves.length === 0;

    button.setAttribute(
      "aria-label",
      `Ficha ${tile.a} y ${tile.b}${
        validMoves.length
          ? ", jugada válida"
          : ", no se puede jugar ahora"
      }`
    );

    button.title = `${tile.a}-${tile.b}`;
    button.innerHTML = tileMarkup(tile, "hand-tile", `${tile.a}-${tile.b}`);

    button.addEventListener("click", () => {
      onHumanTileClick(tile.id);
    });

    return button;
  }

  function renderPlayerHand() {
    el.playerHand.replaceChildren();

    const isTurn =
      !!state &&
      !state.gameOver &&
      state.currentTurn === "player";

    const legal = state
      ? Engine.getLegalMoves(state, "player")
      : [];

    const ordered = state
      ? state.hands.player
          .slice()
          .sort((x, y) => x.a - y.a || x.b - y.b)
      : [];

    for (const tile of ordered) {
      el.playerHand.appendChild(
        makeHandButton(tile, legal, isTurn)
      );
    }
  }

  function renderBotHand() {
    el.botHand.replaceChildren();

    if (!state) {
      return;
    }

    for (let i = 0; i < state.hands.bot.length; i++) {
      const back = document.createElement("div");

      back.className = "tile-back";
      back.setAttribute("aria-hidden", "true");

      el.botHand.appendChild(back);
    }
  }

  function renderBoard() {
    el.board.replaceChildren();

    if (!state || state.board.length === 0) {
      const empty = document.createElement("div");

      empty.className = "empty-board";

      empty.innerHTML = `
        <div class="empty-symbol" aria-hidden="true">◇</div>
        <strong>La mesa está vacía</strong>
        <span>La primera ficha inicia la cadena</span>
      `;

      el.board.appendChild(empty);

      el.leftEnd.textContent = "—";
      el.rightEnd.textContent = "—";
      el.moveCounter.textContent = "0 jugadas";

      lastAnimatedMoveNumber = 0;

      return;
    }

    const width = Math.max(
      220,
      el.board.clientWidth || 400
    );

    const tileWidth = window.matchMedia("(max-width: 380px)").matches
      ? 42
      : window.matchMedia("(min-width: 640px)").matches
        ? 52
        : 47;

    const perRow = Math.max(
      3,
      Math.floor((width + 4) / (tileWidth + 4))
    );

    const shouldAnimate =
      state.lastMove &&
      state.lastMove.moveNumber > lastAnimatedMoveNumber;

    for (
      let start = 0, rowIndex = 0;
      start < state.board.length;
      start += perRow, rowIndex++
    ) {
      const row = document.createElement("div");

      row.className =
        "board-row" +
        (rowIndex % 2 === 1 ? " row-reverse" : "");

      const chunk = state.board.slice(
        start,
        start + perRow
      );

      for (const tile of chunk) {
        const piece = document.createElement("div");

        piece.className = "tile board-tile";

        if (
          shouldAnimate &&
          state.lastMove.tileId === tile.id
        ) {
          piece.classList.add("tile-new");
        }

        piece.setAttribute(
          "aria-label",
          `Ficha jugada ${tile.a}-${tile.b}`
        );

        const shown = rowIndex % 2 === 1
          ? {
              a: tile.b,
              b: tile.a,
              id: tile.id
            }
          : tile;

        piece.innerHTML = tileMarkup(
          shown,
          "board-tile",
          `${tile.a}-${tile.b}`
        );

        row.appendChild(piece);
      }

      el.board.appendChild(row);
    }

    const ends = Engine.getBoardEnds(state);

    el.leftEnd.textContent = ends ? ends.left : "—";
    el.rightEnd.textContent = ends ? ends.right : "—";

    el.moveCounter.textContent =
      `${state.moveCount} ${
        state.moveCount === 1 ? "jugada" : "jugadas"
      }`;

    if (state.lastMove) {
      lastAnimatedMoveNumber = state.lastMove.moveNumber;
    }
  }

  function renderSidePicker() {
    const isTurn =
      state &&
      !state.gameOver &&
      state.currentTurn === "player";

    if (!pendingTileId || !isTurn) {
      el.sidePicker.classList.add("is-hidden");
      return;
    }

    const options = Engine.getLegalMoves(state, "player")
      .filter(action => action.tileId === pendingTileId);

    const hasLeft = options.some(
      action => action.side === "left"
    );

    const hasRight = options.some(
      action => action.side === "right"
    );

    const center = options.some(
      action => action.side === "center"
    );

    if (options.length <= 1) {
      el.sidePicker.classList.add("is-hidden");
      return;
    }

    el.sidePicker.classList.remove("is-hidden");

    el.playLeft.classList.toggle("is-hidden", !hasLeft);
    el.playRight.classList.toggle("is-hidden", !hasRight);

    if (center) {
      el.sidePicker.classList.add("is-hidden");
    }
  }

  function updateControls() {
    const active = state && !state.gameOver;

    const isPlayer =
      active &&
      state.currentTurn === "player";

    const legal = isPlayer
      ? Engine.getLegalMoves(state, "player")
      : [];

    el.passButton.disabled = !isPlayer || legal.length > 0;
    el.hintButton.disabled = !isPlayer || legal.length === 0;

    el.playerCount.textContent = state
      ? state.hands.player.length
      : "0";

    el.botCount.textContent = state
      ? state.hands.bot.length
      : "0";

    if (!active) {
      el.playerSubtext.textContent = "Partida finalizada";
      el.botSubtext.textContent = "Esperando nueva partida";
      return;
    }

    if (state.currentTurn === "player") {
      el.botSubtext.textContent = "Esperando tu jugada";

      if (!legal.length) {
        el.playerSubtext.textContent =
          "No tienes jugada: puedes pasar";
      } else if (state.board.length === 0) {
        el.playerSubtext.textContent =
          "Ficha de salida obligatoria";
      } else {
        el.playerSubtext.textContent =
          "Toca una ficha iluminada";
      }
    } else {
      el.playerSubtext.textContent = "El bot está pensando";
      el.botSubtext.textContent =
        "Calculando continuaciones probables…";
    }
  }

  function render() {
    renderPlayerHand();
    renderBotHand();
    renderBoard();
    renderSidePicker();
    updateControls();
    recordResultIfNeeded();
  }

  function maybeScheduleBot() {
    if (
      !state ||
      state.gameOver ||
      state.currentTurn !== "bot"
    ) {
      return;
    }

    const thisGeneration = generation;

    setStatus(
      "🤖 El bot está evaluando la mesa…",
      "Simulando manos compatibles con el historial de pases."
    );

    botTimer = window.setTimeout(() => {
      if (
        thisGeneration !== generation ||
        !state ||
        state.gameOver ||
        state.currentTurn !== "bot"
      ) {
        return;
      }

      performBotTurn();
    }, 420);
  }

  function difficultyName(value) {
    return AI.PRESETS[value]
      ? AI.PRESETS[value].label
      : "Difícil";
  }

  function openingRuleDescription() {
    if (!state || !state.openingTileId) {
      return "Juega la ficha de salida obligatoria.";
    }

    const tile = state.hands.player
      .concat(state.hands.bot)
      .find(t => t.id === state.openingTileId);

    if (!tile) {
      return "Juega la ficha de salida obligatoria.";
    }

    return tile.a === tile.b
      ? `La salida obliga a jugar el doble ${tile.a}-${tile.a}.`
      : `No había dobles: la salida obliga a jugar ${tile.a}-${tile.b}, la ficha con mayor suma.`;
  }

  function performBotTurn() {
    if (
      !state ||
      state.gameOver ||
      state.currentTurn !== "bot"
    ) {
      return;
    }

    const legal = Engine.getLegalMoves(state, "bot");

    if (!legal.length) {
      Engine.passTurn(state, "bot");
      render();

      if (state.gameOver) {
        setStatus(
          state.result.message,
          "Partida bloqueada: gana quien conserva menos puntos."
        );
      } else {
        setStatus(
          "🤖 El bot pasa. No tenía ninguna jugada legal.",
          "Las restricciones del pase se guardaron en el historial."
        );

        if (Engine.getLegalMoves(state, "player").length === 0) {
          setStatusForPlayerTurn();
        }
      }

      return;
    }

    const level = el.difficulty.value;

    const decision = AI.chooseMove(
      state,
      "bot",
      { difficulty: level }
    );

    if (
      !decision.action ||
      !Engine.applyMove(state, "bot", decision.action)
    ) {
      const fallback = Engine.getLegalMoves(state, "bot")[0];

      if (!fallback) {
        Engine.passTurn(state, "bot");
      } else {
        Engine.applyMove(state, "bot", fallback);
      }
    }

    const statsText =
      `${difficultyName(level)} · ` +
      `${decision.stats.simulations} simulaciones por opción · ` +
      `${decision.stats.candidates} opciones · ` +
      `${decision.stats.elapsedMs} ms`;

    render();

    if (state.gameOver) {
      setStatus(state.result.message, statsText);
      return;
    }

    const played = decision.action
      ? decision.action.tileId.replace("-", "–")
      : "una ficha";

    setStatus(
      `🤖 El bot juega ${played}. Tu turno.`,
      statsText
    );

    if (Engine.getLegalMoves(state, "player").length === 0) {
      setStatusForPlayerTurn();
    }
  }

  function setStatusForPlayerTurn() {
    if (!state || state.gameOver) {
      if (state && state.result) {
        setStatus(
          state.result.message,
          "Pulsa Nueva partida para volver a jugar."
        );
      }

      return;
    }

    if (state.currentTurn !== "player") {
      return;
    }

    const legal = Engine.getLegalMoves(state, "player");

    if (!legal.length) {
      setStatus(
        "No tienes ninguna jugada legal. Pulsa Pasar.",
        "El bot no puede ver tus fichas; solo conoce el historial público."
      );
    } else if (state.board.length === 0) {
      setStatus(
        "Tu turno. Juega la ficha de salida indicada.",
        openingRuleDescription()
      );
    } else {
      setStatus(
        "Tu turno. Elige una ficha iluminada.",
        "La IA recuerda los pases y evalúa continuaciones posibles."
      );
    }
  }

  function startNewGame() {
    if (botTimer !== null) {
      window.clearTimeout(botTimer);
    }

    botTimer = null;
    generation += 1;
    pendingTileId = null;
    hintAction = null;
    lastAnimatedMoveNumber = 0;
    resultRecordedForGeneration = -1;

    state = Engine.startGame();

    render();

    if (state.currentTurn === "bot") {
      setStatus(
        "🤖 El bot tiene la salida.",
        openingRuleDescription()
      );

      maybeScheduleBot();
    } else {
      setStatus(
        "Te toca abrir la partida.",
        "Juega la ficha de salida obligatoria; después podrás elegir cualquier jugada legal."
      );
    }
  }

  function onHumanTileClick(tileId) {
    if (
      !state ||
      state.gameOver ||
      state.currentTurn !== "player"
    ) {
      return;
    }

    const legal = Engine.getLegalMoves(state, "player")
      .filter(action => action.tileId === tileId);

    if (!legal.length) {
      setStatus(
        "Esa ficha no conecta con los extremos de la mesa.",
        "Elige una ficha iluminada o pasa si no tienes jugadas."
      );

      return;
    }

    pendingTileId = tileId;

    if (legal.length === 1) {
      playHumanMove(legal[0]);
      return;
    }

    render();

    setStatus(
      `Ficha ${tileId.replace("-", "–")}: puedes jugar por ambos lados.`,
      "Selecciona izquierda o derecha para confirmar."
    );
  }

  function playHumanMove(action) {
    if (
      !state ||
      state.gameOver ||
      state.currentTurn !== "player"
    ) {
      return;
    }

    const playedId = action.tileId;

    if (!Engine.applyMove(state, "player", action)) {
      setStatus(
        "No se pudo aplicar esa jugada. Elige otra ficha."
      );

      pendingTileId = null;
      render();

      return;
    }

    pendingTileId = null;
    hintAction = null;

    render();

    if (state.gameOver) {
      setStatus(
        state.result.message,
        "Partida finalizada. Las estadísticas se guardaron en este dispositivo."
      );

      return;
    }

    setStatus(
      `Jugaste ${playedId.replace("-", "–")}. El bot está pensando…`,
      "La IA está comparando jugadas posibles."
    );

    maybeScheduleBot();
  }

  function playSelectedSide(side) {
    if (!state || !pendingTileId) {
      return;
    }

    const action = Engine.getLegalMoves(state, "player")
      .find(
        move =>
          move.tileId === pendingTileId &&
          move.side === side
      );

    if (action) {
      playHumanMove(action);
    }
  }

  function onPlayerPass() {
    if (
      !state ||
      state.gameOver ||
      state.currentTurn !== "player"
    ) {
      return;
    }

    if (!Engine.passTurn(state, "player")) {
      setStatus(
        "No puedes pasar si tienes una jugada legal.",
        "Debes jugar una de las fichas iluminadas."
      );

      return;
    }

    pendingTileId = null;
    hintAction = null;

    render();

    if (state.gameOver) {
      setStatus(
        state.result.message,
        "La partida terminó bloqueada: se compararon los puntos restantes."
      );

      return;
    }

    setStatus(
      "Pasaste. Turno del bot.",
      "El pase se usará para inferir qué números no tienes."
    );

    maybeScheduleBot();
  }

  function onHint() {
    if (
      !state ||
      state.gameOver ||
      state.currentTurn !== "player"
    ) {
      return;
    }

    const legal = Engine.getLegalMoves(state, "player");

    if (!legal.length) {
      return;
    }

    setStatus(
      "✦ Calculando una pista…",
      "La IA está comparando continuaciones para tu mano."
    );

    window.setTimeout(() => {
      if (
        !state ||
        state.gameOver ||
        state.currentTurn !== "player"
      ) {
        return;
      }

      const level = el.difficulty.value;

      const decision = AI.chooseMove(
        state,
        "player",
        { difficulty: level }
      );

      if (!decision.action) {
        return;
      }

      hintAction = decision.action;
      pendingTileId = decision.action.tileId;

      render();

      const placement =
        decision.action.side === "left"
          ? "por la izquierda"
          : decision.action.side === "right"
            ? "por la derecha"
            : "como primera ficha";

      setStatus(
        `✦ Pista: juega ${decision.action.tileId.replace("-", "–")} ${placement}.`,
        "Es una recomendación probabilística, no una jugada garantizada."
      );

      el.aiInfo.textContent =
        `Pista ${difficultyName(level)} · ` +
        `${decision.stats.simulations} simulaciones por opción · ` +
        `${decision.stats.elapsedMs} ms`;
    }, 25);
  }

  el.newGame.addEventListener("click", startNewGame);
  el.passButton.addEventListener("click", onPlayerPass);
  el.hintButton.addEventListener("click", onHint);

  el.playLeft.addEventListener("click", () => {
    playSelectedSide("left");
  });

  el.playRight.addEventListener("click", () => {
    playSelectedSide("right");
  });

  el.difficulty.addEventListener("change", () => {
    if (
      state &&
      !state.gameOver &&
      state.currentTurn === "player"
    ) {
      el.aiInfo.textContent =
        `Dificultad seleccionada: ${difficultyName(el.difficulty.value)}.`;
    }
  });

  window.addEventListener(
    "resize",
    () => {
      if (state) {
        renderBoard();
      }
    },
    { passive: true }
  );

  renderStats();
  startNewGame();
})();
