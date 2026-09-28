"""
엑셀 계산기의 '소환수S도감' 시트에서 앱 도감(app/src/main/assets/pets.json)을 다시 만든다.

사용법 (overlay-app 폴더에서):
    python tools/update_pets.py "../테이밍마스터2 성장률계산기 VER1.2의 사본.xlsx"
그다음 APK 를 다시 빌드하면 새 소환수가 들어간다. (필요: pip install openpyxl)
"""
import json
import pathlib
import sys

import openpyxl

src = sys.argv[1]
out = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "pets.json"

ws = openpyxl.load_workbook(src, data_only=True)["소환수S도감"]
pets = []
for r in ws.iter_rows(min_row=2, values_only=True):
    if not r[0]:
        continue
    pets.append({
        "n": str(r[0]).strip(), "g": r[1], "e": r[2], "t": r[3],
        "i": [int(x) for x in r[4:8]],      # 초기치 공/방/순/체
        "s": [float(x) for x in r[8:12]],   # 성장S등급
        "m": [int(x) for x in r[12:16]],    # 만렙S
    })
out.write_text(json.dumps(pets, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"{len(pets)}종 → {out}")
