import { clsx } from 'clsx';
export { clsx };

/**
 * 공통 토큰 — "관제실" 방향
 *
 * 규율: 색은 상태를 의미할 때만 쓴다.
 *   먹색(ink) ......... 브랜드, 본문, 버튼, 포커스
 *   유채색 ............ PENDING / PROCESSING / DONE / FAILED 전용
 *
 * 실제 값은 index.css의 CSS 변수에 있다. 라이트 단일 테마다.
 */

const RADIUS = 'rounded-[var(--radius)]';

export const cx = {
  page: 'min-h-screen bg-[var(--paper)] text-[var(--ink)]',

  // ── 면 ────────────────────────────────────────────────
  card: [
    'bg-[var(--surface)] border border-[var(--rule)]',
    RADIUS,
    'p-4',
  ].join(' '),

  cardInteractive: [
    'bg-[var(--surface)] border border-[var(--rule)]',
    RADIUS,
    'p-4 cursor-pointer',
    'hover:border-[var(--rule-strong)] hover:bg-[var(--sunken)]',
    'transition-[background-color,border-color] duration-150',
  ].join(' '),

  // ── 버튼 ──────────────────────────────────────────────
  btn: {
    // 주 동작은 색이 아니라 무게로 눈에 띈다
    primary: [
      'bg-[var(--ink-solid)] text-[var(--ink-inv)]',
      'text-[13px] font-medium whitespace-nowrap',
      RADIUS,
      'px-3.5 py-2',
      'hover:bg-[var(--ink-solid-hover)] transition-colors duration-150',
      'disabled:opacity-35 disabled:cursor-not-allowed',
    ].join(' '),

    secondary: [
      'bg-transparent border border-[var(--rule-strong)]',
      'text-[var(--ink-2)] hover:text-[var(--ink)] hover:border-[var(--ink-3)]',
      'text-[13px] font-medium whitespace-nowrap',
      RADIUS,
      'px-3.5 py-2',
      'transition-colors duration-150',
      'disabled:opacity-35 disabled:cursor-not-allowed',
    ].join(' '),

    danger: [
      'text-[var(--ink-3)] hover:text-[var(--st-failed)]',
      'text-[13px] font-medium whitespace-nowrap',
      'transition-colors duration-150',
      'disabled:opacity-35',
    ].join(' '),

    ghost: [
      'text-[var(--ink-3)] hover:text-[var(--ink)]',
      'text-[13px] font-medium whitespace-nowrap',
      'transition-colors duration-150',
    ].join(' '),

    // 채운 알약 대신 밑줄. 밝은 지면에 검은 블록을 놓지 않는다.
    filter:
      'relative pb-2 text-[14px] transition-colors duration-150 after:absolute after:left-0 after:right-0 after:-bottom-px after:h-[2px] after:transition-colors after:duration-150',
    filterActive: 'text-[var(--ink)] font-medium after:bg-[var(--ink)]',
    filterInactive:
      'text-[var(--ink-3)] hover:text-[var(--ink)] after:bg-transparent',

    statusTransition: [
      'px-2 py-1 text-[12px] font-medium whitespace-nowrap',
      'border border-[var(--rule)] text-[var(--ink-2)]',
      'hover:border-[var(--ink-3)] hover:text-[var(--ink)]',
      RADIUS,
      'transition-colors duration-150',
      'disabled:opacity-35 disabled:cursor-not-allowed',
    ].join(' '),
  },

  // ── 입력 ──────────────────────────────────────────────
  input: [
    'w-full bg-[var(--surface)] border border-[var(--rule-strong)]',
    RADIUS,
    'px-3 py-2 text-[14px] text-[var(--ink)]',
    'placeholder:text-[var(--ink-3)]',
    'focus:outline-none focus:border-[var(--ink)]',
    'transition-colors duration-150',
  ].join(' '),

  inputError: 'border-[var(--st-failed)] focus:border-[var(--st-failed)]',

  textarea: [
    'w-full bg-[var(--surface)] border border-[var(--rule-strong)]',
    RADIUS,
    'px-3 py-2 text-[14px] text-[var(--ink)]',
    'placeholder:text-[var(--ink-3)]',
    'focus:outline-none focus:border-[var(--ink)]',
    'resize-none transition-colors duration-150',
  ].join(' '),

  // ── 모달 ──────────────────────────────────────────────
  overlay:
    'fixed inset-0 bg-[var(--ink)]/25 flex items-center justify-center z-50',
  modal: [
    'bg-[var(--surface)] border border-[var(--rule-strong)]',
    RADIUS,
    'p-6 w-full max-w-md mx-4',
    'shadow-[0_16px_48px_-12px_rgba(20,22,26,0.28)]',
  ].join(' '),

  // ── 헤더 ──────────────────────────────────────────────
  header: [
    'bg-[var(--paper)] border-b border-[var(--rule)]',
    'px-6 py-3',
    'sticky top-0 z-40',
  ].join(' '),

  // ── 상태 배지 ─────────────────────────────────────────
  //  여기가 이 앱에서 색이 허용된 유일한 자리다.
  badge: {
    base: [
      'inline-flex items-center gap-1.5 px-2 py-0.5',
      RADIUS,
      'font-mono text-[11px] font-medium tracking-[0.04em] uppercase',
    ].join(' '),
    REQUESTED: 'bg-[var(--st-pending-bg)] text-[var(--st-pending)]',
    IN_PROGRESS: 'bg-[var(--st-running-bg)] text-[var(--st-running)]',
    DONE: 'bg-[var(--st-done-bg)] text-[var(--st-done)]',
    BLOCKED: 'bg-[var(--st-failed-bg)] text-[var(--st-failed)]',
  },

  // ── 텍스트 계층 ───────────────────────────────────────
  //  이전 버전은 전부 11~13px이라 위계가 없었다.
  //  디스플레이(Archivo)와 본문(Plex Sans KR)의 대비를 만든다.
  text: {
    display:
      'font-[family-name:var(--font-display)] text-[40px] leading-[0.98] font-extrabold tracking-[-0.03em] text-[var(--ink)]',
    heading: 'text-[19px] font-semibold tracking-[-0.015em] text-[var(--ink)]',
    subheading: 'text-[15px] font-semibold text-[var(--ink)]',
    cardTitle: 'text-[14px] font-medium text-[var(--ink)]',
    body: 'text-[14px] text-[var(--ink-2)]',
    // 한글 산문용. 모노스페이스를 쓰면 자간이 벌어져 읽기 나쁘다.
    meta: 'text-[13px] text-[var(--ink-3)]',
    // 숫자·날짜·시각 전용. 자리를 맞춰야 하는 값만 여기에 넣는다.
    data: 'font-mono text-[12px] text-[var(--ink-3)] tabular',
    label:
      'block font-mono text-[11px] font-medium text-[var(--ink-3)] uppercase tracking-[0.1em] mb-1.5',
  },

  // ── 구분선 ───────────────────────────────────────────
  divider: 'border-t border-[var(--rule)]',

  // ── 에러 / 빈 상태 ────────────────────────────────────
  errorBox: [
    'px-3 py-2.5',
    'bg-[var(--st-failed-bg)] text-[var(--st-failed)]',
    RADIUS,
    'text-[13px]',
  ].join(' '),

  emptyState: 'py-16 text-[var(--ink-3)] text-[14px]',
} as const;

