import {useCallback, useState} from 'react';
import {motion, useReducedMotion} from 'framer-motion';
import HomeWordmark from '@/components/HomeWordmark';
import {cx, clsx} from '@/styles/cx';

const HOLD_S = 0.1;
const SHRINK_S = 0.48;
const COLOR_S = 0.22;
const BLINK_S = 0.65;
const WAVE_S = 1.1;
const WAVE_DELAY_S = HOLD_S + SHRINK_S + BLINK_S;
const EASE_IN_OUT = [0.77, 0, 0.175, 1] as const;
const EASE_OUT = [0.23, 1, 0.32, 1] as const;

type SlotFrame = {
    top: number;
    left: number;
    centerX: number;
    centerY: number;
    width: number;
    height: number;
    radius: number;
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
            left: rect.left,
            centerX,
            centerY,
            width: rect.width,
            height: rect.height,
            radius: Math.hypot(
                Math.max(centerX, pageWidth - centerX),
                Math.max(centerY, pageHeight - centerY),
            ) * 1.013,
        };

        setSlot(current => current
            && Math.abs(current.left - next.left) < 0.25
            && Math.abs(current.top - next.top) < 0.25
            && Math.abs(current.width - next.width) < 0.25
            && Math.abs(current.height - next.height) < 0.25
            ? current
            : next);
    }, []);

    const overlayTransform = slot
        ? `translate(${slot.left}px, ${slot.top}px) scale(${slot.width / window.innerWidth}, ${slot.height / window.innerHeight})`
        : 'translate(0px, 0px) scale(1, 1)';
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
                    initial={{
                        backgroundColor: '#f7f8f6',
                        opacity: 1,
                        transform: 'translate(0px, 0px) scale(1, 1)',
                    }}
                    animate={slot ? {
                        backgroundColor: '#14161a',
                        opacity: [1, 0.2, 1, 0.25, 1],
                        transform: overlayTransform,
                    } : undefined}
                    transition={slot ? {
                        backgroundColor: {
                            delay: HOLD_S + SHRINK_S - COLOR_S,
                            duration: COLOR_S,
                            ease: EASE_IN_OUT,
                        },
                        opacity: {
                            delay: HOLD_S + SHRINK_S,
                            duration: BLINK_S,
                            times: [0, 0.26, 0.52, 0.78, 1],
                            ease: EASE_OUT,
                        },
                        transform: {
                            delay: HOLD_S,
                            duration: SHRINK_S,
                            ease: EASE_IN_OUT,
                        },
                    } : {duration: 0}}
                    style={{
                        borderRadius: '0.75px',
                        transformOrigin: '0 0',
                        willChange: 'transform, background-color, opacity',
                    }}
                />
            )}

            <motion.div
                key={slot ? 'wave-ready' : 'wave-measuring'}
                data-testid="home-wave"
                className={clsx(cx.page, 'px-6')}
                initial={reduceMotion || !slot ? false : {clipPath: waveStart}}
                animate={{clipPath: reduceMotion ? 'none' : waveEnd}}
                transition={reduceMotion || !slot ? {duration: 0} : {
                    clipPath: {
                        delay: WAVE_DELAY_S,
                        duration: WAVE_S,
                        ease: 'linear',
                    },
                }}
                onAnimationComplete={slot && !reduceMotion ? () => setOverlayDone(true) : undefined}
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

                        <p data-testid="home-slogan" className="mt-4 text-left font-[family-name:var(--font-display)] text-[clamp(30px,5vw,52px)] font-extrabold leading-tight tracking-[-0.04em]">
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
