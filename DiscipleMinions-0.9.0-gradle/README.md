# DiscipleMinions 0.9.0 (Gradle)

Bản 0.9.0 thêm Thẻ Offline (Đệ Tử làm việc khi chủ nhân offline, xem `CHANGELOG-0.9.0.md`). Bản 0.8.1 sửa độ bền công cụ, bảo vệ WorldGuard/claim, lag và tốc độ (xem `CHANGELOG-0.8.1.md`). Bản 0.8.0 thêm hỗ trợ Bedrock/Geyser. Bản 0.7 cập nhật GUI và hệ thống hiển thị Đệ Tử theo các yêu cầu mới.

## Thay đổi chính
- Chuyển quản lý item được phép farm/đào ra hàng cuối của `/detu admin`.
- `/detu admin` có 2 nút riêng: Quản lý Khoáng Sản và Quản lý Nông Sản.
- Bỏ nút Thông Tin khỏi Settings của từng Đệ Tử.
- Bỏ `/detu trade <player>`; trade theo từng Đệ Tử từ nút `TRADE ĐỆ TỬ` trong Settings.
- Trade: nhập tên người nhận (GUI Anvil nếu server hỗ trợ, nếu không thì gõ tên vào chat, gõ `huy` để hủy), người nhận nhận GUI rương 27 ô với nút đỏ/từ chối và xanh/xác nhận. Trong lúc chờ xác nhận, Đệ Tử bị khóa (không đổi công cụ/nâng cấp/thu hồi/đặt).
- Khi trade sẽ reset liên kết rương/ExtraStorage của chủ cũ.
- Nếu Đệ Tử đang ở dạng item, plugin chỉ trade khi Nether Star đúng ID còn trong inventory người gửi; khi xác nhận item được chuyển sang người nhận để tránh dupe.
- Skin Đệ Tử đổi từ Villager sang ArmorStand nhỏ có tay.
- Đệ Tử Khoáng Sản mặc giáp da màu đen.
- Đệ Tử Nông Sản mặc giáp da màu vàng.
- ArmorStand cầm đúng item đang có trong kho công cụ của Đệ Tử; thay/lấy công cụ sẽ cập nhật skin ngay.
- Các nút chức năng Settings được xếp liền nhau, không chừa slot trống ở giữa.

## Build
```bash
gradle clean build
```

JAR sau khi build:
`build/libs/DiscipleMinions-0.9.0.jar`

Baseline hiện tại: Spigot/Paper API 1.16.5, Java target 8.
