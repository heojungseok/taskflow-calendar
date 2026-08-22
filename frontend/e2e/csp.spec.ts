import { expect, test, type Page } from '@playwright/test';

/**
 * CSP 위반 회귀 검사.
 *
 * CSP 위반은 화면에 드러나지 않고 콘솔에만 남는다. 눈으로 보면 멀쩡한데 특정 기능만
 * 죽는 식이라 수동 확인으로는 놓치기 쉽다.
 *
 * 이 검사는 nginx가 서빙할 때만 의미가 있다. Vite dev 서버는 CSP 헤더를 붙이지 않으므로
 * "위반 0건"이 통과가 아니라 정책 부재를 뜻한다. 그래서 nginx를 가리키는 NGINX_BASE_URL이
 * 있을 때만 실행하고, 없으면 건너뛴다. 다른 e2e 스펙의 기본 실행을 방해하지 않기 위해서다.
 *
 * frontend/nginx.conf를 고친 뒤 배포 전후로 실행한다.
 *
 *   NGINX_BASE_URL=http://127.0.0.1:8088 npx playwright test csp
 */

const NGINX = process.env.NGINX_BASE_URL;

type Violation = { directive: string; blocked: string };

const url = (path: string) => new URL(path, NGINX).toString();

const collect = (page: Page): Promise<Violation[]> =>
  page.evaluate(() => (window as unknown as { __csp: Violation[] }).__csp ?? []);

test.describe('CSP', () => {
  test.skip(!NGINX, 'NGINX_BASE_URL이 없다. nginx를 가리켜 실행해야 의미가 있다.');

  test.beforeEach(async ({ page }) => {
    // 페이지 스크립트보다 먼저 실행돼야 최초 로드 위반까지 잡는다.
    await page.addInitScript(() => {
      (window as unknown as { __csp: unknown[] }).__csp = [];
      document.addEventListener('securitypolicyviolation', e => {
        (window as unknown as { __csp: unknown[] }).__csp.push({
          directive: e.violatedDirective,
          blocked: e.blockedURI,
        });
      });
    });
  });

  // 대조군. 이게 없으면 "위반 0건"이 정책 부재와 구분되지 않는다.
  // CSP 헤더를 통째로 지워도 나머지 테스트는 전부 통과한다.
  test('정책이 실제로 적용 중이다', async ({ page }) => {
    await page.goto(url('/'));

    const result = await page.evaluate(async () => {
      const s = document.createElement('script');
      s.textContent = 'window.__inlineRan = true;';
      document.head.appendChild(s);

      const bad = document.createElement('link');
      bad.rel = 'stylesheet';
      bad.href = 'https://cdn.example.com/evil.css';
      document.head.appendChild(bad);

      await new Promise(r => setTimeout(r, 1500));
      return {
        inlineRan: (window as unknown as { __inlineRan?: boolean }).__inlineRan === true,
        violations: (window as unknown as { __csp: Violation[] }).__csp,
      };
    });

    expect(result.inlineRan, 'CSP 헤더가 없다. 배포되지 않은 이미지를 보고 있을 수 있다').toBe(false);
    expect(result.violations.some(v => v.blocked.includes('cdn.example.com'))).toBe(true);
  });

  for (const path of ['/', '/login', '/privacy', '/terms', '/projects']) {
    test(`위반 없음: ${path}`, async ({ page }) => {
      await page.goto(url(path));
      await page.waitForLoadState('networkidle');

      expect(await collect(page), `${path} 에서 CSP 위반 발생`).toEqual([]);
    });
  }

  test('Google Fonts가 실제로 로드된다', async ({ page }) => {
    const responses: { url: string; status: number }[] = [];
    page.on('response', r => {
      if (r.url().includes('fonts.googleapis.com') || r.url().includes('fonts.gstatic.com')) {
        responses.push({ url: r.url(), status: r.status() });
      }
    });

    await page.goto(url('/'));
    await page.waitForLoadState('networkidle');
    // 폰트 파일은 스타일시트 파싱 뒤에 요청되므로 한 박자 기다린다.
    await page.waitForTimeout(2000);

    const stylesheet = responses.find(r => r.url.includes('fonts.googleapis.com'));
    expect(stylesheet, 'CSP가 폰트 스타일시트를 막았거나 요청 자체가 없다').toBeTruthy();
    expect(stylesheet!.status).toBe(200);

    expect(await collect(page)).toEqual([]);
  });

  test('index.html은 캐시되지 않는다', async ({ page }) => {
    // 캐시되면 새 배포를 해도 옛 화면과 옛 CSP가 계속 적용된다.
    const response = await page.goto(url('/'));
    expect(response!.headers()['cache-control']).toContain('no-cache');
  });
});
