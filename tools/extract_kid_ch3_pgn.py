#!/usr/bin/env python3
"""
Trích Chương 3 từ 7. KING'S INDIAN DEFENCE-vi-dual.html -> studies/Kings_Indian_Vol1_Ch3_from_html.pgn
"""
from __future__ import annotations

import html as html_module
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import chess
import chess.pgn

REPO_ROOT = Path(__file__).resolve().parents[1]
HTML_PATH = REPO_ROOT / "7. KING'S INDIAN DEFENCE-vi-dual.html"
OUT_PATH = REPO_ROOT / "studies" / "Kings_Indian_Vol1_Ch3_from_html.pgn"

CH3_START_MARKERS = (
    'id="ch%C6%B0%C6%A1ng-3-4.nf3-0-0-5.bf4"',
    "chương-3-4.nf3-0-0-5.bf4",
)
CH4_START_MARKERS = (
    'id="ch%C6%B0%C6%A1ng-4-4.e4-d6-5.bg5"',
    "chương-4-4.e4-d6-5.bg5",
)

RE_H2 = re.compile(
    r'<h2[^>]*class="section-title"[^>]*>(.*?)</h2>',
    re.DOTALL | re.IGNORECASE,
)
RE_DIV_OPEN = re.compile(r"<div\b[^>]*>", re.I)
RE_TAG = re.compile(r"<[^>]+>")
RE_ASCII_MATH = re.compile(
    r'<asciimath[^>]*>(.*?)</asciimath>',
    re.DOTALL | re.I,
)
RE_BRANCH = re.compile(r"^([ABC])(\d*)\)\s*", re.I)
RE_FIG = re.compile(r"\s*\(\d+\)\s*$")
# Nhánh A/B/C trong <div> (không phải tiêu đề h2), thường sau <br>
RE_EMBED_ASCII = re.compile(
    r"<br\s*/?>\s*([ABC])(\d*)\)\s*[\s\S]{0,3000}?<asciimath[^>]*>([^<]+)</asciimath>",
    re.IGNORECASE,
)
RE_EMBED_PLAIN = re.compile(
    r"<br\s*/?>\s*([ABC])(\d*)\)\s*([\d\.\sNBRQKxa-h0-8=#\+\-O\-]+?)(?=\s*(?:<|\(|Figure|\())",
    re.IGNORECASE,
)


def slice_chapter3(raw: str) -> str:
    start_mark = -1
    for m in CH3_START_MARKERS:
        i = raw.find(m)
        if i >= 0:
            start_mark = i
            break
    if start_mark < 0:
        raise SystemExit("Không tìm thấy marker mở Chương 3 trong HTML.")
    # Bắt đầu từ <h2> của Chương 3, không cắt giữa thẻ (tránh lẫn cuối Chương 2).
    start = raw.rfind("<h2", 0, start_mark + 1)
    if start < 0:
        start = start_mark
    end = len(raw)
    end_mark = len(raw)
    for m in CH4_START_MARKERS:
        j = raw.find(m, start_mark + 1)
        if j >= 0:
            end_mark = min(end_mark, j)
    if end_mark < len(raw):
        end_h2 = raw.rfind("<h2", start_mark, end_mark + 1)
        end = end_h2 if end_h2 > start else end_mark
    else:
        end = end_mark
    return raw[start:end]


def strip_tags(fragment: str) -> str:
    t = RE_TAG.sub(" ", fragment)
    t = html_module.unescape(t)
    t = re.sub(r"[\xa0\u2009]+", " ", t)
    t = re.sub(r"\s+", " ", t).strip()
    return t


def extract_comment_after_h2(chunk_after_h2: str, max_len: int = 280) -> str:
    """Lấy văn bản div đầu tiên sau </h2> (bỏ figure/table nếu quá ngắn)."""
    m = RE_DIV_OPEN.search(chunk_after_h2)
    if not m:
        return ""
    depth = 0
    i = m.start()
    j = i
    while j < len(chunk_after_h2):
        if chunk_after_h2[j : j + 4].lower() == "<div":
            if j + 4 < len(chunk_after_h2) and chunk_after_h2[j + 4] in " \t\n>":
                depth += 1
            j += 1
            continue
        if chunk_after_h2[j : j + 6].lower() == "</div>":
            depth -= 1
            j += 6
            if depth == 0:
                inner = chunk_after_h2[m.end() : j - 6]
                txt = strip_tags(inner)
                if len(txt) < 20 and ("figure" in inner.lower() or "img" in inner.lower()):
                    return ""
                if len(txt) > max_len:
                    txt = txt[: max_len - 3].rsplit(" ", 1)[0] + "..."
                return txt
            continue
        j += 1
    return ""