/**
 * 파이프라인 4단계 — 로그인 화면과 상태 배지가 같은 어휘를 쓴다.
 *
 * mark  점·스와치용 선명한 색
 * text  글자용 어두운 색
 *
 * 첫 단계 `저장`만 무채색이다. 사용자가 한 일이지 기계의 상태가
 * 아니므로, 색을 주지 않는 것이 이 화면의 규율에 맞다.
 */
export const PIPELINE = [
  { ko: '저장', en: 'SAVED', mark: 'var(--ink-3)', text: 'var(--ink-2)' },
  {
    ko: '적재',
    en: 'PENDING',
    mark: 'var(--st-pending-mark)',
    text: 'var(--st-pending)',
  },
  {
    ko: '처리',
    en: 'PROCESSING',
    mark: 'var(--st-running-mark)',
    text: 'var(--st-running)',
  },
  {
    ko: '동기화',
    en: 'SYNCED',
    mark: 'var(--st-done-mark)',
    text: 'var(--st-done)',
  },
] as const;

/**
 * 동기화 상태 — 기계가 지금 어디까지 했는지.
 * 색이 붙는 자리는 여기뿐이다.
 */
export const SYNC_STATE = {
  SYNCED: { ko: '동기화됨', mark: 'var(--st-done-mark)' },
  PENDING_SYNC: { ko: '대기', mark: 'var(--st-pending-mark)' },
  FAILED_SYNC: { ko: '실패', mark: 'var(--st-failed-mark)' },
  SYNC_DISABLED: { ko: '연동 안 함', mark: 'var(--ink-3)' },
  DELETE_PENDING: { ko: '삭제 대기', mark: 'var(--st-pending-mark)' },
  DELETE_FAILED: { ko: '삭제 실패', mark: 'var(--st-failed-mark)' },
} as const;

/**
 * 작업 상태 — 사람이 정하는 것.
 * 기계 상태와 구분하려고 색 대신 무채색 글자로만 쓴다.
 */
export const TASK_STATUS = {
  REQUESTED: '요청됨',
  IN_PROGRESS: '진행 중',
  DONE: '완료',
  BLOCKED: '막힘',
} as const;
