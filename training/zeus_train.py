#!/usr/bin/env python3
"""ZEUS AI 1.0: self-play trainer for double-six, head-to-head, no-draw domino.

The observation encoder and action IDs intentionally mirror DominoNeuralModel.kt
and DominoBot.kt. Training runs outside Android; exported inference is pure Kotlin.
"""
from __future__ import annotations

import argparse
import copy
import csv
from concurrent.futures import ProcessPoolExecutor
import math
import os
import random
import struct
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

TILE_LIST = [(a, b) for a in range(7) for b in range(a, 7)]
TILE_TO_ID = {tile: i for i, tile in enumerate(TILE_LIST)}
INPUT_SIZE = 89
ACTION_SIZE = 84
VALUE_SIZE = 4
HIDDEN_SIZE = 128
MAGIC = b"ZEUSNN01"


class PolicyValueNet(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.fc1 = nn.Linear(INPUT_SIZE, HIDDEN_SIZE)
        self.fc2 = nn.Linear(HIDDEN_SIZE, HIDDEN_SIZE)
        self.policy = nn.Linear(HIDDEN_SIZE, ACTION_SIZE)
        self.value = nn.Linear(HIDDEN_SIZE, VALUE_SIZE)

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        h = F.relu(self.fc1(x))
        h = F.relu(self.fc2(h))
        return self.policy(h), self.value(h)


@dataclass
class Sample:
    features: np.ndarray
    legal: tuple[int, ...]
    action: int
    actor: int
    teacher: bool
    round_win: float = 0.5
    round_margin: float = 0.0
    blocked: float = 0.0
    match_win: float = 0.5


@dataclass
class RoundResult:
    winner: Optional[int]
    blocked: bool
    points_awarded: int
    margin: float
    samples: list[Sample]


def tile_mask(hand: list[int]) -> int:
    mask = 0
    for tile_id in hand:
        mask |= 1 << tile_id
    return mask


def pip_sum(hand: list[int]) -> int:
    return sum(TILE_LIST[t][0] + TILE_LIST[t][1] for t in hand)


def encode_state(hand: list[int], played_mask: int, left: Optional[int], right: Optional[int],
                 opponent_count: int, pass_streak: int, has_board: bool,
                 opponent_pass_mask: int = 0) -> np.ndarray:
    """89-value player-view vector. Hidden opponent tile identities are excluded."""
    x = np.zeros(INPUT_SIZE, dtype=np.float32)
    i = 0
    own = tile_mask(hand)
    for tile_id in range(28):
        x[i] = 1.0 if own & (1 << tile_id) else 0.0
        i += 1
    for tile_id in range(28):
        x[i] = 1.0 if played_mask & (1 << tile_id) else 0.0
        i += 1
    for value in range(7):
        x[i] = 1.0 if has_board and left == value else 0.0
        i += 1
    for value in range(7):
        x[i] = 1.0 if has_board and right == value else 0.0
        i += 1
    for count in range(8):
        x[i] = 1.0 if opponent_count == count else 0.0
        i += 1
    for count in range(3):
        x[i] = 1.0 if min(pass_streak, 2) == count else 0.0
        i += 1
    x[i] = 1.0 if has_board else 0.0
    i += 1
    for value in range(7):
        x[i] = 1.0 if opponent_pass_mask & (1 << value) else 0.0
        i += 1
    assert i == INPUT_SIZE
    return x


def legal_moves(hand: list[int], left: Optional[int], right: Optional[int], has_board: bool) -> list[int]:
    out: list[int] = []
    for tile_id in hand:
        a, b = TILE_LIST[tile_id]
        if not has_board:
            out.append(tile_id * 3)
        else:
            if a == left or b == left:
                out.append(tile_id * 3 + 1)
            if a == right or b == right:
                out.append(tile_id * 3 + 2)
    return out


def apply_move(hand: list[int], tile_id: int, side: int,
               left: Optional[int], right: Optional[int], has_board: bool) -> tuple[Optional[int], int, int]:
    if tile_id not in hand:
        raise ValueError(f"La acción intenta jugar una ficha ajena a la mano: {tile_id}")
    a, b = TILE_LIST[tile_id]
    if not has_board:
        if side != 0:
            raise ValueError("La salida inicial debe usar side=0")
        hand.remove(tile_id)
        return a, b, 1
    if side == 1:
        if left is None or (a != left and b != left):
            raise ValueError("Jugada ilegal por el extremo izquierdo")
        hand.remove(tile_id)
        new_left = b if a == left else a
        return new_left, int(right), 1
    if side == 2:
        if right is None or (a != right and b != right):
            raise ValueError("Jugada ilegal por el extremo derecho")
        hand.remove(tile_id)
        new_right = b if a == right else a
        return int(left), new_right, 1
    raise ValueError(f"Lado de jugada desconocido: {side}")


def heuristic_action(hand: list[int], actions: list[int], left: Optional[int], right: Optional[int],
                     has_board: bool, rng: random.Random, style: str = "balanced") -> int:
    if style == "random":
        return rng.choice(actions)
    best_action = actions[0]
    best_score = -1e30
    weights = {
        "balanced": (0.12, 0.52, 0.35, 0.22),
        "aggressive": (0.25, 0.30, 0.20, 0.10),
        "blocking": (0.08, 0.34, 1.05, 0.25),
        "conservative": (0.05, 0.75, 0.30, 0.42),
    }.get(style, (0.12, 0.52, 0.35, 0.22))
    pip_w, mobility_w, block_w, double_penalty = weights
    hand_set = set(hand)
    for action in actions:
        tile_id, side = divmod(action, 3)
        a, b = TILE_LIST[tile_id]
        after = [t for t in hand if t != tile_id]
        if not has_board:
            nl, nr = a, b
        elif side == 1:
            nl, nr = (b if a == left else a), right
        else:
            nl, nr = left, (b if a == right else a)
        mobility = sum(1 for t in after if TILE_LIST[t][0] in (nl, nr) or TILE_LIST[t][1] in (nl, nr))
        support = sum(1 for t in after for v in (nl, nr) if v is not None and v in TILE_LIST[t])
        orphan_doubles = sum(1 for t in after if TILE_LIST[t][0] == TILE_LIST[t][1] and TILE_LIST[t][0] not in (nl, nr))
        score = (a + b) * pip_w + mobility * mobility_w + support * 0.13 + (1.0 / (1 + mobility)) * block_w - orphan_doubles * double_penalty
        score += rng.random() * 0.18
        if score > best_score:
            best_score, best_action = score, action
    return best_action


def choose_model_action(model: PolicyValueNet, x: np.ndarray, actions: list[int],
                        device: torch.device, rng: random.Random, temperature: float = 0.9) -> int:
    with torch.no_grad():
        logits, _ = model(torch.from_numpy(x).to(device).unsqueeze(0))
        legal = torch.tensor(actions, dtype=torch.long, device=device)
        selected = logits[0].index_select(0, legal) / max(0.15, temperature)
        probs = torch.softmax(selected, dim=0).cpu().numpy().astype(np.float64)
    # Stable sampling, with a uniform fallback if a corrupt model produces bad values.
    if not np.all(np.isfinite(probs)) or probs.sum() <= 0:
        return rng.choice(actions)
    probs /= probs.sum()
    return int(rng.choices(actions, weights=probs.tolist(), k=1)[0])


def play_round(rng: random.Random, agents: list[str], model: PolicyValueNet,
               archive: Optional[PolicyValueNet], device: torch.device,
               teacher_probability: float, collect: bool,
               blocked_scoring: str, starter: int) -> RoundResult:
    tiles = list(range(28))
    rng.shuffle(tiles)
    hands = [sorted(tiles[:7]), sorted(tiles[7:14])]
    # The other 14 tiles remain outside the game. There is no draw pile.
    hands[0].sort(); hands[1].sort()
    turn = starter
    left: Optional[int] = None
    right: Optional[int] = None
    has_board = False
    played_mask = 0
    pass_streak = 0
    passed_values = [0, 0]
    samples: list[Sample] = []
    board_count = 0

    for _ply in range(60):
        hand = hands[turn]
        actions = legal_moves(hand, left, right, has_board)
        if not actions:
            if has_board and left is not None and right is not None:
                passed_values[turn] |= (1 << left) | (1 << right)
            pass_streak += 1
            turn = 1 - turn
            if pass_streak >= 2:
                p0, p1 = pip_sum(hands[0]), pip_sum(hands[1])
                winner = 0 if p0 < p1 else 1 if p1 < p0 else None
                loser_points = max(p0, p1)
                margin_points = abs(p1 - p0)
                award = 0 if winner is None else (loser_points if blocked_scoring == "loser_pips" else margin_points)
                margin = 0.0 if winner is None else min(1.0, margin_points / 84.0)
                return RoundResult(winner, True, int(award), margin, samples)
            continue

        opponent_count = len(hands[1 - turn])
        x = encode_state(hand, played_mask, left, right, opponent_count, pass_streak, has_board,
                         passed_values[1 - turn])
        agent = agents[turn]
        teacher = False
        if agent == "current":
            teacher = collect and rng.random() < teacher_probability
            if teacher:
                action = heuristic_action(hand, actions, left, right, has_board, rng, "balanced")
            else:
                action = choose_model_action(model, x, actions, device, rng)
            if collect:
                samples.append(Sample(x, tuple(actions), action, turn, teacher))
        elif agent == "archive" and archive is not None:
            action = choose_model_action(archive, x, actions, device, rng)
        else:
            style = agent if agent in {"random", "aggressive", "blocking", "conservative"} else "balanced"
            action = heuristic_action(hand, actions, left, right, has_board, rng, style)

        tile_id, side = divmod(action, 3)
        left, right, has_board = apply_move(hand, tile_id, side, left, right, has_board)
        played_mask |= 1 << tile_id
        pass_streak = 0
        board_count += 1
        if not hand:
            winner = turn
            loser_points = pip_sum(hands[1 - turn])
            award = loser_points
            margin = min(1.0, loser_points / 84.0)
            return RoundResult(winner, False, int(award), margin, samples)
        turn = 1 - turn

    # Guard against a simulator bug; this result is scored as a tie, not hidden.
    return RoundResult(None, True, 0, 0.0, samples)


def play_match(rng: random.Random, model: PolicyValueNet, archive: Optional[PolicyValueNet],
               device: torch.device, training: bool, teacher_probability: float,
               match_target: int, blocked_scoring: str,
               agents_override: Optional[list[str]] = None) -> tuple[list[Sample], int, list[int], int]:
    scores = [0, 0]
    starter = rng.randrange(2)
    collected: list[Sample] = []
    rounds = 0
    while max(scores) < match_target and rounds < 300:
        if agents_override is not None:
            agents = agents_override
        elif not training:
            agents = ["current", "balanced"]
        else:
            choice = rng.random()
            if choice < 0.42:
                agents = ["current", "current"]
            elif choice < 0.74:
                agents = ["current", "balanced"]
            elif choice < 0.90:
                agents = ["current", "archive"] if archive is not None else ["current", "blocking"]
            elif choice < 0.96:
                agents = ["current", "random"]
            else:
                agents = ["current", "aggressive"]
            if rng.random() < 0.5:
                agents.reverse()
        result = play_round(rng, agents, model, archive, device,
                            teacher_probability, training, blocked_scoring, starter)
        rounds += 1
        if result.winner is not None:
            scores[result.winner] += result.points_awarded
            starter = result.winner
        else:
            starter = 1 - starter
        # Attach round labels after the round ends. Samples are always from the
        # current agent's perspective, while the true actor seat is retained.
        for sample in result.samples:
            if result.winner is None:
                sample.round_win = 0.5
                sample.round_margin = 0.0
            else:
                sample.round_win = 1.0 if sample.actor == result.winner else 0.0
                sample.round_margin = result.margin if sample.actor == result.winner else -result.margin
            sample.blocked = 1.0 if result.blocked else 0.0
        collected.extend(result.samples)
        # In the specified game the previous hand winner starts the next hand.
        # Tie fallback is deterministic alternation (starter was already flipped).
    match_winner = 0 if scores[0] >= match_target else 1 if scores[1] >= match_target else (0 if scores[0] > scores[1] else 1 if scores[1] > scores[0] else -1)
    for sample in collected:
        sample.match_win = 0.5 if match_winner == -1 else (1.0 if sample.actor == match_winner else 0.0)
    return collected, match_winner, scores, rounds


def generate_training_chunk(payload: tuple) -> list[Sample]:
    """Generate one batch chunk in an isolated CPU worker process."""
    current_state, archive_state, seeds, teacher_probability, match_target, blocked_scoring = payload
    torch.set_num_threads(1)
    current = PolicyValueNet().cpu().eval()
    current.load_state_dict(current_state)
    archive = None
    if archive_state is not None:
        archive = PolicyValueNet().cpu().eval()
        archive.load_state_dict(archive_state)
    collected: list[Sample] = []
    device = torch.device("cpu")
    for seed in seeds:
        rng = random.Random(int(seed))
        samples, _, _, _ = play_match(
            rng, current, archive, device, True, teacher_probability,
            match_target, blocked_scoring
        )
        collected.extend(samples)
    return collected


def cpu_state_dict(model: PolicyValueNet) -> dict[str, torch.Tensor]:
    return {key: value.detach().cpu().clone() for key, value in model.state_dict().items()}


def train_batch(model: PolicyValueNet, optimizer: torch.optim.Optimizer,
                samples: list[Sample], device: torch.device,
                batch_size: int, epochs: int) -> dict[str, float]:
    if not samples:
        return {"loss": 0.0, "policy": 0.0, "value": 0.0}
    xs = torch.from_numpy(np.stack([s.features for s in samples])).to(device)
    legal = torch.zeros((len(samples), ACTION_SIZE), dtype=torch.bool, device=device)
    for row, sample in enumerate(samples):
        legal[row, list(sample.legal)] = True
    actions = torch.tensor([s.action for s in samples], dtype=torch.long, device=device)
    round_win = torch.tensor([s.round_win for s in samples], dtype=torch.float32, device=device)
    margin = torch.tensor([s.round_margin for s in samples], dtype=torch.float32, device=device)
    blocked = torch.tensor([s.blocked for s in samples], dtype=torch.float32, device=device)
    match_win = torch.tensor([s.match_win for s in samples], dtype=torch.float32, device=device)
    teacher = torch.tensor([s.teacher for s in samples], dtype=torch.bool, device=device)

    order = np.arange(len(samples))
    totals = {"loss": 0.0, "policy": 0.0, "value": 0.0, "n": 0}
    model.train()
    for _ in range(epochs):
        np.random.shuffle(order)
        for start in range(0, len(samples), batch_size):
            idx_np = order[start:start + batch_size]
            idx = torch.as_tensor(idx_np, dtype=torch.long, device=device)
            policy_logits, value_raw = model(xs.index_select(0, idx))
            mask = legal.index_select(0, idx)
            masked_logits = policy_logits.masked_fill(~mask, -1e9)
            log_probs = F.log_softmax(masked_logits, dim=1)
            probs = log_probs.exp()
            selected_logp = log_probs.gather(1, actions.index_select(0, idx).unsqueeze(1)).squeeze(1)
            batch_match = match_win.index_select(0, idx)
            predicted_match = torch.sigmoid(value_raw[:, 3])
            advantage = (batch_match - predicted_match).detach()
            teacher_mask = teacher.index_select(0, idx)
            rl_mask = ~teacher_mask
            if rl_mask.any():
                policy_rl = -(selected_logp[rl_mask] * advantage[rl_mask]).mean()
            else:
                policy_rl = torch.zeros((), device=device)
            if teacher_mask.any():
                policy_teacher = -selected_logp[teacher_mask].mean()
            else:
                policy_teacher = torch.zeros((), device=device)
            entropy = -(probs * log_probs.masked_fill(~mask, 0.0)).sum(dim=1).mean()

            rw = round_win.index_select(0, idx)
            rm = margin.index_select(0, idx)
            bl = blocked.index_select(0, idx)
            round_loss = F.binary_cross_entropy_with_logits(value_raw[:, 0], rw)
            margin_loss = F.smooth_l1_loss(torch.tanh(value_raw[:, 1]), rm)
            blocked_loss = F.binary_cross_entropy_with_logits(value_raw[:, 2], bl)
            match_loss = F.binary_cross_entropy_with_logits(value_raw[:, 3], batch_match)
            value_loss = round_loss + 0.35 * margin_loss + 0.30 * blocked_loss + 0.70 * match_loss
            loss = policy_rl + 0.12 * policy_teacher + value_loss - 0.008 * entropy

            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            count = len(idx_np)
            totals["loss"] += float(loss.detach()) * count
            totals["policy"] += float((policy_rl + 0.12 * policy_teacher).detach()) * count
            totals["value"] += float(value_loss.detach()) * count
            totals["n"] += count
    n = max(1, totals.pop("n"))
    return {key: value / n for key, value in totals.items()}


def evaluate(model: PolicyValueNet, device: torch.device, games: int,
             match_target: int, blocked_scoring: str, seed: int) -> dict[str, float]:
    model.eval()
    rng = random.Random(seed)
    wins = 0
    ties = 0
    total_rounds = 0
    score_margin = 0
    styles = ["random", "conservative", "aggressive", "blocking", "balanced"]
    for i in range(games):
        style = styles[i % len(styles)]
        agents = ["current", style] if i % 2 == 0 else [style, "current"]
        _, winner, scores, rounds = play_match(
            rng, model, None, device, False, 0.0, match_target, blocked_scoring,
            agents_override=agents
        )
        my_seat = 0 if agents[0] == "current" else 1
        if winner == my_seat:
            wins += 1
        elif winner == -1:
            ties += 1
        score_margin += scores[my_seat] - scores[1 - my_seat]
        total_rounds += rounds
    return {
        "games": float(games),
        "win_rate": wins / max(1, games),
        "tie_rate": ties / max(1, games),
        "mean_score_margin": score_margin / max(1, games),
        "mean_rounds": total_rounds / max(1, games),
    }


def export_model(model: PolicyValueNet, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    state = model.state_dict()
    layers = [
        (state["fc1.weight"], state["fc1.bias"]),
        (state["fc2.weight"], state["fc2.bias"]),
        (state["policy.weight"], state["policy.bias"]),
        (state["value.weight"], state["value.bias"]),
    ]
    with destination.open("wb") as f:
        f.write(MAGIC)
        f.write(struct.pack(">4i", INPUT_SIZE, HIDDEN_SIZE, ACTION_SIZE, VALUE_SIZE))
        for weight, bias in layers:
            f.write(weight.detach().cpu().numpy().astype(">f4", copy=False).tobytes(order="C"))
            f.write(bias.detach().cpu().numpy().astype(">f4", copy=False).tobytes(order="C"))


def save_checkpoint(path: Path, model: PolicyValueNet, optimizer: torch.optim.Optimizer,
                    games_done: int, best_win_rate: float, args: argparse.Namespace,
                    rng: random.Random) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.save({
        "model": model.state_dict(),
        "optimizer": optimizer.state_dict(),
        "games_done": games_done,
        "best_win_rate": best_win_rate,
        "config": vars(args),
        "torch_version": torch.__version__,
        "python_rng_state": rng.getstate(),
        "numpy_rng_state": np.random.get_state(),
        "torch_rng_state": torch.get_rng_state(),
        "torch_cuda_rng_state": torch.cuda.get_rng_state_all() if torch.cuda.is_available() else None,
    }, path)


def run_self_test() -> None:
    rng = random.Random(99173)
    model = PolicyValueNet()
    device = torch.device("cpu")
    for _ in range(25):
        samples, winner, scores, rounds = play_match(
            rng, model, None, device, True, 0.25, 100, "loser_pips"
        )
        assert rounds > 0 and rounds < 301
        assert len(scores) == 2 and all(score >= 0 for score in scores)
        for sample in samples:
            assert sample.features.shape == (INPUT_SIZE,)
            assert len(sample.legal) > 0
            assert sample.action in sample.legal
            assert 0 <= sample.action < ACTION_SIZE
            assert np.isfinite(sample.features).all()
    # Ensure private opposing tile identities do not enter the feature vector.
    own = [TILE_TO_ID[(0, 0)], TILE_TO_ID[(0, 1)]]
    sample = encode_state(own, 0, None, None, 7, 0, False)
    assert sample.shape == (89,)
    print("SELF-TEST OK: 25 encuentros completos, acciones legales y vector de 89 entradas.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Entrenamiento masivo ZEUS AI 1.0")
    parser.add_argument("--games", type=int, default=1_000_000, help="Número objetivo de encuentros completos hasta 100 puntos")
    parser.add_argument("--match-target", type=int, default=100)
    parser.add_argument("--blocked-scoring", choices=["loser_pips", "difference"], default="loser_pips",
                        help="Puntos por tranca: puntos de la mano perdedora o diferencia de puntos")
    parser.add_argument("--teacher-probability", type=float, default=0.30)
    parser.add_argument("--batch-matches", type=int, default=128)
    parser.add_argument("--workers", type=int, default=max(1, min(4, os.cpu_count() or 1)),
                        help="Procesos CPU paralelos para generar encuentros simulados")
    parser.add_argument("--batch-size", type=int, default=512)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--learning-rate", type=float, default=0.00035)
    parser.add_argument("--save-every", type=int, default=5000)
    parser.add_argument("--eval-every", type=int, default=10000)
    parser.add_argument("--eval-games", type=int, default=250)
    parser.add_argument("--seed", type=int, default=20261107)
    parser.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    parser.add_argument("--out", type=Path, default=Path("runs/zeus_ai_1_0"))
    parser.add_argument("--resume", type=Path, default=None)
    parser.add_argument("--apk-assets", type=Path, default=None,
                        help="Carpeta app/src/main/assets del proyecto Android para copiar el modelo ganador")
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.self_test:
        run_self_test()
        return
    if args.games <= 0 or args.match_target <= 0 or args.batch_matches <= 0:
        raise SystemExit("games, match-target y batch-matches deben ser positivos")
    if args.batch_size <= 0 or args.epochs <= 0 or args.save_every <= 0 or args.workers <= 0:
        raise SystemExit("batch-size, epochs, save-every y workers deben ser positivos")
    if args.eval_every > 0 and args.eval_games <= 0:
        raise SystemExit("eval-games debe ser positivo cuando la evaluación está activada")
    if not 0.0 <= args.teacher_probability <= 1.0:
        raise SystemExit("teacher-probability debe estar entre 0 y 1")
    if args.device == "cuda" and not torch.cuda.is_available():
        raise SystemExit("Se solicitó CUDA, pero PyTorch no detecta una GPU CUDA")
    device = torch.device("cuda" if args.device == "cuda" or (args.device == "auto" and torch.cuda.is_available()) else "cpu")
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    rng = random.Random(args.seed)

    model = PolicyValueNet().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate, weight_decay=1e-5)
    games_done = 0
    best_win_rate = -1.0
    archive: Optional[PolicyValueNet] = None
    if args.resume:
        checkpoint = torch.load(args.resume, map_location=device, weights_only=False)
        model.load_state_dict(checkpoint["model"])
        optimizer.load_state_dict(checkpoint["optimizer"])
        games_done = int(checkpoint.get("games_done", 0))
        best_win_rate = float(checkpoint.get("best_win_rate", -1.0))
        archive = copy.deepcopy(model).eval()
        if "python_rng_state" in checkpoint:
            rng.setstate(checkpoint["python_rng_state"])
        if "numpy_rng_state" in checkpoint:
            np.random.set_state(checkpoint["numpy_rng_state"])
        if "torch_rng_state" in checkpoint:
            torch.set_rng_state(checkpoint["torch_rng_state"].cpu())
        if torch.cuda.is_available() and checkpoint.get("torch_cuda_rng_state") is not None:
            torch.cuda.set_rng_state_all(checkpoint["torch_cuda_rng_state"])
        print(f"Reanudado desde {args.resume}; encuentros previos: {games_done}")

    args.out.mkdir(parents=True, exist_ok=True)
    metrics_path = args.out / "metrics.csv"
    if not metrics_path.exists():
        with metrics_path.open("w", newline="", encoding="utf-8") as f:
            csv.writer(f).writerow(["games", "loss", "policy_loss", "value_loss", "eval_win_rate", "eval_tie_rate", "eval_margin", "seconds"])
    start_time = time.time()
    while games_done < args.games:
        batch_samples: list[Sample] = []
        batch_end = min(args.games, games_done + args.batch_matches)
        batch_count = batch_end - games_done
        seeds = [rng.getrandbits(63) for _ in range(batch_count)]
        current_state = cpu_state_dict(model)
        archive_state = cpu_state_dict(archive) if archive is not None else None
        worker_count = min(args.workers, batch_count)
        seed_chunks = [seeds[i::worker_count] for i in range(worker_count)]
        payloads = [
            (current_state, archive_state, chunk, args.teacher_probability,
             args.match_target, args.blocked_scoring)
            for chunk in seed_chunks
        ]
        if worker_count == 1:
            batch_samples.extend(generate_training_chunk(payloads[0]))
        else:
            with ProcessPoolExecutor(max_workers=worker_count) as pool:
                for generated in pool.map(generate_training_chunk, payloads):
                    batch_samples.extend(generated)
        games_done = batch_end
        stats = train_batch(model, optimizer, batch_samples, device, args.batch_size, args.epochs)
        elapsed = time.time() - start_time
        print(f"partidas={games_done:,}/{args.games:,} muestras={len(batch_samples):,} "
              f"loss={stats['loss']:.4f} policy={stats['policy']:.4f} value={stats['value']:.4f} "
              f"tiempo={elapsed/60:.1f} min", flush=True)

        should_save = games_done % args.save_every < args.batch_matches or games_done >= args.games
        if should_save:
            save_checkpoint(args.out / "latest.pt", model, optimizer, games_done, best_win_rate, args, rng)
            export_model(model, args.out / "zeus_domino_policy_value_v1.bin")
            # Keep a frozen opponent from an earlier generation for diversity.
            archive = copy.deepcopy(model).eval()

        eval_result = {"win_rate": float("nan"), "tie_rate": float("nan"), "mean_score_margin": float("nan")}
        if args.eval_every > 0 and (games_done % args.eval_every < args.batch_matches or games_done >= args.games):
            eval_result = evaluate(model, device, args.eval_games, args.match_target, args.blocked_scoring,
                                   args.seed + games_done)
            print("EVALUACIÓN", eval_result, flush=True)
            if eval_result["win_rate"] > best_win_rate:
                best_win_rate = eval_result["win_rate"]
                save_checkpoint(args.out / "best.pt", model, optimizer, games_done, best_win_rate, args, rng)
                export_model(model, args.out / "zeus_domino_policy_value_best.bin")
                if args.apk_assets:
                    export_model(model, args.apk_assets / "zeus_domino_policy_value_v1.bin")
        with metrics_path.open("a", newline="", encoding="utf-8") as f:
            csv.writer(f).writerow([games_done, stats["loss"], stats["policy"], stats["value"],
                                    eval_result["win_rate"], eval_result["tie_rate"],
                                    eval_result["mean_score_margin"], elapsed])

    final_model_path = args.out / "zeus_domino_policy_value_v1.bin"
    export_model(model, final_model_path)
    print(f"FINALIZADO: {games_done:,} encuentros simulados.")
    print(f"Modelo exportado: {final_model_path}")
    if args.apk_assets:
        android_model_path = args.apk_assets / "zeus_domino_policy_value_v1.bin"
        export_model(model, android_model_path)
        print(f"Modelo Android actualizado: {android_model_path}")


if __name__ == "__main__":
    main()
