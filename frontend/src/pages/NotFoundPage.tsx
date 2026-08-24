import { cx } from '@/styles/cx';

export default function NotFoundPage() {
  return (
    <div className={`${cx.page} grid place-items-center px-6`}>
      <main className="text-center">
        <h1 className="text-[28px] font-semibold tracking-[-0.02em]">페이지를 찾을 수 없습니다</h1>
        <p className="mt-3 text-[14px] text-[var(--ink-2)]">
          주소를 확인하거나 TaskFlow의 다른 페이지로 이동해주세요.
        </p>
        <nav aria-label="404 이동" className="mt-7 flex justify-center gap-3">
          <a href="/" className={cx.btn.secondary}>홈으로</a>
          <a href="/login" className={cx.btn.primary}>로그인</a>
        </nav>
      </main>
    </div>
  );
}
