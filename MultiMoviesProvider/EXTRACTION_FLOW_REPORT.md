# MultiMovies Extractor - Complete Analysis Report

## 📋 Overview
यह document MultiMovies साइट से मूवी और टीवी शो के वीडियो स्रोतों की पूरी extraction flow को detail में explain करता है।

## 🎬 Movie Extraction Flow

### Step-by-Step Process:

#### 1. Main Movie Page
- **URL Pattern**: `https://multimovies.cheap/movies/{movie-name}/`
- **Example**: `https://multimovies.cheap/movies/war-2-2/`
- **Key Elements**: 
  - Player options list: `<ul id="playeroptionsul">`
  - Each option has: `data-type`, `data-post`, `data-nume`

#### 2. Player API Call
- **Endpoint**: `https://multimovies.cheap/wp-admin/admin-ajax.php`
- **Method**: POST
- **Parameters**:
  ```
  action=doo_player_ajax
  post={post_id}
  nume={player_number}
  type={player_type}
  ```
- **Response**: JSON with `embed_url`

#### 3. Primary Embed URL (GD MIRROR)
- **URL Pattern**: `https://gdmirrorbot.nl/embed/{video_id}`
- **Example**: `https://gdmirrorbot.nl/embed/bz2hpe3`
- **Content**: 
  - Contains multiple server options
  - Primary player iframe: `https://multimoviesshg.com/e/{video_id}`
  - Download button "5GDL" redirects to: `https://ddn.gtxgamer.site/file/{video_id}`

#### 4. Download Page (Multiple Servers)
- **URL Pattern**: `https://ddn.gtxgamer.site/file/{video_id}`
- **Purpose**: Lists multiple video hosting servers
- **Available Servers**:
  1. **Buzzheavier** → `https://igx.gtxgamer.site/?m=...`
  2. **Gofile** → `https://igx.gtxgamer.site/?m=...`
  3. **GDtot** → `https://igx.gtxgamer.site/?m=...`
  4. **Filepress** → `https://igx.gtxgamer.site/?m=...`
  5. **Streamwish** → `https://igx.gtxgamer.site/?m=...`
  6. **RpmShare** → `https://igx.gtxgamer.site/?m=...`
  7. **StreamP2P** → `https://igx.gtxgamer.site/?m=...`
  8. **UpnShare** → `https://igx.gtxgamer.site/?m=...`
  9. **VidHide** → `https://igx.gtxgamer.site/?m=...`

#### 5. Intermediate Redirect Page
- **URL Pattern**: `https://igx.gtxgamer.site/?m={encrypted}&i={encrypted}&...`
- **Purpose**: Shows video player with embedded iframe
- **Contains**: `<iframe src="https://multimoviesshg.com/e/{video_id}">`

#### 6. Final Video Player (MultiMoviesShg)
- **URL Pattern**: `https://multimoviesshg.com/e/{video_id}`
- **Example**: `https://multimoviesshg.com/e/508wb91ibrm1`
- **Video Stream**: M3U8 HLS stream
- **Stream URL Example**:
  ```
  https://hi1qz0vp55y7ifq.premilkyway.com/hls2/01/12265/508wb91ibrm1_,l,n,h,.urlset/master.m3u8
  ```

## 📺 TV Show Extraction Flow

### Step-by-Step Process:

#### 1. Episode Page
- **URL Pattern**: `https://multimovies.cheap/episodes/{show-name}-{season}x{episode}/`
- **Example**: `https://multimovies.cheap/episodes/the-trial-1x1/`
- **Key Elements**: Same as movies (player options list)

#### 2. Alternative Embed Source
- **URL Pattern**: `https://stream.techinmind.space/embed/tv/{tmdb_id}/{season}/{episode}?key={key}`
- **Example**: `https://stream.techinmind.space/embed/tv/228718/1/1?key=e11a7debaaa4f5d25b671706ffe4d2acb56efbd4`
- **Purpose**: Different embed source for TV shows
- **May contain**: Nested iframes leading to MultiMoviesShg or other hosters

#### 3. Common Pattern
- TV shows भी ultimately same video hosters (MultiMoviesShg, etc.) पर redirect होते हैं
- Flow similar to movies but with different initial embed URL

## 🔧 Implemented Extractors

### Core Extractors:

#### 1. **MultiMoviesShgExtractor**
- **Priority**: PRIMARY VIDEO HOSTER
- **Domain**: `multimoviesshg.com`
- **Type**: M3U8 HLS Streams
- **Methods**:
  - HTML/JavaScript extraction (regex search for m3u8 URLs)
  - WebView extraction (fallback)
- **Success Rate**: High (main hoster)

#### 2. **GdMirrorExtractor**
- **Purpose**: Handles gdmirrorbot.nl and gtxgamer redirects
- **Domain**: `gdmirrorbot.nl`, `gtxgamer.site`
- **Methods**:
  - WebView to capture dynamic redirects
  - Manual redirect following
  - Iframe extraction
- **Target**: Extracts MultiMoviesShg URLs from iframes

#### 3. **TechInMindExtractor**
- **Purpose**: TV show embed handler
- **Domain**: `stream.techinmind.space`, `ssn.techinmind.space`
- **Methods**:
  - Iframe extraction
  - Nested iframe following
  - Regex search for MultiMoviesShg URLs
- **Target**: Ultimately redirects to MultiMoviesShg

### Additional Hosters:

#### 4. **StreamwishExtractor**
- **Domain**: `streamwish.to`
- **Type**: M3U8 streams
- **Method**: WebView with m3u8 regex

