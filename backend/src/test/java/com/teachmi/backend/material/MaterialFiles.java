package com.teachmi.backend.material;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;

/** 테스트용 강의자료 파일을 메모리에서 만든다. 각 페이지는 첫 줄이 제목, 나머지가 본문이다. */
final class MaterialFiles {
    private MaterialFiles() {}

    record Slide(String title, String body) {}

    /** 표준 PDF 글꼴은 한글을 쓸 수 없어 영문으로 만든다. */
    static byte[] pdf(List<Slide> pages) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (Slide page : pages) {
                var pdfPage = new PDPage();
                document.addPage(pdfPage);
                if (page == null) {
                    continue;
                }
                try (var content = new PDPageContentStream(document, pdfPage)) {
                    content.beginText();
                    content.setFont(font, 14);
                    content.setLeading(18);
                    content.newLineAtOffset(50, 700);
                    content.showText(page.title());
                    for (String line : page.body().split("\n")) {
                        content.newLine();
                        content.showText(line);
                    }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    static byte[] pptx(List<Slide> slides) throws IOException {
        try (var show = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            var layout = show.getSlideMasters().getFirst().getLayout(SlideLayout.TITLE_AND_CONTENT);
            for (Slide slide : slides) {
                XSLFSlide created = show.createSlide(layout);
                created.getPlaceholder(0).setText(slide.title());
                created.getPlaceholder(1).setText(slide.body());
            }
            show.write(out);
            return out.toByteArray();
        }
    }

    static byte[] ppt(List<Slide> slides) throws IOException {
        try (var show = new HSLFSlideShow(); var out = new ByteArrayOutputStream()) {
            for (Slide slide : slides) {
                HSLFSlide created = show.createSlide();
                created.addTitle().setText(slide.title());
                var body = new HSLFTextBox();
                body.setText(slide.body());
                body.setAnchor(new java.awt.Rectangle(50, 150, 600, 300));
                created.addShape(body);
            }
            show.write(out);
            return out.toByteArray();
        }
    }
}
