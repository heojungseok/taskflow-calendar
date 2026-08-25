import { expect, test, type BrowserContext } from '@playwright/test';

async function csrfHeader(context: BrowserContext) {
  const token = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN');
  expect(token).toBeTruthy();
  return { 'X-XSRF-TOKEN': token!.value };
}

test('privacy policy does not wait for session initialization', async ({ page }) => {
  await page.route('**/api/auth/session', async route => {
    await new Promise(resolve => setTimeout(resolve, 5_000));
    await route.fulfill({ json: { success: true, data: { authenticated: false } } });
  });

  await page.goto('/privacy');

  await expect(page.getByRole('heading', { name: 'Privacy Policy' })).toBeVisible({ timeout: 1_000 });
});

test('public homepage and terms do not require a session', async ({ page }) => {
  let sessionRequested = false;
  await page.route('**/api/auth/session', route => {
    sessionRequested = true;
    return route.abort();
  });

  await page.goto('/');
  await expect(page.getByText('쓰는 대로, 맞춰진다.')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Privacy' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Terms' })).toBeVisible();

  await page.goto('/terms');
  await expect(page.getByRole('heading', { name: 'Terms of Service' })).toBeVisible();
  expect(sessionRequested).toBe(false);
});

test('browser project creation forwards the CSRF token', async ({ page }) => {
  await page.goto('/login');
  await page.getByRole('button', { name: '데모로 둘러보기' }).click();
  await expect(page).toHaveURL(/\/projects$/);

  await page.getByRole('button', { name: '새 프로젝트' }).first().click();
  await page.getByLabel('이름').fill('CSRF 회귀 확인');
  const projectResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/api/projects')
  );
  await page.getByRole('button', { name: '만들기' }).click();
  expect((await projectResponse).status()).toBe(201);
  await expect(page).toHaveURL(/\/projects$/);
});

test('project list switches between newest and oldest order', async ({ page }) => {
  await page.route('**/api/projects', route => {
    if (route.request().method() !== 'GET') return route.fallback();
    return route.fulfill({
      json: {
        success: true,
        data: [
          { id: 1, name: '먼저 만든 프로젝트', createdAt: '2026-08-21T09:00:00', updatedAt: '2026-08-21T09:00:00' },
          { id: 2, name: '나중에 만든 프로젝트', createdAt: '2026-08-21T09:00:00', updatedAt: '2026-08-21T09:00:00' },
        ],
      },
    });
  });

  await page.goto('/login');
  await page.getByRole('button', { name: '데모로 둘러보기' }).click();
  await expect(page).toHaveURL(/\/projects$/);

  const projectRows = page.getByTestId('project-list').getByRole('button');
  await expect(projectRows.first()).toContainText('나중에 만든 프로젝트');

  await page.getByRole('combobox', { name: '프로젝트 정렬' }).selectOption('oldest');
  await expect(projectRows.first()).toContainText('먼저 만든 프로젝트');
});

test('empty search result stays visible above the project list', async ({ page }) => {
  await page.goto('/login');
  await page.getByRole('button', { name: '데모로 둘러보기' }).click();
  await expect(page).toHaveURL(/\/projects$/);

  await page.route('**/api/search/tasks', route => route.fulfill({
    json: {
      success: true,
      data: {
        query: '이번 주 슈퍼가세',
        intentFallback: false,
        semanticStatus: 'READY',
        intent: {
          rawQuery: '이번 주 슈퍼가세',
          queryType: 'TOPIC_SEARCH',
          targetType: 'TASK',
          domainType: 'UNKNOWN',
          mainAction: 'UNKNOWN',
          secondaryActions: [],
          topicTerms: ['슈퍼가세'],
          participantTerms: [],
          locationTerms: [],
          timeIntent: 'THIS_WEEK',
          priorityIntent: 'NONE',
          statusIntents: [],
          syncIntent: 'ANY',
          relationPolicy: 'ALLOW_PARTIAL',
          overallConfidence: 1,
          fieldConfidence: {},
        },
        taskResults: [],
        relatedProjects: [],
        suggestedQueries: ['이번 주 슈퍼 일정', '중요한 슈퍼 일정', '슈퍼 관련 일정'],
      },
    },
  }));

  await page.getByLabel('자연어로 일정 찾기').fill('이번 주 슈퍼가세');
  await page.getByRole('button', { name: '찾기' }).click();

  await expect(page.getByRole('heading', { name: '검색 결과' })).toBeVisible();
  await expect(page.getByText('0건', { exact: true })).toBeVisible();
  await expect(page.getByText('조건에 맞는 일정이 없습니다.')).toBeVisible();
  await expect(page.getByRole('button', { name: '이번 주 슈퍼 일정' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '전체 프로젝트' })).toBeVisible();
});

test('public sync route and in-page task deletion work', async ({ page }) => {
  await page.goto('/login');
  await page.getByRole('button', { name: '데모로 둘러보기' }).click();
  await expect(page).toHaveURL(/\/projects$/);

  const outboxRequest = page.waitForRequest(request =>
    new URL(request.url()).pathname === '/api/calendar-outbox',
    { timeout: 5_000 }
  );
  await page.getByRole('button', { name: '동기화 현황' }).click();
  await outboxRequest;
  await page.getByRole('button', { name: '프로젝트' }).click();

  await page.getByRole('button', { name: '새 프로젝트' }).first().click();
  await page.getByLabel('이름').fill('삭제 확인 프로젝트');
  await page.getByRole('button', { name: '만들기' }).click();
  await page.getByText('삭제 확인 프로젝트').click();

  await page.getByRole('button', { name: '새 Task' }).click();
  await page.getByRole('textbox', { name: 'Task 제목' }).fill('삭제 확인 Task');
  await page.getByRole('button', { name: '만들기' }).click();

  await page.getByRole('button', { name: '삭제', exact: true }).first().click();
  await expect(page.getByRole('dialog', { name: 'Task 삭제' })).toBeVisible({ timeout: 5_000 });

  const deleteResponse = page.waitForResponse(response =>
    response.request().method() === 'DELETE' && /\/api\/tasks\/\d+$/.test(response.url())
  );
  await page.getByRole('button', { name: '확인', exact: true }).click();
  expect((await deleteResponse).status()).toBe(200);
});

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
    const response = await first.request.get('/api/calendar-outbox?status=SKIPPED');
    const entries = (await response.json()).data as Array<{ status: string; lastError: string }>;
    return entries.some(entry => entry.status === 'SKIPPED' && entry.lastError === 'no_google_link');
  }, { timeout: 90_000, intervals: [2_000, 5_000] }).toBeTruthy();

  await firstPage.getByRole('button', { name: '로그아웃' }).click();
  await expect(firstPage).toHaveURL(/\/login$/);
  expect((await (await first.request.get('/api/auth/session')).json()).data.authenticated).toBe(false);

  await first.close();
  await second.close();
});
