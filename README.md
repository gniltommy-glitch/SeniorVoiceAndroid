# SeniorVoice-Android

Trợ lý giọng nói tiếng Việt cho người cao tuổi. Android SpeechRecognizer/TTS nhận và đọc giọng nói; luật Kotlin xử lý lịch Âm, giờ và sổ tay; Intent/ContentProvider thực hiện tác vụ. Cập nhật theo báo cáo ngày 11/11/2026, chuyển sang các đường API miễn phí theo yêu cầu mới.

## Luồng xử lý

```text
Giọng nói → grounding và luật offline
  ├─ Khẩn cấp / câu đơn rõ → kiểm tra → xác nhận nếu nhạy cảm → thực thi
  └─ Câu khó → Ollama riêng (tùy chọn, qua proxy xác thực)
            → Qwen trên OpenGateway (khi còn giá 0)
            → OpenRouter :free
            → Groq
            → luật đủ rõ hoặc hỏi lại
```

5 tool: thời tiết theo giờ, báo thức, lịch Android, gọi/soạn tin người thân và mở bản đồ/ứng dụng di chuyển. Chưa đặt chuyến xe thật; SMS/Zalo phụ thuộc ứng dụng đích. `ON_DEVICE_EDGE` là luật offline, chưa có runtime LLM trên điện thoại.

Mọi kế hoạch dùng schema chung và được kiểm tra tại máy: tool, ngày/giờ, tọa độ, người nhận và địa chỉ. Số điện thoại lấy lại từ sổ tay; model không quyết định rủi ro. Gọi/nhắn/dẫn đường luôn cần xác nhận trong luồng thật. Không hiểu câu thì hỏi lại, không tự ghi lịch. Triệu chứng khẩn cấp không đợi mạng.

## Model và giá được kiểm tra ngày 11/11/2026

| Đường | Model | Trạng thái và cách dùng |
|---|---|---|
| OpenGateway | `qwen/qwen3.8-flash` | Chọn cho tác vụ trích tham số; khuyến mãi giá 0 đến 13/11/2026 00:00 UTC, tức 07:00 Việt Nam, hoặc hết quỹ sớm hơn |
| OpenGateway | `qwen/qwen3.8-max` | Cũng khuyến mãi, nặng hơn nhu cầu hiện tại nên không chọn mặc định |
| OpenRouter | `nvidia/nemotron-3.5-super-vl:free` | Có endpoint đang hoạt động, hỗ trợ structured outputs; dự phòng mặc định |
| OpenRouter | `nvidia/nemotron-3.5-lightning:free` | Nhẹ hơn nhưng endpoint hiện không liệt kê `response_format`; không chọn cho schema nghiêm ngặt |
| Groq | `openai/gpt-oss-20b` | Dự phòng cuối bằng key đã có, trong hạn mức tài khoản hiện tại |
| Gemini trực tiếp | `gemini-3.5-flash-lite` | Đã lưu key thật; dự án hiện không có free tier, cấu hình dự phòng tùy chọn |
| DeepSeek trực tiếp | `deepseek-flash` | Có adapter, nhưng là API tính phí; không bật trong chế độ miễn phí |

Một số trang Qwen/DeepSeek cũ có `:free` vẫn mở được nhưng API trả `endpoints: []`, ví dụ `qwen/qwen3.8-27b:free` và `deepseek/deepseek-v4-flash-0731:free`. Không cấu hình các tên đó làm đường hoạt động. Chưa có benchmark tiếng Việt thực tế để khẳng định chất lượng model mới.

