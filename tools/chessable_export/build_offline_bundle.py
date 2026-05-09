#!/usr/bin/env python3
"""
Copy course_explorer_snapshot.json + raw_json/*.json into Chessable_offline/data/
and build lessons_by_oid.json (oid → relative path) for Chessable_offline.html.

Usage (from tools/chessable_export):
  python build_offline_bundle.py --export-dir out_series_full

Then serve the folder (fetch JSON không chạy với file:// trên nhiều trình duyệt):
  cd Chessable_offline && python -m http.server 8765
  http://localhost:8765/Chessable_offline.html
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path


OID_IN_NAME = re.compile(r"_oid(\d+)_", re.I)


def _oid_from_lesson_json(data: dict, path: Path) -> int | None:
    moves = (data.get("lesson") or {}).get("moves") or []
    if moves and isinstance(moves[0], dict):
        oid = moves[0].get("oid")
        if oid is not None:
            return int(oid)
    m = OID_IN_NAME.search(path.name)
    if m:
        return int(m.group(1))
    return None


def main() -> None:
    ap = argparse.ArgumentParser(description="Đổ dữ liệu export vào Chessable_offline/data/")
    ap.add_argument(
        "--export-dir",
        type=Path,
        required=True,
        help="Thư mục chứa course_explorer_snapshot.json và raw_json/",
    )
    ap.add_argument(
        "--out-data",
        type=Path,
        default=None,
        help="Đích (mặc định: Chessable_offline/data bên cạnh script)",
    )
    args = ap.parse_args()
    script_dir = Path(__file__).resolve().parent
    exp = args.export_dir.resolve()
    out_data = (args.out_data or (script_dir / "Chessable_offline" / "data")).resolve()

    snap = exp / "course_explorer_snapshot.json"
    if not snap.is_file():
        raise SystemExit(f"Không thấy {snap}")

    out_data.mkdir(parents=True, exist_ok=True)
    shutil.copy2(snap, out_data / "course_explorer_snapshot.json")

    raw_src = exp / "raw_json"
    raw_dst = out_data / "raw_json"
    if raw_dst.exists():
        shutil.rmtree(raw_dst)
    if raw_src.is_dir():
        shutil.copytree(raw_src, raw_dst)
    else:
        raw_dst.mkdir(parents=True, exist_ok=True)
        print("Cảnh báo: không có raw_json/ — sidebar vẫn load nhưng bài học không mở được.")

    by_oid: dict[str, str] = {}
    dup = 0
    for p in sorted(raw_dst.glob("*.json")):
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
            oid = _oid_from_lesson_json(data, p)
            if oid is None:
                continue
            key = str(oid)
            rel = f"raw_json/{p.name}"
            if key in by_oid:
                dup += 1
            by_oid[key] = rel
        except Exception as e:
            print(f"Bỏ qua {p.name}: {e}")

    (out_data / "lessons_by_oid.json").write_text(
        json.dumps(by_oid, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"Đã ghi {out_data}")
    print(f"  lessons_by_oid.json: {len(by_oid)} oid (trùng lặp ghi đè: {dup})")


if __name__ == "__main__":
    main()
