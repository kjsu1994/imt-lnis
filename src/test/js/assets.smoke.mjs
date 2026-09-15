import assert from 'node:assert/strict';
import {readFileSync,readdirSync,existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../../main/resources/static/',import.meta.url));
const assets=path.join(root,'assets');
function walk(dir) {return readdirSync(dir,{withFileTypes:true}).flatMap(entry => entry.isDirectory()?walk(path.join(dir,entry.name)):[path.join(dir,entry.name)]);}
for(const file of walk(root)) {
  if(!/\.(js|html|css)$/.test(file))continue;
  const source=readFileSync(file,'utf8');
  for(const [,url] of source.matchAll(/(?:src|href)="(\/lnis\/assets\/[^"]+)"/g)) {
    assert.ok(existsSync(path.join(assets,url.replace('/lnis/assets/','').split('?')[0])),file+' missing '+url);
  }
  for(const [,relative] of source.matchAll(/(?:from|import)\s*['"]([^'"]+)['"]/g)) {
    if(!relative.startsWith('.'))continue;
    assert.ok(existsSync(path.resolve(path.dirname(file),relative.split('?')[0])),file+' missing '+relative);
  }
}
console.log('PASS: all HTML asset links and ES module imports resolve after folder relocation');
