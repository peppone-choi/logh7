import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator


def load(path: str):
    return json.loads(Path(path).read_text(encoding="utf-8"))


if len(sys.argv) != 4:
    raise SystemExit("usage: validate-json-schema.py SCHEMA TEMPLATE SPECIMEN")

schema = load(sys.argv[1])
template = load(sys.argv[2])
specimen = load(sys.argv[3])
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)
for name, value in (("template", template), ("specimen", specimen)):
    errors = sorted(validator.iter_errors(value), key=lambda error: list(error.path))
    if errors:
        details = [f"{name}:{'/'.join(map(str, error.path))}:{error.message}" for error in errors]
        raise SystemExit("\n".join(details))

print(json.dumps({"result": "PASS", "dialect": "2020-12", "documents": 2}, separators=(",", ":")))
