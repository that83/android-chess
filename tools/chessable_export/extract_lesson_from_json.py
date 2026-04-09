#!/usr/bin/env python3
"""
Parse Chessable api/v1/getLesson JSON: emit a small PGN fragment + markdown of coaching text.

Usage:
  python extract_lesson_from_json.py path/to/getLesson.json [--out-dir dir]
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, TypedDict


class LessonRef(TypedDict):
    bid: int
    oid: int
    lid: int
    chapter: str
    name: str


def _walk_comment_tree(node: Any, out: list[tuple[str, str]]) -> None:
    """Collect (kind, text): kind in ('comment','san') from Chessable comment JSON."""
    if isinstance(node, dict):
        k = node.get("key")
        v = node.get("val")
        if k == "C" and isinstance(v, str) and v.strip():
            out.append(("comment", v.strip()))
        elif k == "S" and isinstance(v, str) and v.strip():
            out.append(("san", v.strip()))
        for child in node.values():
            _walk_comment_tree(child, out)
    elif isinstance(node, list):
        for x in node:
            _walk_comment_tree(x, out)


def _parse_comment_field(raw: str) -> list[tuple[str, str]]:
    if not raw or not str(raw).strip():
        return []
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError:
        return [("raw", str(raw)[:5000])]
    out: list[tuple[str, str]] = []
    _walk_comment_tree(obj, out)
    return out


def _escape_pgn_header(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def collect_guide_text_for_move(move: dict[str, Any]) -> list[str]:
    """Plain-text lines for coaching (comments + variant SANs)."""
    lines: list[str] = []
    for field in ("comment_white", "comment_black", "comment_before_white", "comment_before_black"):
        raw = move.get(field)
        if not raw:
            continue
        items = _parse_comment_field(str(raw))
        if not items:
            continue
        short = field.replace("comment_", "").replace("_", " ")
        for kind, text in items:
            if kind == "comment":
                lines.append(f"[{short}] {text}")
            elif kind == "san":
                lines.append(f"[{short} var] {text}")
            else:
                lines.append(f"[{short}] {text}")
    return lines


def build_movetext_from_moves(moves: list[dict[str, Any]]) -> str:
    """
    Build a single-line movetext from Chessable lesson.moves (ordered plies).
    """
    if not moves:
        return ""
    tokens: list[str] = []
    fullmove = 1
    expect_black = False

    for mv in moves:
        w = mv.get("move_white")
        b = mv.get("move_black")
        w = w if isinstance(w, str) and w.strip() else None
        b = b if isinstance(b, str) and b.strip() else None

        if w and b:
            tokens.append(f"{fullmove}. {w} {b}")
            fullmove += 1
            expect_black = False
        elif w:
            tokens.append(f"{fullmove}. {w}")
            expect_black = True
        elif b:
            if expect_black and tokens:
                tokens[-1] = f"{tokens[-1]} {b}"
                fullmove += 1
                expect_black = False
            else:
                tokens.append(f"{fullmove}... {b}")
                fullmove += 1
                expect_black = False
    return " ".join(tokens)


def build_guide_header_value(moves: list[dict[str, Any]], max_len: int = 24_000) -> str:
    parts: list[str] = []
    for i, mv in enumerate(moves):
        sub = collect_guide_text_for_move(mv)
        if sub:
            label = mv.get("opening_name") or f"move {i + 1}"
            parts.append(f"## {label}")
            parts.extend(sub)
    raw = " | ".join(p.replace("\r\n", " ").replace("\n", " ").strip() for p in parts if p.strip())
    raw = re.sub(r"\s+", " ", raw).strip()
    if len(raw) > max_len:
        raw = raw[: max_len - 3] + "..."
    return raw


def lesson_api_json_to_lichess_pgn(data: dict[str, Any]) -> str:
    """
    One PGN game from a full getLesson JSON object (root has \"lesson\").
    Uses custom tag [Guide \"...\"] for coaching text (Lichess import / studies).
    """
    lesson = data.get("lesson") or {}
    moves = lesson.get("moves") or []
    chapter = lesson.get("chapter") or {}
    ch_title = (chapter.get("title") or "").strip()
    first = moves[0] if moves else {}
    event = (first.get("opening_name") or "Chessable lesson").strip()
    book_name = (first.get("book_name") or "Chessable").strip()
    res = (first.get("result") or "*").strip()
    fen = ((first.get("move_fen") or "") if first else "").strip()

    guide_val = build_guide_header_value(moves)

    hdr: list[str] = [
        f'[Event "{_escape_pgn_header(str(event))}"]',
        '[Site "Chessable"]',
        f'[Source "{_escape_pgn_header(str(book_name))}"]',
    ]
    if ch_title:
        hdr.append(f'[Chapter "{_escape_pgn_header(str(ch_title))}"]')
    hdr.append(f'[Result "{_escape_pgn_header(str(res))}"]')
    if guide_val:
        hdr.append(f'[Guide "{_escape_pgn_header(guide_val)}"]')
    if fen:
        hdr.append('[SetUp "1"]')
        hdr.append(f'[FEN "{_escape_pgn_header(fen)}"]')

    body = build_movetext_from_moves(moves)
    if not body:
        body = ""
    return "\n".join(hdr) + "\n\n" + body + "\n\n"


def build_lesson_queue_from_explorer(explorer_body: dict[str, Any]) -> list[LessonRef]:
    """
    Flat lesson list in UI order from courseExplorerData JSON (same order as sidebar).
    """
    res = explorer_body.get("result") or explorer_body
    bid = int(res.get("bid") or 0)
    if not bid:
        raise ValueError("courseExplorerData: missing result.bid")
    chapters = res.get("chapters") or []
    ordered = sorted(chapters, key=lambda c: int(c.get("order") or 0))
    out: list[LessonRef] = []
    for ch in ordered:
        lid = int(ch.get("lid") or 0)
        ctitle = str(ch.get("title") or "")
        for var in ch.get("variations") or []:
            oid = var.get("oid")
            if oid is None:
                continue
            out.append(
                {
                    "bid": bid,
                    "oid": int(oid),
                    "lid": lid,
                    "chapter": ctitle,
                    "name": str(var.get("name") or ""),
                }
            )
    return out


def find_lesson_index(queue: list[LessonRef], oid: int, lid: int) -> int:
    for i, item in enumerate(queue):
        if int(item["oid"]) == int(oid) and int(item["lid"]) == int(lid):
            return i
    raise ValueError(f"No lesson with oid={oid} lid={lid} in course explorer queue ({len(queue)} items)")


def extract(lesson_path: Path, out_dir: Path) -> None:
    data = json.loads(lesson_path.read_text(encoding="utf-8"))
    lesson = data.get("lesson") or {}
    moves = lesson.get("moves") or []
    chapter = lesson.get("chapter") or {}
    books = lesson.get("books") or {}

    out_dir.mkdir(parents=True, exist_ok=True)
    stem = lesson_path.stem
    pgn_path = out_dir / f"{stem}_positions.pgn"
    md_path = out_dir / f"{stem}_guides.md"

    pgn_chunks: list[str] = []
    md_lines: list[str] = [
        "# Trích từ getLesson (Chessable)\n",
        f"- Nguồn file: `{lesson_path.name}`\n",
    ]

    for i, mv in enumerate(moves):
        bid = mv.get("bid", "")
        oid = mv.get("oid", "")
        book_name = mv.get("book_name") or "Chessable"
        opening = mv.get("opening_name") or f"move_{i}"
        fen = (mv.get("move_fen") or "").strip()
        mw = mv.get("move_white")
        mb = mv.get("move_black")
        res = mv.get("result") or "*"
        ch_title = mv.get("chapterTitle") or chapter.get("title") or ""

        md_lines.append(f"\n## Nước {i + 1}: {opening}\n")
        md_lines.append(f"- bid={bid} oid={oid}\n")
        if ch_title:
            md_lines.append(f"- Chương: {ch_title}\n")

        if fen:
            md_lines.append(f"- **FEN:** `{fen}`\n")
        line_bits = []
        if mw:
            line_bits.append(f"Trắng: `{mw}`")
        if mb:
            line_bits.append(f"Đen: `{mb}`")
        if line_bits:
            md_lines.append("- " + " | ".join(line_bits) + "\n")

        for field in ("comment_white", "comment_black", "comment_before_white", "comment_before_black"):
            raw = mv.get(field)
            if not raw:
                continue
            items = _parse_comment_field(raw)
            if not items:
                continue
            md_lines.append(f"\n### {field}\n")
            for kind, text in items:
                if kind == "comment":
                    md_lines.append(f"- **Giải thích:** {text}\n")
                elif kind == "san":
                    md_lines.append(f"- **Biến / SAN:** `{text}`\n")
                else:
                    md_lines.append(f"- {text}\n")

        # Mini PGN per position (Chessable không gửi cả ván một chuỗi PGN)
        hdr = [
            '[Event "%s"]' % _escape_pgn_header(str(opening)),
            '[Site "Chessable"]',
            '[Source "%s"]' % _escape_pgn_header(str(book_name)),
        ]
        if ch_title:
            hdr.append('[Chapter "%s"]' % _escape_pgn_header(str(ch_title)))
        hdr.append('[Result "%s"]' % _escape_pgn_header(str(res)))
        if fen:
            hdr.append('[SetUp "1"]')
            hdr.append('[FEN "%s"]' % _escape_pgn_header(fen))
        san_line = " ".join(
            x for x in (mw, mb) if x
        )
        if san_line:
            body = f"1. {san_line}\n"
        else:
            body = "\n"
        pgn_chunks.append("\n".join(hdr) + "\n\n" + body + "\n")

    pgn_path.write_text("\n".join(pgn_chunks), encoding="utf-8")
    md_path.write_text("".join(md_lines), encoding="utf-8")

    index = out_dir / "EXTRACT_INDEX.txt"
    index.write_text(
        f"PGN (từng position): {pgn_path.name}\n"
        f"Hướng dẫn / comment: {md_path.name}\n",
        encoding="utf-8",
    )
    print(f"Wrote {pgn_path}")
    print(f"Wrote {md_path}")
    print(f"Wrote {index}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("getlesson_json", type=Path, help="Captured getLesson *.json")
    ap.add_argument(
        "--out-dir",
        type=Path,
        default=None,
        help="Default: <json_dir>/extracted",
    )
    args = ap.parse_args()
    p = args.getlesson_json.resolve()
    if not p.is_file():
        raise SystemExit(f"Not found: {p}")
    out = args.out_dir
    if out is None:
        out = p.parent / "extracted"
    else:
        out = out.resolve()
    extract(p, out)


if __name__ == "__main__":
    main()
