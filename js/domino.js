// ============================================================
// DOMINÓ CLÁSICO DOBLE-SEIS
// 1 vs 1
// 28 fichas
// 7 fichas por jugador
// ============================================================

const playerHandElement = document.getElementById("playerHand");
const botHandElement = document.getElementById("botHand");
const boardElement = document.getElementById("board");

const statusElement = document.getElementById("status");

const playerCountElement = document.getElementById("playerCount");
const botCountElement = document.getElementById("botCount");

const newGameButton = document.getElementById("newGame");

// ------------------------------------------------------------
// ESTADO DEL JUEGO
// ------------------------------------------------------------

let playerHand = [];
let botHand = [];

let boneyard = [];

let board = [];

let currentTurn = "player";

let gameOver = false;


// ------------------------------------------------------------
// CREAR LAS 28 FICHAS
// ------------------------------------------------------------

function createDominoSet() {

    const tiles = [];

    for (let a = 0; a <= 6; a++) {

        for (let b = a; b <= 6; b++) {

            tiles.push({
                a: a,
                b: b,
                id: `${a}-${b}`
            });

        }

    }

    return tiles;
}


// ------------------------------------------------------------
// BARAJAR
// ------------------------------------------------------------

function shuffle(array) {

    for (let i = array.length - 1; i > 0; i--) {

        const j = Math.floor(Math.random() * (i + 1));

        [array[i], array[j]] = [array[j], array[i]];
    }

    return array;
}


// ------------------------------------------------------------
// INICIAR PARTIDA
// ------------------------------------------------------------

function startGame() {

    gameOver = false;

    board = [];

    currentTurn = "player";

    let tiles = createDominoSet();

    shuffle(tiles);

    playerHand = tiles.splice(0, 7);

    botHand = tiles.splice(0, 7);

    boneyard = tiles;

    render();

    statusElement.textContent = "Tu turno. Elige una ficha.";

}


// ------------------------------------------------------------
// RENDERIZAR TODO
// ------------------------------------------------------------

function render() {

    renderPlayerHand();

    renderBotHand();

    renderBoard();

    playerCountElement.textContent = playerHand.length;

    botCountElement.textContent = botHand.length;
}


// ------------------------------------------------------------
// MOSTRAR FICHAS DEL JUGADOR
// ------------------------------------------------------------

function renderPlayerHand() {

    playerHandElement.innerHTML = "";

    playerHand.forEach((tile, index) => {

        const element = createTileElement(tile);

        element.addEventListener("click", () => {

            if (gameOver) return;

            if (currentTurn !== "player") return;

            playPlayerTile(index);

        });

        playerHandElement.appendChild(element);

    });

}


// ------------------------------------------------------------
// MOSTRAR FICHAS DEL BOT
// ------------------------------------------------------------

function renderBotHand() {

    botHandElement.innerHTML = "";

    botHand.forEach(() => {

        const element = document.createElement("div");

        element.className = "domino hidden-domino";

        element.innerHTML = `
            <div class="domino-half"></div>
            <div class="domino-half"></div>
        `;

        botHandElement.appendChild(element);

    });

}


// ------------------------------------------------------------
// CREAR ELEMENTO VISUAL DE FICHA
// ------------------------------------------------------------

function createTileElement(tile) {

    const element = document.createElement("div");

    element.className = "domino";

    element.innerHTML = `
        <div class="domino-half">${tile.a}</div>
        <div class="domino-half">${tile.b}</div>
    `;

    return element;
}


// ------------------------------------------------------------
// MOSTRAR MESA
// ------------------------------------------------------------

function renderBoard() {

    boardElement.innerHTML = "";

    if (board.length === 0) {

        boardElement.innerHTML =
            `<div class="empty-board">La mesa está vacía</div>`;

        return;
    }

    board.forEach(tile => {

        const element = createTileElement(tile);

        boardElement.appendChild(element);

    });

}


// ------------------------------------------------------------
// EXTREMOS DE LA MESA
// ------------------------------------------------------------

function getBoardEnds() {

    if (board.length === 0) {

        return null;
    }

    return {
        left: board[0].a,
        right: board[board.length - 1].b
    };
}


// ------------------------------------------------------------
// ¿PUEDE JUGARSE UNA FICHA?
// ------------------------------------------------------------

function canPlayTile(tile) {

    if (board.length === 0) {

        return true;
    }

    const ends = getBoardEnds();

    return (
        tile.a === ends.left ||
        tile.b === ends.left ||
        tile.a === ends.right ||
        tile.b === ends.right
    );
}


// ------------------------------------------------------------
// JUGADA DEL JUGADOR
// ------------------------------------------------------------

function playPlayerTile(index) {

    const tile = playerHand[index];

    if (!canPlayTile(tile)) {

        statusElement.textContent =
            "Esa ficha no se puede jugar ahora.";

        return;
    }

    playerHand.splice(index, 1);

    placeTile(tile);

    render();

    if (checkWinner()) return;

    currentTurn = "bot";

    statusElement.textContent = "El bot está pensando...";

    setTimeout(botTurn, 700);

}