def normalize_moveline(s: str) -> str:
    s = s.replace("×", "x")
    s = s.replace("−", "-").replace("–", "-")
    s = s.replace("…", "...")
    s = re.sub(r"\.\.\.(?=\S)", "... ", s)
    # Dính số: 18.f4Nf5 -> 18.f4 Nf5
    s = re.sub(r"([a-h0-9=+#x])(\d+\.)", r"\1 \2", s, flags=re.I)
    s = re.sub(r"([a-h0-9=+#x])(\d+\.\.\.)", r"\1 \2", s, flags=re.I)
    # e3c5 -> e3 c5
    s = re.sub(r"([a-h])(\d)([a-h])(\d)(?!\d)", r"\1\2 \3\4", s, flags=re.I)
    # B xx d4 -> Bxd4 (lỗi asciimath)
    s = re.sub(r"\bB\s*xx\s*d4\b", "Bxd4", s, flags=re.I)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def pgn_escape_comment(text: str) -> str:
    t = text.replace("\\", "\\\\").replace("}", "\\}")
    t = re.sub(r"[\r\n]+", " ", t)
    return t


SKIP_TOKENS = frozenset(
    {
        "oo",
        "dots",
        "Figure",
        "Chương",
        "A)",
        "B)",
        "C)",
    }
)


def tokenize_moves(title: str) -> list[str]:
    """Tách token SAN từ tiêu đề đã strip."""
    t = normalize_moveline(title)
    t = RE_FIG.sub("", t)
    t = re.sub(r"[!?]+$", "", t)
    t = re.sub(r"^[ABC]\d*\)\s*", "", t, flags=re.I)
    parts = re.findall(
        r"\d+\.\.\.|\d+\.|O-O-O|0-0-0|O-O|0-0|"
        r"[NBRQK]?[a-h]?[1-8]?x?[a-h][1-8](?:=[NBRQ])?[+#]?|"
        r"[NBRQK][a-h1-8]",
        t,
        flags=re.I,
    )
    out: list[str] = []
    for p in parts:
        low = p.lower()
        if low in SKIP_TOKENS:
            continue
        if p.endswith("..."):
            continue
        if re.match(r"^\d+\.$", p):
            continue
        if re.match(r"^\d+\.\.\.$", p):
            continue
        san = re.sub(r"^\d+\.\.\.", "", p)
        san = re.sub(r"^\d+\.", "", san)
        san = san.strip()
        if not san:
            continue
        if san.lower() in ("oo", "dots"):
            continue
        out.append(san)
    return out


def parse_moves(board: chess.Board, tokens: list[str]) -> list[chess.Move] | None:
    b = board.copy()
    moves: list[chess.Move] = []
    for san in tokens:
        try:
            m = b.parse_san(san)
        except (ValueError, chess.IllegalMoveError, chess.AmbiguousMoveError):
            return None
        b.push(m)
        moves.append(m)
    return moves


def try_moves_from_root(tokens: list[str]) -> list[chess.Move] | None:
    return parse_moves(chess.Board(), tokens)


@dataclass
class BranchCtx:
    """Nhánh sách sau 5...d6: mở rộng từ ctx.tail; A1/B1/C1 bắt từ numbered_fork."""

    d6_node: chess.pgn.GameNode
    tail: chess.pgn.GameNode
    numbered_fork: chess.pgn.GameNode | None = None


