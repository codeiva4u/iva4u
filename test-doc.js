const fs = require('fs');
const html = fs.readFileSync('html_dump_2.txt', 'utf8');
const cheerio = require('cheerio');
const $ = cheerio.load(html);

console.log($('ul#playeroptionsul li').length);
