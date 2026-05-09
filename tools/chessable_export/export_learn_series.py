#!/usr/bin/env python3
"""
Theo thứ tự sidebar (courseExplorerData.chapters): mở từng bài Learn, bắt getLesson,
ghép nhiều game PGN trong một file (tag tùy chỉnh [Guide "..."] cho nội dung hướng dẫn).

  cd tools/chessable_export
  python export_learn_series.py --url "https://www.chessable.com/learn/5193/2657521/15"
  python export_learn_series.py --url "..." --max-lessons 50   # giới hạn N bài (test)

Mặc định: lấy hết các bài từ URL bắt đầu tới cuối khóa (raw_json/*.json + combined_lichess.pgn).

Giám sát lâu: mở file ``run_<id>_progress.json`` trong ``--out-dir`` (cập nhật sau mỗi bài OK);
hoặc chạy ``python -u export_learn_series.py ...`` để log console không bị buffer.

Cần auth.json (python export_chessable.py login --headed).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from playwright.sync_api import sync_playwright

from extract_lesson_from_json import (
    build_guide_header_value,
    build_lesson_queue_from_explorer,
    find_lesson_index,
    lesson_api_json_to_lichess_pgn,
)

DEFAULT_AUTH = Path(__file__).resolve().parent / "auth.json"

_LEARN_PATH_RE = re.compile(r"/learn/(\d+)/(\d+)/(\d+)(?:/|$)")


def parse_learn_url(url: str) -> tuple[int, int, int]:
    path = urlparse(url.strip()).path or ""
    m = _LEARN_PATH_RE.search(path)
    if not m:
        raise SystemExit(
            f"URL không đúng dạng .../learn/{{bid}}/{{oid}}/{{lid}} — ví dụ "
            f"https://www.chessable.com/learn/5193/2657521/15 — nhận được path: {path!r}"
        )
    return int(m.group(1)), int(m.group(2)), int(m.group(3))


def _launch(p, headed: bool, channel: str | None):
    kw: dict = {"headless": not headed}
    if channel:
        kw["channel"] = channel
    return p.chromium.launch(**kw)


def _chan(s: str) -> str | None:
    return s.strip() or None


def _parse_oid_from_getlesson(url: str) -> int | None:
    q = urlparse(url).query
    if not q:
        return None
    vals = parse_qs(q).get("oid")
    if not vals:
        return None
    try:
        return int(vals[0])
    except ValueError:
        return None


def _parse_oid_from_request(request) -> int | None:
    """getLesson có thể GET (?oid=) hoặc POST (JSON / form)."""
    u = request.url
    o = _parse_oid_from_getlesson(u)
    if o is not None:
        return o
    try:
        raw = request.post_data
    except Exception:
        raw = None
    if not raw:
        return None
    raw = raw.strip()
    try:
        payload = json.loads(raw)
        if isinstance(payload, dict) and "oid" in payload:
            return int(payload["oid"])
    except json.JSONDecodeError:
        pass
    try:
        vals = parse_qs(raw).get("oid")
        if vals:
            return int(vals[0])
    except (ValueError, TypeError):
        pass
    return None


def _explorer_url_matches(resp_url: str, bid: int) -> bool:
    try:
        q = parse_qs(urlparse(resp_url).query)
        b = q.get("bid", [None])[0]
        return b is not None and int(b) == int(bid)
    except (ValueError, TypeError):
        return f"bid={bid}" in resp_url


def _fetch_explorer_api(context, bid: int) -> dict | None:
    r = context.request.get(
        f"https://www.chessable.com/api/v1/courseExplorerData?bid={bid}",
        timeout=120_000,
    )
    if not r.ok:
        return None
    try:
        return r.json()
    except Exception:
        return None


def _planned_lesson_count(max_lessons: int, idx0: int, queue_len: int) -> int:
    """Số bài sẽ thử tải: còn lại trong queue, hoặc giới hạn bởi max_lessons (>0)."""
    remaining = queue_len - idx0
    if max_lessons <= 0:
        return remaining
    return min(max_lessons, remaining)


def _progress_step(n_to_fetch: int, override: int) -> int:
    if override > 0:
        return override
    if n_to_fetch <= 15:
        return 1
    if n_to_fetch <= 80:
        return 5
    return 25


def _write_checkpoint(
    out_dir: Path,
    run_id: str,
    games: list[str],
    meta: list[dict],
    n_to_fetch: int,
    t_start: float,
    last_i: int,
) -> None:
    """Ghi PGN + progress JSON sau mỗi bài OK — hạn chế mất dữ liệu khi gián đoạn."""
    out_pgn = out_dir / "combined_lichess.pgn"
    out_pgn.write_text("\n\n".join(g for g in games if g) + "\n", encoding="utf-8")
    prog = out_dir / f"run_{run_id}_progress.json"
    elapsed = max(time.monotonic() - t_start, 0.001)
    done = len(meta)
    eta = (elapsed / done) * (n_to_fetch - done) if done else None
    prog.write_text(
        json.dumps(
            {
                "run_id": run_id,
                "last_completed_loop_index": last_i,
                "games_written": done,
                "planned_lessons": n_to_fetch,
                "elapsed_sec": round(elapsed, 1),
                "eta_sec_remaining": round(eta, 1) if eta is not None else None,
                "lessons": meta,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def cmd_run(
    auth_path: Path,
    start_url: str,
    out_dir: Path,
    max_lessons: int,
    headed: bool,
    channel: str | None,
    wait_ms: int,
    progress_every: int,
) -> None:
    if not auth_path.is_file():
        print(
            f"Thiếu {auth_path} — chạy: python export_chessable.py login --headed",
            file=sys.stderr,
        )
        sys.exit(1)

    bid, oid0, lid0 = parse_learn_url(start_url)
    out_dir = out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    raw_dir = out_dir / "raw_json"
    raw_dir.mkdir(parents=True, exist_ok=True)

    captured: dict[int, dict] = {}
    explorer_payload: dict | None = None

    def on_response(resp) -> None:
        nonlocal explorer_payload
        try:
            if resp.request.method not in ("GET", "POST"):
                return
            u = resp.url
            if "/api/v1/courseExplorerData" in u and _explorer_url_matches(u, bid):
                explorer_payload = resp.json()
            if "/api/v1/getLesson" in u:
                oid = _parse_oid_from_request(resp.request) or _parse_oid_from_getlesson(u)
                if oid is not None:
                    captured[oid] = resp.json()
        except Exception:
            pass

    games: list[str] = []
    meta: list[dict] = []
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    with sync_playwright() as p:
        browser = _launch(p, headed, channel)
        context = browser.new_context(storage_state=str(auth_path))
        page = context.new_page()
        page.on("response", on_response)

        page.goto(start_url.strip(), wait_until="domcontentloaded", timeout=120_000)
        try:
            page.wait_for_load_state("networkidle", timeout=30_000)
        except Exception:
            pass
        page.wait_for_timeout(wait_ms)

        if explorer_payload is None:
            explorer_payload = _fetch_explorer_api(context, bid)
        if not explorer_payload:
            browser.close()
            print("Không lấy được courseExplorerData (kiểm tra đăng nhập / quyền khóa học).", file=sys.stderr)
            sys.exit(1)

        try:
            queue = build_lesson_queue_from_explorer(explorer_payload)
            idx0 = find_lesson_index(queue, oid0, lid0)
        except ValueError as e:
            browser.close()
            print(str(e), file=sys.stderr)
            sys.exit(1)

        queue_len = len(queue)
        n_to_fetch = _planned_lesson_count(max_lessons, idx0, queue_len)
        stopped_early_end_of_course = max_lessons > 0 and idx0 + max_lessons > queue_len
        if n_to_fetch <= 0:
            browser.close()
            print("Không có bài nào để xuất (đã ở cuối danh sách?).", file=sys.stderr)
            sys.exit(1)
        print(
            f"Plan: {n_to_fetch} lesson(s) from queue index {idx0} (course has {queue_len} lines).",
            flush=True,
        )
        (out_dir / "course_explorer_snapshot.json").write_text(
            json.dumps(explorer_payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"Wrote {out_dir / 'course_explorer_snapshot.json'}", flush=True)

        step = _progress_step(n_to_fetch, progress_every)
        t_run = time.monotonic()

        for i in range(n_to_fetch):
            pos = idx0 + i
            if pos >= len(queue):
                print(f"Dừng: hết danh sách (đã tới cuối queue, {len(queue)} mục).", flush=True)
                break
            item = queue[pos]
            url = f"https://www.chessable.com/learn/{item['bid']}/{item['oid']}/{item['lid']}"

            if i > 0:
                captured.pop(int(item["oid"]), None)
                page.goto(url, wait_until="domcontentloaded", timeout=120_000)
                try:
                    page.wait_for_load_state("networkidle", timeout=30_000)
                except Exception:
                    pass
                page.wait_for_timeout(wait_ms)

            oid_i = int(item["oid"])
            max_wait = max(wait_ms + 12_000, 25_000)

            def _wait_lesson() -> bool:
                waited = 0
                while oid_i not in captured and waited < max_wait:
                    page.wait_for_timeout(250)
                    waited += 250
                return oid_i in captured

            if not _wait_lesson():
                try:
                    page.reload(wait_until="domcontentloaded", timeout=120_000)
                    page.wait_for_timeout(min(4000, wait_ms))
                    _wait_lesson()
                except Exception:
                    pass
            if oid_i not in captured and wait_ms < 20_000:
                try:
                    page.wait_for_timeout(5000)
                    _wait_lesson()
                except Exception:
                    pass

            if oid_i not in captured:
                print(
                    f"Bỏ qua oid={oid_i} ({item.get('name')!r}) — không thấy getLesson.",
                    file=sys.stderr,
                    flush=True,
                )
                continue

            lesson_json = captured[oid_i]
            raw_name = f"{pos + 1:05d}_oid{oid_i}_lid{item['lid']}.json"
            raw_path = raw_dir / raw_name
            raw_path.write_text(
                json.dumps(lesson_json, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )

            pgn_game = lesson_api_json_to_lichess_pgn(lesson_json)
            moves = (lesson_json.get("lesson") or {}).get("moves") or []
            guide_val = build_guide_header_value(moves)
            in_combined = bool(pgn_game.strip())
            if in_combined:
                games.append(pgn_game.strip())
            else:
                print(
                    f"Bỏ qua oid={oid_i} ({item.get('name')!r}) — không có movetext (chỉ nội dung text).",
                    file=sys.stderr,
                    flush=True,
                )
            meta.append(
                {
                    "i": i,
                    "queue_pos": pos,
                    "url": url,
                    "bid": item["bid"],
                    "oid": item["oid"],
                    "lid": item["lid"],
                    "chapter": item["chapter"],
                    "name": item["name"],
                    "move_count": len(moves),
                    "has_guide": bool(guide_val),
                    "in_combined_pgn": in_combined,
                    "raw_json": raw_path.relative_to(out_dir).as_posix(),
                }
            )

            _write_checkpoint(out_dir, run_id, games, meta, n_to_fetch, t_run, i)

            if (i + 1) % step == 0 or (i + 1) == n_to_fetch:
                el = time.monotonic() - t_run
                done = len(meta)
                eta_s = (el / done) * (n_to_fetch - done) if done else 0.0
                print(
                    f"  [{i + 1}/{n_to_fetch}] ok | {el:.0f}s elapsed | ETA ~{eta_s:.0f}s | {item.get('name', '')[:56]!r}",
                    flush=True,
                )

        browser.close()

    combined = "\n\n".join(g for g in games if g) + "\n"
    out_pgn = out_dir / "combined_lichess.pgn"
    out_pgn.write_text(combined, encoding="utf-8")

    summary_path = out_dir / f"run_{run_id}.json"
    summary = {
        "run_id": run_id,
        "start_url": start_url.strip(),
        "bid": bid,
        "queue_total_lessons": queue_len,
        "start_queue_index": idx0,
        "planned_lessons": n_to_fetch,
        "max_lessons_cap": max_lessons if max_lessons > 0 else None,
        "games_written": len(meta),
        "stopped_early_end_of_course": stopped_early_end_of_course,
        "out_pgn": out_pgn.name,
        "lessons": meta,
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {out_pgn} ({len(meta)} games), raw_json: {len(meta)} files", flush=True)
    print(f"Wrote {summary_path}", flush=True)

    if len(meta) < n_to_fetch:
        print(
            f"Cảnh báo: chỉ ghi được {len(meta)}/{n_to_fetch} bài (getLesson lỗi hoặc thiếu).",
            file=sys.stderr,
        )


def cmd_selfcheck() -> None:
    """Kiểm tra parser + queue offline (không cần auth), cần sẵn file trong out_capture/."""
    root = Path(__file__).resolve().parent
    gl = root / "out_capture" / "00003_www.chessable.com_api_v1_getLesson_b0da5e29fe.json"
    ex = root / "out_capture" / "00006_www.chessable.com_api_v1_courseExplorerData_68ea3d53de.json"
    if not gl.is_file():
        print(f"Selfcheck thiếu {gl}", file=sys.stderr)
        sys.exit(1)
    if not ex.is_file():
        print(f"Selfcheck thiếu {ex}", file=sys.stderr)
        sys.exit(1)
    data = json.loads(gl.read_text(encoding="utf-8"))
    pgn = lesson_api_json_to_lichess_pgn(data)
    if "[FEN " not in pgn or "[Guide " not in pgn:
        print("Selfcheck: PGN thiếu FEN hoặc Guide", file=sys.stderr)
        sys.exit(1)
    explorer = json.loads(ex.read_text(encoding="utf-8"))
    q = build_lesson_queue_from_explorer(explorer)
    ix = find_lesson_index(q, 2657521, 15)
    if ix < 0 or q[ix]["name"] != "Position I.1":
        print("Selfcheck: queue / index sai", file=sys.stderr)
        sys.exit(1)
    if q[ix + 1]["oid"] != 2657656:
        print("Selfcheck: bài kế oid không khớp mong đợi", file=sys.stderr)
        sys.exit(1)
    print("Selfcheck OK: PGN + courseExplorer queue + next-lesson order.")


def main() -> None:
    ap = argparse.ArgumentParser(description="Export nhiều bài Learn Chessable → một file PGN (sidebar order).")
    ap.add_argument(
        "--selfcheck",
        action="store_true",
        help="Chạy kiểm tra offline (fixture trong out_capture/), không mở trình duyệt",
    )
    ap.add_argument("--url", help="URL bài đầu tiên .../learn/bid/oid/lid")
    ap.add_argument("--auth", type=Path, default=DEFAULT_AUTH)
    ap.add_argument(
        "--out-dir",
        type=Path,
        default=Path(__file__).resolve().parent / "out_series",
        help="Thư mục ghi combined_lichess.pgn và raw_json/",
    )
    ap.add_argument(
        "--max-lessons",
        type=int,
        default=0,
        metavar="N",
        help="Toi da N bai; mac dinh 0 = lay het tu URL bat dau den het khoa",
    )
    ap.add_argument("--headed", action="store_true")
    ap.add_argument(
        "--channel",
        default="",
        metavar="NAME",
        help="chrome | msedge | … — giống export_chessable.py",
    )
    ap.add_argument("--wait-ms", type=int, default=8000, help="Chờ sau load (XHR getLesson)")
    ap.add_argument(
        "--progress-every",
        type=int,
        default=0,
        metavar="K",
        help="In log mỗi K bài (0 = tự chọn theo độ dài lô)",
    )
    args = ap.parse_args()

    if args.selfcheck:
        cmd_selfcheck()
        return
    if not args.url:
        ap.error("--url là bắt buộc (trừ khi dùng --selfcheck)")
    cmd_run(
        auth_path=args.auth.resolve(),
        start_url=args.url,
        out_dir=args.out_dir,
        max_lessons=args.max_lessons,
        headed=args.headed,
        channel=_chan(args.channel),
        wait_ms=args.wait_ms,
        progress_every=args.progress_every,
    )


if __name__ == "__main__":
    main()
