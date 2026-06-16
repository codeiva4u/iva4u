const fetch = require('node-fetch');
const fs = require('fs');

async function scrape() {
    const response = await fetch("https://multimovies.makeup/movies/haunted-3d-ghosts-of-the-past/", {
        headers: {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9"
        }
    });
    const html = await response.text();
    fs.writeFileSync('html_dump_2.txt', html);
    
    const idx = html.indexOf('GDMIRROR');
    if (idx !== -1) {
        console.log(html.substring(Math.max(0, idx - 200), idx + 300));
    } else {
        console.log("GDMIRROR not found in fetch dump");
    }
}
scrape();
