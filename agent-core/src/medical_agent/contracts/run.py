from enum import Enum

from pydantic import BaseModel, Field, model_validator


class RunMode(str, Enum):
    AUTO_KB = "AUTO_KB"
    MANUAL_KB = "MANUAL_KB"
    AGENT = "AGENT"


class CreateRunRequest(BaseModel):
    question: str = Field(min_length=1, max_length=10_000)
    mode: RunMode
    knowledge_base_ids: tuple[str, ...] = ()

    @model_validator(mode="after")
    def validate_knowledge_scope(self) -> "CreateRunRequest":
        selected = self.knowledge_base_ids
        if self.mode is RunMode.MANUAL_KB and not selected:
            raise ValueError("knowledge_base_ids is required for MANUAL_KB")
        if self.mode is not RunMode.MANUAL_KB and selected:
            raise ValueError("knowledge_base_ids is only accepted for MANUAL_KB")
        if len(selected) > 5:
            raise ValueError("knowledge_base_ids may contain at most 5 values")
        if len(set(selected)) != len(selected):
            raise ValueError("knowledge_base_ids must not contain duplicates")
        return self
