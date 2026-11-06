package com.teachmi.backend.material;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 자료 API의 요청·응답 형식. */
final class MaterialViews {
    private MaterialViews() {}

    record ConceptInput(@NotNull @Size(max = 60) String name, @NotNull @Size(max = 500) String scope) {}

    /** S02-5 직접 입력: 자료 본문과(선택) 학습할 개념 하나를 함께 저장한다. */
    record CreateTextMaterialRequest(@Size(max = 200) String title, @NotNull @Size(max = 15000) String text,
                                     @Valid ConceptInput concept) {}

    /**
     * 목록에 없는 개념 추가. 근거는 자료의 원문 조각 ID를 고르거나(sourceChunkIds) 직접 입력(evidenceText)한다.
     * 직접 입력 자료는 둘 다 비워도 자료 전체를 근거로 쓴다.
     */
    record AddConceptRequest(@NotNull @Size(max = 60) String name, @NotNull @Size(max = 500) String scope,
                             @Size(max = 5000) String evidenceText, @Size(max = 50) List<String> sourceChunkIds) {}

    record UpdateConceptRequest(@Size(max = 60) String name, @Size(max = 500) String scope) {}

    record SourceView(String chunkId, int order, String locationKind, int locationNumber, String location) {}

    record ConceptView(String conceptId, String name, String scope, boolean custom, List<String> sourceChunkIds,
                       String location) {}

    record MaterialView(String materialId, String sourceType, String title, String fileName, String status,
                        String errorCode, int analysisVersion, List<ConceptView> concepts, List<SourceView> sources,
                        String createdAt, String updatedAt) {}
}
