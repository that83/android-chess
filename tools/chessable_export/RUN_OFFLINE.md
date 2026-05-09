# Run Chessable Offline (Quick Guide)

## 1) Chạy server local đúng URL

Từ thư mục repo:

```powershell
Set-Location "D:\github\android-chess\android-chess\tools\chessable_export"
npx http-server . -p 8891
```

Mở trang:

`http://localhost:8891/Chessable_offline/Chessable_offline.html`

Ghi chú:
- Máy hiện tại không có `python`/`py`, nên dùng `npx http-server` là ổn định nhất.
- Nếu cổng 8891 bận, đổi cổng khác, ví dụ `-p 8765`.

---

## 2) Lấy data một khóa học từ Chessable

### Bước A: chuẩn bị môi trường Python + Playwright (lần đầu)

```powershell
Set-Location "D:\github\android-chess\android-chess\tools\chessable_export"
pip install -r requirements.txt
playwright install chromium
```

### Bước B: login để tạo session

```powershell
python export_chessable.py login --headed
```

Lệnh này tạo file `auth.json` trong `tools/chessable_export`.

### Bước C: export chuỗi bài từ một URL Learn

```powershell
python export_learn_series.py --url "https://www.chessable.com/learn/<bid>/<oid>/<lid>"
```

Ví dụ:

```powershell
python export_learn_series.py --url "https://www.chessable.com/learn/5193/2657521/15"
```

Output mặc định:
- `out_series_full/raw_json/*.json`
- `out_series_full/course_explorer_snapshot.json`
- `out_series_full/combined_lichess.pgn`

### Bước D: build bundle cho viewer offline

```powershell
python build_offline_bundle.py --export-dir out_series_full
```

Lệnh này copy dữ liệu vào:
- `Chessable_offline/data/course_explorer_snapshot.json`
- `Chessable_offline/data/raw_json/*.json`
- `Chessable_offline/data/lessons_by_oid.json`

### Bước E: mở lại viewer

```powershell
npx http-server . -p 8891
```

Rồi truy cập:

`http://localhost:8891/Chessable_offline/Chessable_offline.html`

---

## 3) Template URL nhanh

File mẫu: `urls.example.txt`

Nội dung mẫu:

`https://www.chessable.com/learn/5193/2817294/15`

Trong đó:
- `bid`: book id
- `oid`: variation/lesson id
- `lid`: chapter id

