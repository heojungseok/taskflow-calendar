import {useCallback, useState} from 'react';
import {motion, useReducedMotion} from 'framer-motion';
import HomeWordmark from '@/components/HomeWordmark';
import {cx, clsx} from '@/styles/cx';
import {MOTION_EASE_OUT} from '@/styles/motion';

const DURATION = 6;
const FULL_SCREEN = 'inset(0px 0px 0px 0px round 0px)';

type SlotFrame = {
    top: number;
    right: number;
    bottom: number;
    left: number;
    radius: number;
    centerX: number;
    centerY: number;
};

export default function HomePage() {
    const reduceMotion = useReducedMotion();
    const [slot, setSlot] = useState<SlotFrame | null>(null);
    const [overlayDone, setOverlayDone] = useState(false);

    const setSlotLayout = useCallback((rect: DOMRect) => {
        const centerX = rect.left + rect.width / 2;
        const centerY = rect.top + window.scrollY + rect.height / 2;
        const pageWidth = document.documentElement.scrollWidth;
        const pageHeight = document.documentElement.scrollHeight;
        const next = {
            top: rect.top,
            right: window.innerWidth - rect.right,
            bottom: window.innerHeight - rect.bottom,
            left: rect.left,
            radius: Math.hypot(
                Math.max(centerX, pageWidth - centerX),
                Math.max(centerY, pageHeight - centerY),
            ),
            centerX,
            centerY,
        };

        setSlot(current => current
            && Math.abs(current.left - next.left) < 0.25
            && Math.abs(current.top - next.top) < 0.25
            ? current
            : next);
    }, []);

    const targetClip = slot
        ? `inset(${slot.top}px ${slot.right}px ${slot.bottom}px ${slot.left}px round 0.75px)`
        : FULL_SCREEN;
    const waveStart = slot
        ? `circle(0px at ${slot.centerX}px ${slot.centerY}px)`
        : 'circle(0px at 0px 0px)';
    const waveEnd = slot
        ? `circle(${slot.radius}px at ${slot.centerX}px ${slot.centerY}px)`
        : waveStart;

    return (
        <>
            {!reduceMotion && !overlayDone && (
                <motion.div
                    data-testid="home-entry-overlay"
                    aria-hidden="true"
                    className="pointer-events-none fixed inset-0 z-[100]"
                    initial={{backgroundColor: '#f7f8f6', clipPath: FULL_SCREEN, opacity: 1}}
                    animate={slot ? {
                        backgroundColor: '#14161a',
                        clipPath: targetClip,
                        opacity: [1, 0.2, 1, 0.25, 1],
                    } : undefined}
                    transition={slot ? {
                        backgroundColor: {
                            delay: DURATION * 0.2,
                            duration: DURATION * 0.09,
                            ease: [0.77, 0, 0.175, 1],
                        },
                        clipPath: {
                            delay: DURATION * 0.08,
                            duration: DURATION * 0.21,
                            ease: [0.77, 0, 0.175, 1],
                        },
                        opacity: {
                            delay: DURATION * 0.29,
                            duration: DURATION * 0.23,
                            times: [0, 0.26, 0.52, 0.78, 1],
                            ease: MOTION_EASE_OUT,
                        },
                    } : {duration: 0}}
                    onAnimationComplete={slot ? () => setOverlayDone(true) : undefined}
                />
            )}

            <motion.div
                key={slot ? 'wave-ready' : 'wave-measuring'}
                className={clsx(cx.page, 'px-6')}
                initial={reduceMotion || !slot ? false : {clipPath: waveStart}}
                animate={{clipPath: reduceMotion ? 'none' : waveEnd}}
                transition={reduceMotion || !slot ? {duration: 0} : {
                    delay: DURATION * 0.43,
                    duration: DURATION * 0.41,
                    ease: [0.77, 0, 0.175, 1],
                }}
                style={!slot && !reduceMotion ? {visibility: 'hidden'} : undefined}
            >
                <header className="mx-auto flex max-w-5xl items-center justify-between border-b border-[var(--rule)] py-5">
                <span className="inline-flex min-h-[18px] min-w-[72px] items-center font-[family-name:var(--font-display)] text-[15px] font-extrabold tracking-[-0.02em] [&>img]:h-[18px] [&>img]:w-auto [&>svg]:h-[18px] [&>svg]:w-auto">
                    TaskFlow
                </span>
                <nav className="flex items-center gap-4 text-[13px] text-[var(--ink-3)]" aria-label="Public">
                    <a href="/privacy" className="hover:text-[var(--ink)]">Privacy</a>
                    <a href="/terms" className="hover:text-[var(--ink)]">Terms</a>
                </nav>
                </header>

            <main className="mx-auto flex min-h-[calc(100vh-65px)] max-w-5xl flex-col justify-center py-4">
                <div className="max-w-3xl">
                    <p className="font-mono text-[11px] font-medium uppercase tracking-[0.1em] text-[var(--ink-3)]">
                        할 일은 간단히, 일정은 정확하게
                    </p>
                    <h1 className="mt-4 w-full max-w-[720px]">
                        <HomeWordmark onSlotLayout={setSlotLayout}/>
                    </h1>

                    <p data-testid="home-slogan" className="mt-4 font-[family-name:var(--font-display)] text-[clamp(30px,5vw,52px)] font-extrabold leading-tight tracking-[-0.04em]">
                        맞춰진다.
                        <br />
                        쓰는 대로.
                    </p>

                    <p className="mt-8 max-w-2xl text-[17px] leading-8 text-[var(--ink-2)]">
                        TaskFlow는 사용자가 선택한 할 일을 Google Calendar 일정으로<br/>
                        생성·수정·삭제하는 작업 관리 서비스입니다.<br/>
                        관련 없는 기존 캘린더 일정은 조회하거나 가져오지 않습니다.

                        <span lang="en" className="mt-4 block text-[14px] leading-6">
                            TaskFlow is a task management service that creates, updates, and deletes
                            Google Calendar events for Tasks selected by the user. It does not list or
                            import unrelated calendar events.
                        </span>
                    </p>
                    <a href="/projects" className={clsx(cx.btn.primary, 'mt-8 inline-flex px-5 py-3 text-[14px]')}>
                        TaskFlow 시작하기
                    </a>
                </div>

                <section className="mt-12 grid gap-8 border-t border-[var(--rule)] py-8 sm:grid-cols-2">
                    <div>
                        <h2 className={cx.text.heading}>동기화는 사용자가 결정합니다</h2>
                        <p className="mt-3 text-[14px] leading-7 text-[var(--ink-2)]">
                            할 일마다 캘린더 동기화 여부를 선택할 수 있습니다.<br/>
                            구글 연결을 해제하면 이후 동기화만 중단되고<br/>
                            기존 일정은 그대로 유지됩니다.
                        </p>
                    </div>
                    <div>
                        <h2 className={cx.text.heading}>필요한 일정만 관리합니다</h2>
                        <p className="mt-3 text-[14px] leading-7 text-[var(--ink-2)]">
                            TaskFlow는 사용자가 소유한 캘린더의 일정 관리 권한을 요청하지만<br/>
                            현재는 기본 캘린더에 연결한 일정만 다룹니다.<br/>
                            관련 없는 일정은 조회하거나 가져오지 않습니다.
                        </p>
                    </div>
                </section>
            </main>
            </motion.div>
        </>
    );
}
