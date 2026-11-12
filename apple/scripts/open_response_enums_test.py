"""Checks schema ownership and fail-closed behavior of the generation adapter.

The Swift tests exercise actual generated decoding through production clients.
"""
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("response_enums", Path(__file__).with_name("open-response-enums.py"))
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


class ResponseEnumsTest(unittest.TestCase):
    def test_response_refs_follow_cycles_and_leave_request_only_values_closed(self):
        document = {
            "paths": {"/example": {"post": {
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Input"}}}},
                "responses": {"200": {"$ref": "#/components/responses/Reply"}},
            }}},
            "components": {
                "responses": {"Reply": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Output"}}}}},
                "schemas": {
                    "Input": {"type": "string", "enum": ["known"]},
                    "Output": {"type": "object", "properties": {
                        "kind": {"type": "string", "enum": ["known"]},
                        "child": {"$ref": "#/components/schemas/Output"},
                    }},
                },
            },
        }
        self.assertEqual(adapter.response_enums(document), {"#/components/schemas/Output/kind"})

    def test_generation_is_idempotent_and_does_not_change_request_enums(self):
        source = """            /// - Remark: Generated from `#/components/schemas/Output/kind`.
            @frozen public enum KindPayload: String, Codable, Hashable, Sendable, CaseIterable {
                case known = "known"
            }"""
        locations = {"#/components/schemas/Output/kind"}
        self.assertEqual(adapter.open_enums(source, set()), source)
        opened = adapter.open_enums(source, locations)
        self.assertIn("case undocumented(Swift.String)", opened)
        self.assertEqual(adapter.open_enums(opened, locations), opened)

    def test_upstream_enum_shape_changes_stop_generation(self):
        changed = """            /// - Remark: Generated from `#/components/schemas/Output/kind`.
            @frozen public enum KindPayload: String, Codable, Hashable, Sendable {
                case known = "known"
            }"""
        with self.assertRaisesRegex(ValueError, "Response enum stayed closed"):
            adapter.open_enums(changed, {"#/components/schemas/Output/kind"})


if __name__ == "__main__":
    unittest.main()
