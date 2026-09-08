"""A small, explicit tool boundary for the autonomous Agent.

Tools are registered by application code, validated before invocation, and
executed only when their identifier is present in the gateway-provided route.
There is deliberately no dynamic import, shell bridge, or callable lookup from
model-provided text.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass

from medical_agent.retrieval.scope import ResolvedKnowledgeScope


class ToolError(ValueError):
    """A compact, client-safe tool failure."""

    def __init__(self, code: str, message: str, *, retryable: bool = False) -> None:
        self.code = code
        self.retryable = retryable
        super().__init__(f"{code}: {message}")


class ToolAuthorizationError(ToolError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(code, message)


class ToolInputValidationError(ToolError):
    def __init__(self, message: str) -> None:
        super().__init__("TOOL_INPUT_INVALID", message)


@dataclass(frozen=True, slots=True)
class ToolExecutionContext:
    scope: ResolvedKnowledgeScope


ToolHandler = Callable[[Mapping[str, object], ToolExecutionContext], Mapping[str, object]]


@dataclass(frozen=True, slots=True)
class ToolDefinition:
    name: str
    description: str
    input_schema: Mapping[str, object]
    handler: ToolHandler

    def __post_init__(self) -> None:
        if not self.name.strip() or not self.description.strip():
            raise ValueError("tool name and description must not be blank")
        if self.input_schema.get("type") != "object":
            raise ValueError("tool input schema must describe an object")

    def openai_schema(self) -> dict[str, object]:
        """Return the provider-facing function descriptor without executable data."""

        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": dict(self.input_schema),
            },
        }


class ToolRegistry:
    def __init__(self, *, allowed_tool_names: tuple[str, ...]) -> None:
        if len(set(allowed_tool_names)) != len(allowed_tool_names):
            raise ValueError("allowed tool names must be unique")
        if any(not name.strip() for name in allowed_tool_names):
            raise ValueError("allowed tool names must not be blank")
        self._allowed = frozenset(allowed_tool_names)
        self._definitions: dict[str, ToolDefinition] = {}

    def register(self, definition: ToolDefinition) -> ToolRegistry:
        if definition.name in self._definitions:
            raise ValueError("tool already registered")
        self._definitions[definition.name] = definition
        return self

    def provider_tools(self) -> tuple[dict[str, object], ...]:
        return tuple(
            definition.openai_schema()
            for name, definition in self._definitions.items()
            if name in self._allowed
        )

    def execute(
        self, name: str, arguments: Mapping[str, object], context: ToolExecutionContext
    ) -> Mapping[str, object]:
        if name not in self._allowed:
            raise ToolAuthorizationError("TOOL_NOT_AUTHORIZED", "tool is not authorized for this run")
        definition = self._definitions.get(name)
        if definition is None:
            raise ToolAuthorizationError("TOOL_NOT_AVAILABLE", "tool is not registered")
        _validate_json_schema(arguments, definition.input_schema)
        result = definition.handler(arguments, context)
        if not isinstance(result, Mapping):
            raise TypeError("tool handler must return an object")
        return result


def _validate_json_schema(value: object, schema: Mapping[str, object], path: str = "$") -> None:
    """Validate the JSON-Schema subset used by this fixed tool catalogue.

    Avoiding a permissive generic converter makes model input fail closed.  The
    subset covers object, scalar and array constraints required by the three P0
    tools; each unsupported schema keyword is an application authoring error.
    """

    kind = schema.get("type")
    if kind == "object":
        if not isinstance(value, Mapping):
            raise ToolInputValidationError(f"{path} must be an object")
        properties = schema.get("properties", {})
        required = schema.get("required", [])
        if not isinstance(properties, Mapping) or not isinstance(required, list):
            raise RuntimeError("invalid registered object schema")
        for name in required:
            if not isinstance(name, str) or name not in value:
                raise ToolInputValidationError(f"{path}.{name} is required")
        if schema.get("additionalProperties", True) is False:
            extras = set(value) - set(properties)
            if extras:
                raise ToolInputValidationError(f"{path} contains unsupported field {min(extras)}")
        for name, item in value.items():
            child = properties.get(name)
            if child is not None:
                if not isinstance(child, Mapping):
                    raise RuntimeError("invalid registered property schema")
                _validate_json_schema(item, child, f"{path}.{name}")
        return
    if kind == "string":
        if not isinstance(value, str):
            raise ToolInputValidationError(f"{path} must be a string")
        minimum = schema.get("minLength")
        maximum = schema.get("maxLength")
        if isinstance(minimum, int) and len(value) < minimum:
            raise ToolInputValidationError(f"{path} is too short")
        if isinstance(maximum, int) and len(value) > maximum:
            raise ToolInputValidationError(f"{path} is too long")
        return
    if kind == "integer":
        if not isinstance(value, int) or isinstance(value, bool):
            raise ToolInputValidationError(f"{path} must be an integer")
        _validate_number_bounds(value, schema, path)
        return
    if kind == "number":
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            raise ToolInputValidationError(f"{path} must be a number")
        _validate_number_bounds(value, schema, path)
        return
    if kind == "array":
        if not isinstance(value, list):
            raise ToolInputValidationError(f"{path} must be an array")
        minimum = schema.get("minItems")
        maximum = schema.get("maxItems")
        if isinstance(minimum, int) and len(value) < minimum:
            raise ToolInputValidationError(f"{path} contains too few values")
        if isinstance(maximum, int) and len(value) > maximum:
            raise ToolInputValidationError(f"{path} contains too many values")
        if schema.get("uniqueItems") and len({repr(item) for item in value}) != len(value):
            raise ToolInputValidationError(f"{path} must contain unique values")
        item_schema = schema.get("items")
        if not isinstance(item_schema, Mapping):
            raise RuntimeError("invalid registered array schema")
        for index, item in enumerate(value):
            _validate_json_schema(item, item_schema, f"{path}[{index}]")
        return
    raise RuntimeError(f"unsupported registered schema type: {kind!r}")


def _validate_number_bounds(value: float, schema: Mapping[str, object], path: str) -> None:
    minimum = schema.get("minimum")
    maximum = schema.get("maximum")
    if isinstance(minimum, (int, float)) and value < minimum:
        raise ToolInputValidationError(f"{path} is below the minimum")
    if isinstance(maximum, (int, float)) and value > maximum:
        raise ToolInputValidationError(f"{path} is above the maximum")
