async (page) => {
  page.setDefaultTimeout(6000);
  const results = [], consoleErrors = [], network = [];
  page.on('pageerror', error => consoleErrors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('response', response => {
    if (response.url().includes('/api/')) network.push({url: response.url(), method: response.request().method(), status: response.status()});
  });
  const assert = (value, detail) => { if (!value) throw new Error(typeof detail === 'string' ? detail : JSON.stringify(detail)); };
  const check = async (name, fn) => {
    try { results.push({name, passed: true, detail: await fn()}); }
    catch (error) { results.push({name, passed: false, detail: error.message}); }
    console.log('AUDIT ' + JSON.stringify(results[results.length - 1]));
  };
  const textbox = () => page.getByRole('textbox', {name: '给 AI 编程助手发送消息'});
  const send = async (message) => {
    await textbox().fill(message);
    const ticket = page.waitForResponse(r => r.url().endsWith('/api/ai/chat/streams') && r.request().method() === 'POST');
    await page.getByRole('button', {name: '发送消息', exact: true}).click();
    const response = await ticket;
    assert(response.status() === 201, 'Ticket status ' + response.status());
    await page.waitForFunction(() => {
      const s = JSON.parse(localStorage.getItem('lingma:chat-state:v1'));
      return s?.conversations.find(c=>c.id===s.activeConversationId)?.messages.at(-1)?.status === 'done'
        && !document.querySelector('[aria-label="停止生成"]');
    });
  };
  await page.setViewportSize({width: 1440, height: 1000});
  await page.reload();
  await page.waitForFunction(() => !document.querySelector('textarea')?.disabled);
  console.log('DESKTOP_SNAPSHOT\n' + await page.locator('body').ariaSnapshot());
  await check('desktop startup and backend connection', async () => {
    assert(await textbox().isEnabled(), 'Composer disabled');
    await page.screenshot({path: 'output/playwright/desktop-home.png', fullPage: true, animations: "disabled"});
    return await page.locator('.service-status').innerText();
  });
  await check('real POST ticket and EventSource conversation', async () => {
    await send('Java audit-marker-0920');
    const text = await page.getByRole('log').innerText();
    assert(text.includes('Java audit-marker-0920') && text.includes('本地模拟模式'), text);
    assert(network.some(r => r.url.includes('/api/ai/chat/streams/') && r.status === 200), network);
    await page.screenshot({path: 'output/playwright/desktop-chat.png', fullPage: true, animations: "disabled"});
    return network;
  });
  await check('chat displays actual retrieved knowledge sources', async () => {
    const sources = page.locator('.knowledge-sources').first();
    await sources.waitFor();
    await sources.locator('summary').click();
    const titles = await sources.locator('li').allTextContents();
    assert(titles.length > 0 && titles.every(title => title.trim()), titles);
    return titles;
  });
  await check('follow-up remembers previous turn', async () => {
    await send('what did i just say');
    const text = await page.locator('.assistant-message').last().innerText();
    assert(text.includes('Java audit-marker-0920'), text);
    return text;
  });
  await check('completion is announced to screen readers', async () => {
    const text = await page.getByRole('status').textContent();
    assert(text.includes('AI 回复已完成'), {announcement:text});
    return text;
  });
  await check('regenerate preserves turn count', async () => {
    const before = await page.locator('.message-row').count();
    const ticket = page.waitForResponse(r => r.url().endsWith('/api/ai/chat/streams') && r.request().method() === 'POST');
    await page.getByRole('button', {name: '重新生成回复'}).click();
    assert((await ticket).status() === 201, 'regenerate ticket failed');
    await page.waitForFunction(() => !document.querySelector('[aria-label="停止生成"]'));
    assert(await page.locator('.message-row').count() === before, 'Message count changed');
    return {messages: before};
  });
  await check('refresh preserves signed identity and visible history', async () => {
    const before = await page.evaluate(() => JSON.parse(localStorage.getItem('lingma:guest-user:v1')).id);
    await page.reload();
    await page.waitForFunction(() => !document.querySelector('textarea')?.disabled);
    assert(await page.locator('.message-row').count() === 4, 'History lost on reload');
    assert(await page.evaluate(() => JSON.parse(localStorage.getItem('lingma:guest-user:v1')).id) === before, 'Identity changed');
    return {messages: 4, identityPreserved: true};
  });
  console.log('CHAT_SNAPSHOT\n' + await page.locator('body').ariaSnapshot());
  await check('copy reply', async () => {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.getByRole('button', {name: '复制整条回复'}).last().click();
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    assert(copied.includes('Java audit-marker-0920'), copied);
    return {copiedCharacters: copied.length};
  });
  await check('clear dialog keyboard and cancellation', async () => {
    await page.getByRole('button', {name: '清空当前对话记录'}).click();
    await page.getByRole('alertdialog').waitFor();
    assert(await page.getByRole('button', {name: '取消', exact: true}).evaluate(e => e === document.activeElement), 'Cancel did not receive focus');
    await page.keyboard.press('Escape');
    assert(await page.locator('.message-row').count() === 4, 'Cancel deleted messages');
    return {escapeRestoredFocus: await page.getByRole('button', {name: '清空当前对话记录'}).evaluate(e => e === document.activeElement)};
  });
  await check('WCAG automated accessibility scan', async () => {
    await page.addScriptTag({path: '/tmp/audit-tools/node_modules/axe-core/axe.min.js'});
    const result = await page.evaluate(async () => {
      const {violations} = await axe.run(document, {runOnly: {type: 'tag', values: ['wcag2a','wcag2aa','wcag21aa']}});
      return violations.map(v => ({id:v.id, impact:v.impact, description:v.description, nodes:v.nodes.map(n=>({target:n.target,summary:n.failureSummary}))}));
    });
    assert(result.length === 0, result);
    return {violations: 0};
  });
  await check('mobile 320px layout and sidebar focus', async () => {
    await page.setViewportSize({width: 320, height: 740});
    await page.getByRole('button', {name: '打开侧边栏'}).waitFor();
    console.log('MOBILE_SNAPSHOT\n' + await page.locator('body').ariaSnapshot());
    const dims = await page.evaluate(() => ({viewport: innerWidth, width: document.documentElement.scrollWidth}));
    assert(dims.width <= dims.viewport, dims);
    await page.screenshot({path:'output/playwright/mobile-chat.png', fullPage:true, animations:"disabled"});
    await page.getByRole('button', {name:'打开侧边栏'}).click();
    await page.getByRole('button', {name:'关闭侧边栏'}).waitFor();
    await page.screenshot({path:'output/playwright/mobile-sidebar.png', fullPage:true, animations:"disabled"});
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === '打开侧边栏');
    return dims;
  });
  await check('production preview connects to backend', async () => {
    const preview = await page.context().newPage();
    const responses = [];
    preview.on('response', r => { if(r.url().includes('/api/')) responses.push({url:r.url(),status:r.status()}); });
    await preview.goto('http://127.0.0.1:4173');
    await preview.waitForFunction(() => {
      const e = document.querySelector('textarea');
      return e && (!e.disabled || document.querySelector('[role="alert"]'));
    });
    await preview.screenshot({path:'output/playwright/production-preview.png', fullPage:true, animations:"disabled"});
    const details = {responses, text:await preview.locator('body').innerText(), ready:await preview.getByRole('textbox').isEnabled()};
    await preview.close();
    assert(details.ready, details);
    return details.responses;
  });
  await check('unexpected browser console errors on normal flows', async () => { assert(consoleErrors.length === 0, consoleErrors); return consoleErrors; });
  console.log('AUDIT_RESULTS ' + JSON.stringify(results));
  return results;
}
