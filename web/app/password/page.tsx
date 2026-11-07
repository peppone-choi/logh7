import type { Metadata } from "next";
import PasswordForm from "./password-form";

export const metadata: Metadata = { title: "게임 비밀번호 변경 | LOGH7" };

export default function Page() {
  return (
    <main className="portal">
      <section className="intro" aria-labelledby="page-title">
        <p className="brand"><span aria-hidden="true">✦</span> LOGH7</p>
        <p className="series">은하영웅전설 VII</p>
        <h1 id="page-title">게임 비밀번호 변경</h1>
        <p className="lede">현재 비밀번호를 확인한 뒤 새 비밀번호를 저장합니다. 다음 게임 로그인부터 새 비밀번호를 사용하세요.</p>
        <p><a className="portal-link" href="/password/recovery">비밀번호를 잊었거나 복구 코드를 준비하려면</a></p>
        <a className="portal-link" href="/">게임 계정 만들기</a>
      </section>
      <section className="panel" aria-label="게임 비밀번호 변경 양식">
        <PasswordForm />
      </section>
    </main>
  );
}
