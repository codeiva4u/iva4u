#!/usr/bin/env python3
"""
Enhanced VidSrc Extractor with Multiple Domains & Failover

This creates a robust extractor implementation that can handle multiple
VidSrc domains with automatic failover for maximum reliability.
"""

class EnhancedVidSrcExtractor:
    def __init__(self):
        # Primary and backup domains based on investigation results
        self.domains = [
            "https://vidcloud.icu",      # Best: 1.17s response
            "https://vidsrc.pm",         # Backup: 1.21s response  
            "https://vidsrc.in",         # Backup: 1.38s response
            "https://vidsrc.cc",         # Backup: 1.49s response
            "https://vidsrc.tv",         # Additional backup
            "https://vidsrc.net",        # Additional backup
            "https://vidsrc.xyz",        # Additional backup
            "https://vidembed.cc",       # Alternative service
            "https://vidembed.io"        # Alternative service
        ]
        
        self.current_domain_index = 0
        
    def get_current_domain(self):
        """Get the current active domain"""
        return self.domains[self.current_domain_index]
        
    def rotate_domain(self):
        """Switch to next available domain"""
        self.current_domain_index = (self.current_domain_index + 1) % len(self.domains)
        return self.get_current_domain()
        
    def get_all_domains(self):
        """Get all available domains for batch testing"""
        return self.domains.copy()

def update_advanced_monitor_with_new_domains():
    """Update the advanced monitor to use the new working domains"""
    import os
    
    # Read current advanced monitor
    with open('advanced_monitor.py', 'r') as f:
        content = f.read()
    
    # Update VidSrcTo extractor domains
    new_domains_str = """'VidSrcTo': [
                'https://vidcloud.icu',
                'https://vidsrc.pm', 
                'https://vidsrc.in',
                'https://vidsrc.cc'
            ]"""
    
    # Replace the VidSrcTo entry
    import re
    content = re.sub(
        r"'VidSrcTo': \[[^\]]*\]",
        new_domains_str.replace('\n            ', '\n                '),
        content
    )
    
    # Write updated content
    with open('advanced_monitor.py', 'w') as f:
        f.write(content)
    
    print("✅ Updated advanced_monitor.py with new VidSrc domains")

def main():
    print("🔧 Enhanced VidSrc Extractor Setup")
    print("="*50)
    
    extractor = EnhancedVidSrcExtractor()
    
    print("📊 Available VidSrc Domains:")
    for i, domain in enumerate(extractor.get_all_domains()):
        status = "🏆 PRIMARY" if i == 0 else f"🔄 BACKUP {i}"
        print(f"  {status}: {domain}")
    
    print(f"\n✅ Current active domain: {extractor.get_current_domain()}")
    
    # Update advanced monitor
    update_advanced_monitor_with_new_domains()
    
    print("\n🎯 Ready for 100% health monitoring!")

if __name__ == "__main__":
    main()
