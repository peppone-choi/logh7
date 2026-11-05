"""플레이어용 시간 단위와 비용 자리표시자의 회귀 검사. 작성: 최병호."""
import json
from pathlib import Path
import re
import unittest

ROOT=Path(__file__).resolve().parents[2]

class CommandLabelTest(unittest.TestCase):
    def test_catalogues_use_game_time_and_numeric_costs(self):
        for name in ('messages.ko.json','constmsg.ko.json'):
            text=(ROOT/'l10n'/name).read_text(encoding='utf-8')
            self.assertIsNone(re.search(r'G(?:시간|분|초|일)',text),name)
            self.assertNotIn('”MCP”',text,name)
            self.assertNotIn('”PCP”',text,name)

    def test_departure_matches_the_implemented_free_immediate_switch(self):
        entries=json.loads((ROOT/'l10n/messages.ko.json').read_text(encoding='utf-8'))['files']['messages_com_0.dat']['entries']
        for key in ('60/0','60/1'):
            self.assertIn('출항',entries[key])
            self.assertIn('소비: MCP 0',entries[key])
            self.assertIn('대기: 없음',entries[key])
            self.assertNotIn('행성 또는 요새명',entries[key])

if __name__=='__main__':unittest.main()
