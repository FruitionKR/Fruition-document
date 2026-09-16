-- llmPipeline이 사용하되 Flyway가 버전을 관리하는 테이블을 추가한다.
-- Flyway V1~V3가 적용된 뒤 Python init_db()가 pipeline 테이블을 만든 DB도
-- 수용하기 위해 CREATE TABLE/INDEX IF NOT EXISTS를 사용한다.
-- Flyway 이력 없이 Python이 공용 테이블 일부만 만든 DB는 V3 이전에 실패하므로
-- 로컬 DB를 리셋하거나 환경별 데이터 복구 절차를 거쳐야 한다.

CREATE TABLE IF NOT EXISTS public.pipeline_runs (
    id uuid PRIMARY KEY,
    document_id character varying(255) REFERENCES public.documents(id) ON DELETE SET NULL,
    input_source text NOT NULL,
    output_dir text NOT NULL,
    mode text NOT NULL,
    status text NOT NULL,
    manifest jsonb,
    error text,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    finished_at timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.wiki_page_embeddings (
    page_id character varying(255) NOT NULL REFERENCES public.wiki_pages(id) ON DELETE CASCADE,
    embedding_model text NOT NULL,
    representation_hash text NOT NULL,
    embedding_vector double precision[] NOT NULL,
    embedding_dimension integer NOT NULL,
    status text NOT NULL DEFAULT 'completed',
    error text,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (page_id, embedding_model)
);

CREATE INDEX IF NOT EXISTS idx_wiki_page_embeddings_model_hash
    ON public.wiki_page_embeddings (embedding_model, representation_hash);

CREATE TABLE IF NOT EXISTS public.wiki_embedding_vectors (
    id text PRIMARY KEY,
    embedding_model text NOT NULL,
    representation_hash text NOT NULL,
    representation_text text NOT NULL,
    embedding_vector double precision[],
    embedding_dimension integer NOT NULL DEFAULT 0,
    status text NOT NULL DEFAULT 'pending',
    error text,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    UNIQUE (embedding_model, representation_hash)
);

CREATE TABLE IF NOT EXISTS public.wiki_embedding_units (
    id text PRIMARY KEY,
    embedding_vector_id text NOT NULL REFERENCES public.wiki_embedding_vectors(id) ON DELETE RESTRICT,
    page_id character varying(255) NOT NULL REFERENCES public.wiki_pages(id) ON DELETE CASCADE,
    source_document_id character varying(255) NOT NULL REFERENCES public.documents(id) ON DELETE CASCADE,
    unit_type text NOT NULL,
    block_refs text[] NOT NULL DEFAULT ARRAY[]::text[],
    text text NOT NULL,
    weight double precision NOT NULL DEFAULT 1.0,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wiki_embedding_units_page
    ON public.wiki_embedding_units (page_id);

CREATE INDEX IF NOT EXISTS idx_wiki_embedding_units_vector
    ON public.wiki_embedding_units (embedding_vector_id);

-- wiki_schemas는 ai_db로 이전됨 (services/ai-svc/pipeline/db/ai_schema.sql 소유).
-- dev DB는 일회용이라 데이터 이관 없이 CREATE 블록만 제거했다.