def build_game(sections: list[tuple[str, str]]) -> tuple[chess.pgn.Game, list[str]]:
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
    game.headers["Opening"] = (
        "King's Indian Defense: 5.Bf4 (Từ HTML Chương 3)"
    )
    game.headers["StudyName"] = "Hệ thống King's Indian Defence (Quyển 1)"
    game.headers["ChapterName"] = "Chương 3: 4.Nf3 0-0 5.Bf4"
    game.headers["Annotator"] = "HTML extract + python-chess"

    main_tail: chess.pgn.GameNode = game
    board = chess.Board()
    d6_node: chess.pgn.GameNode | None = None
    ctx: BranchCtx | None = None
    logs: list[str] = []

    def attach_comment(n: chess.pgn.GameNode, text: str) -> None:
        if not text:
            return
        prev = n.comment
        esc = pgn_escape_comment(text)
        n.comment = f"{prev} {esc}".strip() if prev else esc

    def gather_tokens(title_raw: str, title: str) -> list[str]:
        t = tokenize_moves(title)
        if t:
            return t
        inner = " ".join(
            strip_tags(m.group(1)) for m in RE_ASCII_MATH.finditer(title_raw)
        )
        return tokenize_moves(inner)

    def maybe_set_numbered_fork() -> None:
        assert ctx is not None
        par = ctx.tail.parent
        if par is None or ctx.tail.move is None:
            return
        pb = par.board()
        if pb.fullmove_number == 6 and pb.turn == chess.BLACK:
            try:
                if pb.san(ctx.tail.move) == "c5":
                    ctx.numbered_fork = ctx.tail
            except ValueError:
                pass

    for title_raw, comment in sections:
        title = strip_tags(title_raw)
        if not title or title.lower().startswith("chương 3"):
            continue
        if "Figure" in title or title.startswith("Figure"):
            continue

        tokens = gather_tokens(title_raw, title)
        bm = RE_BRANCH.match(title)

        if ctx is None:
            if bm:
                logs.append(f"Bỏ qua nhánh ABC trước khi xong dòng tới 5...d6: {title[:55]}")
                continue
            if not tokens:
                continue
            mvs = parse_moves(board, tokens)
            if mvs is None and not game.variations:
                mvs = try_moves_from_root(tokens)
            if mvs is None:
                logs.append(f"Dòng chính không parse được: {title[:80]}")
                continue
            for mv in mvs:
                main_tail = main_tail.add_variation(mv)
                board.push(mv)
            attach_comment(main_tail, comment)
            if len(board.move_stack) >= 10:
                d6_node = main_tail
                ctx = BranchCtx(d6_node=d6_node, tail=d6_node, numbered_fork=None)
            continue

        assert d6_node is not None

        if bm:
            letter = bm.group(1).upper()
            digits = bm.group(2) or ""
            if not digits:
                if letter == "A":
                    if not tokens:
                        logs.append(f"A) thiếu nước: {title[:60]}")
                        continue
                    mvs = parse_moves(d6_node.board(), tokens)
                    if mvs is None:
                        logs.append(f"A) illegal: {title[:80]} tokens={tokens}")
                        continue
                    tail = d6_node
                    for mv in mvs:
                        tail = tail.add_variation(mv)
                    ctx = BranchCtx(d6_node, tail, None)
                    attach_comment(tail, comment)
                    maybe_set_numbered_fork()
                    continue
                if letter in ("B", "C"):
                    if not tokens:
                        logs.append(f"{letter}) thiếu nước: {title[:60]}")
                        continue
                    mvs = parse_moves(d6_node.board(), tokens)
                    if mvs is None:
                        logs.append(f"{letter}) illegal: {title[:80]} tokens={tokens}")
                        continue
                    tail = d6_node
                    for mv in mvs:
                        tail = tail.add_variation(mv)
                    ctx = BranchCtx(d6_node, tail, None)
                    attach_comment(tail, comment)
                    maybe_set_numbered_fork()
                    continue

            if ctx.numbered_fork is None:
                logs.append(f"{letter}{digits}) chưa có numbered_fork (cần 6...c5): {title[:70]}")
                continue
            if not tokens:
                logs.append(f"{letter}{digits}) thiếu nước: {title[:60]}")
                continue
            b_sub = ctx.numbered_fork.board()
            mvs = parse_moves(b_sub, tokens)
            if mvs is None:
                logs.append(f"{letter}{digits}) illegal: {title[:80]} tokens={tokens}")
                continue
            tail = ctx.numbered_fork
            for mv in mvs:
                tail = tail.add_variation(mv)
            ctx.tail = tail
            attach_comment(tail, comment)
            maybe_set_numbered_fork()
            continue

        if not tokens:
            continue
        mvs = parse_moves(ctx.tail.board(), tokens)
        if mvs is None:
            logs.append(f"Bỏ qua illegal: {title[:80]} tokens={tokens[:8]}")
            continue
        tail = ctx.tail
        for mv in mvs:
            tail = tail.add_variation(mv)
        ctx.tail = tail
        attach_comment(tail, comment)
        maybe_set_numbered_fork()

    return game, logs


