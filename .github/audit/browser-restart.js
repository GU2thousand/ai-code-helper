async (page) => {
  const baseline=await page.evaluate(()=>JSON.parse(localStorage.getItem('audit:restartBaseline')));
  await page.reload();
  await page.waitForFunction(()=>!document.querySelector('textarea')?.disabled);
  const after=await page.evaluate(()=>{
    const s=JSON.parse(localStorage.getItem('lingma:chat-state:v1'));
    return {owner:s.ownerUserId,messages:s.conversations.reduce((n,c)=>n+c.messages.length,0)};
  });
  console.log('AUDIT_RESULTS ' + JSON.stringify([{name:'default restart preserves visitor identity and browser history',passed:baseline?.owner===after.owner && after.messages===baseline?.messages,detail:{before:baseline,after}}]));
  console.log('AFTER_RESTART_SNAPSHOT\n'+await page.locator('body').ariaSnapshot());
  await page.screenshot({path:'output/playwright/after-backend-restart.png',fullPage:true});
  return [{name:'default restart preserves visitor identity and browser history',passed:baseline?.owner===after.owner && after.messages===baseline?.messages,detail:{before:baseline,after}}];
}
