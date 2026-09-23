# BÀI TẬP 5 (SS16) — THIẾT KẾ HỆ THỐNG FLASH SALE CHỐNG SẬP

Mục tiêu: chịu tải **5.000 request/giây** mà không làm sập Database, kiểm soát hai rủi ro
**Cache Stampede** và **Cold Start**.

---

## 1. KIẾN TRÚC HỆ THỐNG

```
                          ┌──────────────────────────────────────────────┐
        Client            │              API GATEWAY / LB                │
      (hàng nghìn  ──────►│   (Nginx / Spring Cloud Gateway)             │
       request)           │   - rate limit theo IP                       │
                          │   - cache response tại biên (optional)       │
                          └───────────────────┬──────────────────────────┘
                                              │
                          ┌───────────────────▼──────────────────────────┐
                          │            PRODUCT SERVICE (instance x N)     │
                          │                                              │
                          │  FlashSaleProductService                     │
                          │        │                                     │
                          │        │  bọc try/catch (fallback tường minh)│
                          │        ▼                                     │
                          │  CachedProductReader                         │
                          │        │ @Cacheable(sync = true)             │
                          │        │                                     │
                          │        ▼                                     │
                          │  ProductLoader  ◄── Cửa DUY NHẤT xuống DB    │
                          │        │                                     │
                          │        ├─ RateLimiter   (chặn trần xuống DB) │
                          │        ├─ CircuitBreaker (DB lỗi -> mở mạch) │
                          │        └─ LocalFallbackCache (L1 trong JVM)  │
                          └───────┬──────────────────────────┬───────────┘
                                  │                          │
                    ┌─────────────▼───────────┐   ┌──────────▼──────────┐
                    │   REDIS (Distributed)   │   │   DATABASE (chậm)   │
                    │  flashsale:products::id │   │   ~120ms / truy vấn │
                    └─────────────▲───────────┘   └─────────────────────┘
                                  │
                    ┌─────────────┴───────────┐
                    │   CACHE WARM-UP WORKER  │   CacheWarmupRunner (CommandLineRunner)
                    │  nạp trước khi mở bán   │   + POST /api/flash-sale/warmup
                    └─────────────────────────┘
```

### Luồng dữ liệu khi có request

```
Client → [1] Cache-Aside lookup (Redis)
              │
              ├── HIT  → trả về ngay (~0,6 ms)                        ← 99% request
              │
              └── MISS → [2] sync=true: các luồng khác CHỜ, chỉ 1 luồng đi tiếp
                              │
                              ▼
                        [3] ProductLoader (xin 1 "vé" từ RateLimiter)
                              │
                        ┌─────┴──────┐
                     có vé        hết vé
                        │            │
                        ▼            ▼
                 [4] truy vấn   [5] LocalFallbackCache (L1)
                     DATABASE         │
                        │        ┌────┴────┐
                        ▼      có bản sao  không có
                 nạp lại Redis & L1   →  429 SERVICE_BUSY
```

---

## 2. CHỐNG CACHE STAMPEDE

### 2.1. Vấn đề

Một key sản phẩm vừa hết hạn. 10.000 request đồng thời cùng lúc nhận **cache miss** và cùng lao xuống
Database để tái tạo cache. Database cạn connection pool → sập toàn hệ thống.

### 2.2. Giải pháp: `@Cacheable(sync = true)`

```java
@Cacheable(cacheNames = FLASH_SALE_CACHE, key = "#productId", sync = true)
public FlashSaleProductDTO read(Long productId) {
    return productLoader.load(productId);
}
```

Với `sync = true`, Spring không để mọi luồng cùng chạy vào thân hàm. Luồng đầu tiên đi lấy dữ liệu,
các luồng còn lại **chờ** và nhận lại kết quả từ cache.

**Kết quả đo được** (300 request đồng thời, DB có độ trễ 300 ms):

| Cấu hình | Số truy vấn DB |
|---|---|
| `sync = false` | **300** (đúng bằng số request ⇒ Database sập) |
| `sync = true` | **1** |

### 2.3. ⚠️ Cạm bẫy: `sync = true` có thể KHÔNG có tác dụng

Đây là phát hiện quan trọng nhất của bài. Lần chạy đầu tiên, `sync = true` cho ra **300 truy vấn DB**
— y hệt `sync = false`. Nguyên nhân: cách tạo cache manager thông thường

```java
RedisCacheManager.builder(connectionFactory)   // ← dùng nonLockingRedisCacheWriter
```

dùng **`nonLockingRedisCacheWriter`**, nghĩa là **không có khoá phân tán nào được tạo ra**. Lúc đó
`sync = true` chỉ có ý nghĩa cục bộ trong một JVM và không gom được các luồng đang chờ.

Cách sửa — buộc phải dùng **locking** cache writer:

```java
RedisCacheWriter cacheWriter = RedisCacheWriter.lockingRedisCacheWriter(connectionFactory);
return RedisCacheManager.builder(cacheWriter)...;
```

Sau khi sửa: **300 request → 1 truy vấn DB**, và khoá này nằm trong Redis nên **đúng cho cả nhiều
instance** (khác với khoá cục bộ trong JVM).

