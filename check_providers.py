#!/usr/bin/env python3
"""
Provider Domain and Extractor Checker for iva4u Project

This script checks all providers and extractors in the iva4u project to verify:
1. Domain connectivity and status
2. Extractor endpoints availability  
3. Provider main pages accessibility
4. Dynamic domain updates from remote sources

Usage: python check_providers.py
"""

import requests
import re
import json
import time
from urllib.parse import urlparse, urljoin
from concurrent.futures import ThreadPoolExecutor, as_completed
import sys
from typing import Dict, List, Tuple, Optional

# Configure requests with proper headers and timeouts
session = requests.Session()
session.headers.update({
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.5',
    'Accept-Encoding': 'gzip, deflate, br',
    'Connection': 'keep-alive',
    'Upgrade-Insecure-Requests': '1',
    'Sec-Fetch-Dest': 'document',
    'Sec-Fetch-Mode': 'navigate',
    'Sec-Fetch-Site': 'none'
})

# Provider configurations extracted from the code
PROVIDERS = {
    'MultiMovies': {
        'main_url': 'https://multimovies.buzz/',
        'dynamic_domain_url': 'https://raw.githubusercontent.com/likdev256/MultiMovieAPI/main/domains.json',
        'test_paths': [
            '/',
            '/trending/',
            '/genre/bollywood-movies/',
            '/genre/hollywood/',
            '/wp-admin/admin-ajax.php'
        ]
    },
    'HDhub4u': {
        'main_url': 'https://hdhub4u.build/?utm=mn',
        'dynamic_domain_url': 'https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json',
        'test_paths': [
            '/',
            '/category/bollywood-movies/',
            '/category/hollywood-movies/',
            '/category/hindi-dubbed/'
        ]
    },
    'MoviesDrive': {
        'main_url': 'https://moviesdrive.channel/',
        'test_paths': [
            '/',
            '/page/',
            '/category/hollywood/page/',
            '/hindi-dubbed/page/',
            '/category/south/page/'
        ]
    },
    'Movierulzhd': {
        'main_url': 'https://1movierulzhd.cv/',
        'test_paths': [
            '/',
            '/movies/',
            '/genre/hindi-dubbed/',
            '/genre/hindi/',
            '/search/',
            '/wp-admin/admin-ajax.php'
        ]
    }
}

# Extractor configurations
EXTRACTORS = {
    # VidStack family
    'Vidstack': ['https://vidstack.io', 'https://server1.uns.bio'],
    
    # VidSrc family  
    'VidSrcTo': ['https://vidsrc2.to', 'https://vidsrc.to'],
    
    # File hosting
    'FileMoon': [
        'https://filemoon.to', 
        'https://filemoon.sx',
        'https://filemoon.in',
        'https://fmhd.bar'
    ],
    
    # Stream services
    'Filesim': [
        'https://files.im',
        'https://guccihide.com',
        'https://ahvsh.com', 
        'https://moviesm4u.com',
        'https://streamhide.to',
        'https://streamhide.com',
        'https://movhide.pro',
        'https://ztreamhub.com'
    ],
    
    # VidHidePro family
    'VidHidePro': [
        'https://vidhidepro.com',
        'https://filelions.live',
        'https://filelions.online', 
        'https://filelions.to',
        'https://kinoger.be',
        'https://vidhidevip.com',
        'https://vidhidepre.com',
        'https://dhcplay.com',
        'https://smoothpre.com',
        'https://dhtpre.com',
        'https://peytonepre.com'
    ],
    
    # StreamWish
    'StreamWish': [
        'https://luluvdo.com',
        'https://lulu.st'
    ],
    
    # Other extractors
    'Movierulz': ['https://movierulz2025.bar'],
    'FMX': ['https://fmx.lol'],
    'Akamaicdn': ['https://molop.art'],
    'GDFlix': ['https://new10.gdflix.dad', 'https://new6.gdflix.dad'],
    'Gofile': ['https://gofile.io', 'https://api.gofile.io'],
    'Playonion': ['https://playonion.sbs'],
    
    # HDhub4u specific
    'HdStream4u': ['https://hdstream4u.com'],
    'Hubstream': ['https://hubstream.art'],
    'Hblinks': ['https://hblinks.pro'],
    'Hubcdn': ['https://hubcdn.cloud'],
    'Hubdrive': ['https://hubdrive.fit'],
    'HubCloud': ['https://hubcloud.ink'],
    
    # DriveBot
    'DriveBot': ['https://drivebot.sbs', 'https://drivebot.cfd']
}

