import { cx, clsx } from '@/styles/cx';
import { motion } from 'framer-motion';
import { MOTION } from '@/styles/motion';

export default function HomePage() {
  const step = (index: number) => ({
    initial: { opacity: 0, transform: 'translateY(8px)' },
    animate: { opacity: 1, transform: 'translateY(0)' },
    transition: { ...MOTION.enter, delay: index * 0.05 },
  });

  return (
    <div className={clsx(cx.page, 'px-6')}>
      <header className="mx-auto flex max-w-5xl items-center justify-between border-b border-[var(--rule)] py-5">
        <span className="font-[family-name:var(--font-display)] text-[15px] font-extrabold tracking-[-0.02em]">
          TASKFLOW
        </span>
        <nav className="flex items-center gap-4 text-[13px] text-[var(--ink-3)]" aria-label="Public">
          <a href="/privacy" className="hover:text-[var(--ink)]">Privacy</a>
          <a href="/terms" className="hover:text-[var(--ink)]">Terms</a>
        </nav>
      </header>

      <main className="mx-auto flex min-h-[calc(100vh-65px)] max-w-5xl flex-col justify-center py-4">
        <div className="max-w-3xl">
          <motion.p {...step(0)} className="font-mono text-[11px] font-medium uppercase tracking-[0.1em] text-[var(--ink-3)]">
            할 일은 간단히, 일정은 정확하게
          </motion.p>
          <motion.h1 {...step(1)} className="mt-4 font-[family-name:var(--font-display)] text-[clamp(48px,9vw,96px)] font-extrabold leading-[0.9] tracking-[-0.055em]">
            쓰는 대로,<br />맞춰진다.
          </motion.h1>
          <motion.p {...step(2)} className="mt-8 max-w-2xl text-[17px] leading-8 text-[var(--ink-2)]">
            할 일을 적으면 Google Calendar 일정으로 이어집니다.<br />
            수정하거나 삭제해도 두 곳을 따로 관리할 필요가 없습니다.
          </motion.p>
          <motion.a {...step(3)} href="/login" className={clsx(cx.btn.primary, 'mt-8 inline-flex px-5 py-3 text-[14px]')}>
            TaskFlow 시작하기
          </motion.a>
        </div>

        <motion.section {...step(4)} className="mt-12 grid gap-8 border-t border-[var(--rule)] py-8 sm:grid-cols-2">
          <div>
            <h2 className={cx.text.heading}>동기화는 사용자가 결정합니다</h2>
            <p className="mt-3 text-[14px] leading-7 text-[var(--ink-2)]">
              Task마다 캘린더 동기화 여부를 선택할 수 있습니다.<br />
              Google 연결을 해제하면 이후 동기화만 중단되고<br />
              기존 일정은 그대로 유지됩니다.
            </p>
          </div>
          <div>
            <h2 className={cx.text.heading}>필요한 일정만 관리합니다</h2>
            <p className="mt-3 text-[14px] leading-7 text-[var(--ink-2)]">
              TaskFlow는 사용자가 소유한 캘린더의 일정 관리 권한을 요청하지만<br />
              현재는 기본 캘린더에 연결한 일정만 다룹니다.<br />
              관련 없는 일정은 조회하거나 가져오지 않습니다.
            </p>
          </div>
        </motion.section>
      </main>
    </div>
  );
}
