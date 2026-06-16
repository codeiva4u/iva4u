const fs = require('fs');
const html = fs.readFileSync('html_dump.txt', 'utf8');
const idx = html.indexOf('GDMIRROR');
if (idx !== -1) {
    console.log(html.substring(Math.max(0, idx - 200), idx + 300));
} else {
    console.log("GDMIRROR not found");
}
