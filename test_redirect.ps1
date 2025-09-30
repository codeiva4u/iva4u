# Test redirect flow for igx.gtxgamer.site
$ErrorActionPreference = "Stop"

# Sample redirect URL from the page
$redirectUrl = "https://igx.gtxgamer.site/?m=N2JIVjZKSXAzQzI0amgzZ2RBWjNUZz09&i=VXB2aWZHTytLSUlWcnVhcXRPS050dz09&t=1759256976&si=50625e6174d8403d99bbd1d254504824&xd=eGxxbjZtREtTUURCT0FYNVlxQXNCUT09"

Write-Host "Testing redirect URL: $redirectUrl" -ForegroundColor Cyan

try {
    # Follow redirects and get final URL
    $response = Invoke-WebRequest -Uri $redirectUrl -MaximumRedirection 10 -UseBasicParsing -UserAgent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    
    Write-Host "`nFinal URL: $($response.BaseResponse.ResponseUri.AbsoluteUri)" -ForegroundColor Green
    Write-Host "`nResponse Status: $($response.StatusCode)" -ForegroundColor Yellow
    
    # Check if it's an HTML page with links or a direct video host
    if ($response.Content -match 'vidhide|streamwish|gdtot|filepress|hubcloud|gofile') {
        Write-Host "`nFound video host in content!" -ForegroundColor Green
        
        # Try to extract actual video host link
        if ($response.Content -match 'href="(https?://[^"]+)"') {
            Write-Host "Extracted link: $($Matches[1])" -ForegroundColor Cyan
        }
    }
    
} catch {
    Write-Host "`nError: $_" -ForegroundColor Red
    Write-Host "`nException: $($_.Exception.Message)" -ForegroundColor Red
}