def check_url(url: str, timeout: int = 10, method: str = 'GET') -> Tuple[bool, int, str, float]:
    """
    Check if a URL is accessible and return status information.
    
    Returns:
        Tuple of (success, status_code, error_message, response_time)
    """
    start_time = time.time()
    try:
        if method.upper() == 'HEAD':
            response = session.head(url, timeout=timeout, allow_redirects=True)
        else:
            response = session.get(url, timeout=timeout, allow_redirects=True)
        
        response_time = time.time() - start_time
        return True, response.status_code, '', response_time
    
    except requests.exceptions.Timeout:
        return False, 0, 'Timeout', time.time() - start_time
    except requests.exceptions.ConnectionError:
        return False, 0, 'Connection Error', time.time() - start_time
    except requests.exceptions.TooManyRedirects:
        return False, 0, 'Too Many Redirects', time.time() - start_time
    except requests.exceptions.RequestException as e:
        return False, 0, str(e), time.time() - start_time
    except Exception as e:
        return False, 0, f'Unexpected error: {str(e)}', time.time() - start_time

def get_dynamic_domains(url: str) -> Optional[Dict]:
    """Fetch dynamic domain configuration from remote URL."""
    try:
        response = session.get(url, timeout=15)
        response.raise_for_status()
        return response.json()
    except Exception as e:
        print(f"❌ Failed to fetch dynamic domains from {url}: {e}")
        return None

def test_provider_domains(provider_name: str, config: Dict) -> Dict:
    """Test all domains and paths for a provider."""
    results = {
        'provider': provider_name,
        'main_domain': {'status': 'unknown', 'paths': {}},
        'dynamic_domains': {},
        'overall_status': 'unknown'
    }
    
    print(f"\n🔍 Testing {provider_name} Provider...")
    
    # Test main domain
    main_url = config['main_url']
    success, status, error, response_time = check_url(main_url)
    
    results['main_domain']['status'] = 'working' if success and status == 200 else 'failed'
    results['main_domain']['status_code'] = status
    results['main_domain']['error'] = error
    results['main_domain']['response_time'] = round(response_time, 2)
    
    if success and status == 200:
        print(f"  ✅ Main domain working: {main_url} ({response_time:.2f}s)")
    else:
        print(f"  ❌ Main domain failed: {main_url} - {error} ({status})")
    
    # Test specific paths
    test_paths = config.get('test_paths', ['/'])
    for path in test_paths:
        if not path.startswith('/'):
            continue
            
        test_url = urljoin(main_url, path)
        success, status, error, response_time = check_url(test_url)
        
        results['main_domain']['paths'][path] = {
            'status': 'working' if success and status == 200 else 'failed',
            'status_code': status,
            'error': error,
            'response_time': round(response_time, 2)
        }
        
        if success and status == 200:
            print(f"    ✅ Path working: {path} ({response_time:.2f}s)")
        else:
            print(f"    ❌ Path failed: {path} - {error} ({status})")
    
    # Test dynamic domains if available
    if 'dynamic_domain_url' in config:
        print(f"  🔄 Checking dynamic domains...")
        dynamic_data = get_dynamic_domains(config['dynamic_domain_url'])
        
        if dynamic_data:
            # Extract relevant domain for this provider
            domain_key = provider_name.lower()
            if domain_key == 'multimovies':
                domain_key = 'multiMovies'  # Case sensitive in JSON
            elif domain_key == 'hdhub4u':
                domain_key = 'hdhub4u'
                
            if domain_key in dynamic_data:
                dynamic_domain = dynamic_data[domain_key]
                success, status, error, response_time = check_url(dynamic_domain)
                
                results['dynamic_domains'][domain_key] = {
                    'url': dynamic_domain,
                    'status': 'working' if success and status == 200 else 'failed',
                    'status_code': status,
                    'error': error,
                    'response_time': round(response_time, 2)
                }
                
                if success and status == 200:
                    print(f"    ✅ Dynamic domain working: {dynamic_domain} ({response_time:.2f}s)")
                else:
                    print(f"    ❌ Dynamic domain failed: {dynamic_domain} - {error} ({status})")
            else:
                print(f"    ⚠️  Dynamic domain key '{domain_key}' not found in response")
    
    # Determine overall status
    main_working = results['main_domain']['status'] == 'working'
    paths_working = any(p['status'] == 'working' for p in results['main_domain']['paths'].values()) if results['main_domain']['paths'] else main_working
    dynamic_working = any(d['status'] == 'working' for d in results['dynamic_domains'].values()) if results['dynamic_domains'] else True
    
    if main_working and paths_working and dynamic_working:
        results['overall_status'] = 'working'
    elif main_working or any(d['status'] == 'working' for d in results['dynamic_domains'].values()):
        results['overall_status'] = 'partial'
    else:
        results['overall_status'] = 'failed'
    
    return results

