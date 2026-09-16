package fruition.core.document.dto;

import java.io.InputStream;

public record DocumentOriginalResult(String mimeType, String filename, InputStream inputStream) {}
