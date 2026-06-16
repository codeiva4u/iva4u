const fetch = require('node-fetch');

async function testPost() {
    const params = new URLSearchParams();
    params.append('action', 'doo_player_ajax');
    params.append('post', '164080');
    params.append('nume', '1');
    params.append('type', 'movie');

    const response = await fetch("https://multimovies.makeup/wp-admin/admin-ajax.php", {
        method: 'POST',
        body: params,
        headers: {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "X-Requested-With": "XMLHttpRequest",
            "Referer": "https://multimovies.makeup/movies/haunted-3d-ghosts-of-the-past/",
            "Accept": "*/*"
        }
    });
    
    console.log(await response.text());
}
testPost();
