import { useState, type ReactNode } from 'react';

type Localized = { en: string; ko: string };

type Props = {
  title: Localized;
  effectiveDate: Localized;
  en: ReactNode;
  ko: ReactNode;
};

/**
 * 약관·개인정보처리방침 공통 껍데기.
 *
 * 기본값은 반드시 영문이다. 이 페이지 주소를 Google OAuth 심사에 제출하므로,
 * 심사관이 열었을 때 영문 고지가 먼저 보여야 한다.
 */
export default function LegalPage({ title, effectiveDate, en, ko }: Props) {
  const [lang, setLang] = useState<'en' | 'ko'>('en');

  return (
    <main className="min-h-screen bg-[var(--paper)] px-6 py-12 text-[var(--ink)]">
      <article className="mx-auto max-w-3xl">
        <a
          href="/projects"
          className="font-[family-name:var(--font-display)] text-[15px] font-extrabold tracking-[-0.02em]"
        >
          TaskFlow
        </a>

        <header className="mt-12 border-b border-[var(--rule)] pb-8">
          <p className="font-mono text-[11px] font-medium uppercase tracking-[0.1em] text-[var(--ink-3)]">
            Legal
          </p>
          <h1 className="mt-2 font-[family-name:var(--font-display)] text-[40px] font-extrabold tracking-[-0.03em]">
            {title[lang]}
          </h1>
          <div className="mt-3 flex items-baseline justify-between gap-4">
            <p className="text-[14px] text-[var(--ink-2)]">{effectiveDate[lang]}</p>
            <div className="flex shrink-0 gap-3 font-mono text-[11px] uppercase tracking-[0.1em]">
              {(['en', 'ko'] as const).map((code) => (
                <button
                  key={code}
                  type="button"
                  onClick={() => setLang(code)}
                  aria-pressed={lang === code}
                  className={
                    lang === code
                      ? 'font-semibold text-[var(--ink)] underline underline-offset-4'
                      : 'text-[var(--ink-3)] hover:text-[var(--ink-2)]'
                  }
                >
                  {code === 'en' ? 'English' : '한국어'}
                </button>
              ))}
            </div>
          </div>
        </header>

        <div
          lang={lang}
          className="space-y-10 py-10 text-[14px] leading-7 text-[var(--ink-2)]"
        >
          {lang === 'en' ? en : ko}
        </div>
      </article>
    </main>
  );
}
