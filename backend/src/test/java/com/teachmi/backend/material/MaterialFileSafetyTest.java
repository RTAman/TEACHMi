package com.teachmi.backend.material;
import java.io.*;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MaterialFileSafetyTest {
    @Test void pptxWithDuplicateZipNamesIsRejectedRatherThanSelectingAnAmbiguousOriginal() throws Exception {
        var normal=MaterialFiles.pptx(List.of(new MaterialFiles.Slide("학습 개념","원문에 근거한 설명을 학습합니다.")));
        var bytes=new ByteArrayOutputStream();
        boolean duplicated=false;
        try(var in=new ZipInputStream(new ByteArrayInputStream(normal));
                var out=new ZipArchiveOutputStream(bytes)) {
            for(var entry=in.getNextEntry();entry!=null;entry=in.getNextEntry()) {
                var content=in.readAllBytes();
                out.putArchiveEntry(new ZipArchiveEntry(entry.getName()));out.write(content);out.closeArchiveEntry();
                if(entry.getName().equals("ppt/slides/slide1.xml")) {
                    out.putArchiveEntry(new ZipArchiveEntry(entry.getName()));out.write(content);out.closeArchiveEntry();
                    duplicated=true;
                }
            }
        }
        assertThat(duplicated).isTrue();
        assertThatThrownBy(() -> MaterialTextExtractor.extract(MaterialFormat.PPTX,bytes.toByteArray()))
                .isInstanceOf(MaterialTextExtractor.UnreadableFile.class);
    }
}
