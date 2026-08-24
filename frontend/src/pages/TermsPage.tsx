import LegalPage from './legal/LegalPage';
import TermsEn from './legal/TermsEn';
import TermsKo from './legal/TermsKo';

export default function TermsPage() {
  return (
    <LegalPage
      title={{ en: 'Terms of Service', ko: '서비스 이용약관' }}
      effectiveDate={{ en: 'Effective date: August 24, 2026', ko: '시행일: 2026년 8월 24일' }}
      en={<TermsEn />}
      ko={<TermsKo />}
    />
  );
}
