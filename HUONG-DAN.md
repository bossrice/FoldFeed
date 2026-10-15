# FoldFeed – xem Facebook trên Galaxy Z Fold

App Android bọc trang Facebook, bố cục thay đổi theo màn hình:

- **Gập máy (màn hình ngoài):** 1 khung bảng tin như bình thường.
- **Mở máy (màn hình trong):** 2 khung. Bên trái là bảng tin. Bên phải là khung phụ, có thanh nút: Thông báo · Tin nhắn · Chợ · Nhóm · Video · Lùi · Tải lại · Bản PC/ĐT · A− / A+.
- Gập hoặc mở máy **không tải lại trang**, đang đọc dở bài nào vẫn còn nguyên.
- **Kéo thanh xám ở giữa** để chỉnh độ rộng hai khung. Nhấn đúp thanh này để về mặc định. App nhớ độ rộng anh chọn.
- **Nhấn giữ một link** ở khung trái để chọn: mở ở khung phải, mở bằng trình duyệt, hoặc sao chép link.
- **A− / A+** chỉnh cỡ chữ cho cả hai khung.
- **Bản PC:** khung phải chuyển sang giao diện máy tính của Facebook. Nên dùng khi xem Tin nhắn nếu bản điện thoại bắt cài Messenger.
- Đăng ảnh được (chọn nhiều ảnh), xem video toàn màn hình được.
- Link ngoài Facebook sẽ mở bằng trình duyệt hoặc app tương ứng (Zalo, gọi điện…).
- Đăng nhập một lần, dùng chung cho cả hai khung.

---

## Cách 1: Build APK miễn phí bằng GitHub (không cần cài gì)

1. Tạo tài khoản ở github.com nếu anh chưa có.
2. Bấm **New repository**, đặt tên `FoldFeed`, chọn **Private**, rồi bấm Create.
3. Bấm **uploading an existing file**. Giải nén file zip, rồi kéo **toàn bộ nội dung bên trong thư mục FoldFeed** vào (gồm cả thư mục `.github`). Bấm **Commit changes**.
   - Nếu trình duyệt không cho kéo thư mục `.github` (thư mục ẩn), làm thế này: vào **Add file → Create new file**, gõ tên `.github/workflows/build.yml`, rồi dán nội dung file đó vào.
4. Vào tab **Actions** và đợi khoảng 3–5 phút cho đến khi hiện dấu ✅ xanh.
5. Bấm vào lần chạy đó. Kéo xuống mục **Artifacts**, tải **FoldFeed-apk** (file zip có APK bên trong).
6. Chép APK sang Z Fold rồi mở để cài. Máy sẽ hỏi cho phép cài từ nguồn này, anh chọn **Cho phép**.

## Cách 2: Android Studio trên máy tính

1. Cài Android Studio (miễn phí) từ developer.android.com/studio.
2. Chọn **File → Open** và chọn thư mục `FoldFeed`. Đợi Studio tải thư viện xong. Nếu Studio hỏi về Gradle, chọn dùng bản Gradle được đề xuất.
3. Cắm điện thoại vào máy tính (cần bật *Gỡ lỗi USB* trong Tùy chọn nhà phát triển), rồi bấm nút ▶ **Run**.

---

## Mở link Facebook từ Zalo bằng app này

Vào **Cài đặt → Ứng dụng → FoldFeed → Đặt làm mặc định → Mở liên kết được hỗ trợ**, bật lên và tích chọn các địa chỉ facebook.com.

## Lưu ý

- App hiển thị trang web Facebook, không phải app chính thức. Khi Facebook đổi giao diện web, có thể có chỗ hiển thị khác đi. Phần bố cục 2 khung của app thì không bị ảnh hưởng.
- Không có thông báo đẩy khi app đóng. Đây là giới hạn của bản web.
- Mã nguồn chính nằm ở `app/src/main/java/vn/phucuong/foldfeed/MainActivity.java`. Ngưỡng chuyển sang 2 khung là `WIDE_DP = 600`.
