// =================================================================
// ARCHIVO: js/domino.js (COMPLETO Y CORREGIDO)
// Lógica de Dominó 1v1 vs IA
// =================================================================

class DominoGame {
    constructor() {
        this.deck = [];
        this.playerHand = [];
        this.aiHand = [];
        this.board = [];
        this.leftVal = null;
        this.rightVal = null;
        this.isPlayerTurn = true;
        this.gameOver = false;

        this.initDOM();
        this.startNewGame();
    }

    initDOM() {
        this.boardEl = document.getElementById('board');
        this.playerHandEl = document.getElementById('player-hand');
        this.aiCountEl = document.getElementById('ai-count');
        this.statusEl = document.getElementById('status-msg');
        this.btnPass = document.getElementById('btn-pass');

        this.btnPass.addEventListener('click', () => this.handlePassOrDraw());
    }

    startNewGame() {
        // 1. Generar las 28 fichas (0-0 a 6-6)
        this.deck = [];
        for (let i = 0; i <= 6; i++) {
            for (let j = i; j <= 6; j++) {
                this.deck.push({ a: i, b: j });
            }
        }

        // 2. Barajar
        this.deck.sort(() => Math.random() - 0.5);

        // 3. Repartir 7 fichas a cada uno
        this.playerHand = this.deck.splice(0, 7);
        this.aiHand = this.deck.splice(0, 7);

        this.board = [];
        this.leftVal = null;
        this.rightVal = null;
        this.gameOver = false;
        this.isPlayerTurn = true;

        this.updateUI();
        this.setStatus('¡Comienza el juego! Tu turno.');
    }

    renderBoard() {
        this.boardEl.innerHTML = '';
        this.board.forEach(tile => {
            const tileEl = document.createElement('div');
            tileEl.className = 'tile horizontal';
            tileEl.innerHTML = `
                <div class="tile-half">${tile.a}</div>
                <div class="divider-v"></div>
                <div class="tile-half">${tile.b}</div>
            `;
            this.boardEl.appendChild(tileEl);
        });
    }

    renderPlayerHand() {
        this.playerHandEl.innerHTML = '';
        const playableMoves = this.getPlayableMoves(this.playerHand);

        this.playerHand.forEach((tile, index) => {
            const tileEl = document.createElement('div');
            tileEl.className = 'tile';

            const canPlay = playableMoves.some(m => m.index === index);
            if (canPlay && this.isPlayerTurn && !this.gameOver) {
                tileEl.classList.add('playable');
                tileEl.addEventListener('click', () => this.playPlayerTile(index));
            }

            tileEl.innerHTML = `
                <div class="tile-half">${tile.a}</div>
                <div class="divider"></div>
                <div class="tile-half">${tile.b}</div>
            `;
            this.playerHandEl.appendChild(tileEl);
        });

        if (this.isPlayerTurn && !this.gameOver) {
            this.btnPass.disabled = playableMoves.length > 0;
            this.btnPass.innerText = this.deck.length > 0 ? `Robar (${this.deck.length})` : 'Pasar';
        } else {
            this.btnPass.disabled = true;
        }
    }

    getPlayableMoves(hand) {
        if (this.board.length === 0) {
            return hand.map((tile, index) => ({ index, side: 'first', tile }));
        }

        const moves = [];
        hand.forEach((tile, index) => {
            if (tile.a === this.leftVal) moves.push({ index, side: 'left', tile: { a: tile.b, b: tile.a } });
            else if (tile.b === this.leftVal) moves.push({ index, side: 'left', tile: { a: tile.a, b: tile.b } });

            if (tile.a === this.rightVal) moves.push({ index, side: 'right', tile: { a: tile.a, b: tile.b } });
            else if (tile.b === this.rightVal) moves.push({ index, side: 'right', tile: { a: tile.b, b: tile.a } });
        });

        return moves;
    }

    playTile(tileObj, side) {
        if (side === 'first') {
            this.board.push(tileObj);
            this.leftVal = tileObj.a;
            this.rightVal = tileObj.b;
        } else if (side === 'left') {
            this.board.unshift(tileObj);
            this.leftVal = tileObj.a;
        } else if (side === 'right') {
            this.board.push(tileObj);
            this.rightVal = tileObj.b;
        }
    }

    playPlayerTile(handIndex) {
        if (!this.isPlayerTurn || this.gameOver) return;

        const tile = this.playerHand[handIndex];
        const moves = this.getPlayableMoves([tile]);

        if (moves.length === 0) return;

        const move = moves[0];
        // Ajustamos el lado si es necesario según la jugada real
        let targetSide = move.side;
        if (this.board.length === 0) targetSide = 'first';

        this.playTile(move.tile, targetSide);
        this.playerHand.splice(handIndex, 1);

        if (this.checkWin(this.playerHand, '👤 ¡Ganaste la partida!')) return;

        this.isPlayerTurn = false;
        this.updateUI();
        this.setStatus('Turno de la IA...');

        setTimeout(() => this.aiTurn(), 800);
    }

    aiTurn() {
        if (this.gameOver) return;

        let moves = this.getPlayableMoves(this.aiHand);

        while (moves.length === 0 && this.deck.length > 0) {
            this.aiHand.push(this.deck.pop());
            moves = this.getPlayableMoves(this.aiHand);
        }

        if (moves.length > 0) {
            // Usamos directamente el índice seguro de la jugada
            const move = moves[0];
            this.playTile(move.tile, this.board.length === 0 ? 'first' : move.side);
            this.aiHand.splice(move.index, 1);

            if (this.checkWin(this.aiHand, '🤖 La IA ha ganado.')) return;
            this.setStatus('Tu turno.');
        } else {
            this.setStatus('La IA pasó. Tu turno.');
        }

        this.isPlayerTurn = true;
        this.checkBlock();
        this.updateUI();
    }

    handlePassOrDraw() {
        if (!this.isPlayerTurn || this.gameOver) return;

        if (this.deck.length > 0) {
            this.playerHand.push(this.deck.pop());
            this.updateUI();
        } else {
            this.isPlayerTurn = false;
            this.setStatus('Pasaste el turno. Juega la IA...');
            this.updateUI();
            setTimeout(() => this.aiTurn(), 800);
        }
    }

    checkWin(hand, msg) {
        if (hand.length === 0) {
            this.gameOver = true;
            this.setStatus(msg);
            this.updateUI();
            return true;
        }
        return false;
    }

    checkBlock() {
        const playerMoves = this.getPlayableMoves(this.playerHand);
        const aiMoves = this.getPlayableMoves(this.aiHand);

        if (playerMoves.length === 0 && aiMoves.length === 0 && this.deck.length === 0) {
            this.gameOver = true;
            const playerPoints = this.playerHand.reduce((sum, t) => sum + t.a + t.b, 0);
            const aiPoints = this.aiHand.reduce((sum, t) => sum + t.a + t.b, 0);

            if (playerPoints < aiPoints) {
                this.setStatus(`¡Juego Trancado! Ganaste por puntos (${playerPoints} vs ${aiPoints}).`);
            } else if (aiPoints < playerPoints) {
                this.setStatus(`Juego Trancado. Ganó la IA por puntos (${aiPoints} vs ${playerPoints}).`);
            } else {
                this.setStatus('¡Juego Trancado! Empate en puntos.');
            }
        }
    }

    setStatus(msg) {
        this.statusEl.innerText = msg;
    }

    updateUI() {
        this.aiCountEl.innerText = this.aiHand.length;
        this.renderBoard();
        this.renderPlayerHand();
    }
}

window.addEventListener('DOMContentLoaded', () => {
    new DominoGame();
});
