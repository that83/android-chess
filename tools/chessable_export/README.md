# Chessable export helper (local browser capture)

Tool chạy **trên máy bạn**, dùng trình duyệt Chromium qua [Playwright](https://playwright.dev/python/), **sau khi bạn đăng nhập hợp lệ** vào Chessable. Nó lưu lại các phản hồi HTTP (thường là JSON) mà trang bài học tải về — bạn có thể dùng để tra cứu PGN/text trong các file đó hoặc chạy `merge` để gom đoạn giống PGN (heuristic).

**Điều kiện:** Chỉ dùng cho nội dung bạn **được phép truy cập** (ví dụ đã mua khóa học và được nền tảng đồng ý). Tool không mở khóa tài khoản hay nội dung chưa mua.

**Không ảnh hưởng** tới ứng dụng Android trong repo.

## Cài đặt (Windows / macOS / Linux)

```bash
cd tools/chessable_export
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
playwright install chromium
```

Bạn có thể copy [`urls.example.txt`](urls.example.txt) thành `urls.txt` rồi chỉnh URL.

## Trình duyệt nào được mở?

- **Mặc định:** Playwright dùng **Chromium riêng** (bộ cài `playwright install chromium`), **không phải** cửa sổ Chrome/Edge bạn mở hằng ngày, và **không** dùng profile có sẵn (bookmark, cookie đăng nhập sẵn). Bạn cần **đăng nhập Chessable một lần** trong cửa sổ đó; sau đó tool lưu cookie vào `auth.json`.
- **Nếu trình “Chromium bundled” bị chặn hoặc Chessable chỉ cho đăng nhập trên Chrome/Edge:** dùng trình đã cài trên máy:
  - `python export_chessable.py login --headed --channel chrome`
  - hoặc `python export_chessable.py login --headed --channel msedge`  
  Vẫn là **profile tách biệt** (Playwright), nhưng engine giống Chrome/Edge bạn quen — thường tương thích trang web tốt hơn.

Khi chạy `capture`, nên dùng **cùng** `--channel` như lúc `login` (nếu có).

## Bước 1 — Lưu phiên đăng nhập

```bash
python export_chessable.py login --headed
# hoặc: python export_chessable.py login --headed --channel chrome
```

Cửa sổ trình duyệt mở trang đăng nhập Chessable.

- **Cách A:** Đăng nhập xong, quay lại terminal và **Enter** → lưu `auth.json`.
- **Cách B** (khi terminal không nhận Enter / Cursor chạy nền): đăng nhập trong trình duyệt, rồi chờ — ví dụ  
  `python export_chessable.py login --headed --channel chrome --idle-save-seconds 90`  
  → sau **90 giây** script tự lưu `auth.json` (tăng số giây nếu cần).

File `auth.json` nằm cạnh `export_chessable.py` (đã `.gitignore` — **đừng commit**).

## Bước 2 — Chụp traffic theo danh sách URL bài học

Tạo file `urls.txt` (một URL dòng), ví dụ:

```
https://www.chessable.com/learn/5193/2657521/15
```

Chạy:

```bash
python export_chessable.py capture --urls-file urls.txt --out out
```

Tuỳ chọn:

- `--headed` — hiện trình duyệt khi capture.
- `--wait-ms 8000` — chờ thêm sau khi trang tải (XHR chậm).
- `--url-filter api` — chỉ lưu response có chuỗi con trong URL.
- `--api-only` — **khuyến nghị**: chỉ lưu `path` chứa `/api/` (JSON bài học thường ở đây, ít rác).
- `--all-resources` — lưu mọi response (css/js/ảnh…); mặc định là bỏ qua file tĩnh `.css`, `.png`, …
- `--anonymous` — không dùng `auth.json` (chỉ để thử; **bài đã mua thường cần login** — xem [SMOKE_RESULT.md](SMOKE_RESULT.md)).
- `--save-page-html` — lưu HTML sau mỗi URL (debug).

Sau mỗi lần capture, mở `RUN_SUMMARY_*.json`: `likely_got_lesson_payload` nên là `true` khi đã có API dạng lesson/chapter (thường **sau khi login**).

Kết quả: thư mục `out/` chứa file `00001_....json`, … và `manifest_….json` (mapping file ↔ URL response).

## Bước 3 (tuỳ chọn) — Gom đoạn giống PGN

```bash
python export_chessable.py merge --out out
```

Tạo `out/merged_snippets.md` — **không đảm bảo** đủ mọi biến thể; dùng để lướt nhanh. Cấu trúc JSON Chessable có thể đổi; khi đó cần mở từng file JSON trong `out/` hoặc chỉnh `merge` cho phù hợp.

## Ghi chú kỹ thuật

- DOM/board state có thể nằm trong JSON khó đoán tên field; `merge` chỉ là heuristic.
- Nếu capture quá ít file, tăng `--wait-ms` hoặc bỏ `--url-filter`.
- Session hết hạn: chạy lại `login --headed`.
