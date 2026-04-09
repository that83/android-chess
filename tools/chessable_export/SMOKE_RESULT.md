# Kết quả chạy thử (môi trường CI / không đăng nhập)

**URL:** `https://www.chessable.com/learn/5193/2657521/15`

**Lệnh:** `export_chessable.py capture --anonymous ... --api-only`

**Kết luận:** Với **session ẩn danh**, chỉ thấy API kiểu `course/search`, `cms-routes` — **không** có API lesson/chapter. Trong `RUN_SUMMARY_*.json`, `likely_got_lesson_payload` phải là **`true`** sau khi dùng `login --headed` + `auth.json` (trên máy bạn); lần chạy thử tự động tại đây cho **`false`**.

**Đối chiếu “đã được”:** Khi bạn đã mua khóa và đăng nhập:

1. `python export_chessable.py login --headed` → tạo `auth.json` (không commit).
2. `python export_chessable.py capture --url "…"` (bỏ `--anonymous`) → trong `RUN_SUMMARY_*.json` nên thấy `likely_got_lesson_payload: true` và/hoặc các URL API chứa `lesson`, `chapter`, v.v.

Thư mục `out_smoke/` được `.gitignore` (file lớn, tùy máy).
