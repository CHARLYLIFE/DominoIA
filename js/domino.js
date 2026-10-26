"use strict";

/*
============================================================
DOMINÓ CLÁSICO DOBLE-SEIS
1 VS 1
28 FICHAS TOTALES
7 PARA EL JUGADOR
7 PARA EL BOT
14 QUEDAN FUERA

NO EXISTE ROBO.

SI NO PUEDES JUGAR:
PASAS.

============================================================
*/


// ============================================================
// ELEMENTOS
// ============================================================

const playerHandElement =
    document.getElementById("playerHand");

const botHandElement =
    document.getElementById("botHand");

const boardElement =
    document.getElementById("board");

const statusElement =
    document.getElementById("status");

const playerCountElement =
    document.getElementById("playerCount");

const botCountElement =
    document.getElementById("botCount");

const newGameButton =
    document.getElementById("newGame");

const passButton =
    document.getElementById("passButton");


// ============================================================
// ESTADO
// ============================================================

let playerHand = [];

let botHand = [];

let board = [];

let currentTurn = "player";

let gameOver = false;

let playerPassed = false;

let botPassed = false;


// ============================================================
// CREAR LAS 28 FICHAS
// ============================================================

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


// ============================================================
// BARAJAR
// ============================================================

function shuffle(array) {

    for (let i = array.length - 1; i > 0; i--) {

        const j =
            Math.floor(Math.random() * (i + 1));

        [
            array[i],
            array[j]
        ] =
        [
            array[j],
            array[i]
        ];
    }

    return array;
}


// ============================================================
// INICIAR PARTIDA
// ============================================================

function startGame() {

    gameOver = false;

    board = [];

    playerPassed = false;

    botPassed = false;


    let tiles =
        shuffle(createDominoSet());


    // 7 PARA CADA UNO

    playerHand =
        tiles.splice(0, 7);

    botHand =
        tiles.splice(0, 7);


    // LAS OTRAS 14 NO PARTICIPAN


    // Determinar quién empieza

    const playerDouble =
        getHighestDouble(playerHand);

    const botDouble =
        getHighestDouble(botHand);


    if (
        playerDouble !== null &&
        botDouble !== null
    ) {

        currentTurn =
            playerDouble >= botDouble
                ? "player"
                : "bot";

    }

    else if (playerDouble !== null) {

        currentTurn = "player";

    }

    else if (botDouble !== null) {

        currentTurn = "bot";

    }

    else {

        const playerValue =
            getHighestValue(playerHand);

        const botValue =
            getHighestValue(botHand);


        currentTurn =
            playerValue >= botValue
                ? "player"
                : "bot";
    }


    render();


    if (currentTurn === "player") {

        statusElement.textContent =
            "Tu turno. Elige una ficha.";

    } else {

        statusElement.textContent =
            "El bot empieza...";

        setTimeout(
            botTurn,
            700
        );
    }
}


// ============================================================
// DOBLE MÁS ALTO
// ============================================================

function getHighestDouble(hand) {

    let highest = null;


    for (const tile of hand) {

        if (tile.a === tile.b) {

            if (
                highest === null ||
                tile.a > highest
            ) {

                highest = tile.a;
            }
        }
    }


    return highest;
}


// ============================================================
// MAYOR VALOR
// ============================================================

function getHighestValue(hand) {

    let highest = -1;


    for (const tile of hand) {

        const value =
            tile.a + tile.b;


        if (value > highest) {

            highest = value;
        }
    }


    return highest;
}


// ============================================================
// RENDER
// ============================================================

function render() {

    renderPlayerHand();

    renderBotHand();

    renderBoard();


    playerCountElement.textContent =
        playerHand.length;

    botCountElement.textContent =
        botHand.length;
}


// ============================================================
// RENDER JUGADOR
// ============================================================

