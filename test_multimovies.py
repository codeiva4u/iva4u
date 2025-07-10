#!/usr/bin/env python3
"""
MultiMovies Provider Test Script
यह script MultiMovies provider की functionality को test करने के लिए है
"""

import requests
from bs4 import BeautifulSoup
import json

def test_multimovies_homepage():
    """
    Test करता है कि homepage पर movies properly load हो रही हैं या नहीं
    """
    url = "https://multimovies.agency/movies/"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.5",
    }
    
    try:
        response = requests.get(url, headers=headers)
        soup = BeautifulSoup(response.content, 'html.parser')
        
        # Check for movie items
        movie_items = soup.select("article.item")
        print(f"✅ Found {len(movie_items)} movie items on homepage")
        
        # Test poster URLs
        poster_count = 0
        for item in movie_items[:5]:  # Test first 5 items
            img = item.select_one("img")
            if img:
                src = img.get("src", "")
                if src and src.startswith("http"):
                    poster_count += 1
                    print(f"   📸 Poster found: {src[:50]}...")
        
        print(f"✅ Found {poster_count} valid poster URLs out of 5 tested items")
        
        # Test title extraction
        title_count = 0
        for item in movie_items[:5]:
            title = ""
            
            # Try different selectors for title
            title_elem = item.select_one(".data h3")
            if title_elem:
                title = title_elem.get_text(strip=True)
            
            if not title:
                title_elem = item.select_one("h3")
                if title_elem:
                    title = title_elem.get_text(strip=True)
            
            if title:
                title_count += 1
                print(f"   🎬 Title found: {title}")
        
        print(f"✅ Found {title_count} valid titles out of 5 tested items")
        
        return True
        
    except Exception as e:
        print(f"❌ Error testing homepage: {e}")
        return False

def test_search_functionality():
    """
    Test करता है कि search functionality काम कर रही है या नहीं
    """
    search_url = "https://multimovies.agency/wp-json/dooplay/search/?s=avengers"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept": "application/json, text/plain, */*",
        "Referer": "https://multimovies.agency/"
    }
    
    try:
        response = requests.get(search_url, headers=headers)
        data = response.json()
        
        print(f"✅ Search returned {len(data)} results")
        
        for i, item in enumerate(data[:3]):  # Show first 3 results
            title = item.get("title", "")
            url = item.get("url", "")
            img = item.get("img", "")
            
            print(f"   {i+1}. {title}")
            print(f"      URL: {url}")
            if img:
                print(f"      Image: {img[:50]}...")
        
        return True
        
    except Exception as e:
        print(f"❌ Error testing search: {e}")
        return False

if __name__ == "__main__":
    print("🧪 Testing MultiMovies Provider...")
    print("=" * 50)
    
    print("\n📄 Testing Homepage...")
    homepage_ok = test_multimovies_homepage()
    
    print("\n🔍 Testing Search...")
    search_ok = test_search_functionality()
    
    print("\n" + "=" * 50)
    if homepage_ok and search_ok:
        print("✅ All tests passed! MultiMovies provider should work correctly.")
    else:
        print("❌ Some tests failed. Check the output above.")
