package fruition.core.document.service;

import fruition.core.document.exception.DocumentAssetTooLargeException;
import fruition.core.document.exception.InvalidDocumentAssetException;
import fruition.core.document.exception.UnsupportedDocumentAssetException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class DocumentAssetValidator {

    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final long MAX_REQUEST_BYTES = 100L * 1024 * 1024;
    static final int MAX_FILES = 20;
    static final int MAX_DIMENSION = 16_384;

    /** placeholder와 검증 결과를 같은 key로 묶어 돌려준다. 호출부가 순서로 다시 짝지을 필요가 없다. */
    public Map<UUID, ValidatedAsset> validateAll(Map<UUID, MultipartFile> attachments) {
        if (attachments.size() > MAX_FILES) {
            throw tooLarge("한 번에 새 이미지가 20개를 초과할 수 없습니다.");
        }
        long total = 0;
        for (MultipartFile file : attachments.values()) {
            if (file.getSize() > MAX_FILE_BYTES) {
                throw tooLarge("이미지 하나의 크기는 10MB를 초과할 수 없습니다.");
            }
            total += file.getSize();
            if (total > MAX_REQUEST_BYTES) {
                throw tooLarge("새 이미지의 합계 크기는 100MB를 초과할 수 없습니다.");
            }
        }

        Map<UUID, ValidatedAsset> validated = new LinkedHashMap<>();
        attachments.forEach((attachmentId, file) -> validated.put(attachmentId, validate(file)));
        return validated;
    }

    public ValidatedAsset validate(MultipartFile file) {
        if (file.isEmpty()) throw invalid("빈 이미지는 첨부할 수 없습니다.");
        if (file.getSize() > MAX_FILE_BYTES) {
            throw tooLarge("이미지 하나의 크기는 10MB를 초과할 수 없습니다.");
        }
        try {
            byte[] bytes = file.getBytes();
            ImageType type = detect(bytes);
            Dimensions dimensions = type == ImageType.WEBP
                    ? webpDimensions(bytes)
                    : decodedDimensions(bytes);
            if (dimensions.width() > MAX_DIMENSION || dimensions.height() > MAX_DIMENSION) {
                throw tooLarge("이미지의 가로와 세로는 16,384px를 초과할 수 없습니다.");
            }
            return new ValidatedAsset(
                    safeFilename(file.getOriginalFilename()), type.contentType,
                    bytes, dimensions.width(), dimensions.height(), sha256(bytes));
        } catch (DocumentAssetTooLargeException | InvalidDocumentAssetException
                 | UnsupportedDocumentAssetException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid("이미지 bytes를 읽을 수 없습니다.");
        }
    }

    private ImageType detect(byte[] bytes) {
        if (startsWith(bytes, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) return ImageType.PNG;
        if (startsWith(bytes, new int[]{0xff, 0xd8, 0xff})) return ImageType.JPEG;
        if (asciiAt(bytes, 0, "GIF87a") || asciiAt(bytes, 0, "GIF89a")) return ImageType.GIF;
        if (asciiAt(bytes, 0, "RIFF") && asciiAt(bytes, 8, "WEBP")) return ImageType.WEBP;
        throw new UnsupportedDocumentAssetException("PNG, JPEG, WebP, GIF 이미지만 첨부할 수 있습니다.");
    }

    private Dimensions decodedDimensions(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                throw invalid("손상되었거나 해석할 수 없는 이미지입니다.");
            }
            return new Dimensions(image.getWidth(), image.getHeight());
        } catch (IOException exception) {
            throw invalid("손상되었거나 해석할 수 없는 이미지입니다.");
        }
    }

    private Dimensions webpDimensions(byte[] bytes) {
        if (bytes.length < 30) throw invalid("손상되었거나 해석할 수 없는 WebP 이미지입니다.");
        long riffPayloadSize = Integer.toUnsignedLong(littleEndian32(bytes, 4));
        long chunkSize = Integer.toUnsignedLong(littleEndian32(bytes, 16));
        if (riffPayloadSize + 8 > bytes.length || chunkSize + 20 > bytes.length) {
            throw invalid("손상되었거나 해석할 수 없는 WebP 이미지입니다.");
        }
        if (asciiAt(bytes, 12, "VP8X")) {
            if (chunkSize < 10) throw invalid("손상되었거나 해석할 수 없는 WebP 이미지입니다.");
            return dimensions(littleEndian24(bytes, 24) + 1, littleEndian24(bytes, 27) + 1);
        }
        if (asciiAt(bytes, 12, "VP8L") && unsigned(bytes[20]) == 0x2f && bytes.length >= 25) {
            int width = 1 + unsigned(bytes[21]) + ((unsigned(bytes[22]) & 0x3f) << 8);
            int height = 1 + ((unsigned(bytes[22]) & 0xc0) >> 6)
                    + (unsigned(bytes[23]) << 2) + ((unsigned(bytes[24]) & 0x0f) << 10);
            return dimensions(width, height);
        }
        if (asciiAt(bytes, 12, "VP8 ") && bytes.length >= 30
                && unsigned(bytes[23]) == 0x9d && unsigned(bytes[24]) == 0x01 && unsigned(bytes[25]) == 0x2a) {
            int width = littleEndian16(bytes, 26) & 0x3fff;
            int height = littleEndian16(bytes, 28) & 0x3fff;
            return dimensions(width, height);
        }
        throw invalid("손상되었거나 지원하지 않는 WebP 이미지입니다.");
    }

    private Dimensions dimensions(int width, int height) {
        if (width < 1 || height < 1) throw invalid("이미지 dimension이 올바르지 않습니다.");
        return new Dimensions(width, height);
    }

    private int littleEndian16(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | (unsigned(bytes[offset + 1]) << 8);
    }

    private int littleEndian24(byte[] bytes, int offset) {
        return littleEndian16(bytes, offset) | (unsigned(bytes[offset + 2]) << 16);
    }

    private int littleEndian32(byte[] bytes, int offset) {
        return littleEndian16(bytes, offset)
                | (unsigned(bytes[offset + 2]) << 16)
                | (unsigned(bytes[offset + 3]) << 24);
    }

    private boolean startsWith(byte[] bytes, int[] signature) {
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if (unsigned(bytes[index]) != signature[index]) return false;
        }
        return true;
    }

    private boolean asciiAt(byte[] bytes, int offset, String expected) {
        if (bytes.length < offset + expected.length()) return false;
        for (int index = 0; index < expected.length(); index++) {
            if (unsigned(bytes[offset + index]) != expected.charAt(index)) return false;
        }
        return true;
    }

    private int unsigned(byte value) { return value & 0xff; }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "image";
        String normalized = filename.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private InvalidDocumentAssetException invalid(String message) {
        return new InvalidDocumentAssetException(message);
    }

    private DocumentAssetTooLargeException tooLarge(String message) {
        return new DocumentAssetTooLargeException(message);
    }

    private enum ImageType {
        PNG("image/png"), JPEG("image/jpeg"), GIF("image/gif"), WEBP("image/webp");
        private final String contentType;
        ImageType(String contentType) { this.contentType = contentType; }
    }

    private record Dimensions(int width, int height) {}

    public record ValidatedAsset(
            String originalFilename,
            String contentType,
            byte[] bytes,
            int width,
            int height,
            String contentHash
    ) {
    }
}
