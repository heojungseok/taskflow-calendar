import LegalPage from './legal/LegalPage';
import PrivacyEn from './legal/PrivacyEn';
import PrivacyKo from './legal/PrivacyKo';

export default function PrivacyPage() {
  return (
    <LegalPage
      title={{ en: 'Privacy Policy', ko: '개인정보처리방침' }}
      effectiveDate={{ en: 'Effective date: October 27, 2026', ko: '시행일: 2026년 10월 27일' }}
      en={<PrivacyEn />}
      ko={<PrivacyKo />}
    />
  );
}
