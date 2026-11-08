package com.teachmi.backend.material;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

/** PDF는 페이지, PPT·PPTX는 슬라이드 단위로 텍스트를 뽑는다. */
final class MaterialTextExtractor {
    /** 한 페이지·슬라이드에서 저장하는 최대 글자 수. 개념 하나의 원문이 B의 AI 입력 한도를 넘지 않게 한다. */
    static final int MAX_PAGE_CHARS = 6000;

    private MaterialTextExtractor() {}

    /** title은 슬라이드 제목(없으면 null). text는 제목을 포함한 전체 텍스트. */
    record Page(String kind, int number, String title, String text) {}

    /** 파일을 열 수 없거나 구조가 손상된 경우. */
    static final class UnreadableFile extends Exception {
        UnreadableFile(Throwable cause) {
            super("Unreadable material file", cause);
        }
    }

    static List<Page> extract(MaterialFormat format, byte[] content) throws UnreadableFile {
        try {
            return switch (format) {
                case PDF -> pdf(content);
                case PPTX -> pptx(content);
                case PPT -> ppt(content);
            };
        } catch (Exception | LinkageError e) {
            throw new UnreadableFile(e);
        }
    }

    private static List<Page> pdf(byte[] content) throws Exception {
        var pages = new ArrayList<Page>();
        try (PDDocument document = Loader.loadPDF(content)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int i = 1; i <= document.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = clean(stripper.getText(document));
                if (!text.isBlank()) {
                    pages.add(new Page("page", i, null, text));
                }
            }
        }
        return pages;
    }

    private static List<Page> pptx(byte[] content) throws Exception {
        var pages = new ArrayList<Page>();
        try (var show = new XMLSlideShow(new ByteArrayInputStream(content))) {
            int number = 1;
            for (XSLFSlide slide : show.getSlides()) {
                var text = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    collect(shape, text);
                }
                String cleaned = clean(text.toString());
                if (!cleaned.isBlank()) {
                    pages.add(new Page("slide", number, blankToNull(slide.getTitle()), cleaned));
                }
                number++;
            }
        }
        return pages;
    }

    private static void collect(XSLFShape shape, StringBuilder out) {
        if (shape instanceof XSLFTextShape textShape) {
            append(out, textShape.getText());
        } else if (shape instanceof XSLFGroupShape group) {
            for (XSLFShape child : group.getShapes()) {
                collect(child, out);
            }
        } else if (shape instanceof XSLFTable table) {
            for (XSLFTableRow row : table.getRows()) {
                var cells = new ArrayList<String>();
                for (XSLFTableCell cell : row.getCells()) {
                    cells.add(cell.getText());
                }
                append(out, String.join(" | ", cells));
            }
        }
    }

    private static List<Page> ppt(byte[] content) throws Exception {
        var pages = new ArrayList<Page>();
        try (var show = new HSLFSlideShow(new ByteArrayInputStream(content))) {
            int number = 1;
            for (HSLFSlide slide : show.getSlides()) {
                var text = new StringBuilder();
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        append(text, HSLFTextParagraph.getText(textShape.getTextParagraphs()));
                    }
                }
                String cleaned = clean(text.toString());
                if (!cleaned.isBlank()) {
                    pages.add(new Page("slide", number, blankToNull(slide.getTitle()), cleaned));
                }
                number++;
            }
        }
        return pages;
    }

    private static void append(StringBuilder out, String text) {
        if (text != null && !text.isBlank()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(text);
        }
    }

    /** NUL 등 제어 문자를 지우고 줄바꿈을 정리한다. PostgreSQL TEXT는 NUL을 저장할 수 없다. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u000B', '\n')
                .replaceAll("[\\u0000-\\u0008\\u000C\\u000E-\\u001F\\u007F]", "")
                .replaceAll("[ \\t\\u00A0]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        if (normalized.length() > MAX_PAGE_CHARS) {
            int end = MAX_PAGE_CHARS;
            if (Character.isHighSurrogate(normalized.charAt(end - 1))) {
                end--;
            }
            normalized = normalized.substring(0, end);
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
