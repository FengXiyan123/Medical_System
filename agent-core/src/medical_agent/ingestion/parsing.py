"""Format-specific parsing into a loss-aware, format-neutral section model."""

from __future__ import annotations

import io
import re
import zipfile
from collections.abc import Callable
from dataclasses import dataclass
from enum import StrEnum
from pathlib import PurePath
from typing import Protocol
from xml.etree import ElementTree

from medical_agent.ingestion.chunking import DocumentSection
from medical_agent.ingestion.source import SourceLocation


class DocumentFormat(StrEnum):
    PDF = "PDF"
    DOCX = "DOCX"
    MARKDOWN = "MARKDOWN"
    TEXT = "TEXT"


class DocumentParseStatus(StrEnum):
    PARSED = "PARSED"
    OCR_REQUIRED = "OCR_REQUIRED"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class ParsedSection:
    title: str
    text: str
    source: SourceLocation

    @property
    def heading_path(self) -> tuple[str, ...]:
        return self.source.heading_path

    def as_chunk_section(self) -> DocumentSection:
        return DocumentSection(
            title=self.title,
            text=self.text,
            page_number=self.source.page_number,
            source=self.source,
        )


@dataclass(frozen=True, slots=True)
class ParsedDocument:
    filename: str
    format: DocumentFormat
    status: DocumentParseStatus
    sections: tuple[ParsedSection, ...]
    error_code: str | None = None

    def __post_init__(self) -> None:
        if self.status is DocumentParseStatus.PARSED and not self.sections:
            raise ValueError("a parsed document must contain at least one section")
        if self.status is not DocumentParseStatus.PARSED and self.sections:
            raise ValueError("failed documents must not expose parsed sections")
        if self.status is DocumentParseStatus.OCR_REQUIRED and self.error_code != "OCR_REQUIRED":
            raise ValueError("OCR_REQUIRED documents must use the OCR_REQUIRED error code")

    def as_chunk_sections(self) -> tuple[DocumentSection, ...]:
        return tuple(section.as_chunk_section() for section in self.sections)


class DocumentParser(Protocol):
    def parse(self, *, filename: str, content: bytes) -> ParsedDocument: ...


class PdfPage(Protocol):
    def extract_text(self) -> str | None: ...


class PdfReader(Protocol):
    @property
    def pages(self) -> object: ...


PdfReaderFactory = Callable[[io.BytesIO], PdfReader]


@dataclass(frozen=True, slots=True)
class PdfDocumentParser:
    """Text-PDF parser. Image-only PDFs deliberately stop at OCR_REQUIRED."""

    reader_factory: PdfReaderFactory | None = None

    def parse(self, *, filename: str, content: bytes) -> ParsedDocument:
        reader = (self.reader_factory or _default_pdf_reader)(io.BytesIO(content))
        sections: list[ParsedSection] = []
        for page_index, page in enumerate(reader.pages):  # type: ignore[union-attr]
            text = (page.extract_text() or "").strip()
            if not text:
                continue
            sections.append(
                ParsedSection(
                    title=f"第 {page_index + 1} 页",
                    text=text,
                    source=SourceLocation(
                        page_number=page_index + 1,
                        section_index=len(sections),
                        char_start=0,
                        char_end=len(text),
                    ),
                )
            )
        if not sections:
            return ParsedDocument(
                filename=filename,
                format=DocumentFormat.PDF,
                status=DocumentParseStatus.OCR_REQUIRED,
                sections=(),
                error_code="OCR_REQUIRED",
            )
        return ParsedDocument(
            filename=filename,
            format=DocumentFormat.PDF,
            status=DocumentParseStatus.PARSED,
            sections=tuple(sections),
        )


@dataclass(frozen=True, slots=True)
class TextDocumentParser:
    def parse(self, *, filename: str, content: bytes) -> ParsedDocument:
        text = _decode_text(content).strip()
        return _single_text_document(filename, DocumentFormat.TEXT, text)


@dataclass(frozen=True, slots=True)
class MarkdownDocumentParser:
    def parse(self, *, filename: str, content: bytes) -> ParsedDocument:
        text = _decode_text(content)
        headings = tuple(re.finditer(r"(?m)^(#{1,6})[ \t]+(.+?)[ \t]*$", text))
        if not headings:
            return _single_text_document(filename, DocumentFormat.MARKDOWN, text.strip())

        sections: list[ParsedSection] = []
        path: list[str] = []
        levels: list[int] = []
        for index, heading in enumerate(headings):
            level = len(heading.group(1))
            title = heading.group(2).strip()
            while levels and levels[-1] >= level:
                levels.pop()
                path.pop()
            levels.append(level)
            path.append(title)
            body_start = heading.end()
            body_end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
            body = text[body_start:body_end].strip()
            if not body:
                continue
            section_start = text.index(body, body_start, body_end)
            sections.append(
                ParsedSection(
                    title=title,
                    text=body,
                    source=SourceLocation(
                        page_number=None,
                        section_index=len(sections),
                        char_start=section_start,
                        char_end=section_start + len(body),
                        heading_path=tuple(path),
                    ),
                )
            )
        return _parsed_or_failed(filename, DocumentFormat.MARKDOWN, sections)


