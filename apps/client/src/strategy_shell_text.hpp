#pragma once

#include <string_view>

namespace logh7::client {

struct StrategyShellText {
    std::wstring_view locale;
    std::wstring_view fontFamily;
    std::wstring_view mainViewLabel;
    std::wstring_view communicationLabel;
    std::wstring_view characterStatusLabel;
    std::wstring_view minimapLabel;
    std::wstring_view authorityCardLabel;
    std::wstring_view memberListLabel;
    std::wstring_view iconRailLabel;
    std::wstring_view sessionUnassigned;
    std::wstring_view authorityUnavailable;
    std::wstring_view memberListUnavailable;
    std::wstring_view galaxyModelUnparsed;
    std::wstring_view diagnosticStatus;
};

StrategyShellText StrategyShellTextFor(std::wstring_view locale);

}
