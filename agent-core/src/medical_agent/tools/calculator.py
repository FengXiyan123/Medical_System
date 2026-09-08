"""A deliberately tiny arithmetic tool; it never executes Python source."""

from __future__ import annotations

import ast
import math
from collections.abc import Mapping

from medical_agent.tools.registry import (
    ToolDefinition,
    ToolExecutionContext,
    ToolInputValidationError,
    ToolRegistry,
)


class CalculatorError(ToolInputValidationError):
    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.code = "CALCULATOR_EXPRESSION_FORBIDDEN"
        self.args = (f"{self.code}: {message}",)


class SafeCalculator:
    max_expression_length = 200
    max_nodes = 50
    max_literal_magnitude = 1_000_000_000_000
    max_result_magnitude = 1_000_000_000_000_000
    max_exponent = 12

    def calculate(self, arguments: Mapping[str, object]) -> dict[str, object]:
        expression = arguments.get("expression")
        if not isinstance(expression, str) or not expression.strip():
            raise CalculatorError("expression must be a nonblank string")
        if len(expression) > self.max_expression_length:
            raise CalculatorError("expression is too long")
        try:
            tree = ast.parse(expression, mode="eval")
        except SyntaxError as error:
            raise CalculatorError("expression is not valid arithmetic") from error
        if sum(1 for _ in ast.walk(tree)) > self.max_nodes:
            raise CalculatorError("expression is too complex")
        try:
            value = self._evaluate(tree.body)
        except (ArithmeticError, ValueError) as error:
            raise CalculatorError("arithmetic operation is invalid") from error
        if not math.isfinite(value) or abs(value) > self.max_result_magnitude:
            raise CalculatorError("expression result is outside the allowed range")
        normalized: int | float = int(value) if float(value).is_integer() else value
        result: dict[str, object] = {"value": normalized}
        unit = arguments.get("unit")
        if unit is not None:
            if not isinstance(unit, str) or not unit.strip() or len(unit) > 30:
                raise CalculatorError("unit must be a short nonblank string")
            result["unit"] = unit
        return result

    def _evaluate(self, node: ast.AST) -> float:
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)) and not isinstance(node.value, bool):
            value = float(node.value)
            if not math.isfinite(value) or abs(value) > self.max_literal_magnitude:
                raise CalculatorError("numeric literal is outside the allowed range")
            return value
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            value = self._evaluate(node.operand)
            return value if isinstance(node.op, ast.UAdd) else -value
        if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod, ast.Pow)):
            left = self._evaluate(node.left)
            right = self._evaluate(node.right)
            if isinstance(node.op, ast.Add):
                return left + right
            if isinstance(node.op, ast.Sub):
                return left - right
            if isinstance(node.op, ast.Mult):
                return left * right
            if isinstance(node.op, ast.Div):
                return left / right
            if isinstance(node.op, ast.FloorDiv):
                return left // right
            if isinstance(node.op, ast.Mod):
                return left % right
            if abs(right) > self.max_exponent or (left < 0 and not right.is_integer()):
                raise CalculatorError("exponent is outside the allowed range")
            return left**right
        # Name, Attribute, Call, comprehension, f-string, import and all other
        # AST nodes reach this fail-closed branch.
        raise CalculatorError("only arithmetic literals and operators are allowed")


_SCHEMA: dict[str, object] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["expression"],
    "properties": {
        "expression": {"type": "string", "minLength": 1, "maxLength": 200},
        "unit": {"type": "string", "minLength": 1, "maxLength": 30},
    },
}


def register_calculator(registry: ToolRegistry) -> ToolRegistry:
    calculator = SafeCalculator()

    def handler(arguments: Mapping[str, object], _: ToolExecutionContext) -> Mapping[str, object]:
        return calculator.calculate(arguments)

    return registry.register(ToolDefinition("calculator", "Perform bounded arithmetic.", _SCHEMA, handler))
