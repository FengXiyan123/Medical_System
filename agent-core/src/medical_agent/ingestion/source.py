"""Stable source locations carried from parsing through retrieval citations."""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class SourceLocation:
    """A location in extracted source text, never a generated chunk offset.

    ``char_start`` and ``char_end`` use the parser's extracted-text coordinate
    system.  PDF offsets are page-local; other formats use document-local
    extracted text offsets.  Consumers must therefore retain the page and
    section fields instead of treating the offsets as file byte positions.
    """

    page_number: int | None
    section_index: int
    char_start: int
    char_end: int
    heading_path: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.page_number is not None and self.page_number < 1:
            raise ValueError("page_number must be positive when supplied")
        if self.section_index < 0:
            raise ValueError("section_index must not be negative")
        if self.char_start < 0 or self.char_end < self.char_start:
            raise ValueError("source character range is invalid")
