import { expect, test, type BrowserContext, type Page } from '@playwright/test';

const RETURN_PATH_KEY = 'taskflow:return-path';
const ORIGINAL_PATH = '/projects/7/tasks?sort=oldest#task-3';

type ApiMode = 'authenticated' | 'session-anonymous' | 'session-401' | 'session-500' | 'resource-401' | 'authorize-401' | 'authorize-500';

async function mockApi(
  page: Page,
  mode: ApiMode = 'authenticated',
  expiresAt = new Date(Date.now() + 12 * 60 * 60 * 1000).toISOString()
) {
  let resourceFailures = 0;
  let demoAuthenticated = false;

  await page.route(/^https?:\/\/[^/]+\/api\//, async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;

    if (path === '/api/auth/session') {
      if ((mode === 'session-anonymous' && !demoAuthenticated) || (mode === 'resource-401' && resourceFailures > 0)) {
        return route.fulfill({
          json: {
            success: true,
            data: { authenticated: false, userType: null, expiresAt: null },
          },
        });
      }
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
      demoAuthenticated = true;
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
      if (mode === 'authorize-401') {
        return route.fulfill({ status: 401, json: { success: false } });
      }
      if (mode === 'authorize-500') {
        return route.fulfill({ status: 500, json: { success: false } });
      }
      return route.fulfill({
        json: { success: true, data: { authorizeUrl: 'https://accounts.google.test/oauth' } },
      });
    }

    if (path === '/api/oauth/google/reconsent') {
      return route.fulfill({
        json: { success: true, data: { authorizeUrl: '/oauth/callback?error=oauth_failed' } },
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
  await page.goto('/');
  await page.evaluate(({ key, value }) => sessionStorage.setItem(key, value), {
    key: RETURN_PATH_KEY,
    value: raw,
  });
}

async function storedReturnPath(page: Page) {
  return page.evaluate(key => sessionStorage.getItem(key), RETURN_PATH_KEY);
}

function observeSessionEnded(page: Page) {
  return page.evaluate(() => new Promise<void>(resolve => {
    const channel = new BroadcastChannel('taskflow-auth');
    channel.addEventListener('message', event => {
      if (event.data !== 'session-ended') return;
      channel.close();
      resolve();
    });
  }));
}

function clientAuthenticated(page: Page) {
  return page.evaluate(async modulePath => {
    const module = await import(modulePath);
    return module.useAuthStore.getState().authenticated;
  }, '/src/store/authStore.ts');
}

async function expectStaleSessionIgnored(
  context: BrowserContext,
  entryPath: '/login' | '/oauth/callback',
  loadingText: string
) {
  const target = await context.newPage();
  const broadcaster = await context.newPage();
  await mockApi(target);
  await mockApi(broadcaster);
  let releaseSession!: () => void;
  let finishSession!: () => void;
  const sessionReleased = new Promise<void>(resolve => { releaseSession = resolve; });
  const sessionFinished = new Promise<void>(resolve => { finishSession = resolve; });
  let protectedRequests = 0;
  target.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/projects')) protectedRequests++;
  });
  await target.route('**/api/auth/session', async route => {
    await sessionReleased;
    await route.fulfill({
      json: {
        success: true,
        data: {
          authenticated: true,
          userType: 'GOOGLE',
          expiresAt: new Date(Date.now() + 12 * 60 * 60 * 1000).toISOString(),
        },
      },
    });
    finishSession();
  });
  await target.goto(entryPath);
  await expect(target.getByText(loadingText)).toBeVisible();
  await broadcaster.goto('/projects');
  await expect(broadcaster.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible();
  const sessionEnded = observeSessionEnded(target);

  await broadcaster.getByRole('button', { name: '로그아웃' }).click();
  await sessionEnded;
  releaseSession();
  await sessionFinished;

  await expect(target).toHaveURL(/\/login$/);
  await expect.poll(() => clientAuthenticated(target)).toBe(false);
  expect(protectedRequests).toBe(0);
}

test.describe('return path', () => {
  test('200 익명 세션은 query와 hash를 포함한 보호 경로를 저장하고 로그인으로 이동한다', async ({ page }) => {
    await mockApi(page, 'session-anonymous');

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

    await page.goBack();
    await expect(page).toHaveURL(/\/$/);
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

  test('OAuth callback 오류는 복귀 경로를 보존하고 alert 없이 로그인 화면에 표시한다', async ({ page }) => {
    await mockApi(page, 'session-anonymous');
    await seedReturnPath(page, JSON.stringify({ path: ORIGINAL_PATH, createdAt: Date.now() }));
    let dialogs = 0;
    page.on('dialog', dialog => {
      dialogs++;
      return dialog.dismiss();
    });

    await page.goto('/oauth/callback?error=consent_cancelled');

    await expect(page).toHaveURL(/\/login\?error=consent_cancelled$/);
    await expect(page.getByRole('alert')).toContainText('Google 권한 확인이 취소되었습니다.');
    expect(await storedReturnPath(page)).not.toBeNull();
    expect(dialogs).toBe(0);
  });

  test('복구 가능한 OAuth 오류는 서버의 명시적 재동의 endpoint를 사용한다', async ({ page }) => {
    await mockApi(page, 'session-anonymous');
    await page.goto('/login?error=refresh_token_unavailable');

    await expect(page.getByRole('alert')).toContainText('Google 연결을 복구하지 못했습니다.');
    const request = page.waitForRequest(request =>
      new URL(request.url()).pathname === '/api/oauth/google/reconsent'
    );
    await page.getByRole('button', { name: 'Google 권한 다시 확인' }).click();

    await request;
  });

  test('Calendar 권한 누락도 명시적 재동의 버튼으로 복구한다', async ({ page }) => {
    await mockApi(page, 'session-anonymous');

    await page.goto('/login?error=calendar_permission_required');

    await expect(page.getByRole('alert')).toContainText('Google Calendar 권한이 필요합니다.');
    await expect(page.getByRole('button', { name: 'Google 권한 다시 확인' })).toBeVisible();
  });

  test('미등록 OAuth 오류 코드는 서버 문구를 노출하지 않고 일반 오류로 제한한다', async ({ page }) => {
    await mockApi(page, 'session-anonymous');

    await page.goto('/oauth/callback?error=secret_server_detail');

    await expect(page).toHaveURL(/\/login\?error=oauth_failed$/);
    await expect(page.getByRole('alert')).toHaveText('Google 로그인에 실패했습니다. 다시 시도해주세요.');
    await expect(page.getByRole('button', { name: 'Google 권한 다시 확인' })).toHaveCount(0);
    await expect(page.getByText('secret_server_detail')).toHaveCount(0);
  });

  test('OAuth session 확인 실패는 저장값을 삭제한다', async ({ page }) => {
    await mockApi(page, 'session-500');
    await seedReturnPath(page, JSON.stringify({ path: ORIGINAL_PATH, createdAt: Date.now() }));

    await page.goto('/oauth/callback');

    await expect(page).toHaveURL(/\/login$/);
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('DEMO 로그인은 저장값을 삭제한다', async ({ page }) => {
    await mockApi(page, 'session-anonymous');
    await seedReturnPath(page, JSON.stringify({ path: ORIGINAL_PATH, createdAt: Date.now() }));
    await page.goto('/login');

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

test.describe('authenticated public entry', () => {
  test('로그인 경로 직접 진입은 살아 있는 세션을 프로젝트 목록으로 보낸다', async ({ page }) => {
    await mockApi(page);

    await page.goto('/login');

    await expect(page).toHaveURL(/\/projects$/);
    await expect(page.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible();
  });

  test('OAuth 오류 query는 살아 있는 세션에서도 복구 안내를 보존한다', async ({ page }) => {
    await mockApi(page);

    await page.goto('/login?error=refresh_token_unavailable');

    await expect(page).toHaveURL(/\/login\?error=refresh_token_unavailable$/);
    await expect(page.getByRole('alert')).toContainText('Google 연결을 복구하지 못했습니다.');
  });

  for (const query of ['error=', 'error=unknown', 'error=toString']) {
    test(`유효하지 않은 ${query} query는 세션 확인을 우회하지 않는다`, async ({ page }) => {
      await mockApi(page);

      await page.goto(`/login?${query}`);

      await expect(page).toHaveURL(/\/projects$/);
    });
  }

  test('이미 익명으로 초기화된 탭도 login 재진입 때 서버 세션을 다시 확인한다', async ({ page }) => {
    let authenticated = false;
    await page.route(/^https?:\/\/[^/]+\/api\//, route => {
      const path = new URL(route.request().url()).pathname;
      if (path === '/api/auth/session') {
        return route.fulfill({
          json: {
            success: true,
            data: {
              authenticated,
              userType: authenticated ? 'GOOGLE' : null,
              expiresAt: authenticated ? new Date(Date.now() + 60_000).toISOString() : null,
            },
          },
        });
      }
      if (path === '/api/projects') {
        return route.fulfill({ json: { success: true, data: [] } });
      }
      return route.fulfill({ json: { success: true, data: null } });
    });

    await page.goto('/login');
    await expect(page.getByRole('button', { name: 'Google로 로그인' })).toBeVisible();
    await page.evaluate(() => {
      history.pushState({}, '', '/');
      dispatchEvent(new PopStateEvent('popstate'));
    });
    await expect(page.getByText('쓰는 대로, 맞춰진다.')).toBeVisible();
    authenticated = true;

    await page.evaluate(() => {
      history.pushState({}, '', '/login');
      dispatchEvent(new PopStateEvent('popstate'));
    });

    await expect(page).toHaveURL(/\/projects$/);
  });

  test('login 세션 확인 중에는 접근 가능한 상태를 표시한다', async ({ page }) => {
    let releaseSession!: () => void;
    const pendingSession = new Promise<void>(resolve => {
      releaseSession = resolve;
    });
    await page.route('**/api/auth/session', async route => {
      await pendingSession;
      await route.fulfill({
        json: {
          success: true,
          data: { authenticated: false, userType: null, expiresAt: null },
        },
      });
    });

    await page.goto('/login');
    await expect(page.getByRole('status')).toHaveText('불러오는 중');
    releaseSession();

    await expect(page.getByRole('button', { name: 'Google로 로그인' })).toBeVisible();
  });

  for (const mode of ['session-401', 'session-500'] as const) {
    test(`login 세션 확인 ${mode}은 로그인 화면으로 안전하게 종료한다`, async ({ page }) => {
      await mockApi(page, mode);

      await page.goto('/login');

      await expect(page.getByRole('button', { name: 'Google로 로그인' })).toBeVisible();
    });
  }

  for (const path of ['/privacy', '/terms']) {
    test(`${path} 로고는 살아 있는 세션을 프로젝트 목록으로 보낸다`, async ({ page }) => {
      await mockApi(page);
      await page.goto(path);

      await page.getByRole('link', { name: 'TaskFlow' }).click();

      await expect(page).toHaveURL(/\/projects$/);
      await expect(page.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible();
    });
  }

  test('홈 시작 CTA는 살아 있는 세션을 프로젝트 목록으로 보낸다', async ({ page }) => {
    await mockApi(page);
    await page.goto('/');

    await page.getByRole('link', { name: 'TaskFlow 시작하기' }).click();

    await expect(page).toHaveURL(/\/projects$/);
  });

  test('비로그인 로그인 화면 로고는 공개 홈으로 보낸다', async ({ page }) => {
    await mockApi(page, 'session-anonymous');
    await page.goto('/login');

    await page.getByRole('link', { name: 'TaskFlow', exact: true }).click();

    await expect(page).toHaveURL(/\/$/);
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

  test('dismiss 뒤 expiry에 도달하면 보호 경로를 저장하고 로그인으로 이동한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 9 * 60 * 1000).toISOString());
    await page.goto(ORIGINAL_PATH);
    await expect(expiryDialog(page)).toBeVisible();
    await page.getByRole('button', { name: '나중에' }).click();
    let readbacks = 0;
    await page.route('**/api/auth/session', route => {
      readbacks++;
      return route.fulfill({ json: { success: true, data: { authenticated: false } } });
    });

    await page.clock.setSystemTime(now.getTime() + 9 * 60 * 1000);
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));

    await expect(page).toHaveURL(/\/login$/);
    expect(readbacks).toBe(1);
    const record = JSON.parse((await storedReturnPath(page))!) as { path: string };
    expect(record.path).toBe(ORIGINAL_PATH);
  });

  test('빠른 기기 시계에서도 서버 세션이 유효하면 겹치는 확인 없이 보호 화면을 유지한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 1000).toISOString());
    await page.goto(ORIGINAL_PATH);
    await expect(page.getByRole('heading', { name: '세션 만료 확인' })).toBeVisible();
    let readbacks = 0;
    let releaseReadback!: () => void;
    const readbackReleased = new Promise<void>(resolve => { releaseReadback = resolve; });
    await page.route('**/api/auth/session', async route => {
      readbacks++;
      await readbackReleased;
      await route.fulfill({
        json: {
          success: true,
          data: {
            authenticated: true,
            userType: 'GOOGLE',
            expiresAt: new Date(now.getTime() + 12 * 60 * 60 * 1000).toISOString(),
          },
        },
      });
    });

    await page.clock.setSystemTime(now.getTime() + 1000);
    await page.evaluate(() => {
      document.dispatchEvent(new Event('visibilitychange'));
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await expect.poll(() => readbacks).toBe(1);
    releaseReadback();

    await expect(page).toHaveURL(new RegExp(`${ORIGINAL_PATH.replace(/[?#]/g, '\\$&')}$`));
    await expect(page.getByRole('heading', { name: '세션 만료 확인' })).toBeVisible();
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('만료 확인으로 갱신된 서버 expiry를 반영해 재확인하지 않는다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 1000).toISOString());
    await page.goto(ORIGINAL_PATH);
    await expect(page.getByRole('heading', { name: '세션 만료 확인' })).toBeVisible();
    let readbacks = 0;
    await page.route('**/api/auth/session', route => {
      readbacks++;
      return route.fulfill({
        json: {
          success: true,
          data: {
            authenticated: true,
            userType: 'GOOGLE',
            expiresAt: new Date(now.getTime() + 12 * 60 * 60 * 1000).toISOString(),
          },
        },
      });
    });

    await page.clock.setSystemTime(now.getTime() + 1000);
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
    await expect.poll(() => readbacks).toBe(1);
    await expect(expiryDialog(page)).toHaveCount(0);

    await page.clock.runFor(60_000);
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));

    await expect.poll(() => readbacks).toBe(1);
    await expect(page).toHaveURL(new RegExp(`${ORIGINAL_PATH.replace(/[?#]/g, '\\$&')}$`));
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('expiry 서버 확인 실패는 보호 화면과 세션을 유지한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 1000).toISOString());
    await page.goto(ORIGINAL_PATH);
    await expect(page.getByRole('heading', { name: '세션 만료 확인' })).toBeVisible();
    await page.route('**/api/auth/session', route => route.fulfill({
      status: 500,
      json: { success: false },
    }));

    await page.clock.setSystemTime(now.getTime() + 1000);
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));

    await expect(page).toHaveURL(new RegExp(`${ORIGINAL_PATH.replace(/[?#]/g, '\\$&')}$`));
    await expect(page.getByRole('heading', { name: '세션 만료 확인' })).toBeVisible();
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('expiry 서버가 익명을 확인하면 보호 경로를 저장하고 로그인으로 이동한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 1000).toISOString());
    await page.goto(ORIGINAL_PATH);
    await expect(page.getByRole('heading', { name: '세션 만료 확인' })).toBeVisible();
    let readbacks = 0;
    await page.route('**/api/auth/session', route => {
      readbacks++;
      return route.fulfill({ json: { success: true, data: { authenticated: false } } });
    });

    await page.clock.setSystemTime(now.getTime() + 1000);
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('button', { name: 'Google로 로그인' })).toBeVisible();
    expect(readbacks).toBe(1);
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

  test('authorize 401은 공통 redirect 없이 현재 세션을 유지하고 dialog에서 처리한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authorize-401', new Date(now.getTime() + 9 * 60 * 1000).toISOString());
    await page.goto('/projects');
    await expect(expiryDialog(page)).toBeVisible();

    await page.getByRole('button', { name: '지금 다시 로그인' }).click();

    await expect(page).toHaveURL(/\/projects$/);
    await expect(page.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible();
    await expect(expiryDialog(page)).toBeVisible();
    await expect(page.getByRole('alert')).toContainText('다시 로그인을 시작하지 못했습니다');
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('pending authorize expiry는 늦은 응답 뒤에도 원래 보호 경로를 보존한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 1000).toISOString());
    let startAuthorize!: () => void;
    let releaseAuthorize!: () => void;
    let finishAuthorize!: () => void;
    const authorizeStarted = new Promise<void>(resolve => { startAuthorize = resolve; });
    const authorizeReleased = new Promise<void>(resolve => { releaseAuthorize = resolve; });
    const authorizeFinished = new Promise<void>(resolve => { finishAuthorize = resolve; });
    await page.route('**/api/oauth/google/authorize', async route => {
      startAuthorize();
      await authorizeReleased;
      await route.fulfill({
        json: { success: true, data: { authorizeUrl: 'https://accounts.google.test/oauth' } },
      });
      finishAuthorize();
    });
    await page.goto(ORIGINAL_PATH);
    await expect(expiryDialog(page)).toBeVisible();

    await page.getByRole('button', { name: '지금 다시 로그인' }).click();
    await authorizeStarted;
    let readbacks = 0;
    await page.route('**/api/auth/session', route => {
      readbacks++;
      return route.fulfill({ json: { success: true, data: { authenticated: false } } });
    });
    await page.clock.setSystemTime(now.getTime() + 1000);
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
    await expect(page).toHaveURL(/\/login$/);
    expect(readbacks).toBe(1);
    releaseAuthorize();
    await authorizeFinished;

    await expect(page).toHaveURL(/\/login$/);
    const record = JSON.parse((await storedReturnPath(page))!) as { path: string };
    expect(record.path).toBe(ORIGINAL_PATH);
  });

  test('pending authorize는 나중에 취소하면 늦은 성공에도 현재 화면과 세션을 유지한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 9 * 60 * 1000).toISOString());
    let startAuthorize!: () => void;
    let releaseAuthorize!: () => void;
    let finishAuthorize!: () => void;
    const authorizeStarted = new Promise<void>(resolve => { startAuthorize = resolve; });
    const authorizeReleased = new Promise<void>(resolve => { releaseAuthorize = resolve; });
    const authorizeFinished = new Promise<void>(resolve => { finishAuthorize = resolve; });
    await page.route('**/api/oauth/google/authorize', async route => {
      startAuthorize();
      await authorizeReleased;
      await route.fulfill({
        json: { success: true, data: { authorizeUrl: 'https://accounts.google.test/oauth' } },
      });
      finishAuthorize();
    });
    await page.goto(ORIGINAL_PATH);
    await expect(expiryDialog(page)).toBeVisible();

    await page.getByRole('button', { name: '지금 다시 로그인' }).click();
    await authorizeStarted;
    await page.getByRole('button', { name: '나중에' }).click();
    releaseAuthorize();
    await authorizeFinished;

    await expect(page).toHaveURL(new RegExp(`${ORIGINAL_PATH.replace(/[?#]/g, '\\$&')}$`));
    await expect(page.getByRole('heading', { name: '세션 만료 확인' })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(await storedReturnPath(page)).toBeNull();
  });

  test('dialog 접근성은 focus를 내부 순환시키고 Escape 뒤 이전 focus를 복원한다', async ({ page }) => {
    await page.clock.install({ time: now });
    await mockApi(page, 'authenticated', new Date(now.getTime() + 10 * 60 * 1000 + 1000).toISOString());
    await page.goto('/projects');
    const previousFocus = page.getByRole('button', { name: '로그아웃' });
    await previousFocus.focus();
    await expect(previousFocus).toBeFocused();

    await page.clock.setSystemTime(now.getTime() + 1000);
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));

    await expect(expiryDialog(page)).toBeVisible();
    await expect(page.getByRole('button', { name: '나중에' })).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    expect(await page.evaluate(() => document.querySelector('dialog')?.contains(document.activeElement)))
      .toBe(true);
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => document.querySelector('dialog')?.contains(document.activeElement)))
      .toBe(true);
    await page.keyboard.press('Escape');

    await expect(expiryDialog(page)).toHaveCount(0);
    await expect(previousFocus).toBeFocused();
    expect(await storedReturnPath(page)).toBeNull();
  });
});

for (const action of ['로그아웃', 'Google 연결 해제'] as const) {
  test(`broadcast ${action} 2xx 성공은 같은 BrowserContext의 다른 page도 로그인으로 보낸다`, async ({ context }) => {
    const { first, second } = await openAuthenticatedPages(context);
    const endpoint = action === '로그아웃' ? '/api/auth/logout' : '/api/oauth/google/disconnect';
    await first.route(`**${endpoint}`, route => route.fulfill({
      status: 200,
      json: { success: true, data: action === 'Google 연결 해제' ? true : null },
    }));
    if (action === 'Google 연결 해제') {
      first.on('dialog', dialog => dialog.accept());
    }

    await first.getByRole('button', { name: action }).click();

    await expect(first).toHaveURL(/\/login$/);
    await expect(second).toHaveURL(/\/login$/);
  });
}

test('Google 토큰 폐기 미확인을 안내하고 두 page의 로컬 세션을 종료한다', async ({ context }) => {
  const { first, second } = await openAuthenticatedPages(context);
  await first.route('**/api/oauth/google/disconnect', route => route.fulfill({
    status: 200,
    json: { success: true, data: false },
  }));
  const alerts: string[] = [];
  first.on('dialog', async dialog => {
    if (dialog.type() === 'confirm') {
      await dialog.accept();
    } else {
      alerts.push(dialog.message());
      await dialog.accept();
    }
  });

  await first.getByRole('button', { name: 'Google 연결 해제' }).click();

  await expect.poll(() => alerts).toHaveLength(1);
  expect(alerts[0]).toContain('Google 계정');
  expect(alerts[0]).toContain('TaskFlow 액세스를 직접 삭제');
  await expect(first).toHaveURL(/\/login$/);
  await expect(second).toHaveURL(/\/login$/);
});

const failedEndSessionCases = [
  { name: '403', status: 403 },
  { name: '500', status: 500 },
  { name: 'no-response', status: null },
] as const;

for (const action of ['로그아웃', 'Google 연결 해제'] as const) {
  for (const failure of failedEndSessionCases) {
    test(`fail-closed ${failure.name} ${action}은 두 page의 보호 화면과 세션을 유지하고 알린다`, async ({ context }) => {
      const { first, second } = await openAuthenticatedPages(context);
      const endpoint = action === '로그아웃' ? '/api/auth/logout' : '/api/oauth/google/disconnect';
      await first.route(`**${endpoint}`, route => failure.status === null
        ? route.abort('failed')
        : route.fulfill({ status: failure.status, json: { success: false } }));
      let readbacks = 0;
      await first.route('**/api/auth/session', route => {
        readbacks++;
        return route.fulfill({
          json: {
            success: true,
            data: {
              authenticated: true,
              userType: 'GOOGLE',
              expiresAt: new Date(Date.now() + 12 * 60 * 60 * 1000).toISOString(),
            },
          },
        });
      });
      const alerts: string[] = [];
      first.on('dialog', async dialog => {
        if (dialog.type() === 'confirm') {
          await dialog.accept();
        } else {
          alerts.push(dialog.message());
          await dialog.accept();
        }
      });

      await first.getByRole('button', { name: action }).click();

      await expect(first).toHaveURL(/\/projects$/);
      await expect(second).toHaveURL(/\/projects$/);
      await expect(first.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible();
      await expect(second.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible();
      await expect.poll(() => alerts).toHaveLength(1);
      expect(alerts[0]).toContain('현재 화면을 유지');
      expect(readbacks).toBe(1);
    });
  }
}

for (const action of ['로그아웃', 'Google 연결 해제'] as const) {
  test(`reconciliation ${action} 500 뒤 session 401이면 두 page를 로그인으로 보낸다`, async ({ context }) => {
    const { first, second } = await openAuthenticatedPages(context);
    const endpoint = action === '로그아웃' ? '/api/auth/logout' : '/api/oauth/google/disconnect';
    await first.route(`**${endpoint}`, route => route.fulfill({
      status: 500,
      json: { success: false },
    }));
    await first.route('**/api/auth/session', route => route.fulfill({
      status: 401,
      json: { success: false },
    }));
    const alerts: string[] = [];
    first.on('dialog', async dialog => {
      if (dialog.type() === 'confirm') {
        await dialog.accept();
      } else {
        alerts.push(dialog.message());
        await dialog.accept();
      }
    });

    await first.getByRole('button', { name: action }).click();

    await expect.poll(() => alerts).toHaveLength(1);
    expect(alerts[0]).toContain('다시 로그인');
    await expect(first).toHaveURL(/\/login$/);
    await expect(second).toHaveURL(/\/login$/);
  });
}

for (const action of ['로그아웃', 'Google 연결 해제'] as const) {
  test(`reconciliation ${action} 500 cookie 삭제 뒤 session anonymous면 경고 후 두 page를 로그인으로 보낸다`, async ({ context }) => {
    const { first, second } = await openAuthenticatedPages(context);
    const endpoint = action === '로그아웃' ? '/api/auth/logout' : '/api/oauth/google/disconnect';
    await first.route(`**${endpoint}`, route => route.fulfill({
      status: 500,
      headers: { 'Set-Cookie': 'TASKFLOW_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax' },
      json: { success: false },
    }));
    await first.route('**/api/auth/session', route => route.fulfill({
      json: { success: true, data: { authenticated: false } },
    }));
    const alerts: string[] = [];
    first.on('dialog', async dialog => {
      if (dialog.type() === 'confirm') {
        await dialog.accept();
      } else {
        alerts.push(dialog.message());
        await dialog.accept();
      }
    });

    await first.getByRole('button', { name: action }).click();

    await expect.poll(() => alerts).toHaveLength(1);
    expect(alerts[0]).toContain('다시 로그인');
    await expect(first).toHaveURL(/\/login$/);
    await expect(second).toHaveURL(/\/login$/);
  });
}

test('reconciliation readback 500은 현재 화면과 양쪽 세션을 유지하고 알린다', async ({ context }) => {
  const { first, second } = await openAuthenticatedPages(context);
  await first.route('**/api/auth/logout', route => route.fulfill({
    status: 500,
    json: { success: false },
  }));
  await first.route('**/api/auth/session', route => route.fulfill({
    status: 500,
    json: { success: false },
  }));
  const alerts: string[] = [];
  first.on('dialog', async dialog => {
    alerts.push(dialog.message());
    await dialog.accept();
  });

  await first.getByRole('button', { name: '로그아웃' }).click();

  await expect(first).toHaveURL(/\/projects$/);
  await expect(second).toHaveURL(/\/projects$/);
  await expect(first.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible();
  await expect(second.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible();
  await expect.poll(() => alerts).toHaveLength(1);
  expect(alerts[0]).toContain('현재 화면을 유지');
});

test('stale initial session 응답은 다른 page의 session-ended 뒤 인증을 복원하지 않는다', async ({ context }) => {
  const target = await context.newPage();
  const broadcaster = await context.newPage();
  await mockApi(target);
  await mockApi(broadcaster);
  let startSession!: () => void;
  let releaseSession!: () => void;
  let finishSession!: () => void;
  const sessionStarted = new Promise<void>(resolve => { startSession = resolve; });
  const sessionReleased = new Promise<void>(resolve => { releaseSession = resolve; });
  const sessionFinished = new Promise<void>(resolve => { finishSession = resolve; });
  await target.route('**/api/auth/session', async route => {
    startSession();
    await sessionReleased;
    await route.fulfill({
      json: {
        success: true,
        data: {
          authenticated: true,
          userType: 'GOOGLE',
          expiresAt: new Date(Date.now() + 12 * 60 * 60 * 1000).toISOString(),
        },
      },
    });
    finishSession();
  });
  await target.goto('/projects');
  await sessionStarted;
  await expect(target.getByText('불러오는 중')).toBeVisible();
  await broadcaster.goto('/projects');
  await expect(broadcaster.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible();

  await broadcaster.getByRole('button', { name: '로그아웃' }).click();
  await expect(target).toHaveURL(/\/login$/);
  releaseSession();
  await sessionFinished;

  await expect.poll(() => clientAuthenticated(target)).toBe(false);
});

test('login session 지연 응답은 다른 page의 session-ended 뒤 인증과 보호 요청을 복원하지 않는다', async ({ context }) => {
  await expectStaleSessionIgnored(context, '/login', '불러오는 중');
});

test('OAuth callback session 지연 응답은 다른 page의 session-ended 뒤 인증과 보호 요청을 복원하지 않는다', async ({ context }) => {
  await expectStaleSessionIgnored(context, '/oauth/callback', '로그인하는 중');
});

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

test('404는 인증 요청 없이 홈과 로그인 링크를 제공한다', async ({ page }) => {
  let sessionRequests = 0;
  await page.route(/^https?:\/\/[^/]+\/api\//, route => {
    if (new URL(route.request().url()).pathname === '/api/auth/session') sessionRequests++;
    return route.fulfill({ json: { success: true, data: null } });
  });

  await page.goto('/does-not-exist');

  await expect(page.getByRole('heading', { name: '페이지를 찾을 수 없습니다' })).toBeVisible();
  await expect(page.getByRole('link', { name: '홈으로' })).toHaveAttribute('href', '/');
  await expect(page.getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login');
  expect(sessionRequests).toBe(0);
});
