#include "strategy_shell_text.hpp"

namespace logh7::client {
namespace {

constexpr StrategyShellText kKoreanText{
    L"ko-KR",
    L"Malgun Gothic",
    L"주 화면",
    L"통신 패널",
    L"인물 상태",
    L"미니맵",
    L"권한 카드",
    L"멤버 목록",
    L"아이콘 레일",
    L"세션 미지정",
    L"권한 정보 미수신",
    L"멤버 목록 미수신",
    L"은하 지도 모델 미해석",
    L"MANUAL_2004-10_PARTIAL / PLAYER_GUIDE_2004-05_EARLY_SERVICE / NON_GAMEPLAY_STRATEGY_SHELL / NO_FRAMEBUFFER_FIDELITY_CLAIM",
};

constexpr StrategyShellText kJapaneseText{
    L"ja-JP",
    L"Yu Gothic UI",
    L"メインビュー",
    L"通信パネル",
    L"キャラクター状態",
    L"ミニマップ",
    L"権限カード",
    L"メンバー一覧",
    L"アイコンレール",
    L"セッション未指定",
    L"権限情報未受信",
    L"メンバー一覧未受信",
    L"銀河マップモデル未解析",
    L"MANUAL_2004-10_PARTIAL / PLAYER_GUIDE_2004-05_EARLY_SERVICE / NON_GAMEPLAY_STRATEGY_SHELL / NO_FRAMEBUFFER_FIDELITY_CLAIM",
};

}

StrategyShellText StrategyShellTextFor(const std::wstring_view locale) {
    if (locale == L"ja" || locale == L"ja-JP") {
        return kJapaneseText;
    }
    return kKoreanText;
}

}
