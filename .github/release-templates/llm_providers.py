#!/usr/bin/env python3
"""Ordered LLM provider slots for release-note generation (standard library only).

最多三个槽位，按 1 → 2 → 3 顺序尝试，每个槽位由以下环境变量描述：

    RELEASE_NOTES_LLM_<N>_API_KEY   必填；为空则跳过该槽位
    RELEASE_NOTES_LLM_<N>_PROTOCOL  anthropic（默认）、openai、responses 或 gemini
    RELEASE_NOTES_LLM_<N>_BASE_URL  可选；anthropic 默认 https://api.anthropic.com，
                                    openai 默认 https://api.openai.com/v1；私有代理地址应放 Secret
    RELEASE_NOTES_LLM_<N>_MODEL     anthropic 默认 claude-opus-5；openai 必填
    RELEASE_NOTES_LLM_<N>_HEADERS_JSON / BODY_JSON  可选 Secret，自定义头和请求参数
    RELEASE_NOTES_LLM_<N>_OUTPUT_FORMAT  auto / json_schema / json_object / text

结构化输出参数不被代理或兼容接口支持时自动逐级降级为纯提示词 JSON；所有密钥只进请求头，
不写日志。
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field


SLOT_COUNT = 3
DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"
DEFAULT_ANTHROPIC_MODEL = "claude-opus-5"
DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
REQUEST_TIMEOUT_SECONDS = 300
MAX_OUTPUT_TOKENS = 16000
# 官方 API 上的服务端拒答回退：安全分类器拒答时由服务端换模型重跑，不经过代理时才启用。
ANTHROPIC_FALLBACK_BETA = "server-side-fallback-2026-07-01"
ANTHROPIC_FALLBACK_MODELS = frozenset({"claude-opus-5", "claude-fable-5-1"})


class ProviderError(Exception):
    """Non-recoverable for this provider slot; the caller moves on to the next slot."""


class _RetryWithoutFormat(Exception):
    pass


@dataclass(frozen=True)
class ProviderSlot:
    index: int
    protocol: str
    base_url: str
    model: str
    api_key: str = field(repr=False)
    output_format: str = "auto"
    extra_headers: tuple[tuple[str, str], ...] = field(default=(), repr=False)
    extra_body: dict = field(default_factory=dict, repr=False)

    @property
    def label(self) -> str:
        return f"槽位 {self.index}（{self.protocol} · {self.model}）"


@dataclass
class ProviderSession:
    # 一次生成中的格式探测结果与会话路由；校验重试和后续批次沿用，不跨发布共享。
    formats: dict[int, int] = field(default_factory=dict)
    session_id: str = field(default_factory=lambda: str(uuid.uuid4()))


def _config_object(raw: str, label: str) -> dict:
    try:
        value = json.loads(raw) if raw.strip() else {}
    except ValueError:
        raise ValueError(f"{label} 必须是 JSON 对象") from None
    if not isinstance(value, dict):
        raise ValueError(f"{label} 必须是 JSON 对象")
    return value


def _validated_headers(raw: str) -> tuple[tuple[str, str], ...]:
    headers = _config_object(raw, "HEADERS_JSON")
    names = set()
    for name, value in headers.items():
        if (not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name)
                or not isinstance(value, str) or not value.isascii()
                or any(ord(char) < 32 or ord(char) == 127 for char in value)
                or name.lower() in {"host", "content-length", "transfer-encoding", "connection"}
                or name.lower() in names):
            raise ValueError("HEADERS_JSON 含无效、重复或禁止覆盖的请求头")
        names.add(name.lower())
    return tuple(headers.items())


def load_slots(environ: dict[str, str] | None = None) -> tuple[list[ProviderSlot], list[str]]:
    """读取已配置的槽位；返回（可用槽位, 配置问题说明）。"""
    env = os.environ if environ is None else environ
    slots: list[ProviderSlot] = []
    problems: list[str] = []
    for index in range(1, SLOT_COUNT + 1):
        prefix = f"RELEASE_NOTES_LLM_{index}_"
        api_key = env.get(prefix + "API_KEY", "").strip()
        headers_raw = env.get(prefix + "HEADERS_JSON", "")
        if not api_key and not headers_raw.strip():
            continue
        protocol = (env.get(prefix + "PROTOCOL", "").strip() or "anthropic").lower()
        base_url = env.get(prefix + "BASE_URL", "").strip().rstrip("/")
        model = env.get(prefix + "MODEL", "").strip()
        if protocol == "anthropic":
            base_url = base_url or DEFAULT_ANTHROPIC_BASE_URL
            model = model or DEFAULT_ANTHROPIC_MODEL
        elif protocol in ("openai", "responses"):
            base_url = base_url or DEFAULT_OPENAI_BASE_URL
            if not model:
                problems.append(f"槽位 {index} 使用 {protocol} 协议但未设置 {prefix}MODEL，已跳过")
                continue
        elif protocol == "gemini":
            base_url = base_url or DEFAULT_GEMINI_BASE_URL
            if not model:
                problems.append(f"槽位 {index} 使用 gemini 协议但未设置 {prefix}MODEL，已跳过")
                continue
        else:
            problems.append(f"槽位 {index} 的 PROTOCOL={protocol!r} 无法识别，已跳过")
            continue
        try:
            headers = _validated_headers(headers_raw)
            extra_body = _config_object(env.get(prefix + "BODY_JSON", ""), "BODY_JSON")
            reserved = {"model", "messages", "input", "instructions", "system", "stream", "tools", "toolConfig",
                        "contents", "systemInstruction",
                        "response_format", "text", "output_config", "fallbacks"}
            if reserved.intersection(extra_body):
                raise ValueError("BODY_JSON 不能覆盖模型、消息、工具或输出格式字段")
            if "generationConfig" in extra_body:
                generation = extra_body["generationConfig"]
                if not isinstance(generation, dict) or {"responseMimeType", "responseSchema", "responseJsonSchema"}.intersection(generation):
                    raise ValueError("BODY_JSON 的 generationConfig 无效或覆盖输出格式")
            output_format = env.get(prefix + "OUTPUT_FORMAT", "auto").strip().lower() or "auto"
            if output_format not in {"auto", "json_schema", "json_object", "text"}:
                raise ValueError("OUTPUT_FORMAT 仅支持 auto/json_schema/json_object/text")
            if protocol == "anthropic" and output_format == "json_object":
                raise ValueError("anthropic 协议不支持 json_object，请用 auto/json_schema/text")
            parsed_url = urllib.parse.urlsplit(base_url)
            if parsed_url.scheme not in {"https", "http"} or not parsed_url.netloc or parsed_url.query or parsed_url.fragment:
                raise ValueError("BASE_URL 必须是无查询参数与片段的 HTTP(S) 基础地址")
        except ValueError as error:
            problems.append(f"槽位 {index} 配置无效：{error}，已跳过")
            continue
        slots.append(ProviderSlot(index, protocol, base_url, model, api_key, output_format, headers, extra_body))
    return slots, problems


def _post_json(url: str, headers: dict[str, str], payload: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers},
        method="POST",
    )
    last_error = ""
    for attempt in range(2):
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                result = json.loads(response.read().decode("utf-8"))
                if not isinstance(result, dict):
                    raise ProviderError("响应顶层不是 JSON 对象")
                return result
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:500]
            has_format = (any(key in payload for key in ("response_format", "output_config", "text"))
                          or "responseMimeType" in payload.get("generationConfig", {}))
            if error.code in (400, 422) and has_format and _mentions_format(detail):
                raise _RetryWithoutFormat(detail) from error
            if error.code in (408, 409, 429) or error.code >= 500:
                last_error = f"HTTP {error.code}: {detail}"
                if attempt == 0:
                    time.sleep(5)
                continue
            raise ProviderError(f"HTTP {error.code}: {detail}") from error
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            last_error = f"网络错误：{error}"
            if attempt == 0:
                time.sleep(5)
        except ValueError as error:
            raise ProviderError(f"响应不是合法 JSON：{error}") from error
    raise ProviderError(last_error or "请求失败")


def _mentions_format(detail: str) -> bool:
    lowered = detail.lower()
    return any(key in lowered for key in ("output_config", "response_format", "json_schema", "text.format",
                                         "responsejsonschema", "responseschema", "responsemimetype", "structured output"))


def _endpoint(slot: ProviderSlot, path: str) -> str:
    base = slot.base_url.rstrip("/")
    if base.endswith("/" + path):
        return base
    # Anthropic 代理既可能配置根路径，也可能配置已包含 /v1 的基础地址。
    if path == "messages" and not base.endswith("/v1"):
        base += "/v1"
    return f"{base}/{path}"


def _headers(slot: ProviderSlot, defaults: dict[str, str], session: ProviderSession) -> dict[str, str]:
    headers = {"User-Agent": "Bilibili-Innocent-Lab-release-notes/1.0", **defaults}
    if urllib.parse.urlsplit(slot.base_url).hostname == "opencode.ai":
        headers.update({"x-opencode-session": session.session_id,
                        "x-opencode-request": str(uuid.uuid4()), "x-opencode-client": "innocent-lab-release-notes"})
    # 大小写不敏感地覆盖，允许 API-key、租户、代理认证和归属请求头。
    for name, value in slot.extra_headers:
        headers = {key: old for key, old in headers.items() if key.lower() != name.lower()}
        headers[name] = value
    return headers


def _anthropic_complete(slot: ProviderSlot, system: str, messages: list[dict], schema: dict | None,
                        session: ProviderSession) -> str:
    headers = _headers(slot, {"x-api-key": slot.api_key, "anthropic-version": "2023-06-01"}, session)
    payload: dict = {
        "model": slot.model,
        "max_tokens": MAX_OUTPUT_TOKENS,
        "system": system,
        "messages": messages,
    }
    if schema is not None:
        payload["output_config"] = {"format": {"type": "json_schema", "schema": schema}}
    if slot.base_url == DEFAULT_ANTHROPIC_BASE_URL and slot.model in ANTHROPIC_FALLBACK_MODELS:
        headers["anthropic-beta"] = ANTHROPIC_FALLBACK_BETA
        payload["fallbacks"] = "default"
    payload.update(slot.extra_body)
    response = _post_json(_endpoint(slot, "messages"), headers, payload)
    stop_reason = response.get("stop_reason")
    if stop_reason == "refusal":
        raise ProviderError(f"模型拒答：{response.get('stop_details')}")
    if stop_reason == "max_tokens":
        raise ProviderError("输出达到 max_tokens 上限被截断")
    content = response.get("content")
    if not isinstance(content, list):
        raise ProviderError("响应 content 不是数组")
    text = "".join(block["text"] for block in content
                   if isinstance(block, dict) and block.get("type") == "text" and isinstance(block.get("text"), str))
    if not text.strip():
        raise ProviderError("响应中没有文本内容")
    return text


def _openai_complete(
    slot: ProviderSlot, system: str, messages: list[dict], response_format: dict | None, session: ProviderSession
) -> str:
    payload: dict = {
        "model": slot.model,
        "messages": [{"role": "system", "content": system}, *messages],
    }
    if urllib.parse.urlsplit(slot.base_url).hostname == "api.deepseek.com":
        payload.update({"max_tokens": MAX_OUTPUT_TOKENS, "thinking": {"type": "disabled"}})
    payload.update(slot.extra_body)
    if response_format is not None:
        payload["response_format"] = response_format
    response = _post_json(
        _endpoint(slot, "chat/completions"),
        _headers(slot, {"Authorization": f"Bearer {slot.api_key}"}, session),
        payload,
    )
    try:
        choice = response["choices"][0]
        raw = choice["message"]["content"] or ""
    except (KeyError, IndexError, TypeError) as error:
        raise ProviderError(f"响应结构无法识别：{str(response)[:300]}") from error
    if choice.get("message", {}).get("refusal") or choice.get("finish_reason") == "content_filter":
        raise ProviderError("模型拒答或内容被过滤")
    if isinstance(raw, list):
        # OpenAI 兼容端点可能返回分片式 content（[{type:text,text:...}]）：
        # 直接对 list 调 strip 会抛 AttributeError，绕过 ProviderError 降级链，
        # 让"槽位失败→换下一个→回退规则版"整条设计失效；这里拼回纯文本。
        text = "".join(
            part["text"] for part in raw
            if isinstance(part, dict) and isinstance(part.get("text"), str)
        )
    elif isinstance(raw, str):
        text = raw
    else:
        raise ProviderError(f"响应内容类型异常：{type(raw).__name__}")
    if choice.get("finish_reason") == "length":
        raise ProviderError("输出达到长度上限被截断")
    if not text.strip():
        raise ProviderError("响应中没有文本内容")
    return text


def _responses_complete(slot: ProviderSlot, system: str, messages: list[dict], response_format: dict | None,
                        session: ProviderSession) -> str:
    payload = {"model": slot.model, "instructions": system, "input": messages,
               "max_output_tokens": MAX_OUTPUT_TOKENS}
    payload.update(slot.extra_body)
    if response_format is not None:
        format_value = dict(response_format)
        if format_value.get("type") == "json_schema":
            format_value = {"type": "json_schema", **format_value["json_schema"]}
        payload["text"] = {"format": format_value}
    response = _post_json(_endpoint(slot, "responses"),
                          _headers(slot, {"Authorization": f"Bearer {slot.api_key}"}, session), payload)
    if response.get("error") or response.get("status") in {"failed", "cancelled", "incomplete", "queued", "in_progress"}:
        raise ProviderError(f"Responses 请求未完成：{response.get('status', 'error')}")
    output = response.get("output")
    if not isinstance(output, list):
        raise ProviderError("Responses output 不是数组")
    texts = []
    for item in output:
        if not isinstance(item, dict) or item.get("type") != "message":
            continue
        content = item.get("content")
        if not isinstance(content, list):
            raise ProviderError("Responses message.content 不是数组")
        for part in content:
            if not isinstance(part, dict):
                continue
            if part.get("type") == "refusal":
                raise ProviderError("模型拒答")
            if part.get("type") == "output_text" and isinstance(part.get("text"), str):
                texts.append(part["text"])
    text = "".join(texts)
    if not text.strip():
        raise ProviderError("Responses 响应中没有文本内容")
    return text


def _gemini_complete(slot: ProviderSlot, system: str, messages: list[dict], response_format: dict | None,
                     session: ProviderSession) -> str:
    contents = [{"role": "model" if message["role"] == "assistant" else "user",
                 "parts": [{"text": message["content"]}]} for message in messages]
    generation = {"maxOutputTokens": MAX_OUTPUT_TOKENS, **slot.extra_body.get("generationConfig", {})}
    if response_format is not None:
        generation["responseMimeType"] = "application/json"
        if response_format["type"] == "json_schema":
            generation["responseJsonSchema"] = response_format["json_schema"]["schema"]
    payload = {**slot.extra_body, "systemInstruction": {"parts": [{"text": system}]},
               "contents": contents, "generationConfig": generation}
    base = slot.base_url.rstrip("/")
    model = urllib.parse.quote(slot.model.removeprefix("models/"), safe="")
    if base.endswith(":generateContent"):
        url = base
    elif base.endswith("/models/" + model):
        url = base + ":generateContent"
    else:
        url = f"{base}/models/{model}:generateContent"
    response = _post_json(url, _headers(slot, {"x-goog-api-key": slot.api_key}, session), payload)
    try:
        candidate = response["candidates"][0]
        reason = candidate.get("finishReason")
        if reason != "STOP":
            raise ProviderError(f"Gemini 请求未正常完成：{reason}")
        parts = candidate["content"]["parts"]
        if not isinstance(parts, list):
            raise ProviderError("Gemini parts 不是数组")
    except (KeyError, IndexError, TypeError, AttributeError):
        raise ProviderError("Gemini 响应结构无法识别或请求被拒绝") from None
    text = "".join(part["text"] for part in parts if isinstance(part, dict) and not part.get("thought")
                   and isinstance(part.get("text"), str))
    if not text.strip():
        raise ProviderError("Gemini 响应中没有文本内容")
    return text


_DEFAULT_BASE_URLS = frozenset({DEFAULT_ANTHROPIC_BASE_URL, DEFAULT_OPENAI_BASE_URL})
# 服务端错误里可能回显（部分打码的）密钥；GitHub 只遮蔽完整 Secret，片段需要自己抹掉。
_SECRET_PATTERNS = (
    re.compile(r"\bsk-[A-Za-z0-9_\-*.]{6,}"),
    re.compile(r"(?i)(bearer\s+)[^\s\"',}]+"),
    re.compile(r"(?i)((?:api[_-]?key|x-api-key|token)[\"']?\s*[:=]\s*[\"']?)[^\s\"',}]+"),
)


def redact(text: str, slot: ProviderSlot) -> str:
    """日志与 Job Summary 在公开仓库中可见：抹掉密钥、密钥片段与非官方端点地址。"""
    if slot.api_key:
        text = text.replace(slot.api_key, "***")
    for _, value in slot.extra_headers:
        if value:
            text = text.replace(value, "***")
            if value.lower().startswith("bearer "):
                text = text.replace(value[7:], "***")
    def body_strings(value):
        if isinstance(value, str):
            yield value
        elif isinstance(value, dict):
            for child in value.values():
                yield from body_strings(child)
        elif isinstance(value, list):
            for child in value:
                yield from body_strings(child)
    for value in body_strings(slot.extra_body):
        if value:
            text = text.replace(value, "***")
    if slot.base_url not in _DEFAULT_BASE_URLS:
        host = urllib.parse.urlsplit(slot.base_url).netloc
        for secret in (slot.base_url, host):
            if secret:
                text = text.replace(secret, "<私有端点>")
    for pattern in _SECRET_PATTERNS:
        text = pattern.sub(lambda match: (match.group(1) if match.groups() else "") + "***", text)
    return text


def complete(slot: ProviderSlot, system: str, messages: list[dict], schema: dict,
             *, session: ProviderSession | None = None) -> str:
    """调用一个槽位；错误信息在抛出前脱敏。"""
    try:
        return _complete(slot, system, messages, schema, session or ProviderSession())
    except ProviderError as error:
        raise ProviderError(redact(str(error), slot)) from None


def _complete(slot: ProviderSlot, system: str, messages: list[dict], schema: dict, session: ProviderSession) -> str:
    """结构化输出参数被拒时逐级降级，最终仍依赖提示词约束 JSON。"""
    if slot.protocol == "anthropic":
        formats: list = [schema, None]
        call = _anthropic_complete
    else:
        formats = [
            {"type": "json_schema", "json_schema": {"name": "release_notes", "schema": schema, "strict": True}},
            {"type": "json_object"},
            None,
        ]
        call = {"responses": _responses_complete, "gemini": _gemini_complete}.get(slot.protocol, _openai_complete)
    start = session.formats.get(slot.index)
    if start is None:
        if slot.output_format == "text":
            start = len(formats) - 1
        elif slot.output_format == "json_object" or (slot.output_format == "auto" and slot.protocol == "openai"
                and urllib.parse.urlsplit(slot.base_url).hostname == "api.deepseek.com"):
            start = 1
        else:
            start = 0
    for index in range(start, len(formats)):
        option = formats[index]
        try:
            text = call(slot, system, messages, option, session)
            session.formats[slot.index] = index
            return text
        except _RetryWithoutFormat as rejection:
            session.formats[slot.index] = index + 1
            print(f"{slot.label} 不支持当前结构化输出参数，降级重试：{redact(str(rejection), slot)}", file=sys.stderr)
    raise ProviderError("所有输出格式均被拒绝")
