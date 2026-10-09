# Notifications API

[서비스 문서](../README.md) · [API 목차](README.md)

워크스페이스 안에서 나에게 온 앱 안 알림을 보여주고 읽음 처리한다. 메일·푸시는 없다.
모든 API는 Bearer access token이 필요하고 워크스페이스 구성원이어야 한다(아니면 `404 WORKSPACE_NOT_FOUND`).

## 알림 대상

- `audience=user`: `recipient_user_id` 한 명에게 보인다.
- `audience=workspace_owners`: 조회하는 사람이 **그 시점에** 워크스페이스 OWNER면 보인다. OWNER 목록을 알 수 없어 한 행만 넣기 때문에, 나중에 OWNER가 된 사람도 이전 알림을 본다. 읽음은 OWNER마다 따로다.

| type | 대상 | 생기는 때 | payload |
|---|---|---|---|
| `edit_conflict_registered` | OWNER | [편집 충돌](documents/README.md#편집-충돌)을 새로 등록 | `conflict_id`, `document_id`, `document_name`, `author_user_id` |
| `edit_conflict_resolved` | 충돌을 등록한 작성자 | OWNER가 충돌을 해결 | `conflict_id`, `document_id`, `choice`(`server`/`conflict`/`merged`), `resolved_by` |

## API

| API | 동작 |
|---|---|
| `GET /api/workspaces/{workspace_id}/notifications` | 나에게 보이는 알림을 최신순으로 `200 {"notifications": [{"id", "type", "payload", "read", "created_at"}]}`. query `unread_only`(기본 `false`), `limit`(기본 50, 1~100으로 맞춤), `before`(ISO-8601, 이 시각보다 먼저 만든 알림만), `before_id`(`before`와 함께 보내면 `(created_at, id)`가 그보다 앞선 알림만). 같은 시각에 만든 알림을 건너뛰지 않도록 다음 페이지는 마지막 항목의 `created_at`·`id`를 `before`·`before_id`로 보낸다 |
| `POST /api/workspaces/{workspace_id}/notifications/{notification_id}/read` | 읽음 처리. 이미 읽었어도 `204`. 내가 볼 수 없는 알림이면 `404 NOTIFICATION_NOT_FOUND` |
| `POST /api/workspaces/{workspace_id}/notifications/read-all` | 지금 나에게 보이는 알림을 모두 읽음 처리하고 `204` |

진입점: `src/main/java/fruition/core/notification/controller/NotificationController.java`, 처리: `NotificationService.java`.
알림 생성은 `DocumentEditConflictService`가 충돌 등록·해결과 같은 트랜잭션에서 한다.
