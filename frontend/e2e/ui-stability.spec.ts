import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * 버튼 동작 뒤 화면이 깜빡이거나 지운 항목이 잔상처럼 남지 않는지 확인한다.
 * 응답을 붙잡아 두고 "그 사이 화면"을 검사하므로 네트워크 속도와 무관하게 재현된다.
 */

const NOW = '2026-10-10T12:00:00Z';

function task(id: number, status = 'REQUESTED') {
  return {
    id, projectId: 7, title: `작업 ${id}`, description: null, status,
    assigneeUserId: null, assigneeName: null, startAt: null, dueAt: null,
    calendarSyncEnabled: false, calendarEventId: null, syncState: 'SYNC_DISABLED',
    createdAt: NOW, updatedAt: NOW,
  };
}

/** 목록 조회를 hold()로 붙잡을 수 있는 Task 화면 목 API. */
async function mockTaskPage(page: Page) {
  const tasks = [task(1), task(2, 'IN_PROGRESS'), task(3)];
  let held: (() => void) | null = null;
  let holdNext = false;

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const ok = (data: unknown) => route.fulfill({ json: { success: true, data } });

    if (url.pathname === '/api/auth/session') {
      return ok({ authenticated: true, userType: 'GOOGLE', expiresAt: '2026-10-11T00:00:00Z' });
    }
    if (url.pathname === '/api/projects/7') return ok({ id: 7, name: 'UI 안정성', createdAt: NOW, updatedAt: NOW });
    if (url.pathname === '/api/projects/7/tasks') {
      if (holdNext) {
        holdNext = false;
        await new Promise<void>(resolve => { held = resolve; });
      }
      const status = url.searchParams.get('status');
      return ok(tasks.filter(t => !status || t.status === status));
    }
    const deleted = url.pathname.match(/^\/api\/tasks\/(\d+)$/);
    if (deleted && request.method() === 'DELETE') {
      tasks.splice(tasks.findIndex(t => t.id === Number(deleted[1])), 1);
      return route.fulfill({ status: 204, body: '' });
    }
    return ok(null);
  });

  return {
    holdNextList: () => { holdNext = true; },
    waitUntilHeld: () => expect.poll(() => held !== null).toBe(true),
    release: () => { held?.(); held = null; },
  };
}

test('필터를 처음 고를 때 페이지 전체가 로딩 화면으로 바뀌지 않는다', async ({ page }) => {
  const api = await mockTaskPage(page);
  await page.goto('/projects/7/tasks');
  await expect(page.getByText('작업 1', { exact: true })).toBeVisible();

  api.holdNextList();
  await page.locator('main button', { hasText: /^진행 중$/ }).first().click();
  await api.waitUntilHeld();

  await expect(page.getByText('로딩 중...')).toHaveCount(0);
  await expect(page.locator('main button', { hasText: /^차단됨$/ }).first()).toBeVisible();
  await expect(page.getByText('작업 1', { exact: true })).toBeVisible();

  api.release();
  await expect(page.getByText('작업 1', { exact: true })).toHaveCount(0);
  await expect(page.getByText('작업 2', { exact: true })).toBeVisible();
});

test('삭제 확인 창은 지운 카드가 목록에서 빠진 뒤에 닫힌다', async ({ page }) => {
  const api = await mockTaskPage(page);
  await page.goto('/projects/7/tasks');
  const card = page.locator('main .grid > div').filter({ hasText: '작업 3' });
  await expect(card).toBeVisible();

  await card.getByRole('button', { name: '삭제' }).click();
  api.holdNextList();
  await page.getByRole('button', { name: '확인' }).click();
  await api.waitUntilHeld();

  // DELETE는 끝났지만 목록 재조회가 아직이면 창이 열려 있어야 한다.
  // 창이 먼저 닫히는 경우 퇴장 애니메이션(140ms) 동안에는 보이므로, 그보다 충분히 기다린 뒤 확인한다.
  await page.waitForTimeout(500);
  await expect(page.getByRole('heading', { name: 'Task 삭제' })).toBeVisible();

  api.release();
  await expect(page.getByRole('heading', { name: 'Task 삭제' })).toHaveCount(0);
  await expect(page.getByText('작업 3', { exact: true })).toHaveCount(0);
});

test('모달 등장 애니메이션이 끝나는 프레임에 다시 투명해지지 않는다', async ({ page }) => {
  await mockTaskPage(page);
  await page.goto('/projects/7/tasks');
  await expect(page.getByText('작업 1', { exact: true })).toBeVisible();

  // 매 프레임 모달 opacity를 기록한다. framer-motion 12.34.0은 종료 프레임에 opacity 0을 한 번 그렸다.
  await page.evaluate(() => {
    const w = window as unknown as { __opacity: number[] };
    w.__opacity = [];
    const sample = () => {
      const overlay = document.querySelector('div.fixed.inset-0');
      if (overlay) w.__opacity.push(Number(getComputedStyle(overlay).opacity));
      if (w.__opacity.length < 60) requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  });
  await page.getByRole('button', { name: /새 Task/ }).click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { __opacity: number[] }).__opacity.length)).toBe(60);

  const opacity = await page.evaluate(() => (window as unknown as { __opacity: number[] }).__opacity);
  const shown = opacity.findIndex(value => value >= 0.99);
  expect(shown).toBeGreaterThanOrEqual(0);
  expect(Math.min(...opacity.slice(shown))).toBeGreaterThanOrEqual(0.99);
});
