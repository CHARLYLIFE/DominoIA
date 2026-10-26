// =================================================================
// ARCHIVO: js/domino.js (COMPLETO)
// Motor de Juego: Dominó 1v1 contra la IA
// =================================================================

class DominoGame {
    constructor() {
        this.deck = [];
        this.playerHand = [];
        this.aiHand = [];
        this.board = []; // Array de fichas jugadas en la mesa: [{left, right}]
        this.turn = 'player'; // 'player' o 'ai'
        this.gameOver = false;
        this.statusMessage = "¡Tu turno! Selecciona una ficha.";

        this.init();
    }

    // 1. Generar las 28 fichas (Doble 6)
    generateDeck() {
        this.deck = [];
        for (let i = 0; i <= 6; i++) {
            for (let j = i; j <= 6; j++) {
                this.deck.push({ left: i, right: j });
            }
        }
    }

    // 2. Barajar
    shuffleDeck() {
        for (let i = this.deck.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [this.deck[i], this.deck[j]] = [this.deck[j], this.deck[i]];
        }
    }

    // 3. Inicializar / Reiniciar partida
    init() {
        this.generateDeck();
        this.shuffleDeck();
        this.board = [];
        this.gameOver = false;

        // Repartir 7 fichas a cada uno
        this.playerHand = this.deck.splice(0, 7);
        this.aiHand = this.deck.splice(0, 7);

        // Quien tenga el doble más alto o mayor ficha empieza
        this.turn = 'player';
        this.statusMessage = "Partida iniciada. ¡Tu turno!";
    }

    // Obtener extremos disponibles de la mesa
    getBoardEnds() {
        if (this.board.length === 0) return null;
        const leftEnd = this.board[0].left;
        const rightEnd = this.board[this.board.length - 1].right;
        return { left: leftEnd, right: rightEnd };
    }

    // Obtener las jugadas válidas para una mano
    getValidMoves(hand) {
        if (this.board.length === 0) {
            // Si la mesa está vacía, cualquier ficha se puede jugar
            return hand.map((tile, index) => ({ tile, index, side: 'any' }));
        }

        const ends = this.getBoardEnds();
        const validMoves = [];

        hand.forEach((tile, index) => {
            let canLeft = false;
            let canRight = false;

            // Verificar si encaja en el extremo izquierdo
            if (tile.left === ends.left || tile.right === ends.left) {
                canLeft = true;
            }
            // Verificar si encaja en el extremo derecho
            if (tile.left === ends.right || tile.right === ends.right) {
                canRight = true;
            }

            if (canLeft) validMoves.push({ tile, index, side: 'left' });
            if (canRight) validMoves.push({ tile, index, side: 'right' });
        });

        return validMoves;
    }

    // Ejecutar una jugada
    playTile(playerType, tileIndex, side) {
        if (this.gameOver) return false;

        const hand = playerType === 'player' ? this.playerHand : this.aiHand;
        const tile = hand[tileIndex];

        if (!tile) return false;

        if (this.board.length === 0) {
            this.board.push({ left: tile.left, right: tile.right });
        } else {
            const ends = this.getBoardEnds();

            if (side === 'left') {
                // Orientar la ficha correctamente para el lado izquierdo
                let newTile = { left: tile.left, right: tile.right };
                if (newTile.right !== ends.left) {
                    newTile = { left: tile.right, right: tile.left };
                }
                this.board.unshift(newTile);
            } else if (side === 'right') {
                // Orientar la ficha correctamente para el lado derecho
                let newTile = { left: tile.left, right: tile.right };
                if (newTile.left !== ends.right) {
                    newTile = { left: tile.right, right: tile.left };
                }
                this.board.push(newTile);
            }
        }

        // Remover ficha de la mano
        hand.splice(tileIndex, 1);

        // Verificar si alguien ganó
        if (hand.length === 0) {
            this.gameOver = true;
            this.statusMessage = playerType === 'player' ? "¡GANASTE LA PARTIDA! 🎉" : "¡La IA ha ganado! 🤖";
            return true;
        }

        // Cambiar turno
        this.switchTurn();
        return true;
    }

    // Pasar turno o robar de la sopa si no hay jugadas
    passOrDraw(playerType) {
        const hand = playerType === 'player' ? this.playerHand : this.aiHand;
        
        // Si hay fichas en la sopa (deck), robar una
        if (this.deck.length > 0) {
            const drawnTile = this.deck.pop();
            hand.push(drawnTile);
            this.statusMessage = playerType === 'player' ? "Robaste una ficha." : "La IA robó una ficha.";
            return true;
        } else {
            // Si la sopa está vacía, se pasa el turno
            this.statusMessage = playerType === 'player' ? "Pasaste el turno." : "La IA pasó el turno.";
            this.switchTurn();

            // Comprobar si el juego se trancó
            this.checkTranque();
            return false;
        }
    }

    switchTurn() {
        this.turn = this.turn === 'player' ? 'ai' : 'player';

        if (this.turn === 'ai' && !this.gameOver) {
            this.statusMessage = "Turno de la IA pensando...";
            setTimeout(() => this.makeAiMove(), 1000);
        } else if (this.turn === 'player' && !this.gameOver) {
            // Verificar si el jugador tiene movimientos posibles
            const moves = this.getValidMoves(this.playerHand);
            if (moves.length === 0) {
                if (this.deck.length > 0) {
                    this.statusMessage = "No tienes jugadas. Toca para robar.";
                } else {
                    this.statusMessage = "No tienes jugadas y no hay fichas. Pasas turno.";
                }
            } else {
                this.statusMessage = "¡Tu turno! Toca una ficha válida.";
            }
        }
    }

    // Turno automático de la Inteligencia Artificial
    makeAiMove() {
        if (this.gameOver || this.turn !== 'ai') return;

        const validMoves = this.getValidMoves(this.aiHand);

        if (validMoves.length > 0) {
            // IA Básica: Elige el primer movimiento válido que tenga
            const move = validMoves[0];
            this.playTile('ai', move.index, move.side === 'any' ? 'right' : move.side);
        } else {
            // Si no tiene movimientos, roba o pasa
            const drew = this.passOrDraw('ai');
            if (drew) {
                // Reintentar si robó una nueva ficha
                setTimeout(() => this.makeAiMove(), 800);
            }
        }
    }

    // Verificar si la partida se ha trancado (cerrado)
    checkTranque() {
        const playerMoves = this.getValidMoves(this.playerHand);
        const aiMoves = this.getValidMoves(this.aiHand);

        if (playerMoves.length === 0 && aiMoves.length === 0 && this.deck.length === 0) {
            this.gameOver = true;

            // Contar puntos
            const playerPoints = this.playerHand.reduce((sum, t) => sum + t.left + t.right, 0);
            const aiPoints = this.aiHand.reduce((sum, t) => sum + t.left + t.right, 0);

            if (playerPoints < aiPoints) {
                this.statusMessage = `¡Juego trancado! Ganaste por menos puntos (${playerPoints} vs ${aiPoints}).`;
            } else if (aiPoints < playerPoints) {
                this.statusMessage = `¡Juego trancado! Ganó la IA por menos puntos (${aiPoints} vs ${playerPoints}).`;
            } else {
                this.statusMessage = `¡Empate en tranque! Ambos sumaron ${playerPoints} puntos.`;
            }
        }
    }
}
