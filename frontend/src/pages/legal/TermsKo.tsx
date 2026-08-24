const h2 = 'text-[19px] font-semibold text-[var(--ink)]';
const link = 'underline underline-offset-4';

export default function TermsKo() {
  return (
    <>
      <p className="text-[13px] text-[var(--ink-3)]">
        이 문서는 영문 이용약관의 번역본입니다. 해석에 차이가 있을 경우 영문본이 우선합니다.
      </p>

      <section>
        <h2 className={h2}>1. 서비스와 운영자</h2>
        <p className="mt-3">
          TaskFlow는 허정석이 운영합니다. TaskFlow에서 프로젝트와 작업을 관리할 수 있으며, 사용자가 선택한 경우 기본
          Google Calendar에 연결된 이벤트를 생성·수정·삭제합니다. 문의는{' '}
          <a className={link} href="mailto:tjrwjdgj@gmail.com">tjrwjdgj@gmail.com</a>으로 해주세요.
        </p>
      </section>

      <section>
        <h2 className={h2}>2. 계정과 이용자의 책임</h2>
        <p className="mt-3">
          사용자는 자신의 Google 계정으로 이루어진 활동과 자신이 생성한 작업 및 캘린더 이벤트에 대해 책임을 집니다.
          TaskFlow를 오용하거나, 서비스 운영을 방해하거나, 관련 법령 또는 타인의 권리를 침해하는 방식으로 이용해서는
          안 됩니다.
        </p>
      </section>

      <section>
        <h2 className={h2}>3. Google Calendar 및 AI 기능</h2>
        <p className="mt-3">
          캘린더 동기화에는 사용자의 Google 권한 승인이 필요합니다. 연결은 언제든 해제할 수 있으며, 해제하면 이후
          동기화는 중단되지만 이미 생성된 캘린더 이벤트는 삭제되지 않습니다. AI 기능은 사용자가 요청할 때만
          실행되며, 개인정보처리방침에 기재된 대로 관련 작업 항목이 Google Gemini로 전송될 수 있습니다.
        </p>
      </section>

      <section>
        <h2 className={h2}>4. 서비스 제공과 변경</h2>
        <p className="mt-3">
          TaskFlow는 제공 가능한 범위에서(as-available) 제공됩니다. 기능은 변경·중단·종료될 수 있으며, 네트워크나
          외부 서비스 장애로 동기화가 지연될 수 있습니다. 약관의 중요한 변경은 시행일을 갱신해 이 페이지에
          게시합니다.
        </p>
      </section>

      <section>
        <h2 className={h2}>5. 이용 종료</h2>
        <p className="mt-3">
          사용자는 언제든 TaskFlow 이용을 중단하거나 계정 삭제를 요청할 수 있습니다. 운영자는 서비스와 다른
          사용자를 보호하거나 법령을 준수하기 위해 필요한 범위에서 접근을 제한할 수 있습니다. 데이터 삭제는{' '}
          <a className={link} href="/privacy">개인정보처리방침</a>에 기재된 대로 처리합니다.
        </p>
      </section>

      <section>
        <h2 className={h2}>6. 면책과 책임의 한계</h2>
        <p className="mt-3">
          법이 허용하는 범위에서 TaskFlow는 어떠한 보증도 없이 제공되며, 운영자는 서비스 이용이나 외부 서비스 장애로
          발생한 간접적·결과적 손해에 대해 책임을 지지 않습니다. 다만 법적으로 제한할 수 없는 권리나 책임은 이 약관에
          의해 제한되지 않습니다.
        </p>
      </section>
    </>
  );
}
