async (page) => {
  const context = await page.context().browser().newContext();
  const probe = await context.newPage();
  probe.setDefaultTimeout(6000);
  await probe.addInitScript(() => {
    const NativeEventSource = window.EventSource;
    window.__auditStream = {chunks: [], completed: false, source: null};
    window.EventSource = class extends EventTarget {
      constructor(url, options) {
        super();
        this.native = new NativeEventSource(url, options);
        window.__auditStream.source = this;
        this.native.onmessage = event => window.__auditStream.chunks.push(event.data);
        this.native.addEventListener('done', () => { window.__auditStream.completed = true; this.native.close(); });
        this.native.onerror = event => this.onerror?.(event);
      }
      close() { this.native.close(); }
    };
  });
  const results = [];
  try {
    await probe.goto('http://127.0.0.1:5173');
    await probe.waitForFunction(() => !document.querySelector('textarea')?.disabled);
    console.log('STREAMING_SNAPSHOT\n' + await probe.locator('body').ariaSnapshot());
    await probe.getByRole('textbox', {name:'给 AI 编程助手发送消息'}).fill('Java streaming rendering audit');
    await probe.getByRole('button', {name:'发送消息',exact:true}).click();
    await probe.waitForFunction(() => window.__auditStream.completed && window.__auditStream.chunks.length > 0);
    const chunk = await probe.evaluate(() => {
      const s=window.__auditStream;
      const data=s.chunks.shift();
      s.source.onmessage(new MessageEvent('message',{data}));
      return data;
    });
    const received = chunk + await probe.evaluate(() => window.__auditStream.chunks.join(''));
    const referenceResponse = await context.request.post('http://127.0.0.1:8081/api/ai/chat', {data:{memoryId:'whitespace-probe',message:'Java streaming rendering audit'}});
    const reference = await referenceResponse.json();
    results.push({name:'native EventSource preserves whitespace from deterministic model answer',passed:received===reference.answer,detail:{received,expected:reference.answer}});
    let visibleBeforeDone = false;
    try { await probe.locator('.markdown-body').filter({hasText:chunk}).waitFor({timeout:1500}); visibleBeforeDone=true; }
    catch {}
    const before = await probe.locator('.assistant-message').innerText();
    await probe.screenshot({path:'output/playwright/delayed-sse-before-done.png',fullPage:true, animations:"disabled"});
    await probe.evaluate(() => {
      const s=window.__auditStream;
      for(const data of s.chunks) s.source.onmessage(new MessageEvent('message',{data}));
      s.source.dispatchEvent(new MessageEvent('done',{data:'[DONE]'}));
    });
    await probe.waitForFunction(() => !document.querySelector('[aria-label="停止生成"]'));
    const after = await probe.locator('.assistant-message').innerText();
    await probe.screenshot({path:'output/playwright/delayed-sse-after-done.png',fullPage:true, animations:"disabled"});
    results.push({name:'incremental rendering of real SSE data before done (event delivery controlled)',passed:visibleBeforeDone,detail:{chunk,beforeDone:before,afterDone:after,announcement:await probe.getByRole('status').textContent()}});
  } catch(error) {
    results.push({name:'incremental streaming probe harness',passed:false,detail:error.message});
  } finally {
    await context.close();
  }
  console.log('AUDIT_RESULTS ' + JSON.stringify(results));
  return results;
}