def test_extractor_domains(extractor_name: str, urls: List[str]) -> Dict:
    """Test all domains for an extractor."""
    results = {
        'extractor': extractor_name,
        'domains': {},
        'working_count': 0,
        'total_count': len(urls),
        'overall_status': 'unknown'
    }
    
    print(f"\n🔧 Testing {extractor_name} Extractor ({len(urls)} domains)...")
    
    for url in urls:
        success, status, error, response_time = check_url(url)
        
        results['domains'][url] = {
            'status': 'working' if success and status == 200 else 'failed',
            'status_code': status,
            'error': error,
            'response_time': round(response_time, 2)
        }
        
        if success and status == 200:
            results['working_count'] += 1
            print(f"  ✅ {url} - Working ({response_time:.2f}s)")
        else:
            print(f"  ❌ {url} - Failed: {error} ({status})")
    
    # Determine overall status
    if results['working_count'] == results['total_count']:
        results['overall_status'] = 'all_working'
    elif results['working_count'] > 0:
        results['overall_status'] = 'partial'
    else:
        results['overall_status'] = 'all_failed'
    
    return results

def generate_report(provider_results: List[Dict], extractor_results: List[Dict]) -> str:
    """Generate a comprehensive report."""
    report = []
    report.append("=" * 80)
    report.append("🎬 IVA4U PROJECT - PROVIDERS & EXTRACTORS STATUS REPORT")
    report.append("=" * 80)
    report.append(f"⏰ Generated: {time.strftime('%Y-%m-%d %H:%M:%S UTC')}")
    report.append("")
    
    # Provider Summary
    report.append("📊 PROVIDER SUMMARY")
    report.append("-" * 40)
    working_providers = sum(1 for r in provider_results if r['overall_status'] == 'working')
    partial_providers = sum(1 for r in provider_results if r['overall_status'] == 'partial')
    failed_providers = sum(1 for r in provider_results if r['overall_status'] == 'failed')
    
    report.append(f"✅ Fully Working: {working_providers}")
    report.append(f"⚠️  Partially Working: {partial_providers}")
    report.append(f"❌ Failed: {failed_providers}")
    report.append(f"📈 Total Providers: {len(provider_results)}")
    report.append("")
    
    # Provider Details
    report.append("🏢 PROVIDER DETAILED STATUS")
    report.append("-" * 40)
    for result in provider_results:
        status_icon = {"working": "✅", "partial": "⚠️", "failed": "❌"}[result['overall_status']]
        report.append(f"{status_icon} {result['provider']}")
        
        # Main domain
        main = result['main_domain']
        main_icon = "✅" if main['status'] == 'working' else "❌"
        report.append(f"  {main_icon} Main: {main.get('status_code', 'N/A')} ({main.get('response_time', 0)}s)")
        
        # Dynamic domains
        if result['dynamic_domains']:
            for domain_key, domain_info in result['dynamic_domains'].items():
                domain_icon = "✅" if domain_info['status'] == 'working' else "❌"
                report.append(f"  {domain_icon} Dynamic: {domain_info['status_code']} ({domain_info['response_time']}s)")
        
        # Failed paths
        failed_paths = [p for p, info in result['main_domain']['paths'].items() if info['status'] == 'failed']
        if failed_paths:
            report.append(f"    ⚠️  Failed paths: {', '.join(failed_paths)}")
        
        report.append("")
    
    # Extractor Summary
    report.append("🔧 EXTRACTOR SUMMARY")
    report.append("-" * 40)
    all_working = sum(1 for r in extractor_results if r['overall_status'] == 'all_working')
    partial_working = sum(1 for r in extractor_results if r['overall_status'] == 'partial')
    all_failed = sum(1 for r in extractor_results if r['overall_status'] == 'all_failed')
    
    total_domains = sum(r['total_count'] for r in extractor_results)
    working_domains = sum(r['working_count'] for r in extractor_results)
    
    report.append(f"✅ Fully Working Extractors: {all_working}")
    report.append(f"⚠️  Partially Working Extractors: {partial_working}")
    report.append(f"❌ Failed Extractors: {all_failed}")
    report.append(f"📊 Working Domains: {working_domains}/{total_domains} ({working_domains/total_domains*100:.1f}%)")
    report.append("")
    
    # Extractor Details
    report.append("🔧 EXTRACTOR DETAILED STATUS")
    report.append("-" * 40)
    for result in extractor_results:
        status_icons = {"all_working": "✅", "partial": "⚠️", "all_failed": "❌"}
        status_icon = status_icons[result['overall_status']]
        report.append(f"{status_icon} {result['extractor']} ({result['working_count']}/{result['total_count']} working)")
        
        # Show failed domains
        failed_domains = [url for url, info in result['domains'].items() if info['status'] == 'failed']
        if failed_domains and len(failed_domains) < len(result['domains']):  # Only if not all failed
            report.append(f"    ❌ Failed: {', '.join(failed_domains[:3])}")
            if len(failed_domains) > 3:
                report.append(f"    ... and {len(failed_domains) - 3} more")
        report.append("")
    
    # Recommendations
    report.append("💡 RECOMMENDATIONS")
    report.append("-" * 40)
    
    critical_issues = []
    if failed_providers > 0:
        critical_issues.append(f"❗ {failed_providers} providers completely failed")
    
    if working_domains / total_domains < 0.7:
        critical_issues.append("❗ Less than 70% of extractor domains working")
    
    if critical_issues:
        report.append("🚨 CRITICAL ISSUES:")
        for issue in critical_issues:
            report.append(f"  {issue}")
        report.append("")
    
    report.append("🔍 ACTIONS NEEDED:")
    if partial_providers > 0:
        report.append("  • Check dynamic domain updates for partially working providers")
    if all_failed > 0:
        report.append("  • Update failed extractor domains")
    report.append("  • Monitor domain status regularly")
    report.append("  • Consider implementing domain rotation for failed extractors")
    
    report.append("")
    report.append("=" * 80)
    
    return "\n".join(report)

