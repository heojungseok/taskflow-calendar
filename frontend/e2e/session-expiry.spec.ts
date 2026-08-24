import { expect, test, type BrowserContext, type Page } from '@playwright/test';

const RETURN_PATH_KEY = 'taskflow:return-path';
const ORIGINAL_PATH = '/projects/7/tasks?sort=oldest#task-3';

type ApiMode = 'authenticated' | 'session-401' | 'session-500' | 'resource-401' | 'authorize-500';

async function mockApi(
  page: Page,
  mode: ApiMode = 'authenticated',
  expiresAt = new Date(Date.now() + 12 * 60 * 60 * 1000).toISOString()
) {
  let resourceFailures = 0;

  await page.route(/^https?:\/\/[^/]+\/api\//, async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;

    if (path === '/api/auth/session') {
      if (mode === 'session-401') {
        return route.fulfill({ status: 401, json: { success: false } });
      }
      if (mode === 'session-500') {
        return route.fulfill({ status: 500, json: { success: false } });
      }
      return route.fulfill({
        json: {
          success: true,
          data: { authenticated: true, userType: 'GOOGLE', expiresAt },
        },
      });
    }

    if (path === '/api/auth/demo' && request.method() === 'POST') {
      return route.fulfill({
        json: {
          success: true,
          data: { authenticated: true, userType: 'DEMO', expiresAt: '2026-08-25T00:00:00Z' },
        },
      });
    }

    if (mode === 'resource-401' && (
      path === '/api/projects/7' || path === '/api/projects/7/tasks'
    )) {
      if (resourceFailures++ > 0) {
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      return route.fulfill({ status: 401, json: { success: false } });
    }

    if (path === '/api/projects/7') {
      return route.fulfill({
        json: {
          success: true,
          data: {
            id: 7,
            name: '세션 만료 확인',
            createdAt: '2026-08-24T00:00:00',
            updatedAt: '2026-08-24T00:00:00',
          },
        },
      });
    }

    if (path === '/api/projects/7/tasks' || path === '/api/projects') {
      return route.fulfill({ json: { success: true, data: [] } });
    }

    if (path === '/api/oauth/google/authorize') {
      if (mode === 'authorize-500') {
        return route.fulfill({ status: 500, json: { success: false } });
      }
      return route.fulfill({
        json: { success: true, data: { authorizeUrl: 'https://accounts.google.test/oauth' } },
      });
    }

    return route.fulfill({ json: { success: true, data: null } });
  });
}

