CREATE VIEW agent_route_outcomes AS
SELECT projection.run_id,
       projection.workspace_id,
       projection.user_id,
       user_message.content AS request_text,
       projection.result -> 'route' AS route,
       CASE
           WHEN projection.status = 'consumed' THEN 'accepted'
           WHEN projection.status = 'failed' THEN 'technical_failure'
           ELSE 'unlabeled'
       END AS outcome_label,
       projection.error_code,
       COALESCE(projection.apply_consumed_at, projection.updated_at) AS observed_at
FROM agent_apply_projections projection
LEFT JOIN chat_messages assistant_message
       ON assistant_message.run_id = projection.run_id
      AND assistant_message.role = 'assistant'
LEFT JOIN chat_messages user_message
       ON user_message.session_id = assistant_message.session_id
      AND user_message.pair_id = assistant_message.pair_id
      AND user_message.role = 'user';

COMMENT ON VIEW agent_route_outcomes IS
    'Agent route 운영 평가 후보. 편집 적용만 accepted로 확정하고 실행 실패는 technical_failure, 나머지는 unlabeled로 둔다.';
