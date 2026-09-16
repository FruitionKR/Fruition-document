package fruition.core.aihistory.domain;

/**
 * 변경내역이 가리키는 리소스 종류.
 *
 * <p>{@code resource_id}는 이 값에 따라 {@code documents.id} 또는 {@code wiki_pages.id}를 가리키는
 * 다형 참조라 FK를 걸지 않는다. 대상이 삭제돼도 로그는 남아야 한다.
 */
public enum ResourceType {
    document,
    wiki_page,
    relation_link,
    /** 특정 문서·페이지가 아닌 복구 조치 자체. */
    action
}
