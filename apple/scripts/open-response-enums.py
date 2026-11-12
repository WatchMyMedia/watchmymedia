#!/usr/bin/env python3
"""Preserve new string enum values in generated response DTOs.

Apple's generator currently emits closed raw-value enums. Its normal Codable
decoder rejects a whole reply when a newer server adds one value. This build
step opens only enums reachable from a response, retaining known cases and
adding ``undocumented(String)``. Request-only enums are left closed. Domain
adapters still decide whether an unfamiliar response is usable; this never
turns it into a known command, status or media kind.

Run through ``make apple-api`` after the upstream generator. The schema
remarks and enum shape are checked rather than silently accepting generator
output this script no longer understands.
"""
import json
from pathlib import Path
import re
import sys


def response_enums(document):
    """Find enum schema locations referenced by any HTTP response."""
    found, visited = set(), set()

    def follow(node, location=""):
        if isinstance(node, list):
            for index, value in enumerate(node):
                follow(value, f"{location}/{index}")
        elif isinstance(node, dict):
            ref = node.get("$ref")
            if ref and ref.startswith("#/") and ref not in visited:
                visited.add(ref)
                target = document
                for part in ref[2:].split("/"):
                    target = target[part.replace("~1", "/").replace("~0", "~")]
                follow(target, ref)
            if node.get("type") == "string" and "enum" in node:
                # Generator source remarks elide JSON Schema's properties key.
                found.add(location.replace("/properties/", "/"))
            for key, value in node.items():
                if key != "$ref":
                    follow(value, f"{location}/{key}")

    for path_name, path in document.get("paths", {}).items():
        escaped = path_name.replace("~", "~0").replace("/", "~1")
        for method, operation in path.items():
            if isinstance(operation, dict):
                follow(operation.get("responses", {}), f"#/paths/{escaped}/{method}/responses")
    return found


ENUM = re.compile(
    r"(?P<indent> +)/// - Remark: Generated from `(?P<location>[^`]+)`\.\n"
    r"(?P=indent)@frozen public enum (?P<name>\w+): String, Codable, Hashable, Sendable, CaseIterable \{\n"
    r"(?P<cases>(?:(?P=indent)    case [^\n]+\n)+)"
    r"(?P=indent)\}"
)
CASE = re.compile(r' +case (?P<name>`?\w+`?) = (?P<raw>"(?:[^"\\]|\\.)*")\n')


def open_enums(source, locations):
    def replace(match):
        if match["location"] not in locations:
            return match[0]
        cases = list(CASE.finditer(match["cases"]))
        if not cases or "".join(case[0] for case in cases) != match["cases"]:
            raise ValueError(f"Unsupported generated enum: {match['location']}")
        if any(case["name"] == "undocumented" for case in cases):
            raise ValueError(f"Reserved enum case in {match['location']}")
        indent = match["indent"]
        lines = [
            f"/// - Remark: Generated from `{match['location']}`.",
            f"@frozen public enum {match['name']}: RawRepresentable, Codable, Hashable, Sendable, CaseIterable {{",
            *[f"    case {case['name']}" for case in cases],
            "    /// A newer server's value, preserved without assigning it a known meaning.",
            "    case undocumented(Swift.String)",
            "",
            "    /// Constructing a known request value still rejects unfamiliar strings.",
            "    public init?(rawValue: Swift.String) {",
            "        switch rawValue {",
            *[f"        case {case['raw']}: self = .{case['name']}" for case in cases],
            "        default: return nil",
            "        }",
            "    }",
            "",
            "    public var rawValue: Swift.String {",
            "        switch self {",
            *[f"        case .{case['name']}: return {case['raw']}" for case in cases],
            "        case .undocumented(let value): return value",
            "        }",
            "    }",
            "",
            "    public static var allCases: [Self] {",
            "        [" + ", ".join(f".{case['name']}" for case in cases) + "]",
            "    }",
            "",
            "    public init(from decoder: any Swift.Decoder) throws {",
            "        let value = try decoder.singleValueContainer().decode(Swift.String.self)",
            "        self = Self(rawValue: value) ?? .undocumented(value)",
            "    }",
            "",
            "    public func encode(to encoder: any Swift.Encoder) throws {",
            "        var container = encoder.singleValueContainer()",
            "        try container.encode(rawValue)",
            "    }",
            "}",
        ]
        return "\n".join(indent + line if line else "" for line in lines)

    rewritten = ENUM.sub(replace, source)
    # No response enum may accidentally remain closed after an upstream change.
    declarations = re.finditer(
        r"/// - Remark: Generated from `(?P<location>[^`]+)`\.\n +(?:@frozen )?public enum [^\n]+", rewritten
    )
    for match in declarations:
        if match["location"] in locations and ": RawRepresentable," not in match[0]:
            raise ValueError(f"Response enum stayed closed: {match['location']}")
    return rewritten


def main():
    document = json.loads(Path(sys.argv[1]).read_text())
    generated = Path(sys.argv[2])
    locations = response_enums(document)
    files = list(generated.glob("Types*.swift"))
    if not files:
        raise ValueError("No generated Swift types found")
    for path in files:
        source = path.read_text()
        rewritten = open_enums(source, locations)
        if rewritten != source:
            path.write_text(rewritten)


if __name__ == "__main__":
    main()
