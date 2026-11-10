package com.teachmi.backend.material;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XSLFNotes;
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
    static final String DIAGRAM_DATA_RELATION =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/diagramData";
    static final String DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private MaterialTextExtractor() {}

    /** title은 슬라이드 제목(없으면 null). text는 제목을 포함한 전체 텍스트. */
    record Page(String kind, int number, String title, String text) {}

    /** 파일을 열 수 없거나 구조가 손상된 경우. */
    static final class UnreadableFile extends Exception {
        UnreadableFile(Throwable cause) {
            super("Unreadable material file", cause);
        }

        /** 로그용 원인 요약. 예외 메시지 전문은 남기지 않고 종류만 남긴다. */
        String reason() {
            Throwable cause = getCause();
            String message = cause == null || cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(java.util.Locale.ROOT);
            String hint = message.contains("zip bomb") ? "zip_bomb_ratio"
                    : message.contains("password") || message.contains("encrypt") ? "encrypted"
                    : message.contains("strict") ? "strict_ooxml"
                    : "other";
            return (cause == null ? "unknown" : cause.getClass().getSimpleName()) + "/" + hint;
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
                appendSmartArt(slide, text);
                String cleaned = clean(text.toString());
                if (cleaned.isBlank()) {
                    // 본문이 이미지뿐인 슬라이드는 발표자 노트를 원문으로 쓴다.
                    cleaned = clean(notes(slide));
                }
                if (!cleaned.isBlank()) {
                    pages.add(new Page("slide", number, blankToNull(slide.getTitle()), cleaned));
                }
                number++;
            }
        }
        return pages;
    }

    /** SmartArt 글자는 도형이 아니라 슬라이드에 연결된 다이어그램 데이터 파트에 들어 있다. */
    private static void appendSmartArt(XSLFSlide slide, StringBuilder out) {
        try {
            PackagePart part = slide.getPackagePart();
            for (PackageRelationship relation : part.getRelationshipsByType(DIAGRAM_DATA_RELATION)) {
                try {
                    PackagePart data = part.getRelatedPart(relation);
                    try (InputStream in = data.getInputStream()) {
                        append(out, drawingText(in));
                    }
                } catch (Exception broken) {
                    // 손상된 다이어그램 하나 때문에 슬라이드 전체를 버리지 않는다.
                }
            }
        } catch (Exception ignored) {
            // 관계 목록을 읽을 수 없으면 일반 도형 글자만 사용한다.
        }
    }

    private static String notes(XSLFSlide slide) {
        XSLFNotes notes = slide.getNotes();
        if (notes == null) {
            return "";
        }
        var text = new StringBuilder();
        for (XSLFShape shape : notes.getShapes()) {
            if (shape instanceof XSLFTextShape textShape && textShape.getTextType() == Placeholder.BODY) {
                append(text, textShape.getText());
            }
        }
        return text.toString();
    }

    /** DrawingML 문단(a:p)마다 한 줄로, 글자(a:t)만 모은다. 외부 엔티티·DTD는 읽지 않는다. */
    static String drawingText(InputStream in) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(in);
        var all = new StringBuilder();
        var paragraph = new StringBuilder();
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && DRAWING_NS.equals(reader.getNamespaceURI())
                        && "t".equals(reader.getLocalName())) {
                    paragraph.append(reader.getElementText());
                } else if (event == XMLStreamConstants.END_ELEMENT && DRAWING_NS.equals(reader.getNamespaceURI())
                        && "p".equals(reader.getLocalName())) {
                    if (!paragraph.toString().isBlank()) {
                        all.append(paragraph.toString().strip()).append('\n');
                    }
                    paragraph.setLength(0);
                }
            }
        } finally {
            reader.close();
        }
        return all.toString();
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
                if (cleaned.isBlank() && slide.getNotes() != null) {
                    var notes = new StringBuilder();
                    for (List<HSLFTextParagraph> paragraphs : slide.getNotes().getTextParagraphs()) {
                        append(notes, HSLFTextParagraph.getText(paragraphs));
                    }
                    cleaned = clean(notes.toString());
                }
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
