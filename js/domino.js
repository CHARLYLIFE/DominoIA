"use strict";

/*
============================================================
DOMINÓ DOBLE-SEIS — 1 VS 1
7 fichas por jugador
SIN ROBO

Esta versión utiliza una mesa real:
las fichas se organizan como una cadena,
creciendo desde el centro hacia ambos lados.
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
// NUEVA PARTIDA
// ============================================================

function startGame() {

    gameOver = false;

    board = [];

    playerPassed = false;

    botPassed = false;


    const tiles =
        shuffle(createDominoSet());


    // 7 PARA CADA UNO

    playerHand =
        tiles.splice(0, 7);

    botHand =
        tiles.splice(0, 7);


    // DETERMINAR QUIÉN EMPIEZA

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
            "🤖 El bot empieza...";

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
// VALOR MÁS ALTO
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
// RENDER GENERAL
// ============================================================

function render() {

    renderPlayerHand();

    renderBotHand();

    renderBoard();


    playerCountElement.textContent =
        playerHand.length;

    botCountElement.textContent =
        botHand.length;


    updatePassButton();
}


// ============================================================
// MANO DEL JUGADOR
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
// MANO DEL BOT
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
// MESA
// ============================================================

function renderBoard() {

    boardElement.innerHTML = "";


    if (board.length === 0) {

        boardElement.innerHTML =
            `
            <div class="empty-board">
                La mesa está vacía
            </div>
            `;

        return;
    }


    /*
    ------------------------------------------------------------
    Construimos la cadena de forma visual.

    El primer grupo contiene la ficha central.
    Después añadimos fichas hacia izquierda y derecha.

    Para que la mesa no sea una fila infinita,
    usamos filas visuales.
    ------------------------------------------------------------
    */


    const maxPerRow =
        getMaxTilesPerRow();


    let rows = [];

    let currentRow = [];


    board.forEach(
        (tile, index) => {

            currentRow.push(tile);


            if (
                currentRow.length >=
                maxPerRow
            ) {

                rows.push(currentRow);

                currentRow = [];
            }

        }
    );


    if (currentRow.length > 0) {

        rows.push(currentRow);

    }


    /*
    Si hay varias filas, mostramos
    la cadena en bloques.
    */

    rows.forEach(
        (row, rowIndex) => {

            const rowElement =
                document.createElement("div");


            rowElement.className =
                "board-row";


            /*
            Alternamos la dirección visual
            para dar sensación de recorrido.
            */

            if (rowIndex % 2 === 1) {

                row.reverse();

            }


            row.forEach(
                tile => {

                    const element =
                        createTileElement(tile);


                    rowElement.appendChild(
                        element
                    );

                }
            );


            boardElement.appendChild(
                rowElement
            );

        }
    );
}


// ============================================================
// CUÁNTAS FICHAS CABEN
// ============================================================

function getMaxTilesPerRow() {

    const width =
        boardElement.clientWidth;


    if (width < 360) {

        return 5;

    }


    if (width < 500) {

        return 7;

    }


    if (width < 700) {

        return 9;

    }


    return 11;
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
// JUGAR FICHA DEL JUGADOR
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
            "Esa ficha no puede jugarse.";

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
        "🤖 El bot está pensando...";


    setTimeout(
        botTurn,
        650
    );
}


// ============================================================
// COLOCAR FICHA
// ============================================================

function placeTile(tile) {

    /*
    ============================================================
    PRIMERA FICHA
    ============================================================
    */

    if (board.length === 0) {

        board.push({
            a: tile.a,
            b: tile.b
        });

        return;
    }


    const ends =
        getBoardEnds();


    /*
    ============================================================
    IZQUIERDA
    ============================================================
    */

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


    /*
    ============================================================
    DERECHA
    ============================================================
    */

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
// TURNO DEL BOT
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


    /*
    ============================================================
    NO PUEDE JUGAR
    ============================================================
    */

    if (playable.length === 0) {

        botPassed = true;


        statusElement.textContent =
            "🤖 El bot pasa.";


        if (playerPassed) {

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


    /*
    ============================================================
    ELEGIR JUGADA
    ============================================================
    */

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
// ESTRATEGIA ACTUAL DEL BOT
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

        if (
            tile.a ===
            tile.b
        ) {

            score += 20;
        }


        // Puntos

        score +=
            (tile.a + tile.b) * 2;


        // Mantener números
        // que el bot tiene repetidos

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


        if (
            score >
            bestScore
        ) {

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

        if (
            tile.a === number
        ) {

            count++;

        }

        else if (
            tile.b === number
        ) {

            count++;

        }
    }


    return count;
}


// ============================================================
// PASAR
// ============================================================

function playerPass() {

    if (gameOver) return;

    if (
        currentTurn !==
        "player"
    ) {

        return;
    }


    const playable =
        playerHand.some(
            tile =>
                canPlayTile(tile)
        );


    if (playable) {

        statusElement.textContent =
            "Tienes una jugada disponible.";

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


    render();


    setTimeout(
        botTurn,
        600
    );
}


// ============================================================
// BOTÓN PASAR
// ============================================================

function updatePassButton() {

    if (!passButton) return;


    const canPass =
        currentTurn === "player" &&
        !gameOver &&
        playerHand.length > 0 &&
        !playerHand.some(
            tile =>
                canPlayTile(tile)
        );


    passButton.disabled =
        !canPass;


    passButton.style.opacity =
        canPass
            ? "1"
            : ".45";
}


// ============================================================
// GANADOR
// ============================================================

function checkWinner() {

    if (
        playerHand.length === 0
    ) {

        endGame(
            "🎉 ¡Ganaste la partida!"
        );

        return true;
    }


    if (
        botHand.length === 0
    ) {

        endGame(
            "🤖 El bot ganó la partida."
        );

        return true;
    }


    return false;
}


// ============================================================
// BLOQUEO
// ============================================================

function endBlockedGame() {

    const playerPoints =
        calculatePoints(
            playerHand
        );


    const botPoints =
        calculatePoints(
            botHand
        );


    if (
        playerPoints <
        botPoints
    ) {

        endGame(
            `🔒 Partida bloqueada. Ganaste por ${playerPoints} a ${botPoints}.`
        );

    }

    else if (
        botPoints <
        playerPoints
    ) {

        endGame(
            `🔒 Partida bloqueada. El bot gana ${botPoints} a ${playerPoints}.`
        );

    }

    else {

        endGame(
            `🔒 Partida bloqueada. Empate ${playerPoints} a ${botPoints}.`
        );
    }
}


// ============================================================
// PUNTOS
// ============================================================

function calculatePoints(hand) {

    return hand.reduce(
        (total, tile) =>
            total +
            tile.a +
            tile.b,
        0
    );
}


// ============================================================
// FIN
// ============================================================

function endGame(message) {

    gameOver = true;

    currentTurn = null;

    statusElement.textContent =
        message;


    render();
}


// ============================================================
// EVENTOS
// ============================================================

passButton.addEventListener(
    "click",
    playerPass
);


newGameButton.addEventListener(
    "click",
    startGame
);


// ============================================================
// INICIO
// ============================================================

startGame();
