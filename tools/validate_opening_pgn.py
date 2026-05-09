#!/usr/bin/env python3
"""
Kiểm tra PGN mở đầu: replay toàn bộ nước (python-chess).
Tùy chọn: Stockfish trên Windows (--stockfish path) để gửi position + legal moves
(không chứng minh nước sách tốt nhất, chỉ bắt lỗi parse/FEN).

Cài đặt: pip install chess

Stockfish: tải từ https://stockfishchess.org/download/ , thêm vào PATH
hoặc: python tools/validate_opening_pgn.py studies/foo.pgn --stockfish C:\\path\\stockfish.exe
"""
from __future__ import annotations

import argparse
import io
import subprocess
import sys
from pathlib import Path
from typing import Iterator

import chess
import chess.pgn
import chess.engine


def iter_games(pgn_path: Path) -> Iterator[chess.pgn.Game]:
    text = pgn_path.read_text(encoding="utf-8", errors="replace")
    f = io.StringIO(text)
    while True:
        g = chess.pgn.read_game(f)
        if g is None:
            break
        yield g


def validate_game_legal(game: chess.pgn.Game, game_index: int) -> list[str]:
    errors: list[str] = []

    def walk(n: chess.pgn.GameNode, b: chess.Board, prefix: list[str]) -> None:
        for var in n.variations:
            if var.move is None:
                continue
            bb = b.copy()
            try:
                san = bb.san(var.move)
            except Exception as e:  # noqa: BLE001
                errors.append(f"Game {game_index}: SAN lỗi sau {' '.join(prefix)}: {e}")
                return
            p2 = prefix + [san]
            try:
                if var.move not in bb.legal_moves:
                    errors.append(
                        f"Game {game_index}: illegal {san} sau {' '.join(prefix)}"
                    )
                    return
                bb.push(var.move)
            except Exception as e:  # noqa: BLE001
                errors.append(f"Game {game_index}: {san}: {e}")
                return
            walk(var, bb, p2)

    walk(game, game.board(), [])
    return errors


def validate_with_stockfish(
    game: chess.pgn.Game,
    game_index: int,
    engine_path: str | Path,
) -> list[str]:
    errors: list[str] = []
    try:
        eng = chess.engine.SimpleEngine.popen_uci(str(engine_path))
    except OSError as e:
        return [f"Không chạy được engine {engine_path}: {e}"]

    try:
        board = game.board()

        def check_node(n: chess.pgn.GameNode, b: chess.Board) -> None:
            for var in n.variations:
                if var.move is None:
                    continue
                bb = b.copy()
                info = eng.analyse(bb, chess.engine.Limit(depth=1))
                legal_uci = {m.uci() for m in bb.legal_moves}
                if var.move.uci() not in legal_uci:
                    errors.append(
                        f"Game {game_index} [SF]: {bb.san(var.move)} không legal tại FEN {bb.fen()}"
                    )
                    return
                bb.push(var.move)
                check_node(var, bb)

        check_node(game, board)
    finally:
        eng.quit()
    return errors


def main() -> None:
    ap = argparse.ArgumentParser(description="Validate opening PGN (python-chess + optional Stockfish).")
    ap.add_argument("pgn", type=Path, help="Đường dẫn file .pgn")
    ap.add_argument(
        "--stockfish",
        type=Path,
        default=None,
        help="Đường dẫn stockfish.exe (Windows) hoặc binary UCI",
    )
    args = ap.parse_args()
    if not args.pgn.is_file():
        print(f"Không thấy file: {args.pgn}", file=sys.stderr)
        sys.exit(2)

    all_err: list[str] = []
    for idx, game in enumerate(iter_games(args.pgn), start=1):
        all_err.extend(validate_game_legal(game, idx))
        if args.stockfish:
            all_err.extend(
                validate_with_stockfish(game, idx, args.stockfish)
            )

    if all_err:
        for e in all_err:
            print(e, file=sys.stderr)
        sys.exit(1)
    print(f"OK: {args.pgn} (all moves legal).")


if __name__ == "__main__":
    main()
