"""扫描 static/*.html，列出前端实际调用的 /api/v1/sdui/* 路径。

用途：接口集（CLIENT_API.md）与前端调用点对账——防止"声明了没人用"
或"有人在用却没进契约"。只读，不修改任何文件。

2026-09-20 两处修正：

1. 原 TOKEN 正则写作 ``[^\\s"'`)|]+``，逐字符匹配到右括号即止。JS 模板里的
   ``${encodeURIComponent(deviceId)}`` 含右括号，于是带参数的路径全被截成半截
   （``/api/v1/sdui/debug/${encodeURIComponent(deviceId``），占位符替换也随之失效。
   现改为把 ``${...}`` 整体作为一个匹配单元。**这条修正很重要**：截断后的路径
   无法与控制器端点对账，会让"仍在使用的端点"看起来像没人用。
2. 新增"路径不是字面量"提示。本脚本只能看见字面量路径；若调用点写作
   ``api(devicePath('/requests'))``，路径由辅助函数拼出，脚本看不见——静默漏报。
   因此页面应直接写完整路径（模板字面量即可，内部可插值）。
"""
import glob
import os
import re

TOKEN = re.compile(r"/api/v1/sdui/(?:\$\{[^}]*\}|[^\s\"'`|)])+")
PLACEHOLDER = re.compile(r"\$\{[^}]*\}")
CALL = re.compile(r"\b(?:api|request|EventSource)\(")
LITERAL_START = ("'", '"', "`")
# 允许的包装：这些函数只是给字面量路径加前缀，不改变"路径是字面量"这一事实。
WRAPPERS = ("apiUrl(",)


def normalize(raw: str) -> str:
    return PLACEHOLDER.sub("{param}", raw)


def looks_literal(rest: str) -> bool:
    for wrapper in WRAPPERS:
        while rest.startswith(wrapper):
            rest = rest[len(wrapper):]
    return rest[:1] in LITERAL_START


def main() -> None:
    found: dict[str, set[str]] = {}
    dynamic: list[str] = []
    for path in sorted(glob.glob("src/main/resources/static/*.html")):
        name = os.path.basename(path)
        text = open(path, encoding="utf-8", errors="ignore").read()
        for raw in TOKEN.findall(text):
            found.setdefault(normalize(raw), set()).add(name)
        for match in CALL.finditer(text):
            if text[:match.start()].rstrip().endswith("function"):
                continue
            rest = text[match.end():match.end() + 48]
            if not looks_literal(rest):
                line = text.count("\n", 0, match.start()) + 1
                dynamic.append(f"{name}:{line}  {rest.splitlines()[0][:52]}")

    for p in sorted(found):
        print(f"{p:<70} {', '.join(sorted(found[p]))}")

    if dynamic:
        print("\n!! 以下调用点的路径不是字面量，静态扫描看不见：")
        for item in dynamic:
            print(f"   {item}")
    else:
        print("\n所有 api() / EventSource() 调用点均为字面量路径。")


if __name__ == "__main__":
    main()
