"""PostgreSQL/pgvector adapters for generation drafts and scoped retrieval.

The Java gateway owns authorisation and publication pointers.  This adapter owns
only the immutable chunk content and embeddings that are addressed by those
already-authorised generation ids.
"""

from __future__ import annotations

import json
from hashlib import sha256
from typing import Any
from uuid import UUID

import psycopg

from medical_agent.ingestion.draft_editor import DraftChunk, DraftGeneration
from medical_agent.ingestion.indexing import (
    DeterministicEmbedder,
    Embedder,
    IndexBuildResult,
    build_generation_index,
)
from medical_agent.retrieval.pipeline import RetrievalChunk
from medical_agent.retrieval.vector_store import VectorHit


class PostgresRagRepository:
    """Small synchronous repository used by FastAPI request and worker threads."""

    def __init__(self, dsn: str, *, embedder: Embedder | None = None) -> None:
        self._dsn = dsn
        self._embedder = embedder or DeterministicEmbedder(1024, "mock-text-embedding-v4-1024")
        if self._embedder.dimension != 1024:
            raise ValueError("the configured pgvector index requires 1024-dimensional embeddings")

    def ping(self) -> None:
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute("SELECT 1")

    def create_generation(self, generation: DraftGeneration, *, knowledge_base_id: str,
                          source_checksum: str, parser_name: str, parser_version: str,
                          chunking_config: dict[str, Any], version_no: int) -> bool:
        if generation.document_id is None:
            raise ValueError("document id is required for persisted generation")
        version_id = _version_id(generation.document_id, version_no)
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                """INSERT INTO rag.document_version
                   (id, document_id, knowledge_base_id, version_no, source_checksum, parser_name,
                    parser_version, chunking_config)
                   VALUES (%s,%s,%s,%s,%s,%s,%s,%s::jsonb)
                   ON CONFLICT (document_id, version_no) DO NOTHING""",
                (version_id, generation.document_id, knowledge_base_id, version_no, source_checksum,
                 parser_name, parser_version, json.dumps(chunking_config)),
            )
            cursor.execute(
                """INSERT INTO rag.generation
                   (generation_id, document_version_id, document_id, knowledge_base_id, state, edit_revision)
                   VALUES (%s,%s,%s,%s,'DRAFT',0)
                   ON CONFLICT (generation_id) DO NOTHING""",
                (generation.id, version_id, generation.document_id, knowledge_base_id),
            )
            created = cursor.rowcount == 1
            if created:
                self._replace_chunks(cursor, generation)
            return created

    def load_generation(self, generation_id: str) -> DraftGeneration:
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT document_id, state, edit_revision, embedding_profile_id, chunk_manifest_hash "
                "FROM rag.generation WHERE generation_id=%s", (generation_id,)
            )
            generation = cursor.fetchone()
            if generation is None:
                raise ValueError("generation does not exist")
            cursor.execute(
                """SELECT chunk_id, ordinal, content, enabled, page_start, page_end, section_path,
                          manually_edited FROM rag.chunk WHERE generation_id=%s ORDER BY ordinal""",
                (generation_id,),
            )
            chunks = tuple(DraftChunk(
                id=str(row[0]), ordinal=row[1], content=row[2], enabled=row[3], page_start=row[4],
                page_end=row[5], section_path=row[6], manually_edited=row[7],
            ) for row in cursor.fetchall())
        return DraftGeneration(
            id=generation_id, document_id=str(generation[0]), state=generation[1],
            edit_revision=generation[2], chunks=chunks, embedding_profile_id=generation[3],
            manifest_hash=generation[4],
        )

    def save_draft(self, generation: DraftGeneration) -> None:
        if generation.document_id is None:
            raise ValueError("document id is required for persisted generation")
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "UPDATE rag.generation SET edit_revision=%s, embedding_profile_id=NULL, "
                "embedding_dimension=NULL, chunk_manifest_hash=NULL WHERE generation_id=%s AND state='DRAFT' "
                "AND edit_revision=%s",
                (generation.edit_revision, generation.id, generation.edit_revision - 1),
            )
            if cursor.rowcount != 1:
                raise ValueError("generation was changed; refresh before editing")
            cursor.execute("DELETE FROM rag.chunk_embedding_v1 WHERE generation_id=%s", (generation.id,))
            self._replace_chunks(cursor, generation)

    def build_index(self, generation_id: str) -> IndexBuildResult:
        generation = self.load_generation(generation_id)
        if generation.state == "BUILD_READY":
            return self.manifest(generation_id)
        # The M1/M2 demo deliberately uses deterministic local embeddings.  The
        # vector store itself is real pgvector, so future provider embeddings can
        # replace this adapter without changing lifecycle or retrieval semantics.
        embedder = self._embedder
        from medical_agent.ingestion.indexing import InMemoryVectorStore
        result = build_generation_index(generation, embedder=embedder, store=InMemoryVectorStore(dimension=embedder.dimension))
        with self._connect() as connection, connection.cursor() as cursor:
            for chunk in generation.chunks:
                if not chunk.enabled:
                    continue
                vector = _vector(embedder.embed(chunk.content))
                cursor.execute(
                    """INSERT INTO rag.chunk_embedding_v1
                       (generation_id, chunk_id, embedding_profile_id, content_hash, embedding)
                       VALUES (%s,%s,%s,%s,%s::vector)
                       ON CONFLICT (generation_id, chunk_id, embedding_profile_id)
                       DO UPDATE SET content_hash=EXCLUDED.content_hash, embedding=EXCLUDED.embedding,
                                     created_at=CURRENT_TIMESTAMP""",
                    (generation.id, chunk.id, result.embedding_profile_id, chunk.content_hash, vector),
                )
            cursor.execute(
                """UPDATE rag.generation SET state='BUILD_READY', embedding_profile_id=%s,
                          embedding_dimension=1024, chunk_manifest_hash=%s,
                          build_ready_at=CURRENT_TIMESTAMP WHERE generation_id=%s AND state='DRAFT'""",
                (result.embedding_profile_id, result.manifest_hash, generation.id),
            )
            if cursor.rowcount != 1:
                raise ValueError("generation is not an editable draft")
        return result

    def manifest(self, generation_id: str) -> IndexBuildResult:
        generation = self.load_generation(generation_id)
        if generation.state != "BUILD_READY" or not generation.manifest_hash or not generation.embedding_profile_id:
            raise ValueError("generation is not build ready")
        return IndexBuildResult(generation.id, generation.document_id, generation.state,
                                generation.embedding_profile_id, len([c for c in generation.chunks if c.enabled]),
                                generation.manifest_hash)

    def scoped_chunks(self, generation_ids: frozenset[str]) -> tuple[RetrievalChunk, ...]:
        if not generation_ids:
            return ()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                """SELECT c.generation_id, g.knowledge_base_id, c.chunk_id, c.content_hash, c.content,
                          c.token_count FROM rag.chunk c JOIN rag.generation g ON g.generation_id=c.generation_id
                   WHERE c.enabled AND c.generation_id = ANY(%s::uuid[]) ORDER BY c.ordinal""",
                (list(generation_ids),),
            )
            return tuple(RetrievalChunk(str(row[0]), str(row[1]), str(row[2]), row[3], row[4], row[5])
                         for row in cursor.fetchall())

    def search(self, *, generation_ids: set[str], profile_id: str, query: tuple[float, ...],
               top_k: int) -> tuple[VectorHit, ...]:
        if not generation_ids:
            return ()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                """SELECT generation_id, chunk_id, 1 - (embedding <=> %s::vector) AS score
                   FROM rag.chunk_embedding_v1 WHERE embedding_profile_id=%s
                     AND generation_id = ANY(%s::uuid[])
                   ORDER BY embedding <=> %s::vector LIMIT %s""",
                (_vector(query), profile_id, list(generation_ids), _vector(query), top_k),
            )
            return tuple(VectorHit(str(row[0]), str(row[1]), float(row[2])) for row in cursor.fetchall())

    def _replace_chunks(self, cursor: psycopg.Cursor[Any], generation: DraftGeneration) -> None:
        cursor.execute("DELETE FROM rag.chunk_embedding_v1 WHERE generation_id=%s", (generation.id,))
        cursor.execute("DELETE FROM rag.chunk WHERE generation_id=%s", (generation.id,))
        for chunk in generation.chunks:
            cursor.execute(
                """INSERT INTO rag.chunk
                   (chunk_id,generation_id,ordinal,content,source_text,page_start,page_end,section_path,
                    source_offsets,token_count,tokenizer_version,enabled,content_hash,manually_edited)
                   VALUES (%s,%s,%s,%s,%s,%s,%s,%s,'{}'::jsonb,%s,%s,%s,%s,%s)""",
                (chunk.id, generation.id, chunk.ordinal, chunk.content, chunk.content, chunk.page_start,
                 chunk.page_end, chunk.section_path, max(1, len(chunk.content.split())), "unicode-conservative-v1",
                 chunk.enabled, chunk.content_hash, chunk.manually_edited),
            )

    def _connect(self) -> psycopg.Connection[Any]:
        return psycopg.connect(self._dsn)


