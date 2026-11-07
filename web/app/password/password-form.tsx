"use client";

import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";

const definitions = {
  account: { label: "게임 아이디", type: "text", autoComplete: "username" },
  currentPassword: { label: "현재 비밀번호", type: "password", autoComplete: "current-password" },
  recoveryCode: { label: "일회용 복구 코드", type: "password", autoComplete: "off" },
  newPassword: { label: "새 비밀번호", type: "password", autoComplete: "new-password" },
  confirmPassword: { label: "새 비밀번호 확인", type: "password", autoComplete: "new-password" },
} as const;
type Field = keyof typeof definitions;
type Mode = "change" | "issue" | "reset";
type Result = { kind: "idle" | "pending" | "success" } | { kind: "issued"; code: string } |
  { kind: "error"; message: string; field?: Field };

const modes = {
  change: { title: "현재 비밀번호 확인", action: "비밀번호 변경", path: "/api/password",
    fields: ["account", "currentPassword", "newPassword", "confirmPassword"] },
  issue: { title: "복구 코드 발급", action: "코드 발급", path: "/api/recovery-code",
    fields: ["account", "currentPassword"] },
  reset: { title: "복구 코드로 비밀번호 재설정", action: "비밀번호 재설정", path: "/api/password-reset",
    fields: ["account", "recoveryCode", "newPassword", "confirmPassword"] },
} as const satisfies Record<Mode, { title: string; action: string; path: string; fields: readonly Field[] }>;

const newPasswordHint = "1~10자 · 공백 없이 영문, 숫자, 기호";
const badPassword = "공백 없이 영문, 숫자, 기호로 1~10자를 입력해 주세요.";
const invalidCredentials = "게임 아이디 또는 현재 비밀번호를 확인해 주세요.";
const invalidRecovery = "게임 아이디와 복구 코드를 확인해 주세요. 사용했거나 새로 발급한 이전 코드는 쓸 수 없습니다.";
const unavailable = "변경 결과를 확인하지 못했습니다. 새 비밀번호로 게임 로그인을 먼저 시도해 주세요.";