function renderPlayerHand() {

    playerHandElement.innerHTML = "";


    playerHand.forEach(
        (tile, index) => {

            const element =
                createTileElement(tile);


            element.classList.add(
                "player-tile"
            );


            if (
                currentTurn !== "player" ||
                !canPlayTile(tile)
            ) {

                element.classList.add(
                    "disabled"
                );
            }


            element.addEventListener(
                "click",
                () => {

                    playPlayerTile(index);

                }
            );


            playerHandElement.appendChild(
                element
            );
        }
    );
}


// ============================================================
// RENDER BOT
// ============================================================

function renderBotHand() {

    botHandElement.innerHTML = "";


    botHand.forEach(() => {

        const element =
            document.createElement("div");


        element.className =
            "domino hidden-domino";


        element.innerHTML = `
            <div class="domino-half"></div>
            <div class="domino-half"></div>
        `;


        botHandElement.appendChild(
            element
        );
    });
}


// ============================================================
// CREAR FICHA
// ============================================================

function createTileElement(tile) {

    const element =
        document.createElement("div");


    element.className =
        "domino";


    element.innerHTML = `
        <div class="domino-half">
            ${tile.a}
        </div>

        <div class="domino-half">
            ${tile.b}
        </div>
    `;


    return element;
}


// ============================================================
// RENDER MESA
// ============================================================

function renderBoard() {

    boardElement.innerHTML = "";


    if (board.length === 0) {

        boardElement.innerHTML =
            `<div class="empty-board">
                La mesa está vacía
             </div>`;

        return;
    }


    board.forEach(tile => {

        boardElement.appendChild(
            createTileElement(tile)
        );

    });
}


// ============================================================
// EXTREMOS
// ============================================================

function getBoardEnds() {

    if (board.length === 0) {

        return null;
    }


    return {

        left:
            board[0].a,

        right:
            board[board.length - 1].b
    };
}


// ============================================================
// ¿PUEDE JUGAR?
// ============================================================

function canPlayTile(tile) {

    if (board.length === 0) {

        return true;
    }


    const ends =
        getBoardEnds();


    return (
        tile.a === ends.left ||
        tile.b === ends.left ||
        tile.a === ends.right ||
        tile.b === ends.right
    );
}


// ============================================================
// JUGADA DEL JUGADOR
// ============================================================

function playPlayerTile(index) {

    if (gameOver) return;


    if (currentTurn !== "player") {

        return;
    }


    const tile =
        playerHand[index];


    if (!canPlayTile(tile)) {

        statusElement.textContent =
            "No puedes jugar esa ficha.";

        return;
    }


    playerHand.splice(
        index,
        1
    );


    placeTile(tile);


    playerPassed = false;


    render();


    if (checkWinner()) {

        return;
    }


    currentTurn = "bot";


    statusElement.textContent =
        "El bot está pensando...";


    setTimeout(
        botTurn,
        650
    );
}


// ============================================================
// COLOCAR FICHA
// ============================================================