@dataclass(frozen=True, slots=True)
class DocxDocumentParser:
    """DOCX XML reader that retains headings, paragraphs and table rows."""

    def parse(self, *, filename: str, content: bytes) -> ParsedDocument:
        try:
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                root = ElementTree.fromstring(archive.read("word/document.xml"))
        except (KeyError, zipfile.BadZipFile, ElementTree.ParseError) as error:
            raise ValueError("invalid DOCX document") from error

        namespace = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
        body = root.find(f"{namespace}body")
        if body is None:
            raise ValueError("DOCX document has no body")
        sections: list[ParsedSection] = []
        title = _file_title(filename)
        heading_path: list[str] = []
        content_lines: list[str] = []
        section_start = 0
        extracted_offset = 0

        def flush() -> None:
            nonlocal content_lines, section_start
            cleaned = "\n".join(line for line in content_lines if line.strip()).strip()
            if not cleaned:
                content_lines = []
                return
            sections.append(
                ParsedSection(
                    title=title,
                    text=cleaned,
                    source=SourceLocation(
                        page_number=None,
                        section_index=len(sections),
                        char_start=section_start,
                        char_end=section_start + len(cleaned),
                        heading_path=tuple(heading_path),
                    ),
                )
            )
            content_lines = []

        for child in body:
            if child.tag == f"{namespace}p":
                paragraph = _docx_text(child, namespace).strip()
                if not paragraph:
                    continue
                style = _docx_style(child, namespace)
                if style.lower().startswith("heading"):
                    flush()
                    title = paragraph
                    heading_path = [paragraph]
                    section_start = extracted_offset + len(paragraph) + 1
                else:
                    content_lines.append(paragraph)
                extracted_offset += len(paragraph) + 1
            elif child.tag == f"{namespace}tbl":
                rows = _docx_table_rows(child, namespace)
                content_lines.extend(rows)
                extracted_offset += sum(len(row) + 1 for row in rows)
        flush()
        return _parsed_or_failed(filename, DocumentFormat.DOCX, sections)


class DocumentParserRegistry:
    def __init__(self) -> None:
        self._parsers: dict[DocumentFormat, DocumentParser] = {
            DocumentFormat.PDF: PdfDocumentParser(),
            DocumentFormat.DOCX: DocxDocumentParser(),
            DocumentFormat.MARKDOWN: MarkdownDocumentParser(),
            DocumentFormat.TEXT: TextDocumentParser(),
        }

    def parse(self, *, filename: str, content: bytes) -> ParsedDocument:
        return self._parsers[_format_for_filename(filename)].parse(filename=filename, content=content)


def _format_for_filename(filename: str) -> DocumentFormat:
    suffix = PurePath(filename).suffix.lower()
    formats = {".pdf": DocumentFormat.PDF, ".docx": DocumentFormat.DOCX, ".md": DocumentFormat.MARKDOWN, ".txt": DocumentFormat.TEXT}
    try:
        return formats[suffix]
    except KeyError as error:
        raise ValueError("unsupported document format") from error


def _default_pdf_reader(stream: io.BytesIO) -> PdfReader:
    try:
        from pypdf import PdfReader as ExternalPdfReader
    except ImportError as error:
        raise RuntimeError("pypdf is required to parse PDF documents") from error
    return ExternalPdfReader(stream)


def _decode_text(content: bytes) -> str:
    try:
        return content.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise ValueError("text document must be UTF-8 encoded") from error


def _single_text_document(filename: str, format: DocumentFormat, text: str) -> ParsedDocument:
    if not text:
        return ParsedDocument(
            filename=filename,
            format=format,
            status=DocumentParseStatus.FAILED,
            sections=(),
            error_code="NO_EXTRACTABLE_TEXT",
        )
    return ParsedDocument(
        filename=filename,
        format=format,
        status=DocumentParseStatus.PARSED,
        sections=(
            ParsedSection(
                title=_file_title(filename),
                text=text,
                source=SourceLocation(
                    page_number=None,
                    section_index=0,
                    char_start=0,
                    char_end=len(text),
                ),
            ),
        ),
    )


def _parsed_or_failed(
    filename: str, format: DocumentFormat, sections: list[ParsedSection]
) -> ParsedDocument:
    if sections:
        return ParsedDocument(
            filename=filename,
            format=format,
            status=DocumentParseStatus.PARSED,
            sections=tuple(sections),
        )
    return ParsedDocument(
        filename=filename,
        format=format,
        status=DocumentParseStatus.FAILED,
        sections=(),
        error_code="NO_EXTRACTABLE_TEXT",
    )


def _file_title(filename: str) -> str:
    return PurePath(filename).stem or "未命名文档"


def _docx_text(element: ElementTree.Element, namespace: str) -> str:
    return "".join(node.text or "" for node in element.iter(f"{namespace}t"))


def _docx_style(paragraph: ElementTree.Element, namespace: str) -> str:
    properties = paragraph.find(f"{namespace}pPr")
    if properties is None:
        return ""
    style = properties.find(f"{namespace}pStyle")
    return "" if style is None else style.attrib.get(f"{namespace}val", "")


def _docx_table_rows(table: ElementTree.Element, namespace: str) -> tuple[str, ...]:
    rows: list[str] = []
    for row in table.findall(f"{namespace}tr"):
        cells = tuple(_docx_text(cell, namespace).strip() for cell in row.findall(f"{namespace}tc"))
        if any(cells):
            rows.append(" | ".join(cells))
    return tuple(rows)