Nguồn chính thức: [catalog OpenGateway](https://api.twigpine.com/v1/models), [docs OpenGateway](https://twigpine.com/opengateway/docs), [endpoint OpenRouter được chọn](https://openrouter.ai/api/v1/models/nvidia/nemotron-3.5-super-vl:free/endpoints), [giá DeepSeek](https://api-docs.deepseek.com/quick_start/pricing/).

## Chặn tự chuyển sang model tính phí

- `FREE_ONLY=1` mặc định; `PROVIDER_ORDER=opengateway,openrouter,groq,gemini,deepseek`.
- OpenGateway: lấy catalog giá thực trước mỗi lần gọi; chỉ chấp nhận model có ID chính xác, trạng thái active, input/output đều 0. Promo hết hạn hoặc còn dưới 60 giây thì bỏ qua. Không dùng `auto`, không tự đổi sang model paid.
- OpenRouter: bắt buộc ID kết thúc `:free`, giá catalog bằng 0, yêu cầu provider hỗ trợ schema và đặt `provider.max_price` input/output bằng 0. Catalog cập nhật tối đa mỗi 60 giây; refresh lỗi thì không dùng giá cũ.
- Không đọc được giá thì bỏ qua đường đó. Hết quota hoặc lỗi thì chuyển đường tiếp theo, cuối cùng hỏi lại. Không nạp credit, bật billing hoặc thanh toán x402.
- Gemini và DeepSeek đã có tên model, endpoint, adapter JSON và vị trí cuối chuỗi dự phòng. Với `FREE_ONLY=1` và `METERED_FALLBACKS=` để trống, hai API này được bỏ qua. Dùng `python3 server/run.py --with-metered-fallbacks` khi chủ động muốn dùng thêm hai API có thể tính phí; hoặc đặt `METERED_FALLBACKS=gemini,deepseek` để lưu lựa chọn. Cơ chế này chỉ cho phép hai API trực tiếp, vẫn giữ chặn giá paid cho OpenGateway/OpenRouter.
- Groq dùng gói tài khoản hiện tại; chưa bật thanh toán. Giới hạn provider có thể thay đổi, không đảm bảo API miễn phí luôn sẵn sàng.

## Chạy trên máy phát triển

Python 3.10+, không cần cài thêm thư viện.

```bash
python3 server/run.py --doctor    # chỉ in trạng thái, không in key
python3 server/run.py --smoke     # một câu báo thức giả lập, gọi API thật
python3 server/run.py             # server tại 127.0.0.1:8787
```

Hoặc mở `server/test-model.command` / `server/start-server.command` trên macOS. Các tệp này chạy trong Terminal của máy; không tự cài dịch vụ khởi động hay mở cổng ra Internet.

Trong phiên cập nhật: 22 bài kiểm thử proxy và 17 kiểm tra validator/định danh đã qua; toàn bộ Kotlin/Compose biên dịch thành công bằng compiler cache. Test API thật gặp lỗi DNS trong môi trường thực thi; chạy listener bị từ chối với `Operation not permitted`. Vì vậy **chưa xác minh server chạy thật trên máy**, chưa có proxy HTTPS trực tuyến và chưa đóng gói APK mới. Không coi các bài test giả lập là kết quả gọi model thật.

### Key và cấu hình

Key chỉ nằm trong `server/.env`, quyền `600`, không vào APK hay mã nguồn chia sẻ. Key Gemini/Groq trước đó được giữ nguyên, Gemini dùng lại key hiện có trong cấu hình dự phòng tùy chọn. DeepSeek đã cấu hình `deepseek-flash` tại `https://api.deepseek.com/chat/completions`, key thật đã được người dùng cung cấp và lưu trong `server/.env`; không đưa giá trị key vào tài liệu hoặc mã nguồn chia sẻ. Groq key đã tạo hết hạn 09/02/2027. Key OpenRouter và OpenGateway đã được cấp và lưu ngày 11/11/2026. OpenRouter dùng key đầu tiên được tạo trong bước onboarding của tài khoản; đã bỏ qua bước thêm phương thức thanh toán. OpenGateway dùng key riêng tên `SeniorVoice-Android`. Không có thao tác nạp tiền hay bật trả phí.

Nếu tự nhập key ở máy, dùng `python3 server/configure.py`; để trống sẽ giữ key hiện có. Hoặc chỉ sửa một provider: `python3 server/configure.py --provider openrouter`. Helper không ghi đè model hoặc thứ tự đã chọn.

Trong Android, nhập URL gốc HTTPS của proxy, token thiết bị rồi bật quyền gửi dữ liệu tới OpenGateway/OpenRouter/Groq trong Cập Nhật Sổ Tay. Quyền đồng ý của cấu hình Gemini/Groq cũ không được tự tái sử dụng cho provider mới. APK release chỉ dùng HTTPS; debug còn các ngoại lệ HTTP cho IP phát triển.

### Proxy HTTPS và Ollama tùy chọn

`server/Caddyfile.example` là mẫu reverse proxy. Dùng hostname/chứng chỉ hợp lệ. Không đưa cổng 8787 hoặc Ollama 11434 trực tiếp lên Internet. Nếu dùng Tailscale, thiết bị phải vào mạng và truy cập được hostname HTTPS.

Mỗi máy có token riêng trong `DEVICE_TOKENS_JSON`. Xóa token và restart để thu hồi. Mặc định 10 yêu cầu/phút/thiết bị và 4 yêu cầu đồng thời. Bộ đếm nằm trong RAM một tiến trình, reset khi khởi động lại; chưa có quota theo ngày/nhiều server. Ba lỗi liên tiếp ngắt provider 120 giây. Timeout mỗi provider 6 giây; Android giới hạn một cuộc gọi proxy 30 giây.

Muốn dùng Ollama: tải `qwen2.5:3b`, đặt `LOCAL_ENABLED=1`, `OLLAMA_URL=http://127.0.0.1:11434`. Android gọi `/v1/local-plan` trên proxy HTTPS có cùng token; không gọi Ollama HTTP trực tiếp. Để trống URL server riêng nếu không dùng.

## Dữ liệu và kiểm thử

Thay tên/biệt danh, số điện thoại và địa chỉ đã biết bằng placeholder; ánh xạ ngược chỉ ở máy. Chỉ gửi tối đa 3 người và 3 nơi liên quan. Đây là thay thế định danh, không đảm bảo ẩn danh hoàn toàn: câu tự do vẫn có thể chứa dữ liệu sức khỏe/nhận diện khác. Quyền gửi API mặc định tắt.

Proxy không ghi câu nói, key hoặc body provider; trả `Cache-Control: no-store`. Reverse proxy cũng cần tắt log nội dung. Android tắt backup; release chặn HTTP thường. Chưa cache kế hoạch đã xác nhận.

```bash
python3 -m unittest discover -s server -v
./gradlew assembleDebug
./gradlew assembleRelease
```

Kiểm thử gồm thứ tự provider, timeout/quota/schema, ngắt mạch, token/rate limit, chặn giá paid, hết promo và không dùng giá stale. Bài test dùng dữ liệu giả, không tiêu quota. Kotlin/Compose được kiểm tra trực tiếp bằng compiler cache khi Gradle không mở được socket khóa nội bộ. APK gốc vẫn là bản cũ.

Các phần còn lại của báo cáo: benchmark hiện 46 câu, baseline là ablation của app; cần ≥100 câu và đối sánh thật, test Android 12–15 trên nhiều máy, đo p50/p95/RAM/tải và chất lượng model, xử lý mơ hồ người nhận. Chưa tuyên bố sẵn sàng phổ cập.

## Dữ liệu mẫu trong repo public

`family_memory.json` dùng tên hư cấu, để trống số điện thoại/địa chỉ và tọa độ 0. Không có hồ sơ sức khỏe thật. Cần nhập sổ tay trên thiết bị trước khi thử gọi/nhắn hoặc dẫn đường; dữ liệu mẫu không tự thực hiện cuộc gọi tới người thật. Bản dữ liệu trước khi ẩn danh chỉ được giữ tại máy, trong thư mục đã bị Git loại trừ.
