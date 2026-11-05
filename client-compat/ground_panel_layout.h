// Ground battle HUD geometry and bounded meters. Author: Peppone Choi (peppone-choi).
#pragma once
namespace logh {
constexpr int groundPanelLeft=12, groundPanelTop=260, groundPanelWidth=438, groundPanelHeight=330;
constexpr unsigned groundPanelVisibleRows=5;
inline unsigned groundMeter(unsigned value,unsigned maximum,unsigned width) {
    return maximum ? static_cast<unsigned>((static_cast<unsigned long long>(value>maximum ? maximum : value)*width)/maximum) : 0;
}
}
