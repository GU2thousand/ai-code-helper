async (page) => {
  await page.goto('http://127.0.0.1:5173');
  return await page.evaluate(async () => {
    const {normalizeChunk} = await import('/src/utils/chat.js');
    return ['42', '0', 'true', 'false', 'null'].map(raw => {
      const actual = normalizeChunk(raw);
      return {name:'plain SSE token is preserved: ' + raw,passed:actual.content===raw,detail:{raw,actual,expected:{content:raw}}};
    });
  });
}
