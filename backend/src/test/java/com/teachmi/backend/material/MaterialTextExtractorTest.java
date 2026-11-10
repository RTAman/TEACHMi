package com.teachmi.backend.material;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 강의 PPTX에서 흔한 SmartArt·발표자 노트 슬라이드의 텍스트 추출. */
class MaterialTextExtractorTest {
    static final String SMART_ART = """
            <dgm:dataModel xmlns:dgm="http://schemas.openxmlformats.org/drawingml/2006/diagram"
                           xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <dgm:ptLst>
                <dgm:pt modelId="1"><dgm:t><a:bodyPr/><a:p><a:r><a:t>감가상각 방법</a:t></a:r></a:p></dgm:t></dgm:pt>
                <dgm:pt modelId="2"><dgm:t><a:bodyPr/><a:p><a:r><a:t>정액법: 매년 같은 </a:t></a:r><a:r><a:t>금액을 비용으로 인식</a:t></a:r></a:p></dgm:t></dgm:pt>
                <dgm:pt modelId="3"><dgm:t><a:bodyPr/><a:p><a:r><a:t>정률법: 장부금액에 일정 비율 적용</a:t></a:r></a:p></dgm:t></dgm:pt>
              </dgm:ptLst>
            </dgm:dataModel>
            """;

    @Test
    void smartArtTextAndSpeakerNotesAreExtractedFromPptx() throws Exception {
        var pages = MaterialTextExtractor.extract(MaterialFormat.PPTX, pptxWithSmartArtAndNotes());
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).number()).isEqualTo(1);
        assertThat(pages.get(0).text()).isEqualTo("감가상각 방법\n정액법: 매년 같은 금액을 비용으로 인식\n정률법: 장부금액에 일정 비율 적용");
        assertThat(pages.get(1).number()).isEqualTo(2);
        assertThat(pages.get(1).text()).isEqualTo("내용연수는 자산을 사용할 것으로 예상하는 기간이다.");

        var candidates = ConceptCandidates.from(pages.stream()
                .map(page -> new ConceptCandidates.Source(page.number(), page.title(), page.text())).toList());
        assertThat(candidates).extracting(ConceptCandidates.Candidate::name).contains("감가상각 방법");
    }

    @Test
    void unreadableFilesReportOnlyTheKindOfFailure() {
        assertThatThrownBy(() -> MaterialTextExtractor.extract(MaterialFormat.PPTX, "PK\u0003\u0004 broken".getBytes()))
                .isInstanceOfSatisfying(MaterialTextExtractor.UnreadableFile.class,
                        error -> assertThat(error.reason()).matches("[A-Za-z]+/(other|zip_bomb_ratio|encrypted|strict_ooxml)"));
    }

    @Test
    void drawingTextIgnoresNonDrawingElements() throws Exception {
        String xml = "<root xmlns:a=\"" + MaterialTextExtractor.DRAWING_NS + "\"><t>무시</t><a:p><a:t>첫 줄</a:t></a:p>"
                + "<a:p><a:t>  </a:t></a:p><a:p><a:t>둘째</a:t><a:t> 줄</a:t></a:p></root>";
        String text = MaterialTextExtractor.drawingText(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(text).isEqualTo("첫 줄\n둘째 줄\n");
    }

    /** 1번 슬라이드: 글자가 SmartArt에만 있음. 2번 슬라이드: 본문이 비어 있고 발표자 노트에만 설명이 있음. */
    static byte[] pptxWithSmartArtAndNotes() throws Exception {
        try (var show = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            var blank = show.getSlideMasters().getFirst().getLayout(SlideLayout.BLANK);
            var smartArtSlide = show.createSlide(blank);
            var name = PackagingURIHelper.createPartName("/ppt/diagrams/data1.xml");
            PackagePart data = show.getPackage().createPart(name,
                    "application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml");
            try (OutputStream os = data.getOutputStream()) {
                os.write(SMART_ART.getBytes(StandardCharsets.UTF_8));
            }
            smartArtSlide.getPackagePart().addRelationship(name, TargetMode.INTERNAL,
                    MaterialTextExtractor.DIAGRAM_DATA_RELATION);

            var notesSlide = show.createSlide(blank);
            var notes = show.getNotesSlide(notesSlide);
            for (XSLFTextShape shape : notes.getPlaceholders()) {
                if (shape.getTextType() == Placeholder.BODY) {
                    shape.setText("내용연수는 자산을 사용할 것으로 예상하는 기간이다.");
                }
            }
            show.write(out);
            return out.toByteArray();
        }
    }
}
