"""부록 CSV 보정. 작성자: 최병호. evidence:manual (웹판 p.56–100).

PYTHONPATH=E:\Tools\pylib, PYTHONDONTWRITEBYTECODE=1 환경에서 실행.
기본 추출기를 재사용하되 병합 행 중심·초기 카드 열·생산표 연속쪽을 보정한다.
"""
import csv
import importlib.util
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("manual_tables", ROOT / "tools/manual_tables.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def unmerge(t, cleaner=m.clean):
    raw = t.extract()
    boxes = [(b, raw[i][j]) for i, row in enumerate(t.rows)
             for j, b in enumerate(row.cells) if b is not None]
    col_x = {}
    for row in t.rows:
        for j, b in enumerate(row.cells):
            if b is not None and (j not in col_x or b[2]-b[0] < col_x[j][1]-col_x[j][0]):
                col_x[j] = (b[0], b[2])
    result = []
    for i, row in enumerate(t.rows):
        bs = [b for b in row.cells if b is not None]
        # 첫 셀은 여러 행을 덮을 수 있다. 모든 셀의 교집합이 현재 행이다.
        cy = (max(b[1] for b in bs) + min(b[3] for b in bs)) / 2
        values = []
        for j, b in enumerate(row.cells):
            if b is not None:
                values.append(cleaner(raw[i][j]))
                continue
            cx = sum(col_x[j]) / 2
            hits = [(abs((bb[2]-bb[0])*(bb[3]-bb[1])), v) for bb,v in boxes
                    if bb[0] < cx < bb[2] and bb[1] < cy < bb[3]]
            values.append(cleaner(min(hits, default=(0, ""))[1]))
        result.append(values)
    return result


def cards(doc, out):
    rows = []
    for faction, pages in m.CARD_PAGES.items():
        dept = post = ""
        for p in pages:
            for t in doc[p-1].find_tables().tables:
                for raw in t.extract():
                    r = [m.clean(c) for c in raw]
                    if r[0] == "所属":
                        continue
                    if len(r) == 2:  # p.67: 앞쪽의 소속/직책 셀이 표 밖에 있다.
                        dept, post = "巡察隊", "巡察隊司令"
                        unit, holder, extra = r[0], r[1], ""
                    else:
                        dept, post = r[0] or dept, r[1] or post
                        if r[2].startswith("第"):
                            unit, holder = r[2], r[3]
                            if "巡察隊" in unit:
                                dept, post = "巡察隊", "巡察隊司令"
                            extra = r[4] if len(r) == 6 else ""
                        else:
                            unit, holder, extra = "", r[2], ""
                    rows.append(dict(faction=faction, page=p, post=post,
                                     initial_holder=holder,
                                     raw_cells=" | ".join(x for x in [dept,post,unit,holder] if x),
                                     department=dept, unit=unit))
                    if extra:
                        holder2, role = extra.split("（", 1)
                        rows.append(dict(faction=faction, page=p, post=role.rstrip("）"),
                                         initial_holder=holder2,
                                         raw_cells=" | ".join([dept,unit,extra]),
                                         department=dept, unit=unit))
    m.write(out, "initial-card-holders.csv", rows)


def deploy(doc, out):
    rows = []
    previous = {}
    for p in m.DEPLOY_PAGES:
        # Colored fill rectangles split merged production cells under the default
        # detector. Only actual ruling lines define these tables.
        tables = doc[p-1].find_tables(**({"strategy": "lines_strict"} if p >= 76 else {})).tables
        for ti,table in enumerate(tables):
            t = unmerge(table, lambda c: (c or "").replace("\n", " / ").strip())
            if p == 75:
                header, body = t[0], t[1:]
            else:
                header = ["星系名","惑星名","艦艇ユニット種別","乗組員ユニット種別","陸戦兵ユニット種別"]
                body = t[1:] if p == 76 else t
            # p.76–78은 초기 유닛 보유가 아니라 자동 생산 품목이다.
            faction = ("empire" if (ti in (0,2,5) if p == 75 else ti == 0) else "alliance")
            kind = "deployment" if p == 75 else "automatic_production"
            prev = previous.get((faction, kind), [""] * len(header))
            for row_index, r in enumerate(body):
                if not any(r):
                    continue
                if p == 77 and faction == "empire" and row_index == len(body)-1 and not r[0]:
                    r[0] = "ビルロスト"  # p.77 아래→p.78 위의 성계 병합 셀
                if not r[0]:
                    r[0] = prev[0]
                if p == 76 and faction == "alliance" and row_index == len(body)-1 and not r[1]:
                    r[1] = "ハイネセン"  # p.76 아래→p.77 위 연속 셀
                if p >= 76 and not r[1]:
                    r[1] = prev[1]
                prev = r
                rows.append(dict(page=p, table=ti, columns="|".join(header),
                                 values="|".join(r), faction=faction, kind=kind))
            previous[faction, kind] = prev
    m.write(out, "initial-deployment.csv", rows)


def apply_visual_corrections(out):
    path = pathlib.Path(out) / "ship-units.csv"
    with path.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    page_rows = {}
    for r in rows:
        page_rows[r["page"]] = page_rows.get(r["page"], 0) + 1
        r["source_row"] = page_rows[r["page"]]
        r["verification_status"] = "name_clipped" if r["page"] == "92" and r["unit"] == "艦）" else "reviewed"
        if r["page"] == "88":
            r["対空兵装破壊力:対空兵装破壊力"] = "40"
        if r["page"] == "89" and r["unit"] == "商船":
            r["装甲:前"] = "12"
        if r["page"] == "98":
            r["ビーム兵装破壊力:ビーム兵装破壊力"] = "-"
            r["ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資"] = "-"
            if r["unit"] == "揚陸艦Ⅱ":
                for side, value in zip(["前", "側", "後"], ["17", "12", "7"]):
                    r["装甲:" + side] = value
    m.write(out, "ship-units.csv", rows)


if __name__ == "__main__":
    m.unmerge = unmerge
    m.cards = cards
    m.deploy = deploy
    m.main(sys.argv[1], str(pathlib.Path(__file__).parent / "data"))
    apply_visual_corrections(pathlib.Path(__file__).parent / "data")
