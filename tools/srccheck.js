#!/usr/bin/env node
/**
 * 源码体检。
 *
 * 主要防的是 U+FFFD（替换字符）：我写中文时偶发把字写坏，
 * 而坏掉的字节在编辑器里只显示成一个菱形问号，很容易漏过去，
 * 一路跟着提交到 CI 甚至提交到 APK 里。
 *
 * 另外检查资源 XML 有没有明显的结构问题（标签未闭合、缺少 XML 头），
 * 因为这类错误同样只在打包时才报，本地改完看不出来。
 *
 * 用法: node tools/srccheck.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const REPLACEMENT = 0xFFFD;

let fail = 0;
function bad(msg) {
  fail++;
  console.log('\x1b[31mFAIL\x1b[0m ' + msg);
}
function ok(msg) {
  console.log('\x1b[32mPASS\x1b[0m ' + msg);
}

// 需要扫描的文本类型
const TEXT_EXT = ['.kt', '.xml', '.js', '.css', '.html', '.json', '.md', '.yml', '.yaml', '.sh', '.py', '.bat', '.kts'];
// 跳过构建产物与版本库内部
const SKIP_DIR = new Set(['build', 'node_modules', 'release', 'captures', '.gradle']);

function walk(dir, out) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (e) {
    return;
  }
  for (const e of entries) {
    if (e.name === '.git' || SKIP_DIR.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
}

const files = [];
['app', 'tools', '.github'].forEach(function (d) {
  const p = path.join(ROOT, d);
  if (fs.existsSync(p)) walk(p, files);
});
['README.md', 'PLAN.md'].forEach(function (f) {
  const p = path.join(ROOT, f);
  if (fs.existsSync(p)) files.push(p);
});

const scanned = files.filter(function (f) {
  const ext = path.extname(f).toLowerCase();
  return TEXT_EXT.indexOf(ext) !== -1 || path.basename(f) === 'push.bat';
});

// ---------------------------------------------------------------- U+FFFD

const dirty = [];
for (const f of scanned) {
  let src;
  try {
    src = fs.readFileSync(f, 'utf8');
  } catch (e) {
    bad('无法按 UTF-8 读取: ' + path.relative(ROOT, f));
    continue;
  }
  if (src.indexOf(String.fromCharCode(REPLACEMENT)) === -1) continue;
  src.split(/\r?\n/).forEach(function (line, i) {
    const at = line.indexOf(String.fromCharCode(REPLACEMENT));
    if (at === -1) return;
    dirty.push(path.relative(ROOT, f) + ':' + (i + 1) + '  …' + line.slice(Math.max(0, at - 12), at + 12) + '…');
  });
}

if (dirty.length === 0) {
  ok('无 U+FFFD（扫描 ' + scanned.length + ' 个文本文件）');
} else {
  dirty.slice(0, 10).forEach(function (d) { bad('替换字符: ' + d); });
  if (dirty.length > 10) bad('…另有 ' + (dirty.length - 10) + ' 处');
}

// ---------------------------------------------------------------- 资源 XML

const xmlFiles = scanned.filter(function (f) { return path.extname(f).toLowerCase() === '.xml'; });

for (const f of xmlFiles) {
  const rel = path.relative(ROOT, f);
  const src = fs.readFileSync(f, 'utf8');

  if (!/^\s*<\?xml\s+version="1\.0"\s+encoding="utf-8"\s*\?>/i.test(src)) {
    // manifest 也要求 XML 头；缺了 aapt 会报错但信息很晦涩
    bad(rel + ' 缺少 <?xml version="1.0" encoding="utf-8"?>');
    continue;
  }

  // 极简标签配对检查：忽略自闭合、注释、声明
  const body = src
    .replace(/<\?[\s\S]*?\?>/g, '')
    .replace(/<!--[\s\S]*?-->/g, '');

  const stack = [];
  const tagRe = /<(\/?)([A-Za-z_][\w.:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>/g;
  let m;
  let broken = false;
  while ((m = tagRe.exec(body)) !== null) {
    const closing = m[1] === '/';
    const name = m[2];
    const selfClosed = m[4] === '/';
    if (closing) {
      const top = stack.pop();
      if (top !== name) {
        bad(rel + ' 标签不配对: </' + name + '> 对应 <' + (top || '无') + '>');
        broken = true;
        break;
      }
    } else if (!selfClosed) {
      stack.push(name);
    }
  }
  if (!broken) {
    if (stack.length) bad(rel + ' 有未闭合标签: <' + stack[stack.length - 1] + '>');
    else ok('XML 结构正常: ' + rel);
  }
}

// ---------------------------------------------------------------- 清单必备项

const manifestPath = path.join(ROOT, 'app/src/main/AndroidManifest.xml');
if (fs.existsSync(manifestPath)) {
  const mf = fs.readFileSync(manifestPath, 'utf8');
  const required = [
    ['android:usesCleartextTraffic="true"', '允许明文 HTTP（WebView 与局域网访问都依赖它）'],
    ['android:icon="@mipmap/ic_launcher"', '应用图标'],
    ['android:roundIcon="@mipmap/ic_launcher_round"', '圆形图标'],
    ['foregroundServiceType="specialUse"', '前台服务类型'],
    ['android:name=".ServerService"', '服务声明']
  ];
  required.forEach(function (r) {
    if (mf.indexOf(r[0]) !== -1) ok('manifest 含: ' + r[1]);
    else bad('manifest 缺少: ' + r[1] + '（' + r[0] + '）');
  });
} else {
  bad('找不到 AndroidManifest.xml');
}

console.log('');
console.log(fail === 0 ? '源码体检通过' : '源码体检失败 ' + fail + ' 项');
process.exit(fail === 0 ? 0 : 1);
