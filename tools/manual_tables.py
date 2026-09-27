#!/usr/bin/env python3
"""gin7manual.pdf(웹판) 부록 표를 CSV로 추출한다.

사실 데이터(직책·정원·계급 범위·임명권자·CP·시간·수치)만 남기고, 해설 문장은 넣지 않는다.
병합 셀은 PDF 표 추출에서 빈칸으로 나오므로 아래 규칙으로 복원한다(검증은 페이지 이미지 대조).
  - 병합 셀: PyMuPDF 셀 bbox 로 병합 범위를 계산해 덮인 칸에 같은 값을 채운다(unmerge)
  - 조직표 계급: 최저·최고 모두 빈칸이면 위 행 값, 최고만 빈칸이면 최저와 같은 값
  - 함종 수치표: "-" 는 해당 없음

사용법:
    set PYTHONPATH=E:\\Tools\\pylib
    python tools/manual_tables.py E:\\manual-variants\\internet-archive\\gin7manual.pdf docs\\manual\\data
"""
import csv
import os
import re
import sys

import pymupdf

ORG_PAGES = {"empire": range(56, 60), "alliance": range(62, 66)}
CARD_PAGES = {"empire": range(60, 62), "alliance": range(66, 68)}
COMMAND_PAGES = range(68, 75)
DEPLOY_PAGES = range(75, 79)
SHIP_PAGES = {"empire": range(79, 90), "alliance": range(90, 100)}
CREW_PAGE = 100


def clean(c):
    return (c or "").replace("\n", "").strip()


def unmerge(t):
    """병합 셀을 bbox 기준으로 펼친다. extract()가 None을 준 칸은 그 칸 중심을 덮는 셀의 값으로 채운다."""
    raw = t.extract()
    grid = [[None if c is None else c for c in r] for r in raw]
    boxes = []  # (bbox, value)
    col_x = {}
    for i, row in enumerate(t.rows):
        for j, b in enumerate(row.cells):
            if b is None:
                continue
            boxes.append((b, raw[i][j]))
            w = b[2] - b[0]
            if j not in col_x or w < col_x[j][1] - col_x[j][0]:
                col_x[j] = (b[0], b[2])
    for i, row in enumerate(t.rows):
        # row.bbox 는 병합 셀 때문에 표 끝까지 늘어나는 경우가 있어, 첫 열(병합 안 됨) 셀로 행 중심을 잡는다
        ref = row.cells[0] if row.cells and row.cells[0] is not None else row.bbox
        cy = (ref[1] + ref[3]) / 2
        for j, b in enumerate(row.cells):
            if b is not None or j not in col_x:
                continue
            cx = (col_x[j][0] + col_x[j][1]) / 2
            hits = [(abs((bb[2] - bb[0]) * (bb[3] - bb[1])), v) for bb, v in boxes
                    if bb[0] <= cx <= bb[2] and bb[1] <= cy <= bb[3]]
            if hits:
                grid[i][j] = min(hits, key=lambda h: h[0])[1]
    return [[clean(c) for c in r] for r in grid]


def tables(doc, pno):
    return [unmerge(t) for t in doc[pno - 1].find_tables().tables]


def appointer(desc):
    m = re.search(r"([^\s。、]+?)によって(?:直接)?任(?:命|じ)", desc)
    return m.group(1) if m else ""


def org(doc, out):
    """정원(숫자) 열을 기준점으로 직책·계급 열을 찾는다(페이지마다 열 수가 6~7로 다름)."""
    rows = []
    for faction, pages in ORG_PAGES.items():
        dept = ""
        prev = ("", "")
        for p in pages:
            for t in tables(doc, p):
                if not t or len(t[0]) < 6:
                    continue
                for r in t:
                    r = [clean(c) for c in r]
                    if r[0] == "所属":
                        continue
                    seat_i = next((i for i, c in enumerate(r) if re.fullmatch(r"\d+", c)), None)
                    if seat_i is None or seat_i < 1:
                        continue
                    post = next((r[i] for i in range(seat_i - 1, -1, -1) if r[i]), "")
                    if r[0] and r[0] != post:
                        dept = r[0]
                    # 소속 셀이 페이지를 넘어 병합된 경우(輸送艦隊가 p.57→58) 직책명 접두로 보정
                    if post.startswith("輸送艦隊") and dept == "艦隊":
                        dept = "輸送艦隊"
                    lo = r[seat_i + 1] if seat_i + 1 < len(r) else ""
                    hi = r[seat_i + 2] if seat_i + 2 < len(r) - 1 else ""
                    if not lo and not hi:
                        lo, hi = prev
                    elif not hi:
                        hi = lo
                    prev = (lo, hi)
                    rows.append({
                        "faction": faction, "page": p, "department": dept, "post": post,
                        "seats": r[seat_i], "min_rank": lo, "max_rank": hi,
                        "appointed_by": appointer(r[-1]),
                    })
    write(out, "org-posts.csv", rows)


