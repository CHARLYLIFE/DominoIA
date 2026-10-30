let state = {
    deck: [],
    playerHand: [],
    rivalHand: [],
    board: [],
    turn: 'player',
    gameOver: false,
    fichaSeleccionadaIndex: null
};

document.addEventListener("DOMContentLoaded", () => {
    iniciarPartida();
});

function iniciarPartida() {
    let deck = crearBaraja();
    deck = barajar(deck);

    state.playerHand = ordenarMano(deck.splice(0, 7));
    state.rivalHand = deck.splice(0, 7);
    state.board = [];
    state.gameOver = false;
    state.turn = 'player';
    state.fichaSeleccionadaIndex = null;

    actualizarInterfaz();
    mostrarMensaje("Partida iniciada. ¡Tu turno!");
}

function crearBaraja() {
    let deck = [];
    for (let i = 0; i <= 6; i++) {
        for (let j = i; j <= 6; j++) {
            deck.push({ a: i, b: j });
        }
    }
    return deck;
}

function barajar(array) {
    for (let i = array.length - 1; i > 0; i--) {
        let j = Math.floor(Math.random() * (i + 1));
        [array[i], array[j]] = [array[j], array[i]];
    }
    return array;
}

function ordenarMano(mano) {
    return mano.sort((x, y) => {
        let esDobleX = x.a === x.b;
        let esDobleY = y.a === y.b;
        if (esDobleX && !esDobleY) return -1;
        if (!esDobleX && esDobleY) return 1;
        let sumaX = x.a + x.b;
        let sumaY = y.a + y.b;
        if (sumaX !== sumaY) return sumaY - sumaX;
        return Math.max(y.a, y.b) - Math.max(x.a, x.b);
    });
}

function obtenerPuntasMesa() {
    if (state.board.length === 0) return null;
    let primera = state.board[0];
    let ultima = state.board[state.board.length - 1];
    return {
        izquierda: primera.a,
        derecha: ultima.b
    };
}

function movimientosValidosFicha(ficha, puntas) {
    if (!puntas) return ['inicial'];
    let validos = [];
    if (ficha.a === puntas.izquierda || ficha.b === puntas.izquierda) validos.push('izquierda');
    if (ficha.a === puntas.derecha || ficha.b === puntas.derecha) {
        if (puntas.izquierda !== puntas.derecha || validos.length === 0) validos.push('derecha');
    }
    return validos;
}

function jugadorTieneMovimientos(mano) {
    let puntas = obtenerPuntasMesa();
    if (!puntas) return mano.length > 0;
    return mano.some(f => movimientosValidosFicha(f, puntas).length > 0);
}

function seleccionarFichaJugador(indexFicha) {
    if (state.turn !== 'player' || state.gameOver) return;

    let ficha = state.playerHand[indexFicha];
    let puntas = obtenerPuntasMesa();
    let validos = movimientosValidosFicha(ficha, puntas);

    if (validos.length === 0) {
        mostrarMensaje("¡No puedes jugar esta ficha!");
        state.fichaSeleccionadaIndex = null;
        actualizarInterfaz();
        return;
    }

    if (validos.length === 1 || state.board.length === 0) {
        colocarFichaEnMesa(ficha, validos[0]);
        state.playerHand.splice(indexFicha, 1);
        state.fichaSeleccionadaIndex = null;
        finalizarTurnoJugador();
    } else {
        state.fichaSeleccionadaIndex = indexFicha;
        actualizarInterfaz();
        mostrarMensaje("Toca el extremo izquierdo o derecho en la mesa para colocar la ficha.");
    }
}

function colocarEnExtremo(puntaElegida) {
    if (state.fichaSeleccionadaIndex === null) return;

    let ficha = state.playerHand[state.fichaSeleccionadaIndex];
    colocarFichaEnMesa(ficha, puntaElegida);
    state.playerHand.splice(state.fichaSeleccionadaIndex, 1);
    state.fichaSeleccionadaIndex = null;
    
    finalizarTurnoJugador();
}

function finalizarTurnoJugador() {
    chequearFinPartida();

    if (!state.gameOver) {
        state.turn = 'rival';
        actualizarInterfaz();
        mostrarMensaje("Turno del rival pensando...");
        setTimeout(turnoRival, 1000);
    }
}

function colocarFichaEnMesa(ficha, punta) {
    if (state.board.length === 0) {
        state.board.push(ficha);
        return;
    }

    let puntas = obtenerPuntasMesa();

    if (punta === 'izquierda') {
        if (ficha.b === puntas.izquierda) {
            state.board.unshift({ a: ficha.a, b: ficha.b });
        } else {
            state.board.unshift({ a: ficha.b, b: ficha.a });
        }
    } else {
        if (ficha.a === puntas.derecha) {
            state.board.push({ a: ficha.a, b: ficha.b });
        } else {
            state.board.push({ a: ficha.b, b: ficha.a });
        }
    }
}

