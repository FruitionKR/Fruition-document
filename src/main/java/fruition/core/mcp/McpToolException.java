package fruition.core.mcp;

public final class McpToolException extends RuntimeException {
    private final String code;
    public McpToolException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
