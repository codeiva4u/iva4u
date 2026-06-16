const fetch = require('node-fetch');
async function testFile() {
    const response = await fetch("https://gdmirrorbot.nl/file/qrq3m22", {
        headers: {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Referer": "https://gdmirrorbot.nl/embed/qrq3m22"
        }
    });
    console.log(response.status);
    console.log(await response.text());
}
testFile();
