import type { Metadata } from "next";
import PasswordForm from "../password-form";

export const metadata: Metadata = { title: "게임 비밀번호 복구 | LOGH7" };

export default function Page() {
  return (
    <main className="portal">
      <section className="intro" aria-labelledby="page-title">
        <p className="brand"><span aria-hidden="true">✦</span> LOGH7</p>
        <p className="series">은하영웅전설 VII</p>
        <h1 id="page-title">게임 비밀번호 복구</h1>
        <p className="lede">미리 보관한 복구 코드로 새 비밀번호를 설정할 수 있습니다. 복구 코드도 없다면 운영자에게 문의하세요.</p>
        <p><a className="portal-link" href="#issue">현재 비밀번호로 복구 코드 준비</a></p>
        <a className="portal-link" href="/">게임 계정 만들기</a>
      </section>
      <div className="recovery-forms">
        <section className="panel" aria-label="비밀번호 재설정 양식">
          <PasswordForm mode="reset" />
        </section>
        <section className="panel" id="issue" aria-label="복구 코드 발급 양식">
          <PasswordForm mode="issue" />
        </section>
      </div>
    </main>
  );
}