function turnoRival() {
    if (state.gameOver) return;

    let puntas = obtenerPuntasMesa();
    let jugadasPosibles = [];

    state.rivalHand.forEach((f, idx) => {
        let validos = movimientosValidosFicha(f, puntas);
        if (validos.length > 0) {
            jugadasPosibles.push({ index: idx, ficha: f, punta: validos[0] });
        }
    });

    if (jugadasPosibles.length > 0) {
        jugadasPosibles.sort((x, y) => (y.ficha.a + y.ficha.b) - (x.ficha.a + x.ficha.b));
        let jugadaElegida = jugadasPosibles[0];

        colocarFichaEnMesa(jugadaElegida.ficha, jugadaElegida.punta);
        state.rivalHand.splice(jugadaElegida.index, 1);
        mostrarMensaje("El rival ha jugado.");
    } else {
        mostrarMensaje("¡El rival ha pasado!");
    }

    chequearFinPartida();

    if (!state.gameOver) {
        state.turn = 'player';
        actualizarInterfaz();

        if (!jugadorTieneMovimientos(state.playerHand)) {
            mostrarMensaje("No tienes fichas válidas. ¡Paso automático!");
            setTimeout(() => {
                pasarTurnoAutomatico();
            }, 1200);
        } else {
            mostrarMensaje("¡Tu turno! Toca una ficha.");
        }
    }
}

function pasarTurno() {
    if (state.turn !== 'player' || jugadorTieneMovimientos(state.playerHand)) return;
    pasarTurnoAutomatico();
}

function pasarTurnoAutomatico() {
    state.turn = 'rival';
    state.fichaSeleccionadaIndex = null;
    actualizarInterfaz();
    mostrarMensaje("Has pasado. Turno del rival...");
    setTimeout(turnoRival, 1000);
}

function chequearFinPartida() {
    if (state.playerHand.length === 0) {
        state.gameOver = true;
        mostrarMensaje("¡Felicidades, ganaste la partida! 🎉");
        setTimeout(iniciarPartida, 3000);
    } else if (state.rivalHand.length === 0) {
        state.gameOver = true;
        mostrarMensaje("El rival ganó la partida. 🤖");
        setTimeout(iniciarPartida, 3000);
    }
}

function actualizarInterfaz() {
    let playerContainer = document.getElementById('player-hand');
    if (!playerContainer) return;
    
    playerContainer.innerHTML = '';
    let puntas = obtenerPuntasMesa();
    state.playerHand = ordenarMano(state.playerHand);

    state.playerHand.forEach((f, idx) => {
        let tileDiv = document.createElement('div');
        let esValida = !puntas || movimientosValidosFicha(f, puntas).length > 0;
        let esSeleccionada = state.fichaSeleccionadaIndex === idx;
        
        tileDiv.className = `domino-tile ${esValida && state.turn === 'player' ? 'playable' : ''} ${esSeleccionada ? 'selected' : ''}`;
        tileDiv.innerHTML = `<div class="half">${f.a}</div><div class="divider"></div><div class="half">${f.b}</div>`;
        
        if (state.turn === 'player') {
            tileDiv.onclick = () => seleccionarFichaJugador(idx);
        }
        playerContainer.appendChild(tileDiv);
    });

    let rivalContainer = document.getElementById('rival-hand');
    rivalContainer.innerHTML = '';
    document.getElementById('rival-count').innerText = `Fichas en mano: ${state.rivalHand.length}`;
    for (let i = 0; i < state.rivalHand.length; i++) {
        let backDiv = document.createElement('div');
        backDiv.className = 'domino-tile';
        backDiv.style.background = '#334155';
        backDiv.style.borderColor = '#475569';
        backDiv.innerHTML = `<div class="half" style="color:#64748b">•</div><div class="divider" style="background:#475569"></div><div class="half" style="color:#64748b">•</div>`;
        rivalContainer.appendChild(backDiv);
    }

    let mesaContainer = document.getElementById('table-felt');
    if (state.board.length === 0) {
        mesaContainer.innerHTML = `<div id="mesa-puntas">Mesa vacía. Juega tu primera ficha.</div>`;
    } else {
        mesaContainer.innerHTML = '';
        let chainDiv = document.createElement('div');
        chainDiv.className = 'board-chain';

        let mostrarBtnIzquierda = false;
        let mostrarBtnDerecha = false;

        if (state.fichaSeleccionadaIndex !== null) {
            let fichaSel = state.playerHand[state.fichaSeleccionadaIndex];
            let validosSel = movimientosValidosFicha(fichaSel, puntas);
            if (validosSel.includes('izquierda')) mostrarBtnIzquierda = true;
            if (validosSel.includes('derecha')) mostrarBtnDerecha = true;
        }

        if (mostrarBtnIzquierda) {
            let btnIzq = document.createElement('button');
            btnIzq.className = 'end-selector';
            btnIzq.innerText = '⬅ Izquierda';
            btnIzq.onclick = () => colocarEnExtremo('izquierda');
            chainDiv.appendChild(btnIzq);
        }

        state.board.forEach(f => {
            let tileDiv = document.createElement('div');
            let esDoble = (f.a === f.b);
            tileDiv.className = `domino-tile table-tile ${esDoble ? 'is-double' : ''}`;
            tileDiv.innerHTML = `<div class="half">${f.a}</div><div class="divider"></div><div class="half">${f.b}</div>`;
            chainDiv.appendChild(tileDiv);
        });

        if (mostrarBtnDerecha) {
            let btnDer = document.createElement('button');
            btnDer.className = 'end-selector';
            btnDer.innerText = 'Derecha ➡';
            btnDer.onclick = () => colocarEnExtremo('derecha');
            chainDiv.appendChild(btnDer);
        }

        mesaContainer.appendChild(chainDiv);
    }

    let btnPass = document.getElementById('btn-pass');
    if (state.turn === 'player' && !jugadorTieneMovimientos(state.playerHand)) {
        btnPass.style.display = 'inline-block';
    } else {
        btnPass.style.display = 'none';
    }
}

function mostrarMensaje(texto) {
    let msgEl = document.getElementById('status-msg');
    if (msgEl) msgEl.innerText = texto;
}
