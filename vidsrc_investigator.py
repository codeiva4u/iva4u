#!/usr/bin/env python3
"""
VidSrc Domain Investigator & Alternative Finder

This script investigates VidSrc connectivity issues and finds working alternatives.
"""

import requests
import time
import json
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

class VidSrcInvestigator:
    def __init__(self):
        self.session = requests.Session()
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.5',
            'Accept-Encoding': 'gzip, deflate, br',
            'Connection': 'keep-alive'
        })
        
        # Known VidSrc alternative domains and mirrors
        self.potential_domains = [
            'https://vidsrc.to',
            'https://vidsrc.me',
            'https://vidsrc.net',
            'https://vidsrc.cc',
            'https://vidsrc.xyz',
            'https://vidsrc.pro',
            'https://vidsrc1.to',
            'https://vidsrc2.to', 
            'https://vidsrc3.to',
            'https://vidsrc4.to',
            'https://vidsrc5.to',
            'https://vidsrc.tv',
            'https://vidsrc.in',
            'https://vidsrc.org',
            'https://vidsrc.stream',
            'https://www.vidsrc.to',
            'https://embed.vidsrc.to',
            'https://api.vidsrc.to',
            'https://vidsrc.pm',
            'https://vidsrc.nl',
            'https://vidsrc.icu',
            'https://vidsrc.run',
            # Alternative similar services
            'https://vidembed.cc',
            'https://vidembed.io', 
            'https://embedplex.com',
            'https://vidcloud.icu',
            'https://2embed.to',
            'https://www.2embed.to'
        ]
        
    def test_domain(self, domain, timeout=15):
        """Test a single domain for connectivity and functionality"""
        result = {
            'domain': domain,
            'working': False,
            'response_time': 0,
            'status_code': None,
            'error': None,
            'has_embed_support': False,
            'has_api': False,
            'content_indicators': []
        }
        
        try:
            start_time = time.time()
            response = self.session.get(domain, timeout=timeout, allow_redirects=True)
            result['response_time'] = time.time() - start_time
            result['status_code'] = response.status_code
            
            if response.status_code == 200:
                result['working'] = True
                content = response.text.lower()
                
                # Check for video streaming indicators
                indicators = ['embed', 'stream', 'player', 'video', 'movie', 'watch', 'vidsrc']
                result['content_indicators'] = [ind for ind in indicators if ind in content]
                
                # Check for embed support
                if any(word in content for word in ['embed', 'iframe', 'player']):
                    result['has_embed_support'] = True
                    
                # Check for API endpoints
                if any(word in content for word in ['api', 'json', 'endpoint']):
                    result['has_api'] = True
                    
            else:
                result['error'] = f"HTTP {response.status_code}"
                
        except requests.exceptions.Timeout:
            result['error'] = "Connection timeout"
        except requests.exceptions.ConnectionError as e:
            result['error'] = f"Connection error: {str(e)}"
        except Exception as e:
            result['error'] = f"Unexpected error: {str(e)}"
            
        return result
    
    def test_all_domains(self, max_workers=10):
        """Test all potential domains concurrently"""
        print(f"🔍 Testing {len(self.potential_domains)} VidSrc alternative domains...")
        
        results = []
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_domain = {executor.submit(self.test_domain, domain): domain 
                              for domain in self.potential_domains}
            
            for future in as_completed(future_to_domain):
                domain = future_to_domain[future]
                try:
                    result = future.result()
                    results.append(result)
                    
                    # Print progress
                    status = "✅" if result['working'] else "❌"
                    print(f"{status} {domain} - {result.get('error', 'OK')}")
                    
                except Exception as e:
                    print(f"❌ {domain} - Exception: {e}")
                    
        return results
    
    def analyze_results(self, results):
        """Analyze test results and provide recommendations"""
        working_domains = [r for r in results if r['working']]
        failed_domains = [r for r in results if not r['working']]
        
        # Sort by response time (fastest first)
        working_domains.sort(key=lambda x: x['response_time'])
        
        analysis = {
            'timestamp': datetime.now().isoformat(),
            'total_tested': len(results),
            'working_count': len(working_domains),
            'failed_count': len(failed_domains),
            'success_rate': (len(working_domains) / len(results)) * 100,
            'working_domains': working_domains,
            'failed_domains': failed_domains,
            'recommendations': []
        }
        
        # Generate recommendations
        if working_domains:
            best_domain = working_domains[0]
            analysis['recommendations'].append({
                'type': 'primary_replacement',
                'domain': best_domain['domain'],
                'reason': f"Fastest response time: {best_domain['response_time']:.2f}s"
            })
            
            # Recommend backup domains
            if len(working_domains) > 1:
                backup_domains = working_domains[1:4]  # Top 3 backups
                analysis['recommendations'].append({
                    'type': 'backup_domains',
                    'domains': [d['domain'] for d in backup_domains],
                    'reason': "For redundancy and failover"
                })
                
            # Recommend embed-capable domains
            embed_domains = [d for d in working_domains if d['has_embed_support']]
            if embed_domains:
                analysis['recommendations'].append({
                    'type': 'embed_capable',
                    'domains': [d['domain'] for d in embed_domains[:3]],
                    'reason': "Support video embedding"
                })
        else:
            analysis['recommendations'].append({
                'type': 'alternative_service',
                'reason': "No VidSrc domains working - consider alternative services",
                'suggestions': ['vidembed.cc', '2embed.to', 'embedplex.com']
            })
            
        return analysis
    
    def update_kotlin_files(self, new_domain, backup_domains=None):
        """Update Kotlin files with new working domains"""
        import os
        import re
        
        updates_made = []
        kotlin_files = []
        
        # Find all Kotlin files
        for root, dirs, files in os.walk('.'):
            for file in files:
                if file.endswith('.kt'):
                    kotlin_files.append(os.path.join(root, file))
        
        for file_path in kotlin_files:
            try:
                with open(file_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                
                original_content = content
                
                # Replace vidsrc.to references
                content = re.sub(r'https?://vidsrc\.to', new_domain.rstrip('/'), content)
                content = re.sub(r'"vidsrc\.to"', f'"{new_domain.replace("https://", "").replace("http://", "")}"', content)
                
                # Add backup domains if specified
                if backup_domains and 'vidsrc' in content.lower():
                    # Look for domain arrays or lists
                    if 'listOf(' in content and 'vidsrc' in content.lower():
                        for backup in backup_domains[:2]:  # Add top 2 backups
                            backup_clean = backup.replace('https://', '').replace('http://', '')
                            if backup_clean not in content:
                                content = re.sub(
                                    r'(listOf\([^)]*vidsrc[^)]*)',
                                    rf'\1, "{backup_clean}"',
                                    content
                                )
                
                if content != original_content:
                    with open(file_path, 'w', encoding='utf-8') as f:
                        f.write(content)
                    updates_made.append(file_path)
                    print(f"✅ Updated {file_path}")
                    
            except Exception as e:
                print(f"⚠️ Error updating {file_path}: {e}")
                
        return updates_made
    
    def run_investigation(self):
        """Run complete VidSrc investigation"""
        print("🚀 Starting VidSrc Domain Investigation...")
        print("="*80)
        
        # Test all domains
        results = self.test_all_domains()
        
        # Analyze results
        analysis = self.analyze_results(results)
        
        # Save detailed report
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        report_file = f'vidsrc_investigation_{timestamp}.json'
        
        with open(report_file, 'w') as f:
            json.dump(analysis, f, indent=2, default=str)
        
        # Print summary
        print("\n" + "="*80)
        print("📊 VIDSRC INVESTIGATION RESULTS")
        print("="*80)
        print(f"📈 Success Rate: {analysis['success_rate']:.1f}%")
        print(f"✅ Working Domains: {analysis['working_count']}/{analysis['total_tested']}")
        
        if analysis['working_domains']:
            print(f"\n🏆 BEST ALTERNATIVE: {analysis['working_domains'][0]['domain']}")
            print(f"⚡ Response Time: {analysis['working_domains'][0]['response_time']:.2f}s")
            
            if len(analysis['working_domains']) > 1:
                print(f"\n🔄 BACKUP OPTIONS:")
                for domain in analysis['working_domains'][1:4]:
                    print(f"   • {domain['domain']} ({domain['response_time']:.2f}s)")
        
        print(f"\n📁 Detailed report: {report_file}")
        
        return analysis

def main():
    investigator = VidSrcInvestigator()
    analysis = investigator.run_investigation()
    
    # If we found working alternatives, offer to update files
    if analysis['working_domains']:
        print("\n🔧 APPLYING AUTOMATIC FIXES...")
        best_domain = analysis['working_domains'][0]['domain']
        backup_domains = [d['domain'] for d in analysis['working_domains'][1:3]]
        
        updated_files = investigator.update_kotlin_files(best_domain, backup_domains)
        
        if updated_files:
            print(f"✅ Updated {len(updated_files)} Kotlin files with new VidSrc domain")
        else:
            print("ℹ️ No Kotlin files needed updating")

if __name__ == "__main__":
    main()
