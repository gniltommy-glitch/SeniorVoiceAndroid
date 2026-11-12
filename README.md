# SeniorVoice Android — 1.1.0 API

Trợ lý giọng nói tiếng Việt cho người cao tuổi. Bản này dùng API để lập kế hoạch; không tải model, không chạy LLM trên điện thoại hoặc máy tính trong mạng LAN.

## Cài đặt và cập nhật

Cài `SeniorVoice-Android.apk` ở gốc repo. Mỗi lần cập nhật cần thay APK và cập nhật mã nguồn trên GitHub. APK không chứa API key dùng chung.

Mở **Cập Nhật Sổ Tay → Cài đặt AI**, chọn gọi API trực tiếp, nhập ít nhất một key, bật quyền gửi dữ liệu tới API rồi lưu. Key trên điện thoại được mã hóa bằng Android Keystore và không được backup. Key đã có ở máy phát triển vẫn được giữ riêng trong `server/.env`, không đưa lên GitHub hoặc nhúng vào APK public.

| Thứ tự | API | Model |
|---|---|---|
| 1 | Qwen / OpenGateway | `qwen/qwen3.8-flash` |
| 2 | OpenRouter | `nvidia/nemotron-3.5-super-vl:free` |
| 3 | Groq | `openai/gpt-oss-20b` |
| 4 | Gemini, khi bật dự phòng có tính phí | `gemini-3.5-flash-lite` |
| 5 | DeepSeek, khi bật dự phòng có tính phí | `deepseek-flash` |

Chỉ gọi provider đã nhập key. Giữ nguyên model đã cấu hình; không tự tìm hoặc đổi model. OpenGateway/OpenRouter phải có giá input/output bằng 0 trong catalog trước khi gọi; lỗi kiểm tra giá thì bỏ qua. OpenRouter yêu cầu `:free`, schema và giới hạn giá provider bằng 0. Promo hết hạn hoặc còn dưới 60 giây thì bỏ qua. Groq dùng hạn mức tài khoản. Gemini/DeepSeek mặc định tắt, cần chủ động bật công tắc dự phòng có thể tính phí.

## Luồng và an toàn

Giọng nói được grounding tại máy, thay tên/số điện thoại/địa chỉ đã biết bằng placeholder trước khi gửi API. API trả JSON; Android kiểm tra schema, thời gian, người nhận và địa điểm trước khi thực thi. Chỉ gửi tối đa 3 người và 3 nơi liên quan. Câu nói tự do vẫn có thể chứa thông tin nhận diện hoặc sức khỏe; quyền gửi dữ liệu mặc định tắt.

5 công cụ: thời tiết, báo thức, lịch Android, gọi/soạn tin người thân, mở bản đồ/ứng dụng di chuyển. Gọi/nhắn/dẫn đường luôn cần xác nhận. Số điện thoại được lấy lại từ sổ tay tại máy. Chưa đặt chuyến xe thật; SMS/Zalo phụ thuộc ứng dụng đích.

Quy tắc phát hiện tình huống khẩn cấp vẫn chạy tại máy để không chờ Internet; đây không phải LLM. Baseline luật chỉ dành cho benchmark. Yêu cầu thông thường cần API; hết đường dự phòng thì hỏi lại, không thực thi kế hoạch suy đoán. Android SpeechRecognizer/TTS phụ thuộc dịch vụ giọng nói của thiết bị.

Mỗi lần lập kế hoạch trực tiếp giới hạn tổng 28 giây; mỗi yêu cầu API tối đa 9 giây. Ba lỗi liên tiếp tạm ngắt provider 120 giây. Mọi đường API đều dùng HTTPS, kể cả bản debug.

## Gateway tùy chọn

Có thể chọn chế độ gateway thay vì nhập key provider trên điện thoại. Nhập URL HTTPS và token thiết bị. Gateway Python chỉ gọi API hosted, chỉ nhận `/v1/plan`; không còn đường suy luận local.

```bash
python3 server/configure.py
python3 server/run.py --doctor
python3 server/run.py
```

Key chỉ nằm trong `server/.env` (quyền 600). Dùng `server/Caddyfile.example` để đặt gateway sau HTTPS, cấu hình token riêng trong `DEVICE_TOKENS_JSON`. Không mở cổng nội bộ trực tiếp lên Internet. Proxy mặc định `FREE_ONLY=1`, `METERED_FALLBACKS=`; chỉ thêm Gemini/DeepSeek khi chủ động dùng `--with-metered-fallbacks` hoặc cấu hình tương ứng. Proxy không ghi câu nói, key hoặc body provider.

## Build và kiểm thử

```bash
python3 -m unittest discover -s server -v
./gradlew assembleDebug
```

Version code 2, version name `1.1.0-API`, package `com.khkt.seniorvoice`. Giữ chữ ký của bản đã cài để cập nhật đè. Bộ kiểm tra gồm provider fallback, schema, xác thực, rate limit, giá miễn phí và validator/định danh. Các bài kiểm tra giả lập không xác nhận chất lượng tiếng Việt hoặc tình trạng tài khoản API; cần thử trên điện thoại với key hợp lệ.

`family_memory.json` trong repo chỉ chứa dữ liệu hư cấu, số điện thoại/địa chỉ trống và tọa độ 0. Nhập sổ tay trên thiết bị trước khi thử liên lạc hoặc dẫn đường. Không công khai dữ liệu thật, key, keystore hoặc cấu hình riêng.