async function openAuthenticatedPages(context: BrowserContext) {
  const first = await context.newPage();
  const second = await context.newPage();
  await mockApi(first);
  await mockApi(second);
  await Promise.all([first.goto('/projects'), second.goto('/projects')]);
  await Promise.all([
    expect(first.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible(),
    expect(second.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible(),
  ]);
  return { first, second };
}

async function seedReturnPath(page: Page, raw: string) {
  await page.goto('/login');
  await page.evaluate(({ key, value }) => sessionStorage.setItem(key, value), {
    key: RETURN_PATH_KEY,
    value: raw,
  });
}

async function storedReturnPath(page: Page) {
  return page.evaluate(key => sessionStorage.getItem(key), RETURN_PATH_KEY);
}

test.describe('return path', () => {
  test('401은 query와 hash를 포함한 보호 경로를 저장하고 로그인으로 이동한다', async ({ page }) => {
    await mockApi(page, 'session-401');

    await page.goto(ORIGINAL_PATH);

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('button', { name: 'Google로 로그인' })).toBeVisible();
    const record = JSON.parse((await storedReturnPath(page))!) as {
      path: string;
      createdAt: number;
    };
    expect(record.path).toBe(ORIGINAL_PATH);
    expect(record.createdAt).toBeGreaterThan(0);
  });

  test('동시 401 두 건은 먼저 저장한 유효 경로를 보존한다', async ({ page }) => {
    await mockApi(page, 'resource-401');
    const firstRecord = {
      path: '/tasks/99?view=compact#history',
      createdAt: Date.now(),
    };
    await seedReturnPath(page, JSON.stringify(firstRecord));

    await page.goto(ORIGINAL_PATH);

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('button', { name: 'Google로 로그인' })).toBeVisible();
    expect(JSON.parse((await storedReturnPath(page))!)).toEqual(firstRecord);
  });

  test('OAuth 성공은 저장값을 지우고 원래 경로로 돌아간다', async ({ page }) => {
    await mockApi(page);
    await seedReturnPath(page, JSON.stringify({ path: ORIGINAL_PATH, createdAt: Date.now() }));

    await page.goto('/oauth/callback');

    await expect.poll(() => page.evaluate(() => location.pathname + location.search + location.hash))
      .toBe(ORIGINAL_PATH);
    expect(await storedReturnPath(page)).toBeNull();
  });

  const invalidRecords = [
    {
      name: '15분을 초과한 값',
      raw: () => JSON.stringify({ path: ORIGINAL_PATH, createdAt: Date.now() - 15 * 60 * 1000 - 1 }),
    },
    { name: '손상된 JSON', raw: () => '{not-json' },
    { name: '로그인 경로', raw: () => JSON.stringify({ path: '/login', createdAt: Date.now() }) },
    { name: 'OAuth callback 경로', raw: () => JSON.stringify({ path: '/oauth/callback', createdAt: Date.now() }) },
    { name: '외부 URL', raw: () => JSON.stringify({ path: 'https://evil.test', createdAt: Date.now() }) },
    { name: 'scheme-relative URL', raw: () => JSON.stringify({ path: '//evil.test', createdAt: Date.now() }) },
    { name: '백슬래시 경로', raw: () => JSON.stringify({ path: '/\\evil', createdAt: Date.now() }) },
    { name: '미등록 내부 경로', raw: () => JSON.stringify({ path: '/privacy', createdAt: Date.now() }) },
  ];

  for (const { name, raw } of invalidRecords) {
    test(`${name}은 삭제하고 프로젝트 목록으로 이동한다`, async ({ page }) => {
      await mockApi(page);
      await seedReturnPath(page, raw());

      await page.goto('/oauth/callback');

      await expect(page).toHaveURL(/\/projects$/);
      expect(await storedReturnPath(page)).toBeNull();
    });
  }

  test('OAuth callback 오류는 저장값을 삭제한다', async ({ page }) => {
    await mockApi(page);
    await seedReturnPath(page, JSON.stringify({ path: ORIGINAL_PATH, createdAt: Date.now() }));
    page.on('dialog', dialog => dialog.accept());

    await page.goto('/oauth/callback?error=access_denied');

    await expect(page).toHaveURL(/\/login$/);
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('OAuth session 확인 실패는 저장값을 삭제한다', async ({ page }) => {
    await mockApi(page, 'session-500');
    await seedReturnPath(page, JSON.stringify({ path: ORIGINAL_PATH, createdAt: Date.now() }));

    await page.goto('/oauth/callback');

    await expect(page).toHaveURL(/\/login$/);
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('DEMO 로그인은 저장값을 삭제한다', async ({ page }) => {
    await mockApi(page);
    await seedReturnPath(page, JSON.stringify({ path: ORIGINAL_PATH, createdAt: Date.now() }));

    await page.getByRole('button', { name: '데모로 둘러보기' }).click();

    await expect(page).toHaveURL(/\/projects$/);
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('sessionStorage 저장 예외에도 로그인으로 이동한다', async ({ page }) => {
    await page.addInitScript(key => {
      const original = Storage.prototype.setItem;
      Storage.prototype.setItem = function (storageKey, value) {
        if (storageKey === key) throw new DOMException('storage disabled');
        return original.call(this, storageKey, value);
      };
    }, RETURN_PATH_KEY);
    await mockApi(page, 'session-401');

    await page.goto(ORIGINAL_PATH);

    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe('session expiry dialog', () => {
  const now = new Date('2026-08-24T12:00:00Z');
  const expiryDialog = (page: Page) => page.getByRole('dialog', { name: '세션이 곧 만료됩니다' });

  test('expiry 10분 1초에는 닫혀 있고 정확히 10분에 dialog를 표시한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 10 * 60 * 1000 + 1000).toISOString());

    await page.goto('/projects');

    await expect(expiryDialog(page)).toHaveCount(0);
    await page.clock.setSystemTime(now.getTime() + 1000);
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
    await expect(expiryDialog(page)).toBeVisible();
  });

  test('dialog 취소는 같은 mount의 tick과 visibilitychange 재표시를 막고 저장하지 않는다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 9 * 60 * 1000).toISOString());
    await page.goto('/projects');
    await expect(expiryDialog(page)).toBeVisible();

    await page.getByRole('button', { name: '나중에' }).click();

    await expect(expiryDialog(page)).toHaveCount(0);
    await page.clock.runFor(60_000);
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
    await expect(expiryDialog(page)).toHaveCount(0);
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('dialog 취소 뒤 reload하면 만료 범위 안에서 다시 표시한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 9 * 60 * 1000).toISOString());
    await page.goto('/projects');
    await expect(expiryDialog(page)).toBeVisible();
    await page.getByRole('button', { name: '나중에' }).click();

    await page.reload();

    await expect(expiryDialog(page)).toBeVisible();
  });

  test('expiry 잔여 시간이 0이면 보호 경로를 저장하고 로그인으로 이동한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', now.toISOString());

    await page.goto(ORIGINAL_PATH);

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('button', { name: 'Google로 로그인' })).toBeVisible();
    const record = JSON.parse((await storedReturnPath(page))!) as { path: string };
    expect(record.path).toBe(ORIGINAL_PATH);
  });

  test('expiry 지금 다시 로그인은 logout 없이 경로를 저장하고 authorize 외부 위치로 이동한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 9 * 60 * 1000).toISOString());
    let recordAtAuthorize: string | null = null;
    let logoutRequests = 0;
    page.on('request', request => {
      if (new URL(request.url()).pathname === '/api/auth/logout') logoutRequests++;
    });
    await page.route('**/api/oauth/google/authorize', async route => {
      recordAtAuthorize = await storedReturnPath(page);
      await route.fulfill({
        json: { success: true, data: { authorizeUrl: 'https://accounts.google.test/oauth' } },
      });
    });
    await page.route('https://accounts.google.test/**', route => route.fulfill({
      contentType: 'text/html',
      body: '<title>Google OAuth mock</title>',
    }));
    await page.goto(ORIGINAL_PATH);
    await expect(expiryDialog(page)).toBeVisible();

    await page.getByRole('button', { name: '지금 다시 로그인' }).click();

    await expect(page).toHaveURL('https://accounts.google.test/oauth');
    expect((JSON.parse(recordAtAuthorize!) as { path: string }).path).toBe(ORIGINAL_PATH);
    expect(logoutRequests).toBe(0);
  });

  test('expiry authorize 실패는 반환 경로를 지우고 현재 화면과 세션을 유지하며 alert를 표시한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authorize-500', new Date(now.getTime() + 9 * 60 * 1000).toISOString());
    await page.goto('/projects');
    await expect(expiryDialog(page)).toBeVisible();

    await page.getByRole('button', { name: '지금 다시 로그인' }).click();

    await expect(page).toHaveURL(/\/projects$/);
    await expect(page.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible();
    await expect(expiryDialog(page)).toBeVisible();
    await expect(page.getByRole('alert')).toContainText('다시 로그인을 시작하지 못했습니다');
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('dialog는 접근 가능한 이름과 초기 focus를 제공하고 Escape로 취소된다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 9 * 60 * 1000).toISOString());
    await page.goto('/projects');

    await expect(expiryDialog(page)).toBeVisible();
    await expect(page.getByRole('button', { name: '나중에' })).toBeFocused();
    await page.keyboard.press('Escape');

    await expect(expiryDialog(page)).toHaveCount(0);
    expect(await storedReturnPath(page)).toBeNull();
  });
});

for (const action of ['로그아웃', 'Google 연결 해제'] as const) {
  test(`broadcast ${action}은 같은 BrowserContext의 다른 page도 로그인으로 보낸다`, async ({ context }) => {
    const { first, second } = await openAuthenticatedPages(context);
    const endpoint = action === '로그아웃' ? '/api/auth/logout' : '/api/oauth/google/disconnect';
    await first.route(`**${endpoint}`, route => route.fulfill({
      status: 500,
      json: { success: false },
    }));
    if (action === 'Google 연결 해제') {
      first.on('dialog', dialog => dialog.accept());
    }

    await first.getByRole('button', { name: action }).click();

    await expect(first).toHaveURL(/\/login$/);
    await expect(second).toHaveURL(/\/login$/);
  });
}

test('broadcast 채널이 없어도 현재 tab logout은 정상 동작한다', async ({ page }) => {
  await page.addInitScript(() => {
    Reflect.deleteProperty(window, 'BroadcastChannel');
  });
  await mockApi(page);
  await page.goto('/projects');
  expect(await page.evaluate(() => 'BroadcastChannel' in window)).toBe(false);

  await page.getByRole('button', { name: '로그아웃' }).click();

  await expect(page).toHaveURL(/\/login$/);
});
