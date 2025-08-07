#!/usr/bin/env python3
"""
Advanced Provider & Extractor Monitor

This script performs deep analysis of:
1. Provider website structure and selectors
2. Video extraction capabilities 
3. Domain health and response times
4. Content availability and accessibility
5. Auto-fixes for detected issues

Usage: python advanced_monitor.py [--fix] [--deep]
"""

import requests
import re
import json
import time
import os
from datetime import datetime, timedelta
from urllib.parse import urlparse, urljoin
from bs4 import BeautifulSoup
import argparse
from typing import Dict, List, Tuple, Optional
import asyncio
import aiohttp
from concurrent.futures import ThreadPoolExecutor
import logging

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class AdvancedProviderMonitor:
    def __init__(self):
        self.session = requests.Session()
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.5',
            'Accept-Encoding': 'gzip, deflate, br',
            'Connection': 'keep-alive',
            'Upgrade-Insecure-Requests': '1'
        })
        
        self.providers = {
            'HDhub4u': {
                'url': 'https://hdhub4u.build/?utm=mn',
                'selectors': {
                    'movies': '.recent-movies > li.thumb',
                    'title': 'figcaption:nth-child(2) > a:nth-child(1) > p:nth-child(1)',
                    'poster': 'figure:nth-child(1) > img:nth-child(1)',
                    'link': 'figure:nth-child(1) > a:nth-child(2)'
                },
                'test_paths': ['/category/bollywood-movies/', '/category/hollywood-movies/']
            },
            'MoviesDrive': {
                'url': 'https://moviesdrive.channel/',
                'selectors': {
                    'movies': 'li.thumb',
                    'title': 'figcaption a p',
                    'poster': 'figure img',
                    'link': 'figcaption a'
                },
                'test_paths': ['/']
            },
            'Movierulzhd': {
                'url': 'https://1movierulzhd.cv/',
                'selectors': {
                    'movies': 'div.items.normal article, div#archive-content article',
                    'title': 'h3 > a',
                    'poster': 'div.poster img',
                    'quality': 'span.quality'
                },
                'test_paths': ['/movies/', '/genre/hindi-dubbed/']
            }
        }
        
        self.extractors = {
            'VidStack': ["https://vidstack.io", "https://server1.uns.bio"],
            'VidSrcTo': ["https://vidcloud.icu", "https://vidsrc.pm", "https://vidsrc.in", "https://vidsrc.cc", "https://vidsrc.tv", "https://vidsrc.net", "https://2embed.org"],
            'FileMoon': ["https://filemoon.in", "https://kerapoxy.cc"],
            'GDFlix': ["https://new6.gdflix.dad"],
            'Gofile': ['https://gofile.io', 'https://api.gofile.io'],
            'HubCloud': ['https://hubcloud.ink'],
            'Hubstream': ['https://hubstream.art']
        }
        
        self.results = {
            'timestamp': datetime.now().isoformat(),
            'providers': {},
            'extractors': {},
            'issues': [],
            'fixes_applied': []
        }

    def test_provider_selectors(self, provider_name: str, config: Dict) -> Dict:
        """Test if provider CSS selectors are still valid"""
        logger.info(f"🔍 Testing {provider_name} selectors...")
        
        result = {
            'url_accessible': False,
            'selectors_working': {},
            'content_found': False,
            'structure_valid': False,
            'response_time': 0,
            'issues': []
        }
        
        try:
            start_time = time.time()
            response = self.session.get(config['url'], timeout=15)
            result['response_time'] = time.time() - start_time
            
            if response.status_code == 200:
                result['url_accessible'] = True
                soup = BeautifulSoup(response.text, 'html.parser')
                
                # Test each selector
                for selector_name, selector in config.get('selectors', {}).items():
                    try:
                        elements = soup.select(selector)
                        result['selectors_working'][selector_name] = {
                            'working': len(elements) > 0,
                            'count': len(elements),
                            'sample_text': elements[0].get_text(strip=True)[:50] if elements else None
                        }
                        
                        if len(elements) > 0 and selector_name == 'movies':
                            result['content_found'] = True
                            
                    except Exception as e:
                        result['selectors_working'][selector_name] = {
                            'working': False,
                            'error': str(e)
                        }
                        result['issues'].append(f"Selector '{selector_name}' failed: {str(e)}")
                
                # Check overall page structure
                if soup.find('html') and len(soup.find_all(['div', 'article', 'section'])) > 5:
                    result['structure_valid'] = True
                else:
                    result['issues'].append("Page structure appears invalid or minimal")
                    
                # Test additional paths
                working_paths = 0
                total_paths = len(config.get('test_paths', []))
                
                for path in config.get('test_paths', []):
                    try:
                        test_url = urljoin(config['url'], path)
                        test_response = self.session.get(test_url, timeout=10)
                        if test_response.status_code == 200:
                            working_paths += 1
                        time.sleep(1)  # Be respectful
                    except Exception as e:
                        logger.warning(f"Path test failed for {path}: {e}")
                
                result['path_success_rate'] = working_paths / max(total_paths, 1)
                
            else:
                result['issues'].append(f"HTTP {response.status_code} error")
                
        except Exception as e:
            result['issues'].append(f"Connection error: {str(e)}")
            logger.error(f"Provider test failed for {provider_name}: {e}")
            
        return result

    def test_extractor_domains(self, extractor_name: str, domains: List[str]) -> Dict:
        """Test extractor domains and basic functionality"""
        logger.info(f"🔧 Testing {extractor_name} domains...")
        
        result = {
            'working_domains': [],
            'failed_domains': [],
            'best_domain': None,
            'avg_response_time': 0,
            'video_patterns_found': False,
            'api_endpoints_working': False
        }
        
        response_times = []
        
        for domain in domains:
            try:
                start_time = time.time()
                response = self.session.get(domain, timeout=10, allow_redirects=True)
                response_time = time.time() - start_time
                
                if response.status_code == 200:
                    result['working_domains'].append({
                        'domain': domain,
                        'response_time': response_time,
                        'status_code': response.status_code
                    })
                    response_times.append(response_time)
                    
                    # Check for video-related patterns
                    content = response.text.lower()
                    video_indicators = ['video', 'stream', 'player', 'm3u8', 'mp4', 'download']
                    if any(indicator in content for indicator in video_indicators):
                        result['video_patterns_found'] = True
                    
                    # Check for API endpoints (for services like Gofile)
                    if 'api' in domain or '/api/' in content:
                        result['api_endpoints_working'] = True
                        
                else:
                    result['failed_domains'].append({
                        'domain': domain,
                        'status_code': response.status_code,
                        'error': f"HTTP {response.status_code}"
                    })
                    
            except Exception as e:
                result['failed_domains'].append({
                    'domain': domain,
                    'error': str(e)
                })
                
            time.sleep(1)  # Be respectful to servers
            
        # Calculate metrics
        if response_times:
            result['avg_response_time'] = sum(response_times) / len(response_times)
            result['best_domain'] = min(result['working_domains'], 
                                      key=lambda x: x['response_time'])['domain']
        
        return result

    def test_video_extraction_patterns(self) -> Dict:
        """Test common video extraction patterns and regex"""
        logger.info("🎥 Testing video extraction patterns...")
        
        patterns = {
            'filemoon_url': r'(?:https?://)?(?:www\.)?filemoon\.(?:to|in|sx)/[a-zA-Z0-9]+',
            'm3u8_links': r'https?://[^\s]+\.m3u8',
            'mp4_links': r'https?://[^\s]+\.mp4',
            'streaming_domains': r'(?:vidstack|vidsrc|gofile|gdflix|hubstream)\.(?:io|to|dad|art|ink)',
            'embed_patterns': r'/(?:embed|e|v)/[a-zA-Z0-9]+',
            'api_patterns': r'/api/v\d+/[a-zA-Z0-9/]+'
        }
        
        results = {}
        for pattern_name, pattern in patterns.items():
            try:
                # Test pattern compilation
                compiled = re.compile(pattern)
                
                # Test with sample URLs
                test_urls = [
                    'https://filemoon.to/e/abc123',
                    'https://example.com/stream.m3u8',
                    'https://cdn.example.com/video.mp4',
                    'https://vidstack.io/embed/xyz789',
                    'https://api.gofile.io/getServer'
                ]
                
                matches = []
                for url in test_urls:
                    if compiled.search(url):
                        matches.append(url)
                
                results[pattern_name] = {
                    'pattern_valid': True,
                    'test_matches': len(matches),
                    'sample_matches': matches[:3]
                }
                
            except re.error as e:
                results[pattern_name] = {
                    'pattern_valid': False,
                    'error': str(e)
                }
        
        return results

    def check_kotlin_files_for_issues(self) -> Dict:
        """Check Kotlin files for common issues and outdated domains"""
        logger.info("📋 Checking Kotlin files for issues...")
        
        issues = []
        outdated_domains = []
        
        # Known problematic domains to check for
        problematic_domains = [
            'multimovies.coupons',  # Known to be down
            'vidsrc2.to',          # Timing out
            'molop.art',           # Not responding
            'new10.gdflix.dad',    # Outdated GDFlix domain
            'vidhidepro.com',      # Server overload
            'filemoon.sx'          # Timeout issues
        ]
        
        kotlin_files = []
        for root, dirs, files in os.walk('.'):
            for file in files:
                if file.endswith('.kt'):
                    kotlin_files.append(os.path.join(root, file))
        
        for file_path in kotlin_files:
            try:
                with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
                    content = f.read()
                    
                # Check for problematic domains
                for domain in problematic_domains:
                    if domain in content:
                        outdated_domains.append({
                            'file': file_path,
                            'domain': domain,
                            'line_count': content.count(domain)
                        })
                
                # Check for common issues
                if 'TODO' in content or 'FIXME' in content:
                    todo_count = content.count('TODO') + content.count('FIXME')
                    issues.append({
                        'file': file_path,
                        'type': 'TODO/FIXME',
                        'count': todo_count
                    })
                
                # Check for error handling
                if 'try {' in content and 'catch' not in content:
                    issues.append({
                        'file': file_path,
                        'type': 'Missing error handling',
                        'description': 'Try block without catch'
                    })
                    
            except Exception as e:
                logger.warning(f"Could not analyze {file_path}: {e}")
        
        return {
            'outdated_domains': outdated_domains,
            'code_issues': issues,
            'total_kotlin_files': len(kotlin_files)
        }

    def auto_fix_issues(self, dry_run: bool = False) -> Dict:
        """Automatically fix detected issues"""
        logger.info(f"🔧 {'Simulating' if dry_run else 'Applying'} auto-fixes...")
        
        fixes = {
            'domain_updates': [],
            'selector_fixes': [],
            'code_improvements': []
        }
        
        # Domain fixes
        domain_replacements = {
            'multimovies.coupons': 'multimovies.buzz',
            'vidsrc2.to': 'vidsrc.to',
            'molop.art': 'cdnmovies.net',
            'new10.gdflix.dad': 'new6.gdflix.dad',
            'vidhidepro.com': 'filelions.live',
            'filemoon.sx': 'filemoon.in'
        }
        
        if not dry_run:
            from fix_domains import update_domain_in_file
            
            kotlin_files = []
            for root, dirs, files in os.walk('.'):
                for file in files:
                    if file.endswith('.kt'):
                        kotlin_files.append(os.path.join(root, file))
            
            for old_domain, new_domain in domain_replacements.items():
                for file_path in kotlin_files:
                    if update_domain_in_file(file_path, old_domain, new_domain):
                        fixes['domain_updates'].append({
                            'file': file_path,
                            'old_domain': old_domain,
                            'new_domain': new_domain
                        })
        
        return fixes

    def generate_health_report(self) -> Dict:
        """Generate comprehensive health report"""
        total_providers = len(self.providers)
        working_providers = sum(1 for result in self.results['providers'].values() 
                               if result.get('url_accessible', False))
        
        total_extractors = len(self.extractors)
        working_extractors = sum(1 for result in self.results['extractors'].values()
                                if len(result.get('working_domains', [])) > 0)
        
        # Calculate domain health
        total_domains = sum(len(domains) for domains in self.extractors.values())
        working_domains = sum(len(result.get('working_domains', [])) 
                             for result in self.results['extractors'].values())
        
        health_percentage = (working_domains / max(total_domains, 1)) * 100
        
        return {
            'overall_health': health_percentage,
            'provider_success_rate': (working_providers / max(total_providers, 1)) * 100,
            'extractor_success_rate': (working_extractors / max(total_extractors, 1)) * 100,
            'working_providers': working_providers,
            'total_providers': total_providers,
            'working_extractors': working_extractors,
            'total_extractors': total_extractors,
            'working_domains': working_domains,
            'total_domains': total_domains,
            'critical_issues': len([issue for issue in self.results['issues'] 
                                  if 'critical' in issue.get('severity', '').lower()]),
            'recommendations': self.generate_recommendations()
        }

    def generate_recommendations(self) -> List[str]:
        """Generate recommendations based on analysis"""
        recommendations = []
        
        # Calculate basic health metrics without calling generate_health_report
        total_providers = len(self.providers)
        working_providers = sum(1 for result in self.results['providers'].values() 
                               if result.get('url_accessible', False))
        
        total_extractors = len(self.extractors)
        working_extractors = sum(1 for result in self.results['extractors'].values()
                                if len(result.get('working_domains', [])) > 0)
        
        # Calculate domain health
        total_domains = sum(len(domains) for domains in self.extractors.values())
        working_domains = sum(len(result.get('working_domains', [])) 
                             for result in self.results['extractors'].values())
        
        overall_health = (working_domains / max(total_domains, 1)) * 100
        provider_success_rate = (working_providers / max(total_providers, 1)) * 100
        extractor_success_rate = (working_extractors / max(total_extractors, 1)) * 100
        
        if overall_health < 70:
            recommendations.append("🚨 Overall health is below 70% - immediate action required")
            
        if provider_success_rate < 75:
            recommendations.append("🔍 Multiple providers are down - check for new domains")
            
        if extractor_success_rate < 60:
            recommendations.append("🛠️ Many extractors failing - update domain lists")
            
        # Check for specific issues
        for provider_name, result in self.results['providers'].items():
            if not result.get('url_accessible', False):
                recommendations.append(f"❌ {provider_name} is completely inaccessible - find alternative domain")
            elif result.get('path_success_rate', 0) < 0.5:
                recommendations.append(f"⚠️ {provider_name} has broken paths - check URL structure")
                
        return recommendations

    async def run_monitoring(self, deep_analysis: bool = False, auto_fix: bool = False):
        """Run complete monitoring suite"""
        logger.info("🚀 Starting advanced provider monitoring...")
        
        # Test providers
        for provider_name, config in self.providers.items():
            self.results['providers'][provider_name] = self.test_provider_selectors(provider_name, config)
            
        # Test extractors
        for extractor_name, domains in self.extractors.items():
            self.results['extractors'][extractor_name] = self.test_extractor_domains(extractor_name, domains)
            
        # Test video extraction patterns
        if deep_analysis:
            self.results['video_patterns'] = self.test_video_extraction_patterns()
            self.results['code_analysis'] = self.check_kotlin_files_for_issues()
            
        # Generate health report
        self.results['health_report'] = self.generate_health_report()
        
        # Auto-fix if requested
        if auto_fix:
            self.results['fixes'] = self.auto_fix_issues(dry_run=False)
        else:
            self.results['fixes'] = self.auto_fix_issues(dry_run=True)
            
        # Save results
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        with open(f'advanced_monitor_report_{timestamp}.json', 'w') as f:
            json.dump(self.results, f, indent=2, default=str)
            
        # Print summary
        self.print_summary()
        
        return self.results

    def print_summary(self):
        """Print monitoring summary"""
        health = self.results['health_report']
        
        print("\n" + "="*80)
        print("🎬 ADVANCED PROVIDER MONITORING SUMMARY")
        print("="*80)
        print(f"⏰ Timestamp: {self.results['timestamp']}")
        print(f"📊 Overall Health: {health['overall_health']:.1f}%")
        print(f"✅ Working Providers: {health['working_providers']}/{health['total_providers']}")
        print(f"🔧 Working Extractors: {health['working_extractors']}/{health['total_extractors']}")
        print(f"🌐 Working Domains: {health['working_domains']}/{health['total_domains']}")
        
        if health['recommendations']:
            print(f"\n💡 RECOMMENDATIONS:")
            for rec in health['recommendations']:
                print(f"  {rec}")
        
        print(f"\n📁 Detailed report saved to: advanced_monitor_report_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json")

def main():
    parser = argparse.ArgumentParser(description='Advanced Provider & Extractor Monitor')
    parser.add_argument('--fix', action='store_true', help='Apply auto-fixes for detected issues')
    parser.add_argument('--deep', action='store_true', help='Perform deep analysis including code review')
    args = parser.parse_args()
    
    monitor = AdvancedProviderMonitor()
    
    # Run monitoring
    asyncio.run(monitor.run_monitoring(deep_analysis=args.deep, auto_fix=args.fix))

if __name__ == "__main__":
    main()
