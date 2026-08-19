import { expect, test, type BrowserContext } from '@playwright/test';

async function csrfHeader(context: BrowserContext) {
  const token = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN');
  expect(token).toBeTruthy();
  return { 'X-XSRF-TOKEN': token!.value };
}

test('demo sessions stay isolated and scheduler produces SKIPPED', async ({ browser }) => {
  const first = await browser.newContext();
  const firstPage = await first.newPage();
  await firstPage.goto('/login');
  await firstPage.getByRole('button', { name: '데모로 둘러보기' }).click();
  await expect(firstPage).toHaveURL(/\/projects$/);
  expect(await firstPage.evaluate(() => Object.keys(window.localStorage))).not.toContain('jwt_token');

  const projectResponse = await first.request.post('/api/projects', {
    headers: await csrfHeader(first),
    data: { name: '격리 데모' },
  });
  expect(projectResponse.status()).toBe(201);
  const projectId = (await projectResponse.json()).data.id as number;

  const second = await browser.newContext();
  const secondPage = await second.newPage();
  await secondPage.goto('/login');
  await secondPage.getByRole('button', { name: '데모로 둘러보기' }).click();
  await expect(secondPage).toHaveURL(/\/projects$/);
  expect((await second.request.get(`/api/projects/${projectId}`)).status()).toBe(404);

  await firstPage.reload();
  await expect(firstPage).toHaveURL(/\/projects$/);
  expect((await first.request.get('/api/auth/session')).status()).toBe(200);

  const dueAt = new Date(Date.now() + 3_600_000).toISOString().slice(0, 19);
  const taskResponse = await first.request.post(`/api/projects/${projectId}/tasks`, {
    headers: await csrfHeader(first),
    data: { title: 'SKIPPED 확인', dueAt, calendarSyncEnabled: true },
  });
  expect(taskResponse.ok()).toBeTruthy();

  await expect.poll(async () => {
    const response = await first.request.get('/api/admin/calendar-outbox?status=SKIPPED');
    const entries = (await response.json()).data as Array<{ status: string; lastError: string }>;
    return entries.some(entry => entry.status === 'SKIPPED' && entry.lastError === 'no_google_link');
  }, { timeout: 90_000, intervals: [2_000, 5_000] }).toBeTruthy();

  await firstPage.getByRole('button', { name: '로그아웃' }).click();
  await expect(firstPage).toHaveURL(/\/login$/);
  expect((await (await first.request.get('/api/auth/session')).json()).data.authenticated).toBe(false);

  await first.close();
  await second.close();
});
