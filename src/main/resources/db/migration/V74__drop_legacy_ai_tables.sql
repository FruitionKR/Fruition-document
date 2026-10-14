-- core_db에 남은 이전 AI 테이블을 제거한다 (Fruition-document#97, Fruition-flatform ADR 0005의 "보관 후 삭제" 결정).
-- AI 데이터는 ai_db가 소유하며 document 코드는 이 테이블을 읽거나 쓰지 않는다. V27이 DB 경계를 넘는 FK를 이미 끊었다.
-- CASCADE 없이 한 문장으로 지운다: 지우는 테이블끼리만 FK로 얽혀 있고(agent_runs↔agent_plans 순환 포함),
-- 사용 중인 테이블이 의존하면 마이그레이션이 실패해 조용히 같이 지워지지 않는다.
-- 유지: wiki_page_versions, wiki_page_contributions, wiki_lint_state, chat_partial_wiki.
-- V47 trigger는 위 유지 테이블에만 걸려 있어 영향이 없다.
DROP TABLE IF EXISTS
    wiki_page_links,
    document_wiki_links,
    source_blocks,
    document_processing_queue,
    wiki_embedding_units,
    wiki_page_embeddings,
    wiki_embedding_vectors,
    pipeline_runs,
    wiki_pages,
    skill_version_sources,
    agent_run_artifacts,
    agent_tool_executions,
    agent_approvals,
    agent_plan_operations,
    agent_plans,
    agent_jobs,
    agent_runs,
    skill_versions,
    skills,
    checkpoint_writes,
    checkpoint_blobs,
    checkpoints,
    checkpoint_migrations;