function placeTile(tile) {

    // Primera ficha

    if (board.length === 0) {

        board.push({
            a: tile.a,
            b: tile.b
        });

        return;
    }


    const ends =
        getBoardEnds();


    // --------------------------------------------------------
    // IZQUIERDA
    // --------------------------------------------------------

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


    // --------------------------------------------------------
    // DERECHA
    // --------------------------------------------------------

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


// ============================================================
// BOT
// ============================================================

function botTurn() {

    if (gameOver) return;


    const playable = [];


    botHand.forEach(
        (tile, index) => {

            if (canPlayTile(tile)) {

                playable.push({
                    tile: tile,
                    index: index
                });

            }
        }
    );


    // --------------------------------------------------------
    // NO PUEDE JUGAR
    // --------------------------------------------------------

    if (playable.length === 0) {

        botPassed = true;


        statusElement.textContent =
            "🤖 El bot pasa.";


        if (
            playerPassed &&
            botPassed
        ) {

            endBlockedGame();

            return;
        }


        currentTurn =
            "player";


        render();


        statusElement.textContent =
            "Tu turno.";

        return;
    }


    // --------------------------------------------------------
    // ELEGIR JUGADA
    // --------------------------------------------------------

    const chosen =
        chooseBotMove(playable);


    botHand.splice(
        chosen.index,
        1
    );


    placeTile(chosen.tile);


    botPassed = false;


    render();


    if (checkWinner()) {

        return;
    }


    currentTurn =
        "player";


    statusElement.textContent =
        "Tu turno.";
}


// ============================================================
// ESTRATEGIA BÁSICA DEL BOT
// ============================================================

function chooseBotMove(playable) {

    let best =
        playable[0];

    let bestScore =
        -Infinity;


    for (const candidate of playable) {

        const tile =
            candidate.tile;


        let score = 0;


        // Dobles

        if (tile.a === tile.b) {

            score += 20;
        }


        // Valor

        score +=
            (tile.a + tile.b) * 2;


        // Favorecer números
        // que el bot conserva

        score +=
            countNumberInHand(
                botHand,
                tile.a
            ) * 3;


        score +=
            countNumberInHand(
                botHand,
                tile.b
            ) * 3;


        if (score > bestScore) {

            bestScore =
                score;

            best =
                candidate;
        }
    }


    return best;
}


// ============================================================
// CONTAR NÚMERO
// ============================================================

function countNumberInHand(
    hand,
    number
) {

    let count = 0;


    for (const tile of hand) {

        if (tile.a === number) {

            count++;
        }

        if (
            tile.b === number &&
            tile.a !== number
        ) {

            count++;
        }
    }


    return count;
}


// ============================================================
// PASAR JUGADOR
// ============================================================

function playerPass() {

    if (gameOver) return;


    if (currentTurn !== "player") {

        return;
    }


    // No permitir pasar si sí puede jugar

    const canPlay =
        playerHand.some(
            tile => canPlayTile(tile)
        );


    if (canPlay) {

        statusElement.textContent =
            "Tienes una jugada disponible. No puedes pasar.";

        return;
    }


    playerPassed = true;


    statusElement.textContent =
        "Pasaste.";


    if (botPassed) {

        endBlockedGame();

        return;
    }


    currentTurn =
        "bot";


    setTimeout(
        botTurn,
        650
    );
}


// ============================================================
// COMPROBAR GANADOR
// ============================================================

function checkWinner() {

    if (
        playerHand.length === 0
    ) {

        endGame(
            "🎉 ¡Ganaste!"
        );

        return true;
    }


    if (
        botHand.length === 0
    ) {

        endGame(
            "🤖 El bot ganó."
        );

        return true;
    }


    return false;
}


// ============================================================
// PARTIDA BLOQUEADA
// ============================================================

function endBlockedGame() {

    const playerPoints =
        calculatePoints(playerHand);

    const botPoints =
        calculatePoints(botHand);


    if (
        playerPoints <
        botPoints
    ) {

        endGame(
            `🔒 Partida bloqueada. Ganaste por puntos: ${playerPoints} vs ${botPoints}.`
        );

    }

    else if (
        botPoints <
        playerPoints
    ) {

        endGame(
            `🔒 Partida bloqueada. El bot gana por puntos: ${botPoints} vs ${playerPoints}.`
        );

    }

    else {

        endGame(
            `🔒 Empate. Ambos tienen ${playerPoints} puntos.`
        );
    }
}


// ============================================================
// PUNTOS
// ============================================================

function calculatePoints(hand) {

    return hand.reduce(
        (total, tile) => {

            return total +
                tile.a +
                tile.b;

        },
        0
    );
}


// ============================================================
// FINALIZAR
// ============================================================

function endGame(message) {

    gameOver = true;

    currentTurn = null;

    statusElement.textContent =
        message;


    render();
}


// ============================================================
// BOTÓN PASAR
// ============================================================

passButton.addEventListener(
    "click",
    playerPass
);


// ============================================================
// NUEVA PARTIDA
// ============================================================

newGameButton.addEventListener(
    "click",
    startGame
);


// ============================================================
// INICIAR
// ============================================================

startGame();
