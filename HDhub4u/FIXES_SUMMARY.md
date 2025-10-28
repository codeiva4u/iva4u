# HDHub4U Extractor Fixes - Summary

## Problem Analysis
Based on the error logs, the following issues were identified:
1. **Failed link loading on HDHub4U** - Links were not being extracted properly
2. **API returning invalid URLs** - Multiple `viralkhabarbull.com` URLs with encoded parameters
3. **Hubdrive extractor failing** - Unable to find download links on Hubdrive pages

## Root Causes

### 1. Incorrect Selector in HDhub4uProvider
**Issue**: The selector only looked for links in `h3` and `h4` tags, but actual download links were in `h5` tags.

**Location**: `HDhub4uProvider.kt` line 167

**Fix**: Added `h5` to the selector:
```kotlin
// Before
doc.select("h3 a:matches(480|720|1080|2160|4K), h4 a:matches(480|720|1080|2160|4K)")

// After
doc.select("h3 a:matches(480|720|1080|2160|4K), h4 a:matches(480|720|1080|2160|4K), h5 a:matches(480|720|1080|2160|4K)")
```

### 2. Hubdrive Extractor Button Selector
**Issue**: The extractor was looking for a specific class that doesn't exist on the current Hubdrive pages.

**Location**: `Extractors.kt` line 114

**Fix**: Added multiple fallback selectors:
```kotlin
// Before
val href = document.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")

// After
val href = document.select("a.btn:contains(HubCloud), a.btn:contains(Server), a.btn[href*='hubcloud']").attr("href")
    .ifEmpty { document.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href") }
    .ifEmpty { document.select("a.btn-primary[href]").attr("href") }
```

### 3. Wrong Domain URLs
**Issue**: Outdated domain URLs in extractors causing 404 errors.

**Fixes**:
- **Hubdrive**: Changed from `https://hubdrive.fit` to `https://hubdrive.space`
- **HubCloud**: Changed from `https://hubcloud.ink` to `https://hubcloud.fit`

## Changes Made

### File: `HDhub4uProvider.kt`
1. **Line 167**: Added `h5 a` selector to movie link extraction

### File: `Extractors.kt`
1. **Line 102**: Updated Hubdrive mainUrl to `https://hubdrive.space`
2. **Lines 115-118**: Improved Hubdrive button selector with fallbacks
3. **Line 121**: Enhanced error message to include URL for debugging
4. **Line 125**: Added debug log for found links
5. **Line 133**: Improved error logging with URL context
6. **Line 141**: Updated HubCloud mainUrl to `https://hubcloud.fit`
7. **Line 155**: Added debug logging for HubCloud URL processing

## Expected Results

After these fixes:
1. ✅ Movie download links from `h5` tags will be properly extracted
2. ✅ Hubdrive pages will correctly identify download buttons using multiple selector strategies
3. ✅ Correct domain URLs will prevent 404 errors
4. ✅ Better error logging will help debug future issues

## Testing Recommendations

Test with the following URL types:
- HDHub4U movie pages with h5 links
- Hubdrive file pages (e.g., `https://hubdrive.space/file/1970966865`)
- HubCloud server links (e.g., `https://hubcloud.fit/drive/...`)

## Notes

- **viralkhabarbull.com links**: These appear to be redirect links that should be handled by the existing `getRedirectLinks()` function in `Utils.kt`
- **Future maintenance**: Consider implementing a domain configuration file to make URL updates easier
- **Selector robustness**: The fallback selector strategy ensures better compatibility with site changes

---
**Fix Date**: October 28, 2025  
**Status**: ✅ All fixes implemented and ready for testing