#### 5. **VidHideExtractor**
- **Domain**: `vidhide.com`, `filelion.com`
- **Type**: M3U8 streams
- **Method**: WebView with m3u8 regex

#### 6. **FilepressExtractor**
- **Domain**: `filepress.store`
- **Type**: Direct video or M3U8
- **Method**: HTML source extraction

#### 7. **GofileExtractor**
- **Domain**: `gofile.io`
- **Type**: Direct download links
- **Method**: API-based extraction

#### 8. **BuzzheavierExtractor** ✨ NEW
- **Domain**: `buzzheavier.com`
- **Type**: M3U8 streams
- **Method**: WebView with m3u8 regex

#### 9. **GDtotExtractor** ✨ NEW
- **Domain**: `gdtot.pro`
- **Type**: Google Drive redirects
- **Method**: Download link extraction

#### 10. **RpmShareExtractor** ✨ NEW
- **Domain**: `rpmshare.com`, `rpmhub.site`
- **Type**: M3U8 streams
- **Method**: WebView with m3u8 regex

#### 11. **StreamP2PExtractor** ✨ NEW
- **Domain**: `streamp2p.com`, `p2pplay.pro`
- **Type**: M3U8 streams
- **Method**: WebView with m3u8 regex

#### 12. **UpnShareExtractor** ✨ NEW
- **Domain**: `upnshare.com`, `uns.bio`
- **Type**: M3U8 streams
- **Method**: WebView with m3u8 regex

## 📊 URL Pattern Detection

Provider में automatic detection logic:

```kotlin
// GdMirror/GtxGamer
gdmirrorbot, gdmirror, gtxgamer → GdMirrorExtractor

// TechInMind
techinmind.space, ssn.techinmind → TechInMindExtractor

// Primary Hoster
multimoviesshg → MultiMoviesShgExtractor

// Additional Hosters
streamwish → StreamwishExtractor
vidhide, filelion → VidHideExtractor
filepress → FilepressExtractor
gofile → GofileExtractor
buzzheavier → BuzzheavierExtractor
gdtot → GDtotExtractor
rpmshare, rpmhub → RpmShareExtractor
streamp2p, p2pplay → StreamP2PExtractor
upnshare, uns.bio → UpnShareExtractor
```

## 🎯 Key Findings

### Main Video Flow:
1. **Primary Source**: MultiMoviesShg (`multimoviesshg.com`)
   - सबसे reliable और commonly used
   - Direct M3U8 HLS streams
   - High quality (1080p support)

2. **Redirect Chains**:
   - GdMirrorBot → GtxGamer → MultiMoviesShg
   - TechInMind → MultiMoviesShg (for TV shows)

3. **Download Links**:
   - `ddn.gtxgamer.site` is NOT a video player
   - It's an intermediate page showing multiple server options
   - Each server button redirects through `igx.gtxgamer.site`
   - Final destination varies (MultiMoviesShg, Streamwish, etc.)

### Important Notes:

#### ✅ **क्या करें**:
- Primary focus on GdMirror → MultiMoviesShg flow
- Extract M3U8 streams from MultiMoviesShg
- Handle both movies and TV shows
- Support multiple backup servers

#### ❌ **क्या न करें**:
- Download links (`ddn.gtxgamer.site`) को direct video source मत समझें
- These are redirect/download pages, not streaming players
- CloudStream app focuses on STREAMING, not downloading

## 🔐 Technical Details

### M3U8 Stream Example:
```
https://hi1qz0vp55y7ifq.premilkyway.com/hls2/01/12265/508wb91ibrm1_,l,n,h,.urlset/master.m3u8
```

### Quality Options:
- `l` = Low quality
- `n` = Normal/Medium quality  
- `h` = High quality (1080p)

### Subtitles:
```
https://38hokq0mlb6g.premilkyway.com/vtt/01/12265/508wb91ibrm1_eng.vtt
```

## 📝 Implementation Summary

### Files Modified:
1. **Extractor.kt**:
   - Added 5 new extractor classes
   - Improved existing extractors
   - Better error handling and logging

2. **MultiMoviesProvider.kt**:
   - Enhanced `loadExtractorLink()` method
   - Added detection for all new extractors
   - Domain mapping for various hosters

### Testing Recommendations:

1. **Test Movies**:
   - ✅ Verified: `https://multimovies.cheap/movies/war-2-2/`
   - Stream extracted successfully
   - M3U8 URL confirmed working

2. **Test TV Shows**:
   - ✅ Verified: `https://multimovies.cheap/episodes/the-trial-1x1/`
   - Different embed source detected
   - TechInMind → MultiMoviesShg flow working

3. **Test Multiple Servers**:
   - Each server link should be individually tested
   - Some may require additional WebView handling
   - Fallback to built-in extractors if custom ones fail

## 🚀 Future Improvements

1. **Caching**: Cache successful extraction patterns
2. **Parallel Extraction**: Try multiple servers simultaneously
3. **Quality Selection**: Allow user to choose quality (l/n/h)
4. **Subtitle Support**: Extract and provide VTT subtitle URLs
5. **Error Recovery**: Better handling of failed extractions

## 📌 Conclusion

MultiMovies extraction system अब **पूरी तरह से functional** है:
- ✅ Movies और TV shows दोनों supported
- ✅ Primary hoster (MultiMoviesShg) properly handled
- ✅ Multiple backup hosters implemented
- ✅ Redirect chains correctly followed
- ✅ M3U8 streams successfully extracted

**Total Extractors**: 12 (3 existing improved + 5 new + 4 already working)
**Success Rate**: High for MultiMoviesShg (primary source)
**Fallback Options**: Multiple backup hosters available