// ------------------------------------------------------------
// COLOCAR FICHA EN LA MESA
// ------------------------------------------------------------

function placeTile(tile) {

    if (board.length === 0) {

        board.push({
            a: tile.a,
            b: tile.b
        });

        return;
    }

    const ends = getBoardEnds();

    // Intentar colocar en la izquierda
    if (tile.b === ends.left) {

        board.unshift({
            a: tile.a,
            b: tile.b
        });

        return;
    }

    if (tile.a === ends.left) {

        board.unshift({
            a: tile.b,
            b: tile.a
        });

        return;
    }

    // Intentar colocar en la derecha
    if (tile.a === ends.right) {

        board.push({
            a: tile.a,
            b: tile.b
        });

        return;
    }

    if (tile.b === ends.right) {

        board.push({
            a: tile.b,
            b: tile.a
        });

        return;
    }

}


// ------------------------------------------------------------
// TURNO DEL BOT
// ------------------------------------------------------------

function botTurn() {

    if (gameOver) return;

    let playable = [];

    botHand.forEach((tile, index) => {

        if (canPlayTile(tile)) {

            playable.push({
                tile: tile,
                index: index
            });

        }

    });


    // --------------------------------------------------------
    // SI NO PUEDE JUGAR
    // --------------------------------------------------------

    if (playable.length === 0) {

        if (boneyard.length > 0) {

            const drawn = boneyard.pop();

            botHand.push(drawn);

            render();

            statusElement.textContent =
                "El bot roba una ficha.";

            setTimeout(botTurn, 600);

            return;
        }

        // No puede jugar y no quedan fichas para robar
        statusElement.textContent =
            "El bot pasa.";

        currentTurn = "player";

        checkBlockedGame();

        if (!gameOver) {

            statusElement.textContent =
                "Tu turno.";

        }

        return;
    }


    // --------------------------------------------------------
    // ELEGIR JUGADA
    // --------------------------------------------------------

    const chosen = chooseBotMove(playable);

    botHand.splice(chosen.index, 1);

    placeTile(chosen.tile);

    render();

    if (checkWinner()) return;

    currentTurn = "player";

    statusElement.textContent =
        "Tu turno.";

    checkBlockedGame();

}


// ------------------------------------------------------------
// ESTRATEGIA BÁSICA DEL BOT
// ------------------------------------------------------------

function chooseBotMove(playable) {

    /*
        Primera estrategia:

        1. Preferir dobles.
        2. Si no, jugar la ficha con mayor cantidad de puntos.

        Más adelante reemplazaremos esta función por
        el verdadero motor estratégico.
    */

    playable.sort((x, y) => {

        const xDouble = x.tile.a === x.tile.b;
        const yDouble = y.tile.a === y.tile.b;

        if (xDouble && !yDouble) return -1;

        if (!xDouble && yDouble) return 1;

        const xValue = x.tile.a + x.tile.b;
        const yValue = y.tile.a + y.tile.b;

        return yValue - xValue;

    });

    return playable[0];
}


// ------------------------------------------------------------
// COMPROBAR GANADOR
// ------------------------------------------------------------

function checkWinner() {

    if (playerHand.length === 0) {

        endGame("🎉 ¡Ganaste la partida!");

        return true;
    }

    if (botHand.length === 0) {

        endGame("🤖 El bot ganó la partida.");

        return true;
    }

    return false;
}


// ------------------------------------------------------------
// DETECTAR PARTIDA BLOQUEADA
// ------------------------------------------------------------

function checkBlockedGame() {

    if (boneyard.length > 0) {

        return;
    }

    const playerCanPlay =
        playerHand.some(tile => canPlayTile(tile));

    const botCanPlay =
        botHand.some(tile => canPlayTile(tile));

    if (!playerCanPlay && !botCanPlay) {

        let playerPoints = calculatePoints(playerHand);
        let botPoints = calculatePoints(botHand);

        if (playerPoints < botPoints) {

            endGame(
                `🔒 Partida bloqueada. Ganaste por puntos (${playerPoints} vs ${botPoints}).`
            );

        } else if (botPoints < playerPoints) {

            endGame(
                `🔒 Partida bloqueada. El bot gana por puntos (${botPoints} vs ${playerPoints}).`
            );

        } else {

            endGame(
                `🔒 Partida bloqueada. Empate (${playerPoints} - ${botPoints}).`
            );

        }

    }

}


// ------------------------------------------------------------
// CONTAR PUNTOS
// ------------------------------------------------------------

function calculatePoints(hand) {

    return hand.reduce(
        (total, tile) => total + tile.a + tile.b,
        0
    );

}


// ------------------------------------------------------------
// TERMINAR PARTIDA
// ------------------------------------------------------------

function endGame(message) {

    gameOver = true;

    statusElement.textContent = message;

}


// ------------------------------------------------------------
// NUEVA PARTIDA
// ------------------------------------------------------------

newGameButton.addEventListener("click", startGame);


// ------------------------------------------------------------
// ARRANCAR
// ------------------------------------------------------------

startGame();
