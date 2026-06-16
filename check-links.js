const fs = require('fs');
const cheerio = require('cheerio');

const html = fs.readFileSync('html_dump.txt', 'utf8');
const $ = cheerio.load(html);

console.log("Checking ul#playeroptionsul li:");
const playerOptions = $('ul#playeroptionsul li');
console.log("Count:", playerOptions.length);
playerOptions.each((i, el) => {
    console.log(`- post: ${$(el).attr('data-post')}, nume: ${$(el).attr('data-nume')}, type: ${$(el).attr('data-type')}`);
});

console.log("\nChecking .dooplay-player-option:");
const options = $('.dooplay-player-option');
console.log("Count:", options.length);

console.log("\nChecking all data-post attributes:");
const dataPosts = $('[data-post]');
console.log("Count:", dataPosts.length);
dataPosts.each((i, el) => {
    console.log(`- tag: ${el.name}, id: ${$(el).attr('id')}, class: ${$(el).attr('class')}, data-post: ${$(el).attr('data-post')}`);
});