def cards(doc, out):
    """초기 직무권한 카드 보유자. 긴 해설 셀(25자 초과)은 버린다."""
    rows = []
    for faction, pages in CARD_PAGES.items():
        for p in pages:
            for t in tables(doc, p):
                for r in t:
                    cells = [clean(c) for c in r]
                    if cells and cells[0] == "所属":
                        continue
                    cells = [c for c in cells if c and len(c) <= 25]
                    if len(cells) < 2:
                        continue
                    rows.append({"faction": faction, "page": p, "post": cells[-2] if len(cells) > 2 else cells[0],
                                 "initial_holder": cells[-1], "raw_cells": " | ".join(cells)})
    write(out, "initial-card-holders.csv", rows)


def commands(doc, out):
    rows = []
    group = ""
    for p in COMMAND_PAGES:
        for t in tables(doc, p):
            for r in t:
                r = [clean(c) for c in r] + [""] * 6
                if r[1] in ("コマンド", ""):
                    continue
                group = r[0] or group
                rows.append({"group": group, "command": r[1], "cp": r[2],
                             "wait": r[3], "duration": r[4], "page": p})
    write(out, "strategy-commands.csv", rows)


def deploy(doc, out):
    rows = []
    for p in DEPLOY_PAGES:
        for ti, t in enumerate(tables(doc, p)):
            if not t:
                continue
            header = [clean(c) for c in t[0]]
            prev = [""] * len(header)
            for r in t[1:]:
                r = [clean(c) for c in r]
                if not any(r):
                    continue
                r = [c or (prev[i] if i == 0 else "") for i, c in enumerate(r)]
                prev = r
                rows.append({"page": p, "table": ti, "columns": "|".join(header), "values": "|".join(r)})
    write(out, "initial-deployment.csv", rows)


def ships(doc, out):
    rows = []
    for faction, pages in SHIP_PAGES.items():
        for p in pages:
            for t in tables(doc, p):
                if not t or len(t[0]) < 10:
                    continue
                h1 = [clean(c) for c in t[0]]
                h2 = [clean(c) for c in t[1]] if len(t) > 1 else [""] * len(h1)
                header = []
                last = ""
                for a, b in zip(h1, h2):
                    last = a or last
                    header.append(f"{last}:{b}" if b else last)
                header[0] = "unit"
                for r in t[2:]:
                    if not r[0]:
                        continue
                    row = {"faction": faction, "page": p}
                    row.update({header[i]: r[i] for i in range(len(header))})
                    rows.append(row)
    write(out, "ship-units.csv", rows)


def crew(doc, out):
    rows = []
    for t in tables(doc, CREW_PAGE):
        if not t or clean(t[0][0]) not in ("帝国軍", "同盟軍"):
            continue
        faction = "empire" if clean(t[0][0]) == "帝国軍" else "alliance"
        for r in t[1:]:
            rows.append({"faction": faction, "unit": r[0], "training": r[1],
                         "ground_attack": r[2], "ground_defense": r[3]})
    write(out, "crew-units.csv", rows)


def write(out, name, rows):
    if not rows:
        print(f"{name}: 0 rows")
        return
    keys = []
    for r in rows:
        for k in r:
            if k not in keys:
                keys.append(k)
    with open(os.path.join(out, name), "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        w.writerows(rows)
    print(f"{name}: {len(rows)} rows")


def main(pdf, out):
    os.makedirs(out, exist_ok=True)
    doc = pymupdf.open(pdf)
    org(doc, out)
    cards(doc, out)
    commands(doc, out)
    deploy(doc, out)
    ships(doc, out)
    crew(doc, out)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