def main():
    """Main execution function."""
    print("🚀 Starting IVA4U Provider and Extractor Analysis...")
    print("=" * 60)
    
    provider_results = []
    extractor_results = []
    
    # Test providers
    print("\n🏢 TESTING PROVIDERS...")
    for name, config in PROVIDERS.items():
        result = test_provider_domains(name, config)
        provider_results.append(result)
        time.sleep(1)  # Be nice to servers
    
    # Test extractors with threading for faster execution
    print("\n🔧 TESTING EXTRACTORS...")
    with ThreadPoolExecutor(max_workers=5) as executor:
        future_to_extractor = {
            executor.submit(test_extractor_domains, name, urls): name 
            for name, urls in EXTRACTORS.items()
        }
        
        for future in as_completed(future_to_extractor):
            result = future.result()
            extractor_results.append(result)
    
    # Sort results by name
    provider_results.sort(key=lambda x: x['provider'])
    extractor_results.sort(key=lambda x: x['extractor'])
    
    # Generate and display report
    report = generate_report(provider_results, extractor_results)
    print("\n" + report)
    
    # Save report to file
    report_file = "provider_status_report.txt"
    try:
        with open(report_file, 'w', encoding='utf-8') as f:
            f.write(report)
        print(f"\n💾 Report saved to: {report_file}")
    except Exception as e:
        print(f"\n❌ Failed to save report: {e}")
    
    # Export JSON data for programmatic use
    json_data = {
        'timestamp': time.time(),
        'providers': provider_results,
        'extractors': extractor_results
    }
    
    try:
        with open('provider_status.json', 'w', encoding='utf-8') as f:
            json.dump(json_data, f, indent=2, ensure_ascii=False)
        print(f"📊 JSON data saved to: provider_status.json")
    except Exception as e:
        print(f"❌ Failed to save JSON: {e}")

if __name__ == "__main__":
    main()
