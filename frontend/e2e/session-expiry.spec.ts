import { expect, test, type Page } from '@playwright/test';

const RETURN_PATH_KEY = 'taskflow:return-path';
const ORIGINAL_PATH = '/projects/7/tasks?sort=oldest#task-3';

type ApiMode = 'authenticated' | 'session-401' | 'session-500' | 'resource-401';

async function mockApi(page: Page, mode: ApiMode = 'authenticated') {
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
          data: { authenticated: true, userType: 'GOOGLE', expiresAt: '2026-08-25T00:00:00Z' },
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
      return route.fulfill({
        json: { success: true, data: { authorizeUrl: 'https://accounts.google.test/oauth' } },
      });
    }

    return route.fulfill({ json: { success: true, data: null } });
  });
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
