#!/usr/bin/env python3
"""Writes the copy of api/openapi.json the Swift client is generated from.

The server's document closes every object (`additionalProperties: false`),
and swift-openapi-generator then refuses any field it does not know. Clients
must ignore unknown fields so a newer server does not break an older app
(PLAN-CLIENTS.md §2.1), so this drops those markers and changes nothing else.

    openapi-for-swift.py api/openapi.json apple/build/openapi-swift.json
"""
import json
import sys


def open_objects(node):
    if isinstance(node, dict):
        if node.get("additionalProperties") is False:
            del node["additionalProperties"]
        for value in node.values():
            open_objects(value)
    elif isinstance(node, list):
        for value in node:
            open_objects(value)


source, target = sys.argv[1], sys.argv[2]
with open(source) as f:
    spec = json.load(f)
open_objects(spec)
with open(target, "w") as f:
    json.dump(spec, f, indent=2)
