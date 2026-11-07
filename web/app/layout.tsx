import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./portal.css";

export const metadata: Metadata = {
  title: "게임 계정 만들기 | LOGH7",
  description: "은하영웅전설 VII 클라이언트에서 사용할 게임 계정을 만듭니다.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
