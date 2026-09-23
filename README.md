# BÀI TẬP 5 (SS16) — FLASH SALE CHỐNG SẬP

Xem phân tích tại `Ex05_Analysis.md`, bằng chứng chạy test tại `Ex05_TestEvidence.txt`.

## Tóm tắt

Module sản phẩm cho chương trình Flash Sale, chịu tải cao mà không làm sập Database. Ba tầng bảo vệ:

| Tầng | Chống | Cơ chế |
|---|---|---|
| 1 | **Cache Stampede** | `@Cacheable(sync = true)` + `RedisCacheWriter.lockingRedisCacheWriter` |
| 2 | **Cold Start** | `CacheWarmupRunner` nạp sẵn Redis khi khởi động (có xác minh) |
| 3 | **Redis sập** | `RateLimiter` + `CircuitBreaker` + `LocalFallbackCache` (degraded mode, trả 429 khi cạn) |

### Hai phát hiện quan trọng (đều đã kiểm chứng bằng test)

1. **`sync = true` KHÔNG chống được Stampede nếu dùng cache writer mặc định.** Với
   `RedisCacheManager.builder(connectionFactory)`, 300 request đồng thời vẫn tạo **300 truy vấn DB**.
   Phải dùng `RedisCacheWriter.lockingRedisCacheWriter(connectionFactory)` thì mới còn **1 truy vấn DB**.
2. **Warm-up phải xác minh bằng cách đọc ngược lại.** Thao tác ghi lên Redis cache không hiển thị kết
   quả ngay, nên lần chạy đầu chỉ xác minh được **96/100** key; thêm bước thử lại thì đạt 100/100.

### Số liệu đo được

```
>>> [KB1 STAMPEDE - sync=true]  300 request dong thoi | so truy van DB = 1
>>> [KB2 STAMPEDE - sync=false] 300 request dong thoi | so truy van DB = 300
>>> [KB6 WARM-UP] Thu nap = 100 | xac minh co trong Redis = 100 | thoi gian = 113 ms
>>> [KB7 WARM-UP] Doc 100 san pham sau warm-up: | truy van DB = 0
>>> [KB4 REDIS DOWN] 200 request | loi = 0 | truy van DB = 0 | phuc vu tu cache cuc bo = 200
>>> [KB8 HIEU NANG] Cold = 125.45 ms | Warm = 0.6305 ms | nhanh hon 199 lan
>>> [KB9 10.000 REQUEST] 10.812 request/giay | truy van DB = 0 | loi = 0
```

## Cấu trúc

```
Ex05/
├── Ex05_Analysis.md
├── Ex05_TestEvidence.txt
├── README.md
└── flash-sale-resilient/
    └── src/main/java/com/example/flashsale/
        ├── config/RedisCacheConfig.java        # locking writer + Jackson3 + TTL + fail-open error handler
        ├── config/ResilienceConfig.java        # RateLimiter + CircuitBreaker (API lập trình)
        ├── service/CachedProductReader.java    # @Cacheable(sync = true)
        ├── service/FlashSaleProductService.java# bọc fallback tường minh khi Redis lỗi
        ├── service/ProductLoader.java          # cửa duy nhất xuống DB, xin vé RateLimiter
        ├── service/LocalFallbackCache.java     # L1 trong JVM (degraded mode)
        ├── service/CacheWarmupRunner.java      # CommandLineRunner nạp trước + xác minh
        ├── service/StampedeDemoService.java    # biến thể sync = false (để so sánh)
        ├── repository/InMemoryProductRepository.java  # DB mô phỏng có độ trễ + đếm truy vấn
        └── controller/FlashSaleController.java # API + endpoint đo hiệu năng
```

## Chạy test

```bash
cd flash-sale-resilient
./gradlew test          # Windows: gradlew.bat test
```

> Cần Redis chạy ở `localhost:6379`. Nhóm test "Redis chết" trỏ tới cổng **6399** để mô phỏng sự cố
> mà **không** phải dừng Redis thật.

## Chạy thật

```bash
./gradlew bootRun        # port 8400

curl http://localhost:8400/api/flash-sale/products/1001
curl http://localhost:8400/api/flash-sale/diagnostics
curl -X POST http://localhost:8400/api/flash-sale/warmup
curl "http://localhost:8400/api/flash-sale/benchmark?productId=1001&requests=500&threads=64"
```

**9/9 test PASSED — BUILD SUCCESSFUL in 49s**
