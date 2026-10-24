// CD senryaku_mainpanel.tga coordinates. Author: 최병호
#pragma once

namespace logh {
constexpr int orbitStrokeX[] = {426, 449, 472, 496};

inline bool hudIconRect(unsigned model, int& x, int& y) {
    // p000/p001 .. p100/p101/p102 match the atlas's 33-pixel cells.
    // p032 has an image but no model-number entry in the supported CD profile.
    if (model <= 102 && model % 10 <= (model == 102 ? 2u : 1u)) {
        x = 791 + (model % 10) * 33; y = (model / 10) * 33;
        return true;
    }
    // The CD atlas contains y001..y005. Later fortress slots have no image here.
    if (model >= 110 && model <= 115) {
        x = model == 111 ? 956 : 923; y = model <= 111 ? 0 : (model - 111) * 33;
        return true;
    }
    return false;
}
}