export default function PasswordForm({ mode = "change" }: { mode?: Mode }) {
  const settings = modes[mode];
  const [result, setResult] = useState<Result>({ kind: "idle" });
  const form = useRef<HTMLFormElement>(null);
  const successTitle = useRef<HTMLHeadingElement>(null);
  const error = result.kind === "error" ? result : null;
  const pending = result.kind === "pending";

  useEffect(() => {
    if (result.kind === "success" || result.kind === "issued") successTitle.current?.focus();
    if (error?.field) (form.current?.elements.namedItem(error.field) as HTMLInputElement | null)?.focus();
  }, [result, error]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const data = new FormData(event.currentTarget);
    const account = String(data.get("account") ?? "");
    const currentPassword = String(data.get("currentPassword") ?? "");
    const newPassword = String(data.get("newPassword") ?? "");
    const recoveryCode = String(data.get("recoveryCode") ?? "").trim();
    if (!/^[A-Za-z0-9._-]{1,30}$/.test(account)) {
      setResult({ kind: "error", field: "account", message: "영문, 숫자, 마침표(.), 밑줄(_), 하이픈(-)으로 1~30자를 입력해 주세요." });
      return;
    }
    if (mode !== "reset" && !/^[!-~]{1,10}$/.test(currentPassword)) {
      setResult({ kind: "error", field: "currentPassword", message: invalidCredentials });
      return;
    }
    if (mode === "reset" && !/^[A-Za-z0-9_-]{22}$/.test(recoveryCode)) {
      setResult({ kind: "error", field: "recoveryCode", message: "보관해 둔 22자 복구 코드를 입력해 주세요." });
      return;
    }
    if (mode !== "issue" && !/^[!-~]{1,10}$/.test(newPassword)) {
      setResult({ kind: "error", field: "newPassword", message: badPassword });
      return;
    }
    if (mode !== "issue" && newPassword !== data.get("confirmPassword")) {
      setResult({ kind: "error", field: "confirmPassword", message: "새 비밀번호를 동일하게 입력해 주세요." });
      return;
    }

    setResult({ kind: "pending" });
    const unavailableMessage = mode === "issue"
      ? "발급 결과를 확인하지 못했습니다. 다시 발급한 뒤 화면에 나온 새 코드를 보관해 주세요."
      : unavailable;
    try {
      const values = mode === "reset" ? { account, recoveryCode, newPassword } :
        mode === "issue" ? { account, currentPassword } : { account, currentPassword, newPassword };
      const response = await fetch(settings.path, {
        method: "POST",
        body: new URLSearchParams(Object.entries(values)),
        signal: AbortSignal.timeout(10000),
      });
      const code = await response.text();
      if (mode === "issue" && response.status === 201 && /^code:[A-Za-z0-9_-]{22}$/.test(code)) {
        setResult({ kind: "issued", code: code.slice(5) });
        return;
      }
      switch (code) {
        case "changed": setResult({ kind: "success" }); break;
        case "invalid_credentials": setResult({ kind: "error", field: "currentPassword", message: invalidCredentials }); break;
        case "invalid_recovery": setResult({ kind: "error", field: "recoveryCode", message: invalidRecovery }); break;
        case "invalid_password": setResult({ kind: "error", field: "newPassword", message: badPassword }); break;
        case "retry_later": setResult({ kind: "error", message: "시도 횟수가 너무 많습니다. 1분 뒤에 다시 시도해 주세요." }); break;
        default: setResult({ kind: "error", message: unavailableMessage });
      }
    } catch {
      setResult({ kind: "error", message: unavailableMessage });
    }
  }

  return (
    <>
      <p className="sr-only" role="status">{pending ? `${settings.action} 중입니다.` : ""}</p>
      {result.kind === "issued" ? (
        <div className="success">
          <h2 ref={successTitle} tabIndex={-1}>복구 코드를 발급했습니다</h2>
          <p>이 화면에서만 볼 수 있습니다. 아래 코드를 복사해 비밀번호와 따로 보관하세요.</p>
          <code className="recovery-code">{result.code}</code>
          <button type="button" className="reveal" onClick={() => {
            setResult({ kind: "idle" });
            requestAnimationFrame(() => form.current?.querySelector("input")?.focus());
          }}>코드 닫기</button>
        </div>
      ) : result.kind === "success" ? (
        <div className="success">
          <p className="success-mark" aria-hidden="true">✓</p>
          <h2 ref={successTitle} tabIndex={-1}>비밀번호를 변경했습니다</h2>
          <p>다음 게임 로그인에서 새 비밀번호를 사용하세요.</p>
          <p>이전에 발급한 복구 코드는 폐기됐습니다.</p>
          <a className="portal-link" href={mode === "reset" ? "#issue" : "/password/recovery#issue"}>새 복구 코드 발급</a>
        </div>
      ) : (
        <form ref={form} method="post" onSubmit={submit} noValidate aria-labelledby={`${mode}-form-title`}>
          <h2 id={`${mode}-form-title`}>{settings.title}</h2>
          {mode === "issue" && <p className="hint">발급하면 이전 코드가 폐기됩니다.</p>}
          {settings.fields.map(name => {
            const { label, type, autoComplete } = definitions[name];
            const id = mode === "change" ? name : `${mode}-${name}`;
            const fieldError = error?.field === name ? error.message : null;
            const hint = name === "newPassword";
            return (
              <div className="field" key={name}>
                <label htmlFor={id}>{label}</label>
                <input id={id} name={name} type={type} autoComplete={autoComplete} required
                  readOnly={pending} autoCapitalize="none" spellCheck={false}
                  onChange={() => { if (fieldError) setResult({ kind: "idle" }); }}
                  aria-invalid={Boolean(fieldError) || undefined}
                  aria-describedby={[fieldError && `${id}-error`, hint && `${id}-hint`].filter(Boolean).join(" ") || undefined} />
                {fieldError && <p id={`${id}-error`} className="error" role="alert">{fieldError}</p>}
                {hint && <p id={`${id}-hint`} className="hint">{newPasswordHint}</p>}
              </div>
            );
          })}
          {error && !error.field && <p className="error" role="alert">{error.message}</p>}
          <button type="submit" className="submit" aria-disabled={pending || undefined}>
            {pending ? `${settings.action} 중…` : settings.action}
          </button>
          <noscript><p className="error">이 양식을 사용하려면 브라우저에서 자바스크립트를 켜고 새로고침해 주세요.</p></noscript>
        </form>
      )}
    </>
  );
}
