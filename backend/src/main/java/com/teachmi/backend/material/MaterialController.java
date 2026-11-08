package com.teachmi.backend.material;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.auth.AuthenticatedMember;
import com.teachmi.backend.material.MaterialViews.AddConceptRequest;
import com.teachmi.backend.material.MaterialViews.ConceptView;
import com.teachmi.backend.material.MaterialViews.CreateTextMaterialRequest;
import com.teachmi.backend.material.MaterialViews.MaterialView;
import com.teachmi.backend.material.MaterialViews.UpdateConceptRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/materials")
class MaterialController {
    private final MaterialService materials;

    MaterialController(MaterialService materials) {
        this.materials = materials;
    }

    /** 직접 입력 자료. 분석 없이 바로 done이며 201을 반환한다. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MaterialView> createText(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateTextMaterialRequest request) {
        var result = materials.createText(member(http), key, request);
        return ResponseEntity.created(URI.create("/api/v1/materials/" + result.value().materialId()))
                .cacheControl(CacheControl.noStore()).body(result.value());
    }

    /** 파일 업로드(PDF·PPT·PPTX). 202와 analyzing 상태를 돌려주고, 분석 결과는 GET으로 확인한다. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<MaterialView> upload(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "title", required = false) String title) throws IOException {
        var userId = member(http);
        if (file == null || file.isEmpty()) {
            throw ApiException.invalid("FILE_REQUIRED", "업로드할 파일을 선택하세요.");
        }
        var result = materials.createFile(userId, key, file.getOriginalFilename(), file.getBytes(), title);
        return ResponseEntity.accepted().location(URI.create("/api/v1/materials/" + result.value().materialId()))
                .cacheControl(CacheControl.noStore()).body(result.value());
    }

    @PostMapping("/{materialId}/retry")
    ResponseEntity<MaterialView> retry(HttpServletRequest http, @PathVariable String materialId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = materials.retry(member(http), id(materialId), key);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(result.value());
    }

    @GetMapping("/{materialId}")
    ResponseEntity<MaterialView> get(HttpServletRequest http, @PathVariable String materialId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(materials.get(member(http), id(materialId)));
    }

    @PostMapping("/{materialId}/concepts")
    ResponseEntity<ConceptView> addConcept(HttpServletRequest http, @PathVariable String materialId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AddConceptRequest request) {
        var result = materials.addConcept(member(http), id(materialId), key, request);
        return ResponseEntity.created(URI.create("/api/v1/materials/" + materialId)).body(result.value());
    }

    @PatchMapping("/{materialId}/concepts/{conceptId}")
    ConceptView updateConcept(HttpServletRequest http, @PathVariable String materialId,
            @PathVariable String conceptId, @Valid @RequestBody UpdateConceptRequest request) {
        return materials.updateConcept(member(http), id(materialId), id(conceptId), request);
    }

    private static UUID member(HttpServletRequest http) {
        return AuthenticatedMember.require(http).userId();
    }

    /** 형식이 틀린 ID는 없는 자료와 같은 404로 처리한다. */
    static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound();
        }
    }
}
