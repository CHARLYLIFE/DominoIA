(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const PIPS = {
    0: [], 1:[4], 2:[0,8], 3:[0,4,8], 4:[0,2,6,8],
    5:[0,2,4,6,8], 6:[0,2,3,5,6,8]
  };
  let playerHand = [], botHand = [], board = [];
  let turn = "player", selectedIndex = null, round = 1;
  let previousWinner = null, gameFinished = false, waitingForBot = false;
  let playerWins = 0, botWins = 0, pendingDoubleEnd = null;

  const makeSet = () => {
    const set = [];
    for (let a=0;a<=6;a++) for (let b=a;b<=6;b++) set.push({a,b,id:`${a}-${b}`});
    return set;
  };
  const shuffle = (arr) => {
    for (let i=arr.length-1;i>0;i--) { const j=Math.floor(Math.random()*(i+1)); [arr[i],arr[j]]=[arr[j],arr[i]]; }
    return arr;
  };
  const pipTotal = hand => hand.reduce((sum,t)=>sum+t.a+t.b,0);
  const ends = () => board.length ? {left:board[0].a,right:board[board.length-1].b} : null;
  const legalSides = tile => {
    if (!board.length) return ["left"];
    const e=ends(), sides=[];
    if (tile.a===e.left || tile.b===e.left) sides.push("left");
    if (tile.a===e.right || tile.b===e.right) sides.push("right");
    return sides;
  };
  function orientForLeft(tile, target) {
    if (tile.b===target) return {a:tile.a,b:tile.b};
    return {a:tile.b,b:tile.a};
  }
  function orientForRight(tile, target) {
    if (tile.a===target) return {a:tile.a,b:tile.b};
    return {a:tile.b,b:tile.a};
  }
  function createPips(value) {
    const wrap=document.createElement("div"); wrap.className="pips";
    for(let i=0;i<9;i++){ const dot=document.createElement("i"); dot.className="pip"+(PIPS[value].includes(i)?"":" blank"); wrap.appendChild(dot); }
    return wrap;
  }
  function tileElement(tile, vertical=false, back=false) {
    if(back){const el=document.createElement("div");el.className="back-tile";return el;}
    const el=document.createElement("div");el.className="domino"+(vertical?" vertical":"");
    [tile.a,tile.b].forEach(v=>{const half=document.createElement("div");half.className="half";half.appendChild(createPips(v));el.appendChild(half);});
    return el;
  }
  function render() {
    $("botHand").replaceChildren();
    for(let i=0;i<botHand.length;i++) $("botHand").appendChild(tileElement(null,false,true));
    $("playerHand").replaceChildren();
    playerHand.forEach((tile,index)=>{
      const el=tileElement(tile,true);
      const sides=legalSides(tile);
      el.classList.toggle("playable",sides.length>0);
      el.classList.toggle("selected",selectedIndex===index);
      el.setAttribute("role","button"); el.setAttribute("aria-label",`${tile.a} y ${tile.b}`);
      el.addEventListener("click",()=>onPlayerTile(index));
      $("playerHand").appendChild(el);
    });
    $("board").replaceChildren();
    board.forEach((tile,index)=>{
      // A compact serpentine-like visual rhythm, keeping the real chain order.
      $("board").appendChild(tileElement(tile, index%7===3 || index%7===4));
    });
    $("emptyBoard").classList.toggle("hidden",board.length>0);
    $("botCount").textContent=botHand.length;
    $("playerCount").textContent=playerHand.length;
    $("playerScore").textContent=playerWins;
    $("botScore").textContent=botWins;
    $("roundNumber").textContent=round;
    $("passBtn").disabled=turn!=="player" || gameFinished || waitingForBot || playerHand.some(t=>legalSides(t).length);
    $("turnIndicator").textContent=gameFinished?"PARTIDA TERMINADA":waitingForBot?"ZEUS ESTÁ PENSANDO…":turn==="player"?"TU TURNO":"TURNO DE ZEUS";
    $("playerSub").textContent=turn==="player"?"ELIGE UNA FICHA":"ESPERA TU TURNO";
    $("actionHint").textContent=turn!=="player"?"La IA está jugando":playerHand.some(t=>legalSides(t).length)?"Toca una ficha válida para colocarla":"No tienes jugadas: debes pasar";
    $("statusText").textContent=board.length?`Extremos: ${ends().left}  ·  ${ends().right}`:"La mesa está lista para la primera ficha";
    $("gameMessage").textContent=turn==="player"?"Tu movimiento":waitingForBot?"Calculando jugada":"Partida en curso";
    requestAnimationFrame(()=>{const v=$("boardViewport");v.scrollLeft=v.scrollWidth;});
  }
  function startGame() {
    gameFinished=false;waitingForBot=false;selectedIndex=null;board=[];
    const set=shuffle(makeSet());
    playerHand=set.splice(0,7);botHand=set.splice(0,7);
    turn=previousWinner || (Math.random()<.5?"player":"bot");
    $("resultModal").classList.add("hidden");
    render();
    $("statusText").textContent=turn==="player"?"Te toca salir. Puedes elegir cualquier ficha.":"Zeus tiene la salida.";
    if(turn==="bot") scheduleBot();
  }
  function onPlayerTile(index) {
    if(turn!=="player"||gameFinished||waitingForBot)return;
    const tile=playerHand[index], sides=legalSides(tile);
    if(!sides.length){$("actionHint").textContent="Esa ficha no encaja en los extremos.";return;}
    selectedIndex=index;
    if(sides.length===2){render();$("sideModal").classList.remove("hidden");return;}
    playPlayer(index,sides[0]);
  }
  function playPlayer(index,side) {
    const tile=playerHand[index]; placeTile(tile,side);playerHand.splice(index,1);selectedIndex=null;
    afterMove("player");
  }
  function placeTile(tile,side) {
    if(!board.length){board.push({a:tile.a,b:tile.b});return;}
    const e=ends();
    if(side==="left"){const oriented=orientForLeft(tile,e.left);board.unshift(oriented);}
    else {const oriented=orientForRight(tile,e.right);board.push(oriented);}
  }
  function afterMove(who) {
    render();
    if(playerHand.length===0||botHand.length===0){finish(who);return;}
    turn=who==="player"?"bot":"player";
    waitingForBot=turn==="bot";
    if(turn==="bot")scheduleBot();
    render();
    if(turn==="player")$("statusText").textContent="Tu turno. Busca una buena jugada.";
  }
  function scheduleBot(){waitingForBot=true;render();$("statusText").textContent="Zeus está analizando sus fichas…";setTimeout(botMove,850);}
  function botMove(){
    if(gameFinished)return;
    const moves=[];
    botHand.forEach((tile,index)=>legalSides(tile).forEach(side=>moves.push({index,side,tile})));
    if(!moves.length){
      turn="player";waitingForBot=false;render();
      $("statusText").textContent="Zeus pasa. Vuelve a ser tu turno.";
      if(!playerHand.some(t=>legalSides(t).length))finish("blocked");
      return;
    }
    // Provisional opponent: legal move with a simple pip/connection heuristic.
    // This will be replaced by the trained search-based AI later.
    moves.sort((x,y)=>{
      const score=m=>m.tile.a+m.tile.b+(m.tile.a===m.tile.b?1.5:0)+Math.random()*2;
      return score(y)-score(x);
    });
    const move=moves[0];placeTile(move.tile,move.side);botHand.splice(move.index,1);
    waitingForBot=false;render();
    if(botHand.length===0){finish("bot");return;}
    turn="player";
    if(!playerHand.some(t=>legalSides(t).length)){
      render();$("statusText").textContent="No tienes jugadas disponibles. Debes pasar.";
    }else{$("statusText").textContent="Zeus jugó. Tu turno.";}
    render();
  }
  function passPlayer(){
    if(turn!=="player"||gameFinished||playerHand.some(t=>legalSides(t).length))return;
    turn="bot";scheduleBot();
  }
  function finish(winner) {
    gameFinished=true;waitingForBot=false;
    let title,desc,icon;
    if(winner==="blocked"){
      const p=pipTotal(playerHand),b=pipTotal(botHand);
      if(p<b){winner="player";desc=`La partida se trancó. Tus ${p} puntos son menos que los ${b} de Zeus.`;}
      else if(b<p){winner="bot";desc=`La partida se trancó. Zeus tiene ${b} puntos y tú ${p}.`;}
      else {winner="draw";desc=`La partida se trancó con ${p} puntos por jugador.`;}
    } else desc=winner==="player"?"Te quedaste sin fichas. ¡Excelente partida!":"Zeus se quedó sin fichas esta vez.";
    if(winner==="player"){playerWins++;previousWinner="player";title="¡Victoria!";icon="🏆";}
    else if(winner==="bot"){botWins++;previousWinner="bot";title="Zeus gana";icon="♟";}
    else {previousWinner=null;title="Empate";icon="🤝";}
    render();
    $("resultIcon").textContent=icon;$("resultTitle").textContent=title;$("resultDescription").textContent=desc;
    $("resultModal").classList.remove("hidden");
    $("statusText").textContent=title;
  }
  $("passBtn").addEventListener("click",passPlayer);
  $("newGameBtn").addEventListener("click",startGame);
  $("restartBtn").addEventListener("click",startGame);
  $("nextGameBtn").addEventListener("click",()=>{round++;startGame();});
  $("leftChoice").addEventListener("click",()=>{$("sideModal").classList.add("hidden");playPlayer(selectedIndex,"left");});
  $("rightChoice").addEventListener("click",()=>{$("sideModal").classList.add("hidden");playPlayer(selectedIndex,"right");});
  $("sideModal").addEventListener("click",e=>{if(e.target===$("sideModal"))$("sideModal").classList.add("hidden");});
  startGame();
})();
