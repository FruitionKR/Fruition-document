package fruition.core.authz;

/** access가 내려 주는 멤버 권한 항목 문자열. OWNER는 항상 모두 가진다. */
public final class WorkspacePermissions {

    public static final String WIKI_INGEST = "wiki.ingest";
    public static final String WIKI_LINT = "wiki.lint";
    public static final String SKILL_AUTHOR = "skill.author";
    public static final String WIKI_RESTORE = "wiki.restore";
    public static final String DOCUMENT_CONVERT = "document.convert";

    private WorkspacePermissions() {}
}
