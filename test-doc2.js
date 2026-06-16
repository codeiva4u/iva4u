const fs = require('fs');
const html = fs.readFileSync('html_dump_2.txt', 'utf8');
const cheerio = require('cheerio');
const $ = cheerio.load(html);

$('ul#playeroptionsul li').each((i, el) => {
    console.log(`id: ${$(el).attr('data-post')}, nume: ${$(el).attr('data-nume')}, type: ${$(el).attr('data-type')}, text: ${$(el).text()}`);
});