> **Hệ quả kèm theo:** vì khoá nằm trong Redis, khi Redis sập thì `sync = true` **mất tác dụng**.
> Lúc đó lớp bảo vệ Database là **RateLimiter**, không phải cache. Đây là lý do phải có cả hai.

---

## 3. CHỐNG COLD START BẰNG CACHE WARM-UP

### 3.1. Vấn đề

Vừa restart, hoặc vừa mở bán Flash Sale: cache rỗng. Hàng nghìn request đầu tiên đều miss và cùng
đập xuống Database ngay tại thời điểm đông khách nhất.

### 3.2. Giải pháp: `CacheWarmupRunner`

```java
@Component
public class CacheWarmupRunner implements CommandLineRunner {
    @Override
    public void run(String... args) { warmUp(); }
}
```

Chạy ngay khi ứng dụng khởi động: đọc danh sách sản phẩm Flash Sale từ DB một lần duy nhất rồi nạp
sẵn vào Redis, **trước** khi nhận traffic.

**Kết quả đo được:**

| Chỉ số | Giá trị |
|---|---|
| Số sản phẩm nạp sẵn | 100 / 100 |
| Thời gian warm-up | 113 ms |
| Truy vấn DB khi đọc lại 100 sản phẩm | **0** |

### 3.3. Warm-up phải **xác minh**, không được "tin"

Bài làm chủ động đọc ngược lại từ Redis để kiểm chứng việc nạp (thử lại tối đa 5 lần). Lý do đến từ
bài học ở Bài tập 4: **thao tác ghi lên Redis cache không hiển thị kết quả ngay và không ném lỗi khi
thất bại**. Lần chạy đầu, warm-up chỉ xác minh được **96/100** key vì đọc lại quá sớm; sau khi thêm
bước thử lại thì đạt 100/100. Nếu chỉ log "đã nạp 100 sản phẩm" mà không kiểm chứng thì hệ thống có
thể tưởng đã ấm trong khi cache vẫn rỗng.

---

## 4. FALLBACK & RATE LIMITER KHI REDIS SẬP

### 4.1. Ba tầng bảo vệ Database

| Tầng | Thành phần | Chống được gì |
|---|---|---|
| 1 | Redis + `sync = true` (locking writer) | Cache Stampede khi Redis **còn sống** |
| 2 | `CacheWarmupRunner` | Cold Start khi khởi động / mở bán |
| 3 | `RateLimiter` + `CircuitBreaker` + `LocalFallbackCache` | Redis **sập** hoặc DB bắt đầu lỗi |

### 4.2. RateLimiter — chặn trần số truy vấn xuống DB

```java
@Bean
public RateLimiter dbGuardRateLimiter(...) {
    return RateLimiter.of("dbGuardRateLimiter", RateLimiterConfig.custom()
            .limitForPeriod(50)                  // tối đa 50 lượt xuống DB
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ZERO)      // hết vé là từ chối ngay, không xếp hàng
            .build());
}
```

`ProductLoader` là **cửa duy nhất** được phép chạm Database; mọi request muốn xuống DB đều phải xin
được một "vé". Đây là điểm chốt: dù Redis có sập hoàn toàn, số truy vấn DB mỗi giây vẫn bị chặn trần.

Dùng **API lập trình** của Resilience4j thay vì annotation, vì annotation cần AOP/AspectJ — nếu thiếu
weaver thì annotation **im lặng không chạy**. Gọi trực tiếp vừa an toàn hơn vừa kiểm thử được tất định.

### 4.3. LocalFallbackCache (L1) — degraded mode

Khi hết vé, hệ thống không trả lỗi ngay mà phục vụ từ **bản sao gần nhất trong JVM** (có TTL 30 giây).
Đây là đánh đổi có chủ đích: **thà trả dữ liệu hơi cũ vài giây còn hơn trả lỗi 500 cho khách đang
trong phiên Flash Sale**. Chỉ khi cả vé lẫn bản sao cục bộ đều hết mới trả **429 + Retry-After**.

### 4.4. Kết quả đo được khi Redis sập

| Chỉ số | Giá trị |
|---|---|
| Số request đồng thời | 200 |
| Request bị lỗi | **0** |
| Truy vấn DB phát sinh | **0** |
| Request bị RateLimiter chặn | 200 |
| Request phục vụ từ cache cục bộ | 200 |

---

## 5. XỬ LÝ TÌNH HUỐNG BIÊN

### 5.1. 10.000 request đồng thời vào một sản phẩm vừa hết hạn cache

Diễn biến theo thời gian:

| Thời điểm | Chuyện gì xảy ra |
|---|---|
| t=0 | 10.000 request cùng vào. Luồng đầu tiên MISS và **giành được khoá** trong Redis |
| t=0 → t=300ms | 9.999 luồng còn lại **chờ** trên khoá, **không** luồng nào chạm DB |
| t≈300ms | Luồng đầu tiên đọc xong DB, ghi vào Redis, nhả khoá |
| t=300ms+ | Cả 9.999 luồng đọc được giá trị từ Redis và trả về |