def _version_id(document_id: str, version_no: int) -> str:
    digest = sha256(f"{document_id}:{version_no}".encode()).hexdigest()[:32]
    return str(UUID(digest))


def _vector(values: tuple[float, ...]) -> str:
    return "[" + ",".join(f"{value:.8f}" for value in values) + "]"


class PostgresChunkCatalog:
    """Retrieval catalog facade; it never reads outside the supplied generation scope."""

    def __init__(self, repository: PostgresRagRepository) -> None:
        self._repository = repository

    def for_generations(self, generation_ids: frozenset[str]) -> tuple[RetrievalChunk, ...]:
        return self._repository.scoped_chunks(generation_ids)

    def get(self, generation_id: str, chunk_id: str) -> RetrievalChunk | None:
        for chunk in self._repository.scoped_chunks(frozenset((generation_id,))):
            if chunk.chunk_id == chunk_id:
                return chunk
        return None


class PostgresQueryableVectorStore:
    def __init__(self, repository: PostgresRagRepository) -> None:
        self._repository = repository

    def search(self, *, generation_ids: set[str], profile_id: str, query: tuple[float, ...],
               top_k: int) -> tuple[VectorHit, ...]:
        return self._repository.search(generation_ids=generation_ids, profile_id=profile_id,
                                       query=query, top_k=top_k)
