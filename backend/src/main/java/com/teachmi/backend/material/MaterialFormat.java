package com.teachmi.backend.material;

import java.util.Locale;
import java.util.Optional;

/** 지원하는 강의자료 형식. 확장자와 파일 앞부분(시그니처)이 모두 맞아야 받는다. */
enum MaterialFormat {
    PDF("pdf", new byte[] {'%', 'P', 'D', 'F'}),
    PPTX("pptx", new byte[] {'P', 'K', 3, 4}),
    PPT("ppt", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});

    final String code;
    private final byte[] magic;

    MaterialFormat(String code, byte[] magic) {
        this.code = code;
        this.magic = magic;
    }

    static Optional<MaterialFormat> detect(String fileName, byte[] content) {
        if (fileName == null || content == null) {
            return Optional.empty();
        }
        String lower = fileName.strip().toLowerCase(Locale.ROOT);
        for (MaterialFormat format : values()) {
            if (lower.endsWith("." + format.code) && startsWith(content, format.magic)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    static MaterialFormat fromCode(String code) {
        for (MaterialFormat format : values()) {
            if (format.code.equals(code)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unknown material format");
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
