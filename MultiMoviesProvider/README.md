# MultiMovies Provider for CloudStream 3

यह एक CloudStream 3 प्रोवाइडर प्लगइन है जो MultiMovies वेबसाइट से मूवी और TV शो स्ट्रीम करने के लिए बनाया गया है।

## 🔧 हल की गई समस्याएं

### 1. Cloudflare सुरक्षा समस्या
**समस्या**: वेबसाइट Cloudflare द्वारा संरक्षित है और बॉट डिटेक्शन का उपयोग करती है।

**समाधान**:
- उचित User-Agent headers जोड़े गए
- Browser-like headers का उपयोग
- Request retry mechanism
- Anti-bot detection bypass

### 2. मूवी पोस्टर लोड नहीं हो रहे
**समस्या**: होम पेज पर मूवी पोस्टर लोड नहीं हो रहे थे।

**समाधान**:
- Multiple CSS selectors का fallback system
- विभिन्न image attributes की जांच (src, data-src, data-lazy)
- बेहतर error handling
- Lazy loading images के लिए support

### 3. Network Request Failures
**समस्या**: Network requests fail हो रही थीं।

**समाधान**:
- Proper headers के साथ requests
- Retry mechanism
- Safe request wrapper functions
- Better exception handling

## 🚀 नई सुविधाएं

1. **Utils.kt**: Utility functions के लिए अलग फाइल
2. **Better Error Handling**: Network errors के लिए बेहतर handling
3. **Multiple Selectors**: CSS selectors के लिए fallback system
4. **Headers Management**: Centralized headers management

## 📁 फाइल संरचना

```
MultiMoviesProvider/
├── src/main/kotlin/com/phisher98/
│   ├── MultiMoviesProvider.kt    # मुख्य प्रोवाइडर क्लास
│   ├── MultiMoviesProviderPlugin.kt  # प्लगइन configuration
│   ├── Extractor.kt              # Video extractors
│   └── Utils.kt                  # Utility functions
└── README.md                     # यह फाइल
```

## ⚙️ तकनीकी विवरण

### Headers Used:
```kotlin
val defaultHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.5",
    "DNT" to "1",
    "Connection" to "keep-alive"
)
```

### Selector Fallbacks:
- `.poster img` → `.image img` → `img`
- `src` → `data-src` → `data-lazy` → `data-original`

## 🔍 डिबगिंग

यदि अभी भी समस्याएं आ रही हैं:

1. **Network logs check करें**
2. **Headers verify करें**
3. **Website structure changes check करें**
4. **Cloudflare status monitor करें**

## 🛠️ Installation

1. प्लगइन को build करें
2. CloudStream 3 में install करें
3. MultiMovies provider को enable करें

## 📞 Support

यदि कोई और समस्या आए तो:
- Network logs share करें
- Specific error messages provide करें
- Website changes के बारे में बताएं

---

**नोट**: यह प्लगइन केवल educational purposes के लिए है। सभी content का copyright respective owners के पास है।
