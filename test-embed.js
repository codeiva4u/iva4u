const fetch = require('node-fetch');
async function testEmbed() {
    const response = await fetch("https://gdmirrorbot.nl/embed/qrq3m22", {
        headers: {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Referer": "https://multimovies.makeup/movies/haunted-3d-ghosts-of-the-past/",
        }
    });
    console.log(await response.text());
}
testEmbed();
