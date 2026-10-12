// Read-only reference rendering. Does not start the Web prototype or request audio.
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.argv[3] || 'playwright');

const design = process.argv[2];
if (!design) throw new Error('Usage: node tools/render-design-reference.cjs <design-directory> [playwright-package]');
const css = fs.readFileSync(path.join(design, 'src/index.css'), 'utf8').replace("@import 'tailwindcss';", '');
const output = path.resolve(__dirname, '../automotive/build/reports/design-reference');
fs.mkdirSync(output, {recursive: true});
const iconPaths = {
  mic: '<rect x="9" y="3" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8"/>',
  muted: '<path d="M9 9v3a3 3 0 0 0 5 2M15 9V6a3 3 0 0 0-5-2M5 10v2a7 7 0 0 0 12 5M19 10v2M12 19v3M8 22h8M3 3l18 18"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  more: '<circle cx="5" cy="12" r=".9"/><circle cx="12" cy="12" r=".9"/><circle cx="19" cy="12" r=".9"/>',
  system: '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M12 17v4M8 21h8"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M5 19l1.5-1.5M17.5 6.5l1.5-1.5"/>',
  moon: '<path d="M20 14A8 8 0 0 1 10 4a8 8 0 1 0 10 10Z"/>',
};
const icon = name => `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round">${iconPaths[name]}</svg>`;
const button = (classes, content) => `<button class="button ${classes}">${content}</button>`;

function settings() {
  return `<div class="scrim"></div><aside class="settings-panel">
    <div class="settings-header"><h2>设置</h2>${button('toolbar-button', icon('close'))}</div>
    <div class="settings-section"><div class="row-label">外观</div><div class="appearance-options">${[['system', '跟随系统'], ['sun', '浅色'], ['moon', '深色']].map(([id, label]) => button(`appearance-option ${id === 'system' ? 'selected' : ''}`, `${icon(id)}<span>${label}</span>`)).join('')}</div></div>
    <div class="settings-section settings-row"><div><div class="row-label">显示对话文字</div><div class="row-detail">同步显示你和我的对话</div></div>${button('toggle on', '<span></span>')}</div>
    <div class="settings-section"><div class="row-label">语音播报速度</div><div class="segmented">${['慢', '标准', '快'].map(label => button(label === '标准' ? 'selected' : '', label)).join('')}</div></div>
    <div class="settings-section settings-row"><div><div class="row-label">麦克风权限</div><div class="row-detail"><span class="permission-dot granted"></span>已开启</div></div></div>
    <div class="settings-footnote">让对话保持简单。</div></aside>`;
}

function screen(name, theme) {
  const onboarding = ['onboarding', 'permission'].includes(name);
  const paused = ['paused', 'ended', 'settings'].includes(name);
  const status = {ready: '可以开始说话', listening: '我在听', thinking: '想一想…', speaking: '正在回答，可以直接说话打断', hidden: '正在回答，可以直接说话打断', paused: '麦克风已暂停', ended: '对话已结束', settings: '麦克风已暂停', network: '网络连接中断，正在尝试恢复'};
  const wave = ['listening', 'speaking', 'hidden'].includes(name);
  const caption = name !== 'hidden';
  const conversation = name === 'ready'
    ? '<div class="empty-prompt">请说话<span>我在这里，听你说。</span></div>'
    : name === 'listening' ? '<div class="message user live">今天有点累，想和你聊聊…<span class="transcript-caret"></span></div>'
    : '<div class="message user">今天有点累，想和你聊聊。</div>' + (name === 'thinking' ? '' : '<div class="message assistant">我在。今天发生了什么，\n让你觉得累？</div>');
  const body = onboarding
    ? `<section class="onboarding"><div class="onboard-symbol">${icon(name === 'permission' ? 'muted' : 'mic')}</div><div class="eyebrow">轻声 · 陪你聊聊</div><h1>${name === 'permission' ? '需要麦克风才能聊天' : '开口，聊一聊'}</h1><p>${name === 'permission' ? '开启后，就可以直接与我对话。' : '开启麦克风后，你可以直接说话，<br>我会在你说完后回应。'}</p>${button('primary-action', icon('mic') + '开启麦克风')}${button('text-action', name === 'permission' ? '暂不使用' : '暂不开启')}<div class="onboard-note"><span></span>只需说话，回应自然发生</div></section>`
    : `<header class="top-toolbar">${button('toolbar-button', icon('more'))}${button(`toolbar-button captions-button ${caption ? 'enabled' : ''}`, '<span>字</span><i></i>')}</header><section class="conversation"><div class="conversation-column ${name === 'ready' ? 'empty' : ''}">${caption ? conversation : ''}</div></section><footer class="bottom-area"><div class="voice-status ${paused || name === 'network' ? 'static' : ''} ${wave ? 'waveform' : 'dots'} ${name}"><div class="voice-mark">${'<span></span>'.repeat(wave ? 7 : 3)}</div><div>${status[name]}</div>${name === 'ended' ? '<div class="status-support">点击麦克风，开始新对话</div>' : ''}</div><div class="bottom-controls">${button('circle-control microphone', icon(paused && name !== 'settings' ? 'muted' : 'mic'))}${button('circle-control end-session', icon('close'))}</div></footer>${name === 'settings' ? settings() : ''}`;
  return `<!doctype html><html lang="zh"><meta charset="utf-8"><style>${css}\n*,*:before,*:after{animation:none!important;transition:none!important}</style><body><div class="app-root ${theme}"><main class="voice-app"><div class="ambient"></div>${body}</main></div></body></html>`;
}

(async () => {
  const browser = await chromium.launch({executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true});
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 720}, deviceScaleFactor: 1});
    for (const theme of ['light', 'dark']) for (const name of ['onboarding', 'permission', 'ready', 'listening', 'thinking', 'speaking', 'hidden', 'paused', 'ended', 'settings', 'network']) {
      await page.setContent(screen(name, theme));
      await page.screenshot({path: path.join(output, `${theme}-${name}.png`)});
      if (theme === 'light' && ['ready', 'speaking', 'onboarding', 'settings'].includes(name)) {
        console.log(name, await page.locator('.top-toolbar, .onboard-symbol, h1, .primary-action, .conversation-column, .message, .voice-status, .bottom-controls, .settings-panel, .appearance-options').evaluateAll(nodes => nodes.map(node => ({class: node.className, bounds: node.getBoundingClientRect().toJSON(), font: getComputedStyle(node).fontSize, lineHeight: getComputedStyle(node).lineHeight}))));
      }
    }
  } finally { await browser.close(); }
})();
