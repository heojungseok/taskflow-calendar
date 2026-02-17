import { clsx } from 'clsx';
export { clsx };

export const cx = {
  // ── 레이아웃 ──────────────────────────────────────────
  page: 'min-h-screen bg-[#0a0a0f] text-[#e8e8ed]',

  // ── 카드 ──────────────────────────────────────────────
  card: [
    'bg-[#111118] border border-[#1e1e2a]',
    'rounded-[3px] p-4',
    'shadow-[0_1px_3px_rgba(0,0,0,0.4)]',
  ].join(' '),

  cardInteractive: [
    'bg-[#111118] border border-[#1e1e2a]',
    'rounded-[3px] p-4',
    'shadow-[0_1px_3px_rgba(0,0,0,0.4)]',
    'hover:border-[#3b5bff]/60 hover:bg-[#13131c]',
    'transition-all duration-150 cursor-pointer',
  ].join(' '),

  // ── 버튼 ──────────────────────────────────────────────
  btn: {
    primary: [
      'bg-[#3b5bff] hover:bg-[#2d4de0]',
      'text-white text-xs font-medium tracking-wide',
      'rounded-[3px] px-3 py-1.5',
      'transition-colors duration-150',
      'disabled:opacity-40 disabled:cursor-not-allowed',
    ].join(' '),

    secondary: [
      'bg-transparent border border-[#2a2a38]',
      'text-[#a0a0bc] hover:text-[#e8e8ed] hover:border-[#3a3a50]',
      'text-xs font-medium tracking-wide',
      'rounded-[3px] px-3 py-1.5',
      'transition-all duration-150',
      'disabled:opacity-40',
    ].join(' '),

    danger: [
      // 기본은 눈에 띄지 않게, 호버 시 붉게
      'text-[#686884] hover:text-[#ff5555]',
      'text-xs font-medium',
      'transition-colors duration-150',
      'disabled:opacity-40',
    ].join(' '),

    ghost: [
      'text-[#7a7a96] hover:text-[#b0b0c8]',
      'text-xs font-medium',
      'transition-colors duration-150',
    ].join(' '),

    // 필터 탭 버튼
    filter: 'px-2.5 py-1 text-xs font-medium rounded-[3px] transition-all duration-150',
    filterActive:   'bg-[#3b5bff]/10 text-[#8aabff] border border-[#3b5bff]/30',
    filterInactive: 'text-[#8888a4] hover:text-[#b4b4cc] border border-transparent',

    // 상태 전이 버튼
    statusTransition: [
      'px-2 py-1 text-[11px] font-medium',
      'border border-[#252535] text-[#9898b4]',
      'hover:border-[#303048] hover:text-[#c0c0d8]',
      'rounded-[3px] transition-all duration-150',
      'disabled:opacity-40 disabled:cursor-not-allowed',
    ].join(' '),
  },

  // ── 입력 ──────────────────────────────────────────────
  input: [
    'w-full bg-[#0d0d14] border border-[#1e1e2a]',
    'rounded-[3px] px-3 py-2 text-sm text-[#e8e8ed]',
    'placeholder:text-[#3a3a50]',
    'focus:outline-none focus:border-[#3b5bff]/60 focus:ring-1 focus:ring-[#3b5bff]/20',
    'transition-colors duration-150',
  ].join(' '),

  inputError: 'border-[#ff5555]/60 focus:border-[#ff5555]/60 focus:ring-[#ff5555]/20',

  textarea: [
    'w-full bg-[#0d0d14] border border-[#1e1e2a]',
    'rounded-[3px] px-3 py-2 text-sm text-[#e8e8ed]',
    'placeholder:text-[#3a3a50]',
    'focus:outline-none focus:border-[#3b5bff]/60 focus:ring-1 focus:ring-[#3b5bff]/20',
    'resize-none transition-colors duration-150',
  ].join(' '),

  // ── 모달 ──────────────────────────────────────────────
  overlay: 'fixed inset-0 bg-black/70 flex items-center justify-center z-50 backdrop-blur-sm',
  modal: [
    'bg-[#111118] border border-[#1e1e2a]',
    'rounded-[4px] p-6 w-full max-w-md mx-4',
    'shadow-[0_8px_32px_rgba(0,0,0,0.6)]',
  ].join(' '),

  // ── 헤더 ──────────────────────────────────────────────
  header: [
    'bg-[#0a0a0f]/80 border-b border-[#1a1a24]',
    'backdrop-blur-md px-5 py-2.5',
    'sticky top-0 z-40',
  ].join(' '),

  // ── 상태 배지 ─────────────────────────────────────────
  badge: {
    base: 'inline-flex items-center gap-1 px-1.5 py-0.5 rounded-[3px] text-[11px] font-medium tracking-wide',
    // 텍스트 색상을 이전보다 한 단계씩 밝게
    REQUESTED:   'bg-[#1a1a24] text-[#9090aa] border border-[#2a2a36]',
    IN_PROGRESS: 'bg-[#1a2040] text-[#7a9cff] border border-[#2a3558]',
    DONE:        'bg-[#0f2820] text-[#4de89c] border border-[#1a4030]',
    BLOCKED:     'bg-[#2a1018] text-[#ff7878] border border-[#3d1520]',
  },

  // ── 텍스트 계층 ───────────────────────────────────────
  //
  //  heading    #f0f0f5  — 제목, 가장 밝음
  //  subheading #d4d4e4  — 섹션 헤더
  //  cardTitle  #c8c8dc  — 카드 안 타이틀
  //  body       #a0a0bc  — 본문, 설명
  //  meta       #7a7a96  — 날짜, 부가 정보
  //  label      #8888a4  — 폼 레이블, 소형 라벨
  //
  text: {
    heading:    'text-[15px] font-semibold text-[#f0f0f5] tracking-tight',
    subheading: 'text-sm font-semibold text-[#d4d4e4] tracking-tight',
    cardTitle:  'text-[13px] font-medium text-[#c8c8dc]',
    body:       'text-[13px] text-[#a0a0bc]',
    meta:       'text-[11px] text-[#9090aa]',
    label:      'block text-[11px] font-medium text-[#9898b4] uppercase tracking-wider mb-1.5',
  },

  // ── 구분선 ───────────────────────────────────────────
  divider: 'border-t border-[#1a1a24]',

  // ── 에러 / 빈 상태 ────────────────────────────────────
  errorBox: [
    'p-3 bg-[#2a1018] border border-[#3d1520]',
    'rounded-[3px] text-[#ff8080] text-xs',
  ].join(' '),

  emptyState: 'text-center py-20 text-[#686884]',
} as const;