**Kết quả: Database nhận đúng 1 truy vấn.** Bằng chứng KB1 (đo với 300 luồng): `1` truy vấn.
Đo thực tế 10.000 request (KB9): `0` truy vấn phát sinh, throughput **10.812 request/giây**.

Nếu Redis cũng sập cùng lúc: không còn khoá, cả 10.000 request rơi vào `ProductLoader` — nhưng
RateLimiter chỉ cho 50 vé mỗi giây xuống DB, phần còn lại lấy từ L1 hoặc nhận 429. **Database vẫn an toàn.**

### 5.2. Redis sập hoàn toàn (Degraded mode)

| Bước | Hành vi |
|---|---|
| 1 | Mọi thao tác đọc cache ném `RedisConnectionFailureException`; `CacheErrorHandler` bắt và coi như cache miss |
| 2 | `FlashSaleProductService` bắt lỗi tường minh và chuyển sang `ProductLoader` |
| 3 | RateLimiter chỉ cho tối đa 50 request/giây xuống DB |
| 4 | Các request vượt trần được phục vụ từ `LocalFallbackCache` (L1, TTL 30s) |
| 5 | Nếu L1 cũng không có → **429 SERVICE_BUSY** kèm `Retry-After: 1` |
| 6 | Khi Redis sống lại, `@Cacheable` tự động hoạt động lại, không cần can thiệp |

**Nguyên tắc:** suy giảm chức năng (chậm hơn, dữ liệu có thể cũ vài giây) **tốt hơn nhiều** so với
sập hoàn toàn. Database là tài nguyên không thể nhân bản, nên phải được bảo vệ bằng mọi giá.

---

## 6. CẤU HÌNH

```java
@Bean
public RedisCacheManager cacheManager(RedisConnectionFactory factory, Duration ttl) {
    RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(ttl)                                    // 2 phút - dữ liệu Flash Sale thay đổi nhanh
            .disableCachingNullValues()                       // không cache null
            .prefixCacheNameWith("flashsale:")
            .serializeKeysWith(pair(new StringRedisSerializer()))
            .serializeValuesWith(pair(GenericJacksonJsonRedisSerializer.builder()
                    .enableUnsafeDefaultTyping().build()));
    return RedisCacheManager.builder(RedisCacheWriter.lockingRedisCacheWriter(factory))  // ★ bắt buộc
            .cacheDefaults(base).build();
}
```

TTL chọn **2 phút**: đủ dài để hấp thụ tải đọc (tỷ lệ đọc/ghi của Flash Sale rất cao), đủ ngắn để giá
và tồn kho không bị cũ quá lâu khi có người mua.

> **Ghi chú kỹ thuật:** đề bài gợi ý `Jackson2JsonRedisSerializer`. Trên Spring Boot 4.1.1 (Jackson 3),
> class này **không dùng được** — thiếu `jackson-databind` 2.x, sẽ ném `NoClassDefFoundError`
> (đã kiểm chứng ở Bài tập 4). Bài làm dùng `GenericJacksonJsonRedisSerializer` tương ứng của Jackson 3.

---

## 7. HƯỚNG DẪN CHẠY

```bash
cd Session16/Ex05/flash-sale-resilient
./gradlew test          # Windows: gradlew.bat test

./gradlew bootRun       # port 8400

curl http://localhost:8400/api/flash-sale/products/1001
curl http://localhost:8400/api/flash-sale/diagnostics      # đếm truy vấn DB, số lần bị chặn...
curl -X POST http://localhost:8400/api/flash-sale/warmup    # chạy lại warm-up

# Đo hiệu năng: 500 request song song vào cùng 1 sản phẩm
curl "http://localhost:8400/api/flash-sale/benchmark?productId=1001&requests=500&threads=64"

redis-cli keys "flashsale*"
redis-cli ttl "flashsale:flash-sale-products::1001"
```

---

## 8. KẾT QUẢ CHẠY THỬ

**9/9 test PASSED, BUILD SUCCESSFUL** trên Redis thật. Chi tiết ở `Ex05_TestEvidence.txt`.

| Yêu cầu của đề | Bằng chứng |
|---|---|
| Cấu hình Redis + TTL hợp lý | `RedisCacheConfig`, TTL 2 phút, key có prefix `flashsale:` |
| `@Cacheable(sync = true)` chống Cache Stampede | KB1: 300 request → **1** truy vấn DB (so với KB2 không sync: 300) |
| `CacheWarmupRunner` nạp trước sự kiện | KB6: 100/100 sản phẩm, 113 ms; KB7: đọc 100 sản phẩm → 0 truy vấn DB |
| Fallback + Rate Limiter khi Redis sập | KB4: 200 request → 0 lỗi, 0 truy vấn DB, 100% phục vụ từ L1 |
| Chứng minh hiệu năng | KB8: 125,45 ms → 0,63 ms (**nhanh hơn 199 lần**); KB9: 10.000 request, 10.812 req/giây, 0 truy vấn DB |
| Xử lý tình huống biên | Mục 5.1 (10.000 request) và 5.2 (Redis sập) |