def find_embed_min_pos(ch3: str) -> int:
    """Vị trí sau </h2> của mục 5...d6 — không trích <br>B) từ hình/minh họa trước đó."""
    i = ch3.find('id="5...d6-')
    if i < 0:
        i = ch3.find("5...d6 (32)")
    if i < 0:
        return 0
    j = ch3.find("</h2>", i)
    return j + len("</h2>") if j >= 0 else 0


def append_embedded_branch_sections(html_between_h2: str, sections: list[tuple[str, str]]) -> None:
    """Thêm pseudo-section từ div (vd. <br>B) ... asciimath 6.Qd2)."""
    if not html_between_h2:
        return
    # Chỉ bỏ div tóm tắt A/B/C ngay sau 5...d6 (bắt đầu bằng A) <span...), không bỏ chunk dài có nhắc lại 6.h3/Qd2/e3 trong lời giải.
    head = html_between_h2[:3500]
    if re.search(r"A\)\s*<span", head, re.I) and all(
        x in html_between_h2 for x in ("6.h3", "6.Qd2", "6.e3")
    ):
        return
    seen: set[tuple[str, str, str]] = set()
    for rx in (RE_EMBED_ASCII, RE_EMBED_PLAIN):
        for m in rx.finditer(html_between_h2):
            letter = m.group(1).upper()
            digits = (m.group(2) or "").strip()
            raw_moves = m.group(3).strip()
            if rx is RE_EMBED_ASCII:
                move_blob = strip_tags(raw_moves)
            else:
                move_blob = re.sub(r"\s+", " ", raw_moves).strip()
            if not move_blob:
                continue
            key = (letter, digits, move_blob[:80])
            if key in seen:
                continue
            seen.add(key)
            label = f"{letter}{digits})" if digits else f"{letter})"
            synthetic = f"{label} {move_blob}"
            sections.append((f"<span>{synthetic}</span>", ""))


def main() -> None:
    if not HTML_PATH.is_file():
        print(f"Thiếu file: {HTML_PATH}", file=sys.stderr)
        sys.exit(1)
    raw = HTML_PATH.read_text(encoding="utf-8", errors="replace")
    ch3 = slice_chapter3(raw)
    embed_min = find_embed_min_pos(ch3)

    sections: list[tuple[str, str]] = []
    pos = 0
    while True:
        m = RE_H2.search(ch3, pos)
        if not m:
            lo, hi = max(pos, embed_min), len(ch3)
            if hi > lo:
                append_embedded_branch_sections(ch3[lo:hi], sections)
            break
        lo, hi = max(pos, embed_min), m.start()
        if hi > lo:
            append_embedded_branch_sections(ch3[lo:hi], sections)
        inner = m.group(1)
        end = m.end()
        next_h2 = RE_H2.search(ch3, end)
        tail_end = next_h2.start() if next_h2 else len(ch3)
        comment = extract_comment_after_h2(ch3[end:tail_end])
        sections.append((inner, comment))
        pos = end

    game, logs = build_game(sections)
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUT_PATH.open("w", encoding="utf-8") as f:
        print(game, file=f, end="\n")

    log_path = OUT_PATH.with_suffix(".extract.log")
    log_path.write_text("\n".join(logs) if logs else "(no issues)\n", encoding="utf-8")
    print(f"Wrote {OUT_PATH} ({len(logs)} log lines -> {log_path})")


if __name__ == "__main__":
    main()
