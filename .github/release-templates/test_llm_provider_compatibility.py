from __future__ import annotations

import json
import unittest
import urllib.error
from io import BytesIO
from unittest import mock

import llm_providers as providers
from generate_release_notes import OUTPUT_SCHEMA


class Response:
    def __init__(self, payload):
        self.raw = json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return None

    def read(self):
        return self.raw


def chat_reply():
    return Response({"choices": [{"finish_reason": "stop", "message": {"content": '{"ok":true}'}}]})


class ProviderCompatibilityTests(unittest.TestCase):
    def test_every_chat_endpoint_sends_exactly_one_bounded_output_field(self):
        for base, model, field in (
            ("https://api.openai.com/v1", "gpt-4o", "max_completion_tokens"),
            ("https://API.OPENAI.COM/v1/chat/completions", "o3", "max_completion_tokens"),
            ("https://api.openai.com/v1", "gpt-5.6", "max_completion_tokens"),
            ("https://api.river.ai/v1", "deepseek-ai/DeepSeek-V4.1-Flash", "max_tokens"),
            ("https://api.deepseek.com", "deepseek-flash", "max_tokens"),
            ("https://opencode.ai/inference/v1", "model", "max_tokens"),
            ("https://proxy.example/v1/chat/completions", "model", "max_tokens"),
            ("https://api.openai.com.proxy.example/v1", "model", "max_tokens"),
        ):
            with self.subTest(base=base), mock.patch("urllib.request.urlopen", return_value=chat_reply()) as request:
                self.call(providers.ProviderSlot(1, "openai", base, model, "key"))
                body = json.loads(request.call_args.args[0].data)
                limits = {key: value for key, value in body.items() if key in {"max_tokens", "max_completion_tokens"}}
                self.assertEqual({field: providers.MAX_OUTPUT_TOKENS}, limits)

    def test_explicit_chat_output_field_preserves_existing_gateway_configuration(self):
        for base in ("https://api.openai.com/v1", "https://api.river.ai/v1", "https://proxy.example/v1"):
            for field in ("max_tokens", "max_completion_tokens"):
                for cap in (1, providers.MAX_OUTPUT_TOKENS):
                    with self.subTest(base=base, field=field, cap=cap):
                        slots, problems = providers.load_slots({
                            "RELEASE_NOTES_LLM_1_PROTOCOL": "openai", "RELEASE_NOTES_LLM_1_API_KEY": "key",
                            "RELEASE_NOTES_LLM_1_BASE_URL": base, "RELEASE_NOTES_LLM_1_MODEL": "model",
                            "RELEASE_NOTES_LLM_1_BODY_JSON": json.dumps({field: cap, "reasoning_effort": "low"})})
                        self.assertEqual([], problems)
                        with mock.patch("urllib.request.urlopen", return_value=chat_reply()) as request:
                            self.call(slots[0])
                        body = json.loads(request.call_args.args[0].data)
                        self.assertEqual({field: cap}, {key: body[key] for key in ("max_tokens", "max_completion_tokens") if key in body})
                        self.assertEqual("low", body["reasoning_effort"])

    def test_invalid_chat_budgets_are_rejected_by_loading_and_direct_calls(self):
        invalid = [{field: value} for field in ("max_tokens", "max_completion_tokens")
                   for value in (None, True, False, 0, -1, 1.5, "cap-secret", [], {}, providers.MAX_OUTPUT_TOKENS+1)]
        invalid += [{"max_tokens": 1, "max_completion_tokens": 1}]
        for body in invalid:
            with self.subTest(body=body), mock.patch("urllib.request.urlopen") as request:
                slots, problems = providers.load_slots({
                    "RELEASE_NOTES_LLM_1_PROTOCOL": "openai", "RELEASE_NOTES_LLM_1_API_KEY": "key",
                    "RELEASE_NOTES_LLM_1_MODEL": "model", "RELEASE_NOTES_LLM_1_BODY_JSON": json.dumps(body)})
                self.assertEqual([], slots)
                self.assertTrue(problems)
                self.assertNotIn("cap-secret", " ".join(problems))
                slot = providers.ProviderSlot(1, "openai", "https://proxy.example/v1", "model", "key", extra_body=body)
                with self.assertRaises(providers.ProviderError) as failure:
                    self.call(slot)
                self.assertNotIn("cap-secret", str(failure.exception))
                request.assert_not_called()

    def test_river_deepseek_uses_json_and_bounded_non_reasoning_generation(self):
        slot = providers.ProviderSlot(2, "openai", "https://api.river.ai/v1", "deepseek-ai/DeepSeek-V4.1-Flash", "key")
        with mock.patch("urllib.request.urlopen", return_value=chat_reply()) as request:
            self.call(slot)
        body = json.loads(request.call_args.args[0].data)
        self.assertEqual({"type": "json_object"}, body["response_format"])
        self.assertEqual("none", body["reasoning_effort"])
        self.assertEqual(providers.MAX_OUTPUT_TOKENS, body["max_tokens"])

    def call(self, slot, session=None):
        return providers.complete(slot, "Output JSON", [{"role": "user", "content": "hi"}], OUTPUT_SCHEMA,
                                  session=session)

    def test_deepseek_uses_json_mode_without_schema_probe(self):
        slot = providers.ProviderSlot(2, "openai", "https://api.deepseek.com", "deepseek-flash", "key")
        with mock.patch("urllib.request.urlopen", return_value=chat_reply()) as request:
            self.call(slot)
        body = json.loads(request.call_args.args[0].data)
        self.assertEqual({"type": "json_object"}, body["response_format"])
        self.assertEqual(providers.MAX_OUTPUT_TOKENS, body["max_tokens"])
        self.assertEqual("disabled", body["thinking"]["type"])
        # 代理可能支持 schema，不能根据模型名字猜端点能力。
        proxy = providers.ProviderSlot(2, "openai", "https://proxy.example/v1", "deepseek-flash", "key")
        with mock.patch("urllib.request.urlopen", return_value=chat_reply()) as request:
            self.call(proxy)
        self.assertEqual("json_schema", json.loads(request.call_args.args[0].data)["response_format"]["type"])

    def test_format_negotiation_is_reused_by_validation_retries(self):
        bodies = []
        def receive(request, timeout):
            body = json.loads(request.data)
            bodies.append(body)
            if body.get("response_format", {}).get("type") == "json_schema":
                raise urllib.error.HTTPError(request.full_url, 422, "bad", {},
                                             BytesIO(b'{"error":"json_schema unsupported"}'))
            return chat_reply()
        session = providers.ProviderSession()
        slot = providers.ProviderSlot(1, "openai", "https://proxy.example/v1", "model", "key",
                                      extra_body={"max_completion_tokens": 4096})
        with mock.patch("urllib.request.urlopen", side_effect=receive):
            self.call(slot, session)
            self.call(slot, session)
        self.assertEqual(["json_schema", "json_object", "json_object"],
                         [body["response_format"]["type"] for body in bodies])
        self.assertTrue(all(body["max_completion_tokens"] == 4096 and "max_tokens" not in body for body in bodies))

    def test_all_formats_rejected_falls_back_to_prompt_json(self):
        bodies = []
        def receive(request, timeout):
            body = json.loads(request.data)
            bodies.append(body)
            if "response_format" in body:
                raise urllib.error.HTTPError(request.full_url, 400, "bad", {},
                                             BytesIO(b'{"error":"response_format unavailable"}'))
            return chat_reply()
        slot = providers.ProviderSlot(1, "openai", "https://proxy.example/v1", "model", "key")
        with mock.patch("urllib.request.urlopen", side_effect=receive):
            self.call(slot)
        self.assertEqual(3, len(bodies))
        self.assertNotIn("response_format", bodies[-1])
        self.assertTrue(all(body["max_tokens"] == providers.MAX_OUTPUT_TOKENS for body in bodies))

    def test_unrelated_format_error_does_not_trigger_capability_downgrade(self):
        error = urllib.error.HTTPError("https://proxy.example/v1/chat/completions", 400, "bad", {},
                                       BytesIO(b'{"error":"Invalid date format in input"}'))
        slot = providers.ProviderSlot(1, "openai", "https://proxy.example/v1", "model", "key")
        with mock.patch("urllib.request.urlopen", side_effect=error) as request:
            with self.assertRaises(providers.ProviderError):
                self.call(slot)
        self.assertEqual(1, request.call_count)

    def test_custom_auth_headers_body_and_explicit_format_are_loaded_and_sent(self):
        slots, problems = providers.load_slots({
            "RELEASE_NOTES_LLM_1_PROTOCOL": "openai", "RELEASE_NOTES_LLM_1_MODEL": "model",
            "RELEASE_NOTES_LLM_1_BASE_URL": "https://gateway.example/v1",
            "RELEASE_NOTES_LLM_1_HEADERS_JSON": '{"authorization":"Bearer custom-secret","X-Tenant":"tenant-secret"}',
            "RELEASE_NOTES_LLM_1_BODY_JSON": '{"reasoning_effort":"low"}',
            "RELEASE_NOTES_LLM_1_OUTPUT_FORMAT": "text",
        })
        self.assertEqual([], problems)
        with mock.patch("urllib.request.urlopen", return_value=chat_reply()) as request:
            self.call(slots[0])
        wire = request.call_args.args[0]
        headers = {name.lower(): value for name, value in wire.header_items()}
        self.assertEqual("Bearer custom-secret", headers["authorization"])
        self.assertEqual("tenant-secret", headers["x-tenant"])
        body = json.loads(wire.data)
        self.assertNotIn("response_format", body)
        self.assertEqual("low", body["reasoning_effort"])
        self.assertNotIn("custom-secret", repr(slots[0]))
        self.assertNotIn("tenant-secret", providers.redact("tenant-secret custom-secret", slots[0]))

    def test_rejects_invalid_config_without_echoing_secret_values(self):
        for field, value in (("HEADERS_JSON", '{"X-Token":"secret\\r\\nInjected: yes"}'),
                             ("HEADERS_JSON", '{"Host":"secret"}'),
                             ("HEADERS_JSON", '{"X-Key":"secret","x-key":"other"}'),
                             ("BODY_JSON", '{"messages":"secret"}'),
                             ("BODY_JSON", 'secret'), ("OUTPUT_FORMAT", "secret")):
            with self.subTest(field=field):
                slots, problems = providers.load_slots({"RELEASE_NOTES_LLM_1_API_KEY": "key",
                                                       "RELEASE_NOTES_LLM_1_" + field: value})
                self.assertEqual([], slots)
                self.assertNotIn("secret", " ".join(problems))

    def test_custom_secrets_are_redacted_from_real_http_error_path(self):
        slot = providers.ProviderSlot(1, "openai", "https://private.example/v1", "model", "key-secret",
                                      extra_headers=(("X-Token", "header-secret"),),
                                      extra_body={"metadata": {"tenant": "body-secret"}})
        detail = b'{"error":"header-secret body-secret key-secret private.example"}'
        error = urllib.error.HTTPError("https://private.example/v1/chat/completions", 401, "bad", {}, BytesIO(detail))
        with mock.patch("urllib.request.urlopen", side_effect=error):
            with self.assertRaises(providers.ProviderError) as result:
                self.call(slot)
        for secret in ("header-secret", "body-secret", "key-secret", "private.example"):
            self.assertNotIn(secret, str(result.exception))

    def test_responses_json_mode_and_gemini_schema_rejections_downgrade(self):
        for protocol in ("responses", "gemini"):
            with self.subTest(protocol=protocol):
                bodies = []
                def receive(request, timeout):
                    body = json.loads(request.data)
                    bodies.append(body)
                    if (body.get("text", {}).get("format", {}).get("type") == "json_schema"
                            or "responseJsonSchema" in body.get("generationConfig", {})):
                        raise urllib.error.HTTPError(request.full_url, 400, "bad", {},
                                                     BytesIO(b'{"error":"json_schema or responseJsonSchema unsupported"}'))
                    if protocol == "responses":
                        return Response({"status": "completed", "output": [{"type": "message", "content": [
                            {"type": "output_text", "text": "{}"}]}]})
                    return Response({"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": "{}"}]}}]})
                slot = providers.ProviderSlot(1, protocol, "https://proxy.example/v1", "model", "key")
                with mock.patch("urllib.request.urlopen", side_effect=receive):
                    self.assertEqual("{}", self.call(slot))
                self.assertEqual(2, len(bodies))
                if protocol == "responses":
                    self.assertEqual("json_object", bodies[-1]["text"]["format"]["type"])
                else:
                    self.assertNotIn("responseJsonSchema", bodies[-1]["generationConfig"])

    def test_responses_parses_text_skips_reasoning_and_rejects_incomplete(self):
        slot = providers.ProviderSlot(1, "responses", "https://endpoint.example/v1", "model", "key")
        payload = {"status": "completed", "output": [
            {"type": "reasoning", "summary": [{"text": "do not render"}]},
            {"type": "message", "content": [{"type": "output_text", "text": '{"ok":true}'}]}]}
        with mock.patch("urllib.request.urlopen", return_value=Response(payload)) as request:
            self.assertEqual('{"ok":true}', self.call(slot))
        wire = request.call_args.args[0]
        self.assertEqual("https://endpoint.example/v1/responses", wire.full_url)
        body = json.loads(wire.data)
        self.assertEqual("json_schema", body["text"]["format"]["type"])
        self.assertEqual("Output JSON", body["instructions"])
        self.assertEqual("hi", body["input"][0]["content"])
        self.assertEqual(providers.MAX_OUTPUT_TOKENS, body["max_output_tokens"])
        for status in ("incomplete", "failed", "in_progress"):
            with self.subTest(status=status), mock.patch("urllib.request.urlopen", return_value=Response({**payload, "status": status})):
                with self.assertRaises(providers.ProviderError):
                    self.call(slot)

    def test_opencode_headers_keep_same_session_across_formats_and_batches(self):
        session = providers.ProviderSession()
        wires = []
        def receive(request, timeout):
            wires.append(request)
            return chat_reply()
        slot = providers.ProviderSlot(1, "openai", "https://opencode.ai/inference/v1", "model", "key")
        with mock.patch("urllib.request.urlopen", side_effect=receive):
            self.call(slot, session)
            self.call(slot, session)
        headers = [{key.lower(): value for key, value in wire.header_items()} for wire in wires]
        self.assertEqual(headers[0]["x-opencode-session"], headers[1]["x-opencode-session"])
        self.assertNotEqual(headers[0]["x-opencode-request"], headers[1]["x-opencode-request"])
        self.assertEqual("Bilibili-Innocent-Lab-release-notes/1.0", headers[0]["user-agent"])

    def test_anthropic_base_with_v1_and_content_errors_do_not_escape_fallback_chain(self):
        slot = providers.ProviderSlot(1, "anthropic", "https://opencode.ai/inference/v1", "claude", "key")
        with mock.patch("urllib.request.urlopen", return_value=Response({"content": [{"type": "text", "text": "{}"}]})) as request:
            self.assertEqual("{}", self.call(slot))
        self.assertEqual("https://opencode.ai/inference/v1/messages", request.call_args.args[0].full_url)
        self.assertEqual(providers.MAX_OUTPUT_TOKENS, json.loads(request.call_args.args[0].data)["max_tokens"])
        for payload in ([], {"content": None}, {"content": [None, {"type": "text", "text": 1}]}):
            with self.subTest(payload=payload), mock.patch("urllib.request.urlopen", return_value=Response(payload)):
                with self.assertRaises(providers.ProviderError):
                    self.call(slot)

    def test_gemini_native_schema_headers_retry_roles_and_text_only(self):
        slot = providers.ProviderSlot(1, "gemini", "https://opencode.ai/inference/v1", "gemini-test", "key")
        payload = {"candidates": [{"finishReason": "STOP", "content": {"parts": [
            {"text": "private thought", "thought": True}, {"text": '{"ok":true}'}]}}]}
        messages = [{"role": "user", "content": "question"}, {"role": "assistant", "content": "bad JSON"},
                    {"role": "user", "content": "repair"}]
        with mock.patch("urllib.request.urlopen", return_value=Response(payload)) as request:
            text = providers.complete(slot, "system", messages, OUTPUT_SCHEMA)
        self.assertEqual('{"ok":true}', text)
        wire = request.call_args.args[0]
        self.assertEqual("https://opencode.ai/inference/v1/models/gemini-test:generateContent", wire.full_url)
        self.assertEqual("key", dict((k.lower(), v) for k, v in wire.header_items())["x-goog-api-key"])
        body = json.loads(wire.data)
        self.assertEqual(["user", "model", "user"], [content["role"] for content in body["contents"]])
        self.assertEqual(OUTPUT_SCHEMA, body["generationConfig"]["responseJsonSchema"])
        self.assertEqual(providers.MAX_OUTPUT_TOKENS, body["generationConfig"]["maxOutputTokens"])
        for reason in ("MAX_TOKENS", "SAFETY", "CONTINUATION"):
            with self.subTest(reason=reason), mock.patch("urllib.request.urlopen", return_value=Response(
                    {"candidates": [{**payload["candidates"][0], "finishReason": reason}]})):
                with self.assertRaises(providers.ProviderError):
                    self.call(slot)


if __name__ == "__main__":
    unittest.main()
