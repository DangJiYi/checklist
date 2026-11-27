#!/usr/bin/env node
/**
 * 极简 CSS 结构检查：括号平衡、未闭合的 @media、可疑的重复属性块。
 * 不追求成为解析器，只用来拦住“少一个 } 导致后面全部失效”这类致命错误——
 * 那种错误在浏览器里完全不报错，只是样式默默地和预期不一样。
 *
 * 用法: node tools/csscheck.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'web', 'app.css');
const src = fs.readFileSync(file, 'utf8');

let fail = 0;
function bad(msg) {
  fail++;
  console.log('\x1b[31mFAIL\x1b[0m ' + msg);
}
function ok(msg) {
  console.log('\x1b[32mPASS\x1b[0m ' + msg);
}

// 去掉注释再数括号，避免注释里的花括号干扰
const stripped = src.replace(/\/\*[\s\S]*?\*\//g, '');

let depth = 0;
let line = 1;
let minDepth = 0;
for (let i = 0; i < stripped.length; i++) {
  const c = stripped[i];
  if (c === '\n') line++;
  if (c === '{') depth++;
  if (c === '}') {
    depth--;
    if (depth < minDepth) minDepth = depth;
    if (depth < 0) {
      bad('第 ' + line + ' 行出现多余的 }');
      break;
    }
  }
}

if (depth === 0) ok('花括号平衡');
else bad('花括号不平衡：结束时还剩 ' + depth + ' 个未闭合（多行 @media 常见成因）');

// @media 是否都有块
const mediaOpens = (stripped.match(/@media[^{]*\{/g) || []).length;
if (mediaOpens > 0) ok('@media 块数：' + mediaOpens);

// 是否残留模板占位或明显笔误
if (/\+\s*\+/.test(stripped)) bad('存在连续的 + +，多半是拼接时的手误');
if (/NaN/.test(src)) bad('样式里出现 NaN');

// 关键规则必须存在
const required = [
  ['html, body', '基础背景'],
  ['max-width: 899px', '窄屏媒体查询'],
  ['\\.col \\{', '窄屏内容留边'],
  ['\\.sheet \\{', '卡片样式'],
  ['button\\.sheet-head', '可点击标题'],
  // 双栏网格必须包在宽屏媒体查询里。写在媒体查询外面就是手机上横向溢出的根因。
  ['@media \\(min-width: 900px\\)[^{]*\\{[^}]*\\.wrap\\.has-side', '双栏网格仅在宽屏生效']
];
required.forEach(function (r) {
  if (new RegExp(r[0]).test(stripped)) ok('存在规则：' + r[1]);
  else bad('缺少规则：' + r[1] + '（匹配 ' + r[0] + '）');
});

console.log('\n' + (fail === 0 ? 'CSS 检查通过' : 'CSS 检查失败 ' + fail + ' 项'));
process.exit(fail === 0 ? 0 : 1);
