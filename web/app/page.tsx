import { connection } from "next/server";
import { loginSettings } from "./portal-config";
import RegistrationForm from "./registration-form";

export default async function Page() {
  await connection();

  return (
    <main className="portal">
      <section className="intro" aria-labelledby="page-title">
        <p className="brand"><span aria-hidden="true">✦</span> LOGH7</p>
        <p className="series">은하영웅전설 VII</p>
        <h1 id="page-title">게임 계정 만들기</h1>
        <p className="lede">여기서 만든 아이디와 비밀번호로 게임에 로그인할 수 있습니다.</p>
        <svg className="orbits" viewBox="0 0 380 168" aria-hidden="true" fill="none">
          <path d="M0 84h380M190 0v168" stroke="currentColor" strokeOpacity=".2" strokeDasharray="2 5"/>
          <ellipse cx="190" cy="84" rx="146" ry="46" transform="rotate(-14 190 84)" stroke="currentColor" strokeOpacity=".6"/>
          <ellipse cx="190" cy="84" rx="110" ry="32" transform="rotate(18 190 84)" stroke="currentColor" strokeOpacity=".4"/>
          <circle cx="190" cy="84" r="56" stroke="currentColor" strokeOpacity=".35"/>
          <path d="M190 75v18m-9-9h18" stroke="currentColor"/>
          <circle cx="98" cy="67" r="4" fill="currentColor"/>
          <circle cx="316" cy="61" r="3" fill="currentColor"/>
          <circle cx="246" cy="109" r="2" fill="currentColor"/>
        </svg>
      </section>
      <section className="panel" aria-label="플레이어 가입">
        <RegistrationForm login={loginSettings()} />
        <div className="registration-footer">
          <a className="portal-link" href="/password">기존 게임 비밀번호 변경</a>
          <a className="portal-link" href="/password/recovery">비밀번호 복구</a>
        </div>
      </section>
    </main>
  );
}
