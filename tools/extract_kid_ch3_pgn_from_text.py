#!/usr/bin/env python3
"""
Trích Chương 3 từ `studies/Book full text extract.txt` -> tạo PGN cho Opening Trainer.

Khác với bản HTML (có thể lỗi font/mathjax), file text này cho unicode Tiếng Việt rõ hơn.
Script cố gắng chỉ lấy các dòng "move-ish" (bắt đầu bằng move number / label nhánh),
để hạn chế dính các chuỗi cờ nằm trong câu bình luận (ví dụ chú thích hình / phân tích nhánh phụ).
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

import chess
import chess.pgn

RE_BETWEEN_CHAPTERS = re.compile(r"^Chương\s+(\d+)\b")
RE_LABEL = re.compile(r"^(A\d*|B|C\d*)\)\s*(.*)$")

# Chuẩn hóa một số ký tự từ font/mã hóa lỗi (hay gặp trong bản trích).
def normalize_text(s: str) -> str:
    s = s.replace("…", "...")
    s = s.replace("−", "-").replace("–", "-").replace("—", "-")
    s = s.replace("’", "'")
    # Cyrillic -> Latin (thường thấy trong đoạn trích)
    s = s.replace("В", "B").replace("х", "x")
    # Một lỗi unicode hay thấy: "fæe4" -> "fxe4"
    s = s.replace("f\u00e6", "fx")  # fæ
    s = s.replace("fæ", "fx")
    # Một số chuỗi chứa dấu bằng ở cuối câu.
    s = s.replace("=", "")
    return s


RE_MOVE_NUMBER_PREFIX = re.compile(r"\b\d+\.\.\.\s*|\b\d+\.\s*")
RE_PARENS_PAGE = re.compile(r"\(\s*\d+\s*\)")

# SAN regex (đủ dùng cho các nước xuất hiện trong chương 3)
RE_SAN = re.compile(
    r"(O-O-O|O-O|0-0-0|0-0|"
    r"[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](?:=[QRBN])?[+#]?|"
    r"[a-h][1-8](?:=[QRBN])?[+#]?|"
    r"[KQRBN]x?[a-h]?[1-8]?x?[a-h][1-8])",
    re.IGNORECASE,
)


def strip_pgn_comment(text: str) -> str:
    t = text.replace("}", "]")
    t = t.replace("{", "[")
    t = re.sub(r"\s+", " ", t).strip()
    if len(t) > 90:
        t = t[:87].rsplit(" ", 1)[0] + "..."
    return t


def tokenize_san(s: str) -> list[str]:
    s = normalize_text(s)
    s = RE_PARENS_PAGE.sub(" ", s)
    s = RE_MOVE_NUMBER_PREFIX.sub(" ", s)
    # Castling
    s = s.replace("0-0-0", "O-O-O").replace("0-0", "O-O")

    tokens: list[str] = []
    for m in RE_SAN.finditer(s):
        tok = m.group(1)
        tok = tok.strip()
        tok = tok.rstrip("!?")
        # Một số nơi còn bị dính dấu '.' cuối token
        tok = tok.rstrip(".")
        tok = tok.replace("0-0", "O-O").replace("0-0-0", "O-O-O")
        if tok:
            tokens.append(tok)
    return tokens


def is_moveish_line(line: str) -> bool:
    t = line.strip()
    if not t:
        return False
    if t.startswith(("Figure", "Hình", "Chương")):
        return False
    # Label nhánh: A1) ... / C2) ...
    if RE_LABEL.match(t):
        return True
    # Move-ish: bắt đầu bằng số nước hoặc castling
    if re.match(r"^\d+\.\.\.|^\d+\.", t):
        return True
    if t.startswith(("O-O", "0-0", "O-O-O", "0-0-0")):
        return True
    return False


def iter_move_lines(block_lines: list[str]) -> list[str]:
    out: list[str] = []
    for ln in block_lines:
        if is_moveish_line(ln):
            out.append(ln)
    return out


def extract_short_comment(block_lines: list[str]) -> str:
    # Lấy câu đầu tiên (hoặc vài cụm) mà không phải dòng move.
    for i, ln in enumerate(block_lines):
        t = ln.strip()
        if not t:
            continue
        if is_moveish_line(ln):
            continue
        if t.startswith(("Figure", "Hình", "Chương")):
            continue
        if "Trắng" in t or "Đen" in t:
            return strip_pgn_comment(t)
        # fallback
        if len(t) >= 8:
            return strip_pgn_comment(t)
    return ""


def apply_san_seq(board: chess.Board, node: chess.pgn.GameNode, seq: list[str]) -> tuple[chess.Board, chess.pgn.GameNode] | tuple[chess.Board, None]:
    cur = node
    b = board.copy()
    for san in seq:
        try:
            mv = b.parse_san(san)
        except Exception:
            return b, cur  # dừng ngay tại điểm không parse được
        cur = cur.add_variation(mv)
        b.push(mv)
    return b, cur


@dataclass(frozen=True)
class Seg:
    start: int
    end: int  # exclusive


def find_line_index(lines: list[str], pattern: str, start: int = 0) -> int:
    rx = re.compile(pattern)
    for i in range(start, len(lines)):
        if rx.match(lines[i].strip()):
            return i
    return -1


def find_line_index_contains(lines: list[str], needle: str, start: int = 0) -> int:
    for i in range(start, len(lines)):
        if needle in lines[i]:
            return i
    return -1


def slice_between(lines: list[str], seg_start: int, seg_end: int) -> list[str]:
    return lines[seg_start:seg_end]


def main() -> None:
    repo_root = Path(__file__).resolve().parents[1]
    text_path = repo_root / "studies" / "Book full text extract.txt"
    out_path = repo_root / "studies" / "Kings_Indian_Vol1_Ch3_from_html.pgn"

    raw_lines = text_path.read_text(encoding="utf-8", errors="replace").splitlines()

    # Tìm đúng đoạn Chương 3 (không lấy mục lục ở đầu file).
    idx_start = -1
    for i in range(len(raw_lines)):
        if raw_lines[i].strip() == "Chương 3":
            # điều kiện: phía sau phải có dòng 4.Nf3...
            if i + 1 < len(raw_lines) and "4.Nf3" in raw_lines[i + 1]:
                idx_start = i
                break
    if idx_start < 0:
        print("Không tìm thấy đoạn `Chương 3` trong file text.", file=sys.stderr)
        sys.exit(2)

    idx_end = -1
    for i in range(idx_start + 1, len(raw_lines)):
        if raw_lines[i].strip().startswith("Chương 4"):
            idx_end = i
            break
    if idx_end < 0:
        print("Không tìm thấy `Chương 4` để cắt kết thúc chương 3.", file=sys.stderr)
        sys.exit(2)

    ch3_lines = raw_lines[idx_start:idx_end]

    # Tìm các label nhánh ở level 1/2.
    def idx_label(prefix: str) -> int:
        rx = re.compile(rf"^{re.escape(prefix)}\)")
        for i, ln in enumerate(ch3_lines):
            if rx.match(ln.strip()):
                return i
        return -1

    A1_i = idx_label("A1")
    A2_i = idx_label("A2")
    A3_i = idx_label("A3")
    B_i = idx_label("B")
    C_i = idx_label("C")
    C1_i = idx_label("C1")
    C2_i = idx_label("C2")

    missing = [name for name, idx in [("A1", A1_i), ("A2", A2_i), ("A3", A3_i), ("B", B_i), ("C", C_i), ("C1", C1_i), ("C2", C2_i)] if idx < 0]
    if missing:
        print(f"Thiếu label nhánh trong Chương 3: {missing}", file=sys.stderr)
        sys.exit(3)

    # PGN headers
    game = chess.pgn.Game()
    game.headers["Event"] = "King's Indian Study"
    game.headers["Site"] = "?"
    game.headers["Date"] = "????.??.??"
    game.headers["Round"] = "?"
    game.headers["White"] = "?"
    game.headers["Black"] = "?"
    game.headers["Result"] = "*"
    game.headers["Variant"] = "Standard"
    game.headers["ECO"] = "E61"
    game.headers["Opening"] = "King's Indian Defense: 5.Bf4 (hệ thống Tượng tốt sau Nf3)"
    game.headers["StudyName"] = "Hệ thống King's Indian Defence (Quyển 1)"
    game.headers["ChapterName"] = "Chương 3: 4.Nf3 0-0 5.Bf4"
    game.headers["Annotator"] = "Text extract + python-chess validation"

    # Mainline cố định (đúng theo chương)
    board = chess.Board()
    node = game
    main_san = [
        "d4",
        "Nf6",
        "c4",
        "g6",
        "Nc3",
        "Bg7",
        "Nf3",
        "O-O",
        "Bf4",
        "d6",
    ]

    for san in main_san:
        mv = board.parse_san(san)
        node = node.add_variation(mv)
        board.push(mv)

    main_tail = node  # sau 5...d6, lượt Trắng (6.xxx)

    # --- A branch (6.h3) ---
    # base = 6.h3 6...c5
    board_after_d6 = board.copy()
    branch_comment_A = ""
    # comment: lấy đoạn đầu trong khoảng giữa 5...d6 và A1
    # (không quá chính xác, nhưng đủ ngắn)
    for i in range(0, A1_i):
        if "Trắng mở ô" in ch3_lines[i]:
            branch_comment_A = strip_pgn_comment(ch3_lines[i])
            break

    bA = board_after_d6.copy()
    # apply 6.h3
    mv = bA.parse_san("h3")
    n_h3 = main_tail.add_variation(mv)
    bA.push(mv)
    if branch_comment_A:
        n_h3.comment = branch_comment_A
    # apply 6...c5
    mv = bA.parse_san("c5")
    n_c5 = n_h3.add_variation(mv)
    bA.push(mv)

    # A1 block
    A1_block = slice_between(ch3_lines, A1_i, A2_i)
    A1_comment = extract_short_comment(A1_block)
    A1_move_lines = iter_move_lines(A1_block)
    A1_tokens = []
    for ln in A1_move_lines:
        A1_tokens.extend(tokenize_san(ln))
    # A2 block
    A2_block = slice_between(ch3_lines, A2_i, A3_i)
    A2_comment = extract_short_comment(A2_block)
    # tách A2: giữ phần tới 9.bxa6 và phần sau marker "Quay trở lại phương án chính 9.bxa6"
    i_bxa6 = -1
    for j, ln in enumerate(A2_block):
        if ln.strip().startswith("9.bxa6"):
            i_bxa6 = j
            break
    i_marker = -1
    for j, ln in enumerate(A2_block):
        if "Quay trở lại phương án chính 9.bxa6" in ln:
            i_marker = j
            break
    if i_bxa6 < 0 or i_marker < 0 or i_marker <= i_bxa6:
        A2_move_lines = iter_move_lines(A2_block)
    else:
        prefix = A2_block[: i_bxa6 + 1]
        suffix = A2_block[i_marker + 1 :]
        A2_move_lines = iter_move_lines(prefix) + iter_move_lines(suffix)
    A2_tokens = []
    for ln in A2_move_lines:
        A2_tokens.extend(tokenize_san(ln))

    # A3 block
    A3_block = slice_between(ch3_lines, A3_i, B_i)
    A3_comment = extract_short_comment(A3_block)
    A3_move_lines = iter_move_lines(A3_block)
    A3_tokens = []
    for ln in A3_move_lines:
        A3_tokens.extend(tokenize_san(ln))

    # Apply sub-branches under position after 6...c5
    board_A_sub = bA.copy()

    # A1: starts at move 7
    b1 = board_A_sub.copy()
    node_A1_parent = n_c5
    node = node_A1_parent
    for san in A1_tokens:
        try:
            mv = b1.parse_san(san)
        except Exception:
            break
        node = node.add_variation(mv)
        b1.push(mv)
    if A1_comment and node is not None:
        node.comment = A1_comment

    # A2: prefix white 7.d5 (vì A2) không ghi rõ 7.d5 trong text)
    b2 = board_A_sub.copy()
    node2 = node_A1_parent
    for san in ["d5"] + A2_tokens:
        try:
            mv = b2.parse_san(san)
        except Exception:
            break
        node2 = node2.add_variation(mv)
        b2.push(mv)
    if A2_comment:
        node2.comment = A2_comment

    # A3
    b3 = board_A_sub.copy()
    node3 = node_A1_parent
    for san in A3_tokens:
        try:
            mv = b3.parse_san(san)
        except Exception:
            break
        node3 = node3.add_variation(mv)
        b3.push(mv)
    if A3_comment:
        node3.comment = A3_comment

    # --- B branch (6.Qd2) ---
    # B base: 6.Qd2 6...c5
    board_B = board_after_d6.copy()
    mv = board_B.parse_san("Qd2")
    n_B_qd2 = main_tail.add_variation(mv)
    board_B.push(mv)
    mv = board_B.parse_san("c5")
    n_B_c5 = n_B_qd2.add_variation(mv)
    board_B.push(mv)

    # B continuation tokens: lấy từ sau dòng "6...c5"
    B_block = slice_between(ch3_lines, B_i, C_i)
    # tìm dòng move-ish "6...c5"
    after_c5 = B_block
    for k, ln in enumerate(B_block):
        if ln.strip().startswith("6...c5") or ln.strip().startswith("6...C5"):
            after_c5 = B_block[k + 1 :]
            break
    B_tokens: list[str] = []
    for ln in iter_move_lines(after_c5):
        B_tokens.extend(tokenize_san(ln))

    bB = n_B_c5.board()
    nodeB = n_B_c5
    # parse một prefix legal dài nhất
    for san in B_tokens:
        try:
            mv = bB.parse_san(san)
        except Exception:
            break
        nodeB = nodeB.add_variation(mv)
        bB.push(mv)

    # --- C branch (6.e3) ---
    board_C = board_after_d6.copy()
    mv = board_C.parse_san("e3")
    n_C_e3 = main_tail.add_variation(mv)
    board_C.push(mv)
    mv = board_C.parse_san("Nh5")
    n_C_Nh5 = n_C_e3.add_variation(mv)
    board_C.push(mv)

    # C1 block: starts at C1) 7.d5
    C1_block = slice_between(ch3_lines, C1_i, C2_i)
    C1_tokens: list[str] = []
    for ln in iter_move_lines(C1_block):
        C1_tokens.extend(tokenize_san(ln))
    # C2 block: C2) 7.Be2 -> until end of chapter
    C2_block = slice_between(ch3_lines, C2_i, len(ch3_lines))
    C2_tokens: list[str] = []
    for ln in iter_move_lines(C2_block):
        C2_tokens.extend(tokenize_san(ln))

    # Apply C1/C2 under position after 6...Nh5
    bC_parent = n_C_Nh5.board()

    bC1 = bC_parent.copy()
    nodeC1 = n_C_Nh5
    for san in C1_tokens:
        try:
            mv = bC1.parse_san(san)
        except Exception:
            break
        nodeC1 = nodeC1.add_variation(mv)
        bC1.push(mv)

    bC2 = bC_parent.copy()
    nodeC2 = n_C_Nh5
    for san in C2_tokens:
        try:
            mv = bC2.parse_san(san)
        except Exception:
            break
        nodeC2 = nodeC2.add_variation(mv)
        bC2.push(mv)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(str(game), encoding="utf-8")
    print(f"Wrote: {out_path}")

    # log nhẹ: in ra số nút/nhánh (không bắt buộc)
    # (không in tiếng Việt có dấu để tránh lỗi console)
    try:
        import chess.pgn as _pgn

        def count_variations(n: chess.pgn.GameNode) -> int:
            tot = len(n.variations)
            for v in n.variations:
                tot += count_variations(v)
            return tot

        print(f"Variations edges count: {count_variations(game)}")
    except Exception:
        pass


if __name__ == "__main__":
    main()

