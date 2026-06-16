const fs = require('fs');
const html = fs.readFileSync('html_dump_2.txt', 'utf8');
const cheerio = require('cheerio');
const $ = cheerio.load(html);

console.log("Checking ul:");
$('ul').each((i, el) => {
    if ($(el).find('li.dooplay_player_option').length > 0) {
        console.log(`UL id: ${$(el).attr('id')}, class: ${$(el).attr('class')}`);
    }
});
