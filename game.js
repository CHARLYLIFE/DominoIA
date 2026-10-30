let state = {
    deck: [],
    playerHand: [],
    rivalHand: [],
    board: [], // Array de fichas colocadas en orden en la mesa
    turn: 'player', // 'player' o 'rival'
    gameOver: false
};

// Inicializar juego al cargar la página
window.onload = () => {
    iniciarPartida();
};

function iniciarPartida() {
    let deck = crearBaraja();
    deck = barajar(deck);

    state.playerHand = deck.splice(0, 7);
    state.rivalHand = deck.splice(0, 7);
    state.board = [];
    state.gameOver = false;
    state.turn = 'player'; // Empieza el jugador

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

// Obtener las dos puntas extremas de la mesa
function obtenerPuntasMesa() {
    if (state.board.length === 0) return null;
    let primera = state.board[0];
    let ultima = state.board[state.board.length - 1];
    return {
        izquierda: primera.a,
        derecha: ultima.b
    };
}

// Verificar si una ficha encaja y en qué punta
function movimientosValidosFicha(ficha, puntas) {
    if (!puntas) return ['inicial']; // Si la mesa está vacía, entra por cualquier lado
    let validos = [];
    if (ficha.a === puntas.izquierda || ficha.b === puntas.izquierda) validos.push('izquierda');
    if (ficha.a === puntas.derecha || ficha.b === puntas.derecha) {
        // Evitar duplicar punta si izquierda y derecha son el mismo valor y la ficha es doble
        if (puntas.izquierda !== puntas.derecha || validos.length === 0) validos.push('derecha');
    }
    return validos;
}

function jugadorTieneMovimientos(mano) {
    let puntas = obtenerPuntasMesa();
    if (!puntas) return mano.length > 0;
    return mano.some(f => movimientosValidosFicha(f, puntas).length > 0);
}

// Interacción del Jugador al hacer clic en una ficha
function jugarFichaJugador(indexFicha) {
    if (state.turn !== 'player' || state.gameOver) return;

    let ficha = state.playerHand[indexFicha];
    let puntas = obtenerPuntasMesa();
    let validos = movimientosValidosFicha(ficha, puntas);

    if (validos.length === 0) {
        mostrarMensaje("¡No puedes jugar esta ficha!");
        return;
    }

    // Si hay opción en ambos lados, por comodidad por defecto se intenta ajustar a la derecha o izquierda según convenga
    let puntaElegida = validos[0]; 
    if (validos.length > 1) {
        puntaElegida = prompt(`¿En qué lado deseas colocar la ficha [${ficha.a}|${ficha.b}]?\nEscribe 'izquierda' o 'derecha':`, 'derecha');
        if (puntaElegida !== 'izquierda' && puntaElegida !== 'derecha') return;
    }

    colocarFichaEnMesa(ficha, puntaElegida, true);
    state.playerHand.splice(indexFicha, 1);

    chequearFinPartida();

    if (!state.gameOver) {
        state.turn = 'rival';
        actualizarInterfaz();
        mostrarMensaje("Turno del rival pensando...");
        setTimeout(turnoRival, 1000); // IA responde tras 1 segundo
    }
}

function colocarFichaEnMesa(ficha, punta, esJugador) {
    if (state.board.length === 0) {
        state.board.push(ficha);
        return;
    }

    let puntas = obtenerPuntasMesa();

    if (punta === 'izquierda') {
        if (ficha.b === puntas.izquierda) {
            state.board.unshift({ a: ficha.a, b: ficha.b });
        } else {
            state.board.unshift({ a: ficha.b, b: ficha.a }); // Se voltea si es necesario
        }
    } else {
        if (ficha.a === puntas.derecha) {
            state.board.push({ a: ficha.a, b: ficha.b });
        } else {
            state.board.push({ a: ficha.b, b: ficha.a }); // Se voltea si es necesario
        }
    }
}

// Lógica del Rival (IA Base inteligente)
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
        // IA elige una jugada (prioriza dobles o fichas altas)
        jugadasPosibles.sort((x, y) => (y.ficha.a + y.ficha.b) - (x.ficha.a + x.ficha.b));
        let jugadaElegida = jugadasPosibles[0];

        colocarFichaEnMesa(jugadaElegida.ficha, jugadaElegida.punta, false);
        state.rivalHand.splice(jugadaElegida.index, 1);
        mostrarMensaje("El rival ha jugado.");
    } else {
        mostrarMensaje("¡El rival ha pasado!");
    }

    chequearFinPartida();

    if (!state.gameOver) {
        state.turn = 'player';
        actualizarInterfaz();

        // Verificar si el jugador tiene fichas para jugar; si no, pase automático
        if (!jugadorTieneMovimientos(state.playerHand)) {
            mostrarMensaje("No tienes fichas válidas. ¡Paso automático!");
            setTimeout(() => {
                pasarTurnoAutomatico();
            }, 1200);
        } else {
            mostrarMensaje("¡Tu turno!");
        }
    }
}

function pasarTurno() {
    if (state.turn !== 'player' || jugadorTieneMovimientos(state.playerHand)) return;
    pasarTurnoAutomatico();
}

function pasarTurnoAutomatico() {
    state.turn = 'rival';
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

// Renderizado visual en pantalla
function actualizarInterfaz() {
    // Renderizar mano del jugador
    let playerContainer = document.getElementById('player-hand');
    playerContainer.innerHTML = '';
    let puntas = obtenerPuntasMesa();

    state.playerHand.forEach((f, idx) => {
        let tileDiv = document.createElement('div');
        let esValida = !puntas || movimientosValidosFicha(f, puntas).length > 0;
        
        tileDiv.className = `domino-tile ${esValida && state.turn === 'player' ? 'playable' : ''}`;
        tileDiv.innerHTML = `<div class="half">${f.a}</div><div class="divider"></div><div class="half">${f.b}</div>`;
        
        if (state.turn === 'player') {
            tileDiv.onclick = () => jugarFichaJugador(idx);
        }
        playerContainer.appendChild(tileDiv);
    });

    // Renderizar mano del rival (oculta, solo fichas contadas)
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

    // Renderizar Mesa
    let mesaContainer = document.getElementById('table-felt');
    if (state.board.length === 0) {
        mesaContainer.innerHTML = `<div id="mesa-puntas">Mesa vacía. Juega tu primera ficha.</div>`;
    } else {
        mesaContainer.innerHTML = '';
        state.board.forEach(f => {
            let tileDiv = document.createElement('div');
            tileDiv.className = 'domino-tile';
            tileDiv.style.transform = 'scale(0.85)';
            tileDiv.innerHTML = `<div class="half">${f.a}</div><div class="divider"></div><div class="half">${f.b}</div>`;
            mesaContainer.appendChild(tileDiv);
        });
    }

    // Botón de pase visible solo si el usuario no tiene jugadas válidas
    let btnPass = document.getElementById('btn-pass');
    if (state.turn === 'player' && !jugadorTieneMovimientos(state.playerHand)) {
        btnPass.style.display = 'inline-block';
    } else {
        btnPass.style.display = 'none';
    }
}

function mostrarMensaje(texto) {
    document.getElementById('status-msg').innerText = texto;
}
