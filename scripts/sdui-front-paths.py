"""扫描 static/*.html，列出前端实际调用的 /api/v1/sdui/* 路径。

用途：接口集（CLIENT_API.md）与前端调用点对账——防止"声明了没人用"
或"有人在用却没进契约"。只读，不修改任何文件。
"""
import glob
import os
import re

TOKEN = re.compile(r"/api/v1/sdui/[^\s\"'`)|]+")
PLACEHOLDER = re.compile(r"\$\{[^}]*\}")


def normalize(raw: str) -> str:
    return PLACEHOLDER.sub("{param}", raw)


def main() -> None:
    found: dict[str, set[str]] = {}
    for path in sorted(glob.glob("src/main/resources/static/*.html")):
        text = open(path, encoding="utf-8", errors="ignore").read()
        for raw in TOKEN.findall(text):
            found.setdefault(normalize(raw), set()).add(os.path.basename(path))
    for p in sorted(found):
        print(f"{p:<70} {', '.join(sorted(found[p]))}")


if __name__ == "__main__":
    main()
