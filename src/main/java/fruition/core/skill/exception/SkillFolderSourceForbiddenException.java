package fruition.core.skill.exception;

public class SkillFolderSourceForbiddenException extends RuntimeException {
    public SkillFolderSourceForbiddenException() {
        super("이 폴더의 문서를 읽을 권한이 없습니다.");
    }
}
