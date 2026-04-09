#!/usr/bin/env python3
"""
Capture Chessable HTTP responses while visiting lesson URLs (authenticated Playwright).
Only saves what the browser loads after login — does not bypass paywalls.

  pip install -r requirements.txt
  playwright install chromium

  python export_chessable.py login --headed
  python export_chessable.py login --headed --channel chrome
  python export_chessable.py capture --urls-file urls.txt --out out
  python export_chessable.py capture --anonymous --url "https://..."   # probe without auth.json
  python export_chessable.py merge --out out
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright

DEFAULT_AUTH = Path(__file__).resolve().parent / "auth.json"
DEFAULT_OUT = Path(__file__).resolve().parent / "out"


def _launch_chromium(p, headed: bool, channel: str | None):
    """
    Default: Playwright's bundled Chromium (separate from Chrome/Edge you use daily — no shared profile).
    With channel=chrome | msedge | chrome-beta | msedge-beta: uses that browser if installed (still a fresh profile unless you reuse auth.json).
    """
    kw = {"headless": not headed}
    if channel:
        kw["channel"] = channel
    return p.chromium.launch(**kw)


def _slug_from_url(url: str, max_len: int = 80) -> str:
    p = urlparse(url)
    path = (p.netloc + p.path).replace("/", "_")
    path = re.sub(r"[^a-zA-Z0-9._-]+", "_", path).strip("_")
    if len(path) > max_len:
        path = path[:max_len]
    h = hashlib.sha256(url.encode("utf-8", errors="replace")).hexdigest()[:10]
    return f"{path}_{h}" if path else h


def _host_allowed(url: str) -> bool:
    try:
        host = (urlparse(url).hostname or "").lower()
    except Exception:
        return False
    return "chessable" in host


_STATIC_EXT = (
    ".css",
    ".js",
    ".map",
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".gif",
    ".svg",
    ".ico",
    ".woff",
    ".woff2",
    ".ttf",
    ".eot",
    ".mp3",
    ".mp4",
    ".webm",
    ".wasm",
)


def _url_looks_static_asset(url: str) -> bool:
    path = (urlparse(url).path or "").lower()
    return any(path.endswith(ext) for ext in _STATIC_EXT)


def _should_capture_response(req_url: str, api_only: bool, all_resources: bool) -> bool:
    if all_resources:
        return True
    if api_only:
        return "/api/" in (urlparse(req_url).path or "")
    return not _url_looks_static_asset(req_url)


def cmd_login(
    auth_path: Path,
    headed: bool,
    channel: str | None,
    idle_save_seconds: int | None,
) -> None:
    login_url = "https://www.chessable.com/login"
    with sync_playwright() as p:
        browser = _launch_chromium(p, headed, channel)
        context = browser.new_context()
        page = context.new_page()
        page.goto(login_url, wait_until="domcontentloaded", timeout=120_000)
        print(f"Browser opened: {login_url}")
        if channel:
            print(f"(Using installed channel: {channel}; still an isolated profile — log in here once.)")
        else:
            print("(Using Playwright bundled Chromium — not your everyday Chrome/Edge profile.)")
        if idle_save_seconds is not None and idle_save_seconds > 0:
            print(
                f"Log in in the browser; waiting {idle_save_seconds}s then saving session (no Enter needed)."
            )
            page.wait_for_timeout(idle_save_seconds * 1000)
        else:
            print("Log in, then press Enter here in this terminal.")
            input()
        auth_path.parent.mkdir(parents=True, exist_ok=True)
        context.storage_state(path=str(auth_path))
        browser.close()
    print(f"Saved session to {auth_path}")


def cmd_capture(
    auth_path: Path | None,
    out_dir: Path,
    urls: list[str],
    headed: bool,
    wait_ms: int,
    url_substring: str,
    max_body_bytes: int,
    save_page_html: bool,
    api_only: bool,
    all_resources: bool,
    channel: str | None,
) -> None:
    if auth_path is not None and not auth_path.is_file():
        print(
            f"Missing auth file: {auth_path}\n"
            f"Run: python export_chessable.py login --headed\n"
            f"Or use: --anonymous (limited; paid lessons usually need login)",
            file=sys.stderr,
        )
        sys.exit(1)
    if not urls:
        print("No URLs to visit.", file=sys.stderr)
        sys.exit(1)

    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = []
    counter = 0
    seen_hashes: set[str] = set()
    lesson_tag: list[str] = [""]

    def maybe_save_response(response) -> None:
        nonlocal counter
        try:
            req_url = response.url
            if not _host_allowed(req_url):
                return
            if not _should_capture_response(req_url, api_only, all_resources):
                return
            if url_substring and url_substring not in req_url:
                return
            method = response.request.method
            if method not in ("GET", "POST"):
                return
            body = response.body()
            if not body or len(body) > max_body_bytes:
                return
            digest = hashlib.sha256(body).hexdigest()
            key = f"{method} {req_url} {digest}"
            if key in seen_hashes:
                return
            seen_hashes.add(key)

            ct = (response.headers.get("content-type") or "").split(";")[0].strip().lower()
            ext = ".bin"
            if "json" in ct or body.lstrip().startswith(b"{") or body.lstrip().startswith(b"["):
                ext = ".json"
                try:
                    parsed = json.loads(body.decode("utf-8", errors="replace"))
                    body = json.dumps(parsed, ensure_ascii=False, indent=2).encode("utf-8")
                except Exception:
                    ext = ".txt"
            elif "text/" in ct or ct == "application/javascript":
                ext = ".txt"

            counter += 1
            fname = out_dir / f"{counter:05d}_{_slug_from_url(req_url)}{ext}"
            fname.write_bytes(body)
            manifest.append(
                {
                    "file": str(fname.name),
                    "lesson_page": lesson_tag[0],
                    "response_url": req_url,
                    "method": method,
                    "status": response.status,
                    "content_type": ct,
                }
            )
            print(f"saved {fname.name} <- {response.status} {req_url[:96]}")
        except Exception as e:
            print(f"(skip response) {e}", file=sys.stderr)

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    with sync_playwright() as p:
        browser = _launch_chromium(p, headed, channel)
        if auth_path is not None:
            context = browser.new_context(storage_state=str(auth_path))
        else:
            context = browser.new_context()
        page = context.new_page()
        page.on("response", maybe_save_response)

        for lesson_url in urls:
            lesson_url = lesson_url.strip()
            if not lesson_url or lesson_url.startswith("#"):
                continue
            lesson_tag[0] = lesson_url
            print(f"\n=== Visit: {lesson_url}")
            try:
                page.goto(lesson_url, wait_until="domcontentloaded", timeout=120_000)
                try:
                    page.wait_for_load_state("networkidle", timeout=25_000)
                except Exception:
                    pass
                page.wait_for_timeout(wait_ms)
                if save_page_html:
                    safe = re.sub(r"[^\w.-]+", "_", lesson_url)[:120]
                    html_path = out_dir / f"_page_{run_id}_{safe}.html"
                    html_path.write_text(page.content(), encoding="utf-8", errors="replace")
                    print(f"saved HTML snapshot: {html_path.name}")
            except Exception as e:
                print(f"goto error: {e}", file=sys.stderr)

        browser.close()

    manifest_path = out_dir / f"manifest_{run_id}.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"\nWrote manifest: {manifest_path} ({len(manifest)} files)")

    _write_run_summary(out_dir, run_id, manifest, urls)


def _write_run_summary(
    out_dir: Path,
    run_id: str,
    manifest: list,
    urls: list[str],
) -> None:
    """Light-weight verification file for humans / CI."""
    pgnish = re.compile(r"\b(?:1\.|pgn|fen|\[Event)", re.I)
    json_files = [m for m in manifest if str(m.get("file", "")).endswith(".json")]
    hints = []
    for m in json_files[:40]:
        fp = out_dir / m["file"]
        try:
            text = fp.read_text(encoding="utf-8", errors="replace")[:200_000]
            if pgnish.search(text):
                hints.append(m["file"])
        except Exception:
            pass

    def _is_lesson_like_api_url(u: str) -> bool:
        ul = (u or "").lower()
        if not ul or "course/search" in ul or "cms-routes" in ul:
            return False
        markers = (
            "/learn/",
            "lesson",
            "chapter",
            "movetrainer",
            "move-list",
            "lines",
            "practice",
            "variation",
            "pgn",
        )
        return any(m in ul for m in markers)

    lessonish_urls = [m["response_url"] for m in manifest if _is_lesson_like_api_url(m.get("response_url") or "")]
    summary = {
        "run_id": run_id,
        "urls": [u for u in urls if u.strip() and not u.strip().startswith("#")],
        "responses_saved": len(manifest),
        "json_files_with_pgnish_hint": hints,
        "lesson_like_api_urls_sample": lessonish_urls[:25],
        "likely_got_lesson_payload": len(lessonish_urls) > 0,
        "note": "Anonymous sessions often get the marketing shell only; use login + auth.json, then capture again.",
    }
    sp = out_dir / f"RUN_SUMMARY_{run_id}.json"
    sp.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote summary: {sp.name} (json hints: {len(hints)})")
    print(f"likely_got_lesson_payload={summary['likely_got_lesson_payload']} (need True after real login)")


def cmd_merge(out_dir: Path) -> None:
    pgn_like = re.compile(
        r"\b(?:1\.|1-0|0-1|1/2-1/2|[KQRBN][a-h]?[1-8]?x?[a-h][1-8])",
        re.I,
    )
    lines_out: list[str] = ["# Merged snippets (heuristic)\n"]

    for path in sorted(out_dir.glob("*.json")):
        if path.name.startswith("manifest_") or path.name.startswith("RUN_SUMMARY_"):
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8", errors="replace"))
        except Exception:
            continue

        def walk(obj, prefix: str = ""):
            if isinstance(obj, dict):
                for k, v in obj.items():
                    walk(v, f"{prefix}.{k}" if prefix else k)
            elif isinstance(obj, list):
                for i, v in enumerate(obj[:200]):
                    walk(v, f"{prefix}[{i}]")
            elif isinstance(obj, str) and len(obj) > 20 and pgn_like.search(obj):
                lines_out.append(
                    f"\n## {path.name} `{prefix}`\n\n```\n{obj[:8000]}\n```\n"
                )

        walk(data)

    merged = out_dir / "merged_snippets.md"
    merged.write_text("\n".join(lines_out), encoding="utf-8")
    print(f"Wrote {merged}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Chessable lesson capture (Playwright)")
    sub = parser.add_subparsers(dest="command", required=True)

    p_login = sub.add_parser("login", help="Save storage state after manual login")
    p_login.add_argument("--auth", type=Path, default=DEFAULT_AUTH)
    p_login.add_argument("--headed", action="store_true")
    p_login.add_argument(
        "--channel",
        default="",
        metavar="NAME",
        help="chrome | chrome-beta | msedge | msedge-beta — installed browser; omit = bundled Chromium",
    )
    p_login.add_argument(
        "--idle-save-seconds",
        type=int,
        default=0,
        metavar="N",
        help="After login, wait N seconds then save auth.json automatically (use if terminal Enter is awkward)",
    )

    p_cap = sub.add_parser("capture", help="Visit URLs; save JSON/text bodies")
    p_cap.add_argument("--auth", type=Path, default=DEFAULT_AUTH)
    p_cap.add_argument("--anonymous", action="store_true", help="Do not load auth.json (probe / public only)")
    p_cap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    p_cap.add_argument("--urls-file", type=Path, help="One URL per line")
    p_cap.add_argument("--url", action="append", default=[], help="Lesson URL (repeatable)")
    p_cap.add_argument("--headed", action="store_true")
    p_cap.add_argument("--wait-ms", type=int, default=8000)
    p_cap.add_argument(
        "--url-filter",
        default="",
        help="Only save responses whose URL contains this substring (empty = all allowed hosts)",
    )
    p_cap.add_argument("--max-body-mb", type=float, default=3.0)
    p_cap.add_argument(
        "--save-page-html",
        action="store_true",
        help="Save page DOM HTML after each lesson URL (for debugging)",
    )
    p_cap.add_argument(
        "--api-only",
        action="store_true",
        help="Only save responses whose path contains /api/ (smallest, best for lesson JSON)",
    )
    p_cap.add_argument(
        "--all-resources",
        action="store_true",
        help="Save every Chessable host response (large: css/js/images—default skips static assets)",
    )
    p_cap.add_argument(
        "--channel",
        default="",
        metavar="NAME",
        help="Same as login: chrome | msedge | … (see Playwright docs)",
    )

    p_merge = sub.add_parser("merge", help="Heuristic PGN-like merge to merged_snippets.md")
    p_merge.add_argument("--out", type=Path, default=DEFAULT_OUT)

    args = parser.parse_args()

    def _chan(s: str) -> str | None:
        return s.strip() or None

    if args.command == "login":
        idle = args.idle_save_seconds if getattr(args, "idle_save_seconds", 0) else None
        if idle is not None and idle <= 0:
            idle = None
        cmd_login(
            args.auth,
            headed=args.headed,
            channel=_chan(args.channel),
            idle_save_seconds=idle,
        )
    elif args.command == "capture":
        urls = list(args.url)
        if args.urls_file:
            urls.extend(
                args.urls_file.read_text(encoding="utf-8", errors="replace").splitlines()
            )
        auth = None if args.anonymous else args.auth
        if args.api_only and args.all_resources:
            parser.error("Use only one of --api-only and --all-resources")
        cmd_capture(
            auth_path=auth,
            out_dir=args.out,
            urls=urls,
            headed=args.headed,
            wait_ms=args.wait_ms,
            url_substring=args.url_filter,
            max_body_bytes=int(args.max_body_mb * 1024 * 1024),
            save_page_html=args.save_page_html,
            api_only=args.api_only,
            all_resources=args.all_resources,
            channel=_chan(args.channel),
        )
    elif args.command == "merge":
        cmd_merge(args.out)
    else:
        parser.error("unknown command")


if __name__ == "__main__":
    main()
